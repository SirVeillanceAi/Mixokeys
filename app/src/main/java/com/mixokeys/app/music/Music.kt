package com.mixokeys.app.music

import com.mixokeys.app.midi.Note
import com.mixokeys.app.midi.Part
import com.mixokeys.app.midi.Song
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Note spelling for the staff: which letter a pitch sits on, and its accidental. */
object Spelling {
    private val SHARP_LETTER = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
    private val SHARP_ACC = intArrayOf(0, 1, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0)
    private val FLAT_LETTER = intArrayOf(0, 1, 1, 2, 2, 3, 4, 4, 5, 5, 6, 6)
    private val FLAT_ACC = intArrayOf(0, -1, 0, -1, 0, 0, -1, 0, -1, 0, -1, 0)
    val SHARP_ORDER = intArrayOf(3, 0, 4, 1, 5, 2, 6) // F C G D A E B
    val FLAT_ORDER = intArrayOf(6, 2, 5, 1, 4, 0, 3)  // B E A D G C F
    private val LETTERS = "CDEFGAB"

    /** Diatonic step number (C-1 = 0, one per letter) and accidental (-1, 0, 1). */
    fun spell(pitch: Int, keySharps: Int): Pair<Int, Int> {
        val pc = pitch % 12
        val oct = pitch / 12 - 1
        val flats = keySharps < 0
        val letter = if (flats) FLAT_LETTER[pc] else SHARP_LETTER[pc]
        val acc = if (flats) FLAT_ACC[pc] else SHARP_ACC[pc]
        return (oct + 1) * 7 + letter to acc
    }

    /** The accidental the key signature already applies to this letter. */
    fun keyAccidental(letter: Int, keySharps: Int): Int = when {
        keySharps > 0 -> if (SHARP_ORDER.take(keySharps).contains(letter)) 1 else 0
        keySharps < 0 -> if (FLAT_ORDER.take(-keySharps).contains(letter)) -1 else 0
        else -> 0
    }

    fun pcName(pc: Int, keySharps: Int): String {
        val flats = keySharps < 0
        val l = if (flats) FLAT_LETTER[pc] else SHARP_LETTER[pc]
        val a = if (flats) FLAT_ACC[pc] else SHARP_ACC[pc]
        return LETTERS[l] + when (a) { 1 -> "♯"; -1 -> "♭"; else -> "" }
    }

    fun noteName(pitch: Int, keySharps: Int) = pcName(pitch % 12, keySharps) + (pitch / 12 - 1)
}

data class Chord(val root: Int, val quality: Quality, val start: Double, val end: Double) {
    fun name(key: Int) = Spelling.pcName(root, key) + quality.suffix
}

enum class Quality(val suffix: String, val tones: IntArray) {
    Major("", intArrayOf(0, 4, 7)),
    Minor("m", intArrayOf(0, 3, 7)),
    Dom7("7", intArrayOf(0, 4, 7, 10)),
    Maj7("maj7", intArrayOf(0, 4, 7, 11)),
    Min7("m7", intArrayOf(0, 3, 7, 10)),
    Sus4("sus4", intArrayOf(0, 5, 7)),
    Sus2("sus2", intArrayOf(0, 2, 7)),
    Dim("dim", intArrayOf(0, 3, 6)),
}

