package com.sirveillanceai.mixokeys.notation

import com.sirveillanceai.mixokeys.midi.Part
import com.sirveillanceai.mixokeys.midi.Song
import com.sirveillanceai.mixokeys.music.Spelling
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * Turns MIDI notes into engraved notation: notes are snapped to a 16th-note grid, split into
 * measures, written as real note values (with dots and ties), with rests, beams, stems and
 * accidentals, and laid out into systems of a grand staff.
 *
 * Layout units are staff spaces (the distance between two staff lines). The view scales them to pixels.
 */

/* ───────────────────────── Score model ───────────────────────── */

class Head(val pitch: Int, val step: Int, val acc: Int) {
    var showAcc: Int? = null
    var tieOut = false
    var tieIn = false
}

class Ev(
    val staff: Int,          // 0 = treble, 1 = bass
    val measure: Int,
    val pos: Int,            // in 16ths from the start of the measure
    val len: Int,            // in 16ths
    val base: Int,           // 16 whole, 8 half, 4 quarter, 2 eighth, 1 sixteenth
    val dots: Int,
    val heads: List<Head>,   // empty = rest
    val measureRest: Boolean = false,
) {
    val rest get() = heads.isEmpty()
    // filled in by layout
    var x = 0f
    var stemUp = true
    var tieNext: Ev? = null
}

class Measure(val index: Int, val start: Int, val len: Int, val num: Int, val den: Int, val showTime: Boolean) {
    val events = ArrayList<Ev>()
    val beat get() = if (den == 8 && num % 3 == 0) 6 else 16 / den
}

class Score(val song: Song, val measures: List<Measure>, val key: Int, private val unitToSec: (Double) -> Double) {
    fun timeOf(measure: Measure, pos: Double) = unitToSec(measure.start + pos)

    companion object {
        /** Builds the score from the notes of [parts]. */
        fun build(song: Song, parts: List<Part>): Score {
            val unitTicks = if (song.smpte) 0.0 else song.division / 4.0
            fun unitOf(tick: Long, sec: Double): Int = if (song.smpte) (sec * 8).roundToInt() else (tick / unitTicks).roundToInt()
            val toSec: (Double) -> Double = if (song.smpte) { u -> u / 8.0 } else { u -> song.seconds((u * unitTicks).roundToLong()) }

            data class Q(val s: Int, val e: Int, val pitch: Int)
            val qs = parts.filter { !it.isDrums }.flatMap { p -> p.notes.map { n ->
                val s = unitOf(n.startTick, n.start)
                Q(s, max(s + 1, unitOf(n.endTick, n.end)), n.pitch)
            } }
            val lastUnit = max(qs.maxOfOrNull { it.e } ?: 0, unitOf(song.endTick, song.duration))

            // Measures from the time signatures
            val sigs = song.timeSigs.map { (unitOf(it.tick, song.seconds(it.tick))) to (it.num to it.den) }.sortedBy { it.first }
            val measures = ArrayList<Measure>()
            var start = 0; var si = -1; var num = 4; var den = 4; var lastShown = ""
            while (start < lastUnit || measures.isEmpty()) {
                while (si + 1 < sigs.size && sigs[si + 1].first <= start) { si++; num = sigs[si].second.first; den = sigs[si].second.second }
                val len = max(1, num * 16 / den)
                val sig = "$num/$den"
                measures += Measure(measures.size, start, len, num, den, sig != lastShown)
                lastShown = sig
                start += len
                if (measures.size > 5000) break
            }

            for (staff in 0..1) {
                val notes = qs.filter { (it.pitch >= 60) == (staff == 0) }
                val onsets = notes.map { it.s }.distinct().sorted()
                data class Seg(val s: Int, val e: Int, val pitches: List<Int>)
                val segs = ArrayList<Seg>()
                val byStart = notes.groupBy { it.s }
                for ((i, o) in onsets.withIndex()) {
                    val chord = byStart.getValue(o)
                    val next = onsets.getOrNull(i + 1) ?: Int.MAX_VALUE
                    val e = min(chord.maxOf { it.e }, next)
                    if (e > o) segs += Seg(o, e, chord.map { it.pitch }.distinct().sorted())
                }
                var si2 = 0
                for (m in measures) {
                    val mEnd = m.start + m.len
                    val inM = ArrayList<Seg>()
                    while (si2 < segs.size && segs[si2].e <= m.start) si2++
                    var k = si2
                    while (k < segs.size && segs[k].s < mEnd) { inM += segs[k]; k++ }
                    if (inM.isEmpty()) {
                        m.events += Ev(staff, m.index, 0, m.len, 16, 0, emptyList(), measureRest = true); continue
                    }
                    var cur = 0
                    for (sg in inM) {
                        val s = max(sg.s, m.start) - m.start
                        val e = min(sg.e, mEnd) - m.start
                        if (s > cur) for (r in decompose(cur, s - cur, m, rest = true)) m.events += Ev(staff, m.index, r[0], r[1], r[2], 0, emptyList())
                        val pieces = decompose(s, e - s, m, rest = false)
                        val tiedFromBefore = sg.s < m.start
                        val tiesAfter = sg.e > mEnd
                        pieces.forEachIndexed { pi, p ->
                            val heads = sg.pitches.map { pitch -> val (st, acc) = Spelling.spell(pitch, song.keySharps); Head(pitch, st, acc) }
                            heads.forEach { h ->
                                h.tieIn = pi > 0 || tiedFromBefore
                                h.tieOut = pi < pieces.size - 1 || tiesAfter
                            }
                            m.events += Ev(staff, m.index, p[0], p[1], p[2], p[3], heads)
                        }
                        cur = max(cur, e)
                    }
                    if (cur < m.len) for (r in decompose(cur, m.len - cur, m, rest = true)) m.events += Ev(staff, m.index, r[0], r[1], r[2], 0, emptyList())
                }
            }

            // Ties: link each tied piece to the next piece in the same staff
            for (staff in 0..1) {
                val evs = measures.flatMap { m -> m.events.filter { it.staff == staff } }
                for (i in 0 until evs.size - 1) if (evs[i].heads.any { it.tieOut }) evs[i].tieNext = evs[i + 1]
            }

            // Accidentals, per measure and staff, carried through the measure
            for (m in measures) for (staff in 0..1) {
                val shown = HashMap<Int, Int>()
                for (ev in m.events.filter { it.staff == staff }.sortedBy { it.pos }) for (h in ev.heads) {
                    val current = shown[h.step] ?: Spelling.keyAccidental(h.step % 7, song.keySharps)
                    if (h.acc != current) { if (!h.tieIn || shown[h.step] == null && ev.pos > 0) h.showAcc = h.acc; shown[h.step] = h.acc }
                    else if (h.tieIn && ev.pos == 0) shown[h.step] = h.acc
                }
            }
            for (m in measures) m.events.sortWith(compareBy({ it.pos }, { it.staff }))
            return Score(song, measures, song.keySharps, toSec)
        }

        private val NOTE_VALUES = listOf(intArrayOf(16, 16, 0), intArrayOf(12, 8, 1), intArrayOf(8, 8, 0), intArrayOf(6, 4, 1),
            intArrayOf(4, 4, 0), intArrayOf(3, 2, 1), intArrayOf(2, 2, 0), intArrayOf(1, 1, 0))

        /** Splits a span of a measure into written values. Returns [pos, len, base, dots]. */
        fun decompose(from: Int, length: Int, m: Measure, rest: Boolean): List<IntArray> {
            val out = ArrayList<IntArray>()
            var p = from; var left = length
            val compound = m.beat == 6
            while (left > 0) {
                val pick = NOTE_VALUES.firstOrNull { (v, _, dots) ->
                    when {
                        v > left -> false
                        rest && dots > 0 -> false
                        v == 16 -> p == 0 && m.len == 16
                        m.len == 16 && p in 1..7 && p + v > 8 -> false   // keep the middle of a 4/4 bar visible
                        compound -> when (v) {
                            12, 8, 6 -> p % 6 == 0
                            4, 3 -> p % 2 == 0 && p % 6 + v <= 6
                            2 -> p % 2 == 0
                            else -> true
                        }
                        else -> when (v) {
                            12, 8, 6, 4, 3 -> p % 4 == 0
                            2 -> p % 2 == 0
                            else -> true
                        }
                    }
                } ?: intArrayOf(1, 1, 0)
                out += intArrayOf(p, pick[0], pick[1], pick[2])
                p += pick[0]; left -= pick[0]
            }
            return out
        }
    }
}