object Chords {
    /**
     * Names the chord on every beat from the notes that sound during it (weighted by how long they sound,
     * with the lowest note counted extra as the likely root), then merges repeats into longer chords.
     */
    fun recognise(song: Song, parts: List<Part>): List<Chord> {
        val notes = parts.filter { !it.isDrums }.flatMap { it.notes }.sortedBy { it.start }
        if (notes.isEmpty()) return emptyList()
        val beats = song.beats.toMutableList().apply { if (isEmpty() || last() < song.duration) add(song.duration) }
        val per = ArrayList<Chord?>()
        var from = 0
        for (b in 0 until beats.size - 1) {
            val s = beats[b]; val e = beats[b + 1]
            val w = DoubleArray(12)
            var lowest = 200; var lowestW = 0.0
            while (from < notes.size && notes[from].end < s - 8) from++
            var i = from
            while (i < notes.size && notes[i].start < e) {
                val n = notes[i]; i++
                val ov = min(n.end, e) - max(n.start, s)
                if (ov <= 0) continue
                val weight = ov * (0.4 + n.velocity / 127.0)
                w[n.pitch % 12] += weight
                if (n.pitch < lowest) { lowest = n.pitch; lowestW = weight }
            }
            val total = w.sum()
            if (total < (e - s) * 0.15) { per += null; continue }
            if (lowest < 200) w[lowest % 12] += lowestW * 0.6
            var best: Chord? = null; var bestScore = -1e9
            for (root in 0 until 12) for (q in Quality.entries) {
                var inside = 0.0
                for (t in q.tones) inside += w[(root + t) % 12]
                val outside = w.sum() - inside
                var score = (inside - 0.8 * outside) / w.sum()
                if (w[root] < 0.05 * total) score -= 0.4           // the root should actually sound
                if (q.tones.size == 4) { if (w[(root + q.tones[3]) % 12] < 0.12 * total) score -= 0.3 else score -= 0.02 }
                if (q == Quality.Dim || q == Quality.Sus2 || q == Quality.Sus4) score -= 0.06
                if (lowest < 200 && lowest % 12 == root) score += 0.08
                if (score > bestScore) { bestScore = score; best = Chord(root, q, s, e) }
            }
            per += if (bestScore > 0.1) best else null
        }
        // Smooth one-beat blips between two equal chords, then merge.
        for (i in 1 until per.size - 1) {
            val a = per[i - 1]; val b = per[i + 1]
            if (a != null && b != null && a.root == b.root && a.quality == b.quality && (per[i] == null || per[i]!!.root != a.root || per[i]!!.quality != a.quality))
                per[i] = a.copy(start = beats[i], end = beats[i + 1])
        }
        val out = ArrayList<Chord>()
        for (c in per) {
            if (c == null) continue
            val last = out.lastOrNull()
            if (last != null && last.root == c.root && last.quality == c.quality && abs(last.end - c.start) < 0.05) out[out.size - 1] = last.copy(end = c.end)
            else out += c
        }
        return out
    }

    /** Frets from the low E string to the high E string; -1 = don't play the string. */
    fun shape(c: Chord): IntArray {
        OPEN[c.root * 16 + c.quality.ordinal]?.let { return it }
        val eFret = ((c.root - 4) % 12 + 12) % 12
        val aFret = ((c.root - 9) % 12 + 12) % 12
        val eShape = E_SHAPES[c.quality]; val aShape = A_SHAPES[c.quality]
        val useE = eShape != null && (aShape == null || eFret <= aFret)
        val base = if (useE) eShape!! else aShape!!
        val fret = if (useE) eFret else aFret
        return IntArray(6) { if (base[it] < 0) -1 else base[it] + fret }
    }

    private val E_SHAPES = mapOf(
        Quality.Major to intArrayOf(0, 2, 2, 1, 0, 0), Quality.Minor to intArrayOf(0, 2, 2, 0, 0, 0),
        Quality.Dom7 to intArrayOf(0, 2, 0, 1, 0, 0), Quality.Min7 to intArrayOf(0, 2, 0, 0, 0, 0),
        Quality.Maj7 to intArrayOf(0, -1, 1, 1, 0, -1), Quality.Sus4 to intArrayOf(0, 2, 2, 2, 0, 0),
    )
    private val A_SHAPES = mapOf(
        Quality.Major to intArrayOf(-1, 0, 2, 2, 2, 0), Quality.Minor to intArrayOf(-1, 0, 2, 2, 1, 0),
        Quality.Dom7 to intArrayOf(-1, 0, 2, 0, 2, 0), Quality.Min7 to intArrayOf(-1, 0, 2, 0, 1, 0),
        Quality.Maj7 to intArrayOf(-1, 0, 2, 1, 2, 0), Quality.Sus4 to intArrayOf(-1, 0, 2, 2, 3, 0),
        Quality.Sus2 to intArrayOf(-1, 0, 2, 2, 0, 0), Quality.Dim to intArrayOf(-1, 0, 1, 2, 1, -1),
    )
    private val OPEN: Map<Int, IntArray> = buildMap {
        fun put(root: Int, q: Quality, f: IntArray) = put(root * 16 + q.ordinal, f)
        put(0, Quality.Major, intArrayOf(-1, 3, 2, 0, 1, 0))
        put(2, Quality.Major, intArrayOf(-1, -1, 0, 2, 3, 2))
        put(7, Quality.Major, intArrayOf(3, 2, 0, 0, 0, 3))
        put(2, Quality.Minor, intArrayOf(-1, -1, 0, 2, 3, 1))
        put(2, Quality.Dom7, intArrayOf(-1, -1, 0, 2, 1, 2))
        put(0, Quality.Dom7, intArrayOf(-1, 3, 2, 3, 1, 0))
        put(7, Quality.Dom7, intArrayOf(3, 2, 0, 0, 0, 1))
        put(11, Quality.Dom7, intArrayOf(-1, 2, 1, 2, 0, 2))
        put(2, Quality.Min7, intArrayOf(-1, -1, 0, 2, 1, 1))
        put(0, Quality.Maj7, intArrayOf(-1, 3, 2, 0, 0, 0))
        put(2, Quality.Maj7, intArrayOf(-1, -1, 0, 2, 2, 2))
        put(5, Quality.Maj7, intArrayOf(-1, -1, 3, 2, 1, 0))
        put(7, Quality.Maj7, intArrayOf(3, 2, 0, 0, 0, 2))
        put(2, Quality.Sus2, intArrayOf(-1, -1, 0, 2, 3, 0))
        put(2, Quality.Sus4, intArrayOf(-1, -1, 0, 2, 3, 3))
        put(4, Quality.Sus2, intArrayOf(0, 2, 4, 4, 0, 0))
        put(9, Quality.Dim, intArrayOf(-1, 0, 1, 2, 1, -1))
    }
}