/* ───────────────────────── Layout ───────────────────────── */

sealed class El { open val on: Double = -1.0; open val off: Double = -1.0 }
class LineEl(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float, override val on: Double = -1.0, override val off: Double = -1.0) : El()
/** A music-font glyph; [x] is the left of the glyph's box and [y] its baseline, both in staff spaces. */
class GlyphEl(val text: String, val x: Float, val y: Float, override val on: Double = -1.0, override val off: Double = -1.0) : El()
class BeamEl(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val t: Float, override val on: Double, override val off: Double) : El()
class TieEl(val x1: Float, val x2: Float, val y: Float, val above: Boolean) : El()
class DotEl(val x: Float, val y: Float, override val on: Double, override val off: Double) : El()
class TextEl(val text: String, val x: Float, val y: Float, val size: Float, val serif: Boolean, val center: Boolean = false) : El()
class NameEl(val text: String, val x: Float, val y: Float, override val on: Double, override val off: Double) : El()
class FlagEl(val x: Float, val y: Float, val up: Boolean, val count: Int, override val on: Double, override val off: Double) : El()

class SystemLayout(
    val els: List<El>,
    val anchors: List<Pair<Double, Float>>,
    val start: Double,
    val end: Double,
    val top: Float,        // highest point used, relative to the treble top line (negative = above)
    val bottom: Float,     // lowest point used
    val width: Float,
    val headerEnd: Int,     // els[0 until headerEnd] are the clefs, key and time signature at the start
    val headerW: Float,
    val staffBottom: Float, // bottom line of the lowest staff
) {
    val height get() = bottom - top
    fun xAt(t: Double): Float {
        if (anchors.isEmpty()) return 0f
        if (t <= anchors.first().first) return anchors.first().second
        for (i in 0 until anchors.size - 1) {
            val (t0, x0) = anchors[i]; val (t1, x1) = anchors[i + 1]
            if (t < t1) return x0 + ((t - t0) / (t1 - t0).coerceAtLeast(1e-6)).toFloat() * (x1 - x0)
        }
        return anchors.last().second
    }
    fun timeAt(x: Float): Double {
        if (anchors.isEmpty()) return start
        for (i in 0 until anchors.size - 1) {
            val (t0, x0) = anchors[i]; val (t1, x1) = anchors[i + 1]
            if (x < x1) return t0 + ((x - x0) / (x1 - x0).coerceAtLeast(1e-3f)).coerceIn(0f, 1f) * (t1 - t0)
        }
        return anchors.last().first
    }
}

object Engraver {
    const val TREBLE_BOTTOM = 4f
    const val LYRICS = 7.4f
    const val BASS_TOP = 12f
    const val BASS_BOTTOM = 16f
    private const val HEAD_W = 1.22f
    private const val LSB = 0.205f // left side bearing of most glyphs, in spaces

    const val G_CLEF = "𝄞"; const val F_CLEF = "𝄢"
    const val HEAD_BLACK = "𝅘"; const val HEAD_VOID = "𝅗"; const val WHOLE = "𝅝"
    private val RESTS = mapOf(16 to "𝄻", 8 to "𝄼", 4 to "𝄽", 2 to "𝄾", 1 to "𝄿")
    private val REST_W = mapOf(16 to 2.8f, 8 to 2.8f, 4 to 1.05f, 2 to 1.07f, 1 to 1.35f)
    private val ACC = mapOf(1 to "♯", -1 to "♭", 0 to "♮")
    private val ACC_W = mapOf(1 to 0.92f, -1 to 0.82f, 0 to 0.72f)
    private val ACC_LIFT = mapOf(1 to 0.54f, -1 to 0.33f, 0 to 0.54f)

    private var staves = listOf(0, 1)
    private var names = false
    private fun staffTop(staff: Int) = if (staff == 1 && staves.size == 2) BASS_TOP else 0f
    private val lastBottom get() = staffTop(staves.last()) + 4f
    fun yOf(staff: Int, step: Int) = if (staff == 0) staffTop(0) + 4f - (step - 37) / 2f else staffTop(1) + 4f - (step - 25) / 2f
    private fun middle(staff: Int) = staffTop(staff) + 2f
    private val LETTER = "CDEFGAB"

    private fun colWidth(d: Int) = 1.9f + 1.15f * (ln(d.toFloat()) / ln(2f))

    /** Lays the score out in systems [width] staff spaces wide. */
    fun layout(score: Score, width: Float, staves: List<Int> = listOf(0, 1), names: Boolean = false): List<SystemLayout> {
        this.staves = staves; this.names = names
        val key = score.key
        val keyW = abs(key) * 1.0f
        data class MW(val m: Measure, val cols: List<Int>, val colW: FloatArray, val lead: Float, val total: Float)
        val mws = score.measures.map { m ->
            val mine = m.events.filter { it.staff in staves }
            val cols = mine.map { it.pos }.distinct().sorted()
            val cw = FloatArray(cols.size) { i ->
                val d = (cols.getOrNull(i + 1) ?: m.len) - cols[i]
                val evs = mine.filter { it.pos == cols[i] }
                var w = if (evs.all { it.measureRest }) 6f else colWidth(d.coerceAtLeast(1))
                if (evs.any { e -> e.heads.any { it.showAcc != null } }) w += 1.1f
                if (evs.any { it.dots > 0 }) w += 0.6f
                w
            }
            val lead = 1.0f + if (m.showTime && m.index > 0) 2.6f else 0f
            MW(m, cols, cw, lead, lead + cw.sum() + 0.6f)
        }
        val systems = ArrayList<SystemLayout>()
        var i = 0
        while (i < mws.size) {
            val showTimeHere = mws[i].m.showTime
            val header = 0.5f + 3.2f + keyW + 0.6f + if (showTimeHere) 2.6f else 0f
            var used = header
            var j = i
            while (j < mws.size) {
                val w = mws[j].total - if (j == i && showTimeHere) (mws[j].lead - 1.0f) else 0f
                if (j > i && used + w > width - 0.5f) break
                used += w; j++
            }
            val line = mws.subList(i, j)
            val isLast = j >= mws.size
            val natural = used
            val stretch = if (isLast && natural < width * 0.65f) 0f else max(0f, width - 0.5f - natural)
            val colSum = line.sumOf { it.colW.sum().toDouble() }.toFloat().coerceAtLeast(1f)
            systems += engraveSystem(score, line.map { Triple(it.m, it.cols, it.colW.map { w -> w + stretch * w / colSum }) }, header, showTimeHere, i == 0, width)
            i = j
        }
        return systems
    }