/** A note placed on the guitar: string 0 = low E … 5 = high E. */
data class TabNote(val string: Int, val fret: Int, val start: Double, val end: Double, val pitch: Int)

object Tab {
    val TUNING = intArrayOf(40, 45, 50, 55, 59, 64)
    private const val MAX_FRET = 19

    /**
     * Places each note on a string and fret, keeping the hand in one area as much as possible:
     * notes that start together are fingered together within a 4-fret stretch, and each choice
     * prefers the position closest to the previous one.
     */
    fun arrange(notes: List<Note>): List<TabNote> {
        val sorted = notes.sortedWith(compareBy({ it.start }, { -it.pitch }))
        val groups = ArrayList<MutableList<Note>>()
        for (n in sorted) {
            val g = groups.lastOrNull()
            if (g != null && n.start - g[0].start < 0.03) g += n else groups += mutableListOf(n)
        }
        val out = ArrayList<TabNote>()
        var hand = 3.0
        for (g in groups) {
            // Bring notes into the guitar's range by octaves; at most six notes, distinct pitches.
            val pitches = g.map { fit(it.pitch) to it }.filter { it.first != null }
                .distinctBy { it.first }.sortedByDescending { it.first }.take(6)
            if (pitches.isEmpty()) continue
            var best: IntArray? = null; var bestCost = Double.MAX_VALUE
            val chosen = IntArray(pitches.size) { -1 }
            val used = BooleanArray(6)
            fun search(k: Int) {
                if (k == pitches.size) {
                    val frets = pitches.indices.map { pitches[it].first!! - TUNING[chosen[it]] }
                    val fretted = frets.filter { it > 0 }
                    val span = if (fretted.isEmpty()) 0 else fretted.max() - fretted.min()
                    if (span > 4) return
                    val centre = if (fretted.isEmpty()) hand else fretted.average()
                    val cost = abs(centre - hand) + 0.15 * (fretted.maxOrNull() ?: 0) + span * 0.5
                    if (cost < bestCost) { bestCost = cost; best = chosen.copyOf() }
                    return
                }
                val p = pitches[k].first!!
                for (s in 5 downTo 0) {
                    if (used[s]) continue
                    val f = p - TUNING[s]
                    if (f < 0 || f > MAX_FRET) continue
                    used[s] = true; chosen[k] = s
                    search(k + 1)
                    used[s] = false
                }
            }
            search(0)
            val pick = best ?: continue
            val fretted = ArrayList<Int>()
            pitches.forEachIndexed { k, (p, n) ->
                val f = p!! - TUNING[pick[k]]
                if (f > 0) fretted += f
                out += TabNote(pick[k], f, n.start, n.end, p)
            }
            if (fretted.isNotEmpty()) hand = hand * 0.3 + fretted.average() * 0.7
        }
        return out
    }

    private fun fit(p: Int): Int? {
        var q = p
        while (q < TUNING[0]) q += 12
        while (q > TUNING[5] + MAX_FRET) q -= 12
        return q
    }

    /** The part to show as tab: a guitar part if there is one, otherwise the busiest melodic part. */
    fun defaultPart(parts: List<Part>): Part? =
        parts.filter { it.isGuitar }.maxByOrNull { it.notes.size }
            ?: parts.filter { !it.isDrums && !it.isBass }.maxByOrNull { it.notes.size }
}