    private fun engraveSystem(score: Score, line: List<Triple<Measure, List<Int>, List<Float>>>, header: Float, timeInHeader: Boolean, first: Boolean, width: Float): SystemLayout {
        val els = ArrayList<El>()
        val anchors = ArrayList<Pair<Double, Float>>()
        val key = score.key
        // Header: clefs, key signature, time signature
        if (0 in staves) els += GlyphEl(G_CLEF, 0.5f, staffTop(0) + 4f)
        if (1 in staves) els += GlyphEl(F_CLEF, 0.55f, staffTop(1) + 4f)
        val sharps = intArrayOf(45, 42, 46, 43, 40, 44, 41); val flats = intArrayOf(41, 44, 40, 43, 39, 42, 38)
        for (k in 0 until abs(key)) {
            val st = if (key > 0) sharps[k] else flats[k]
            val a = if (key > 0) 1 else -1
            val x = 3.7f + k * 1.0f
            if (0 in staves) els += GlyphEl(ACC.getValue(a), x - LSB, yOf(0, st) + ACC_LIFT.getValue(a))
            if (1 in staves) els += GlyphEl(ACC.getValue(a), x - LSB, yOf(1, st - 14) + ACC_LIFT.getValue(a))
        }
        val m0 = line.first().first
        if (timeInHeader) timeSig(els, header - 2.0f, m0.num, m0.den)
        if (!first) els += TextEl("${m0.index + 1}", 0.2f, -1.4f, 1.3f, serif = true)
        val headerEnd = els.size

        var x = header
        val staffEnd: Float
        for ((m, cols, colW) in line) {
            if (m.showTime && !(m === m0 && timeInHeader)) { timeSig(els, x + 0.9f, m.num, m.den); x += 2.6f }
            x += 1.0f
            val colX = FloatArray(cols.size)
            var cx = x
            for (c in cols.indices) { colX[c] = cx + (if (m.events.any { it.staff in staves && it.pos == cols[c] && it.heads.any { h -> h.showAcc != null } }) 1.1f else 0f) + HEAD_W / 2; cx += colW[c] }
            for (c in cols.indices) anchors += score.timeOf(m, cols[c].toDouble()) to colX[c]
            val mEndX = cx + 0.6f
            for (ev in m.events) {
                if (ev.staff !in staves) continue
                val c = cols.indexOf(ev.pos)
                ev.x = if (ev.measureRest) (x + mEndX) / 2f else colX[c]
            }
            engraveMeasure(score, m, els)
            els += LineEl(mEndX, 0f, mEndX, lastBottom, 0.13f)
            x = mEndX
        }
        staffEnd = x
        val last = line.last().first
        anchors += score.timeOf(last, last.len.toDouble()) to staffEnd
        if (last.index == score.measures.size - 1) {
            els += LineEl(staffEnd - 0.55f, 0f, staffEnd - 0.55f, lastBottom, 0.13f)
            els += LineEl(staffEnd - 0.2f, 0f, staffEnd - 0.2f, lastBottom, 0.45f)
        }
        // staff lines and the opening bar line
        for (s in staves) for (l in 0..4) { val y = staffTop(s) + l; els += LineEl(0f, y, staffEnd, y, 0.1f) }
        els += LineEl(0.05f, 0f, 0.05f, lastBottom, 0.13f)

        // ties need the x of the next piece, which may be in this system
        val evsHere = line.flatMap { it.first.events.filter { e -> e.staff in staves } }.toSet()
        for (ev in evsHere) {
            val nxt = ev.tieNext ?: continue
            for (h in ev.heads.filter { it.tieOut }) {
                val y = yOf(ev.staff, h.step)
                val above = !ev.stemUp
                val x2 = if (nxt in evsHere) nxt.x - HEAD_W * 0.55f else staffEnd + 0.4f
                els += TieEl(ev.x + HEAD_W * 0.55f, x2, if (above) y - 0.75f else y + 0.75f, above)
            }
        }

        var top = -3.2f; var bottom = lastBottom + 3.5f
        for (e in els) when (e) {
            is LineEl -> { top = min(top, min(e.y1, e.y2) - 1f); bottom = max(bottom, max(e.y1, e.y2) + 1f) }
            is GlyphEl -> { top = min(top, e.y - 4.5f); bottom = max(bottom, e.y + 1.5f) }
            is BeamEl -> { top = min(top, min(e.y1, e.y2) - 1.5f); bottom = max(bottom, max(e.y1, e.y2) + 1.5f) }
            else -> {}
        }
        top = max(top, -9f).let { t -> min(t, -3.2f) }
        val firstM = line.first().first; val lastM = line.last().first
        return SystemLayout(els, anchors, score.timeOf(firstM, 0.0), score.timeOf(lastM, lastM.len.toDouble()), top, bottom, staffEnd, headerEnd, header, lastBottom)
    }

    private fun timeSig(els: MutableList<El>, cx: Float, num: Int, den: Int) {
        for (s in staves) {
            val base = staffTop(s)
            els += TextEl("$num", cx, base + 1.95f, 2.6f, serif = true, center = true)
            els += TextEl("$den", cx, base + 3.95f, 2.6f, serif = true, center = true)
        }
    }

    private fun engraveMeasure(score: Score, m: Measure, els: MutableList<El>) {
        for (staff in staves) {
            val evs = m.events.filter { it.staff == staff }
            // beam groups: 8ths and 16ths within one beat
            val groups = ArrayList<MutableList<Ev>>()
            var curGroup: MutableList<Ev>? = null
            for (ev in evs) {
                val beamable = !ev.rest && ev.base <= 2
                val g = curGroup
                if (beamable && g != null && g.last().pos / m.beat == ev.pos / m.beat && g.last().pos + g.last().len == ev.pos) g += ev
                else { curGroup = if (beamable) mutableListOf(ev) else null; curGroup?.let { groups += it } }
            }
            val inBeam = HashSet<Ev>()
            for (g in groups) if (g.size >= 2) {
                inBeam += g
                val avg = g.flatMap { it.heads }.map { yOf(staff, it.step) }.average().toFloat()
                val up = avg > middle(staff)
                g.forEach { it.stemUp = up }
            }
            for (ev in evs) {
                val on = score.timeOf(m, ev.pos.toDouble()); val off = score.timeOf(m, (ev.pos + ev.len).toDouble())
                if (ev.rest) { rest(els, ev, staff); continue }
                if (ev !in inBeam) {
                    val avg = ev.heads.map { yOf(staff, it.step) }.average().toFloat()
                    ev.stemUp = avg > middle(staff)
                }
                heads(els, ev, staff, on, off)
                if (ev.base < 16 && ev !in inBeam) {
                    val (sx, y1, y2) = stem(ev, staff, 3.5f)
                    els += LineEl(sx, y1, sx, y2, 0.12f, on, off)
                    if (ev.base <= 2) els += FlagEl(sx, if (ev.stemUp) y1 else y2, ev.stemUp, if (ev.base == 1) 2 else 1, on, off)
                }
            }
            for (g in groups) if (g.size >= 2) beam(score, m, els, g, staff)
        }
    }

    /** Stem x and its top/bottom y (top < bottom). */
    private fun stem(ev: Ev, staff: Int, len: Float): Triple<Float, Float, Float> {
        val ys = ev.heads.map { yOf(staff, it.step) }
        val hi = ys.min(); val lo = ys.max()
        return if (ev.stemUp) {
            val sx = ev.x + HEAD_W / 2 - 0.06f
            Triple(sx, min(hi - len, middle(staff)), lo - 0.15f)
        } else {
            val sx = ev.x - HEAD_W / 2 + 0.06f
            Triple(sx, hi + 0.15f, max(lo + len, middle(staff)))
        }
    }

    private fun heads(els: MutableList<El>, ev: Ev, staff: Int, on: Double, off: Double) {
        val glyph = when { ev.base >= 16 -> WHOLE; ev.base >= 8 -> HEAD_VOID; else -> HEAD_BLACK }
        val hw = if (ev.base >= 16) 1.7f else HEAD_W
        val steps = ev.heads.map { it.step }.sorted()
        // seconds in a chord: put one of the pair on the other side of the stem
        val displaced = HashSet<Int>()
        if (ev.stemUp) { for (k in 1 until steps.size) if (steps[k] - steps[k - 1] == 1 && steps[k - 1] !in displaced) displaced += steps[k] }
        else { for (k in steps.size - 2 downTo 0) if (steps[k + 1] - steps[k] == 1 && steps[k + 1] !in displaced) displaced += steps[k] }
        var accCol = 0; var lastAccStep = Int.MAX_VALUE
        for (h in ev.heads.sortedByDescending { it.step }) {
            val y = yOf(staff, h.step)
            val dx = if (h.step in displaced) (if (ev.stemUp) HEAD_W - 0.12f else -(HEAD_W - 0.12f)) else 0f
            val cx = ev.x + dx
            els += GlyphEl(glyph, cx - hw / 2 - LSB, y + 0.55f, on, off)
            if (names) els += NameEl(LETTER[((h.step % 7) + 7) % 7].toString(), cx, y, on, off)
            // ledger lines
            val bottomStep = if (staff == 0) 37 else 25
            var l = bottomStep - 2
            while (l >= h.step) { val ly = yOf(staff, l); els += LineEl(cx - hw * 0.8f, ly, cx + hw * 0.8f, ly, 0.12f); l -= 2 }
            l = bottomStep + 10
            while (l <= h.step) { val ly = yOf(staff, l); els += LineEl(cx - hw * 0.8f, ly, cx + hw * 0.8f, ly, 0.12f); l += 2 }
            h.showAcc?.let { a ->
                if (lastAccStep - h.step < 6) accCol++ else accCol = 0
                lastAccStep = h.step
                val right = ev.x - HEAD_W / 2 - 0.25f - (if (displaced.isNotEmpty() && !ev.stemUp) HEAD_W else 0f) - accCol * 1.0f
                els += GlyphEl(ACC.getValue(a), right - ACC_W.getValue(a) - LSB, y + ACC_LIFT.getValue(a), on, off)
            }
            if (ev.dots > 0) {
                val onLine = (h.step - (if (staff == 0) 37 else 25)) % 2 == 0
                val dxDot = ev.x + hw / 2 + 0.45f + (if (displaced.isNotEmpty() && ev.stemUp) HEAD_W else 0f)
                els += DotEl(dxDot, if (onLine) y - 0.5f else y, on, off)
            }
        }
    }

    private fun rest(els: MutableList<El>, ev: Ev, staff: Int) {
        val bottom = staffTop(staff) + 4f
        val base = if (ev.measureRest) 16 else ev.base
        val w = REST_W.getValue(base)
        val baseline = when (base) { 16 -> bottom - 0.9f; 8 -> bottom - 1.0f; else -> bottom }
        els += GlyphEl(RESTS.getValue(base), ev.x - w / 2 - LSB, baseline)
    }

    private fun beam(score: Score, m: Measure, els: MutableList<El>, g: List<Ev>, staff: Int) {
        val up = g.first().stemUp
        val xs = g.map { if (up) it.x + HEAD_W / 2 - 0.06f else it.x - HEAD_W / 2 + 0.06f }
        val ext = g.map { ev -> val ys = ev.heads.map { yOf(staff, it.step) }; if (up) ys.min() else ys.max() }
        val far = g.map { ev -> val ys = ev.heads.map { yOf(staff, it.step) }; if (up) ys.max() else ys.min() }
        val x0 = xs.first(); val xn = xs.last()
        val slope = if (xn - x0 < 0.01f) 0f else ((ext.last() - ext.first()) / (xn - x0)).coerceIn(-0.12f, 0.12f)
        val len = 3.25f
        var b = if (up) Float.MAX_VALUE else -Float.MAX_VALUE
        for (k in g.indices) {
            val c = if (up) ext[k] - len - slope * (xs[k] - x0) else ext[k] + len - slope * (xs[k] - x0)
            b = if (up) min(b, c) else max(b, c)
        }
        // keep beams near the staff
        val mid = middle(staff)
        if (up && b > mid) b = mid
        if (!up && b < mid) b = mid
        fun yb(x: Float) = b + slope * (x - x0)
        val t = 0.5f
        val on0 = score.timeOf(m, g.first().pos.toDouble()); val offN = score.timeOf(m, (g.last().pos + g.last().len).toDouble())
        for (k in g.indices) {
            val ev = g[k]
            val on = score.timeOf(m, ev.pos.toDouble()); val off = score.timeOf(m, (ev.pos + ev.len).toDouble())
            val y = yb(xs[k])
            els += LineEl(xs[k], min(y, far[k]), xs[k], max(y, far[k]), 0.12f, on, off)
        }
        val off1 = if (up) 0f else -t
        els += BeamEl(x0, yb(x0) + off1, xn, yb(xn) + off1, t, on0, offN)
        // second beams for 16ths
        val sec = if (up) 0.75f else -0.75f
        for (k in g.indices) {
            if (g[k].base != 1) continue
            val nextIs16 = k + 1 < g.size && g[k + 1].base == 1
            val prevIs16 = k > 0 && g[k - 1].base == 1
            if (nextIs16) els += BeamEl(xs[k], yb(xs[k]) + off1 + sec, xs[k + 1], yb(xs[k + 1]) + off1 + sec, t, on0, offN)
            else if (!prevIs16) {
                val (a, bx) = if (k == 0) xs[k] to xs[k] + 1.1f else xs[k] - 1.1f to xs[k]
                els += BeamEl(a, yb(a) + off1 + sec, bx, yb(bx) + off1 + sec, t, on0, offN)
            }
        }
    }
}
