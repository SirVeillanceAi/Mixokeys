package com.sirveillanceai.mixokeys.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import com.sirveillanceai.mixokeys.R
import com.sirveillanceai.mixokeys.lyrics.Lyrics
import com.sirveillanceai.mixokeys.midi.Note
import com.sirveillanceai.mixokeys.midi.Song
import com.sirveillanceai.mixokeys.music.Chord
import com.sirveillanceai.mixokeys.music.Chords
import com.sirveillanceai.mixokeys.music.Spelling
import com.sirveillanceai.mixokeys.music.TabNote
import kotlin.math.max
import kotlin.math.min

object Palette {
    val Bg = Color(0xFF0B0B10)
    val Card = Color(0xFF16161E)
    val Paper = Color(0xFF111119)
    val Line = Color(0xFF262633)
    val StaffLine = Color(0xFF4A4860)
    val Text1 = Color(0xFFF1F0F7)
    val Muted = Color(0xFFA3A1B5)
    val Accent = Color(0xFFA594FF)
    val Left = Color(0xFF5FD4C4)
    val Now = Color(0xFFF0A43A)
    val OnAccent = Color(0xFF120E2B)
    val Bad = Color(0xFFFF7A7A)
}

/** Index of the first note that may still be visible at time [t] (notes sorted by start). */
private fun firstVisible(notes: List<Note>, t: Double, maxLen: Double): Int {
    var lo = 0; var hi = notes.size
    val from = t - maxLen
    while (lo < hi) { val m = (lo + hi) / 2; if (notes[m].start < from) lo = m + 1 else hi = m }
    return lo
}

@Composable
private fun musicTypeface(): Typeface {
    val ctx = LocalContext.current
    return remember { ResourcesCompat.getFont(ctx, R.font.noto_music) ?: Typeface.DEFAULT }
}

private fun Modifier.scrubX(pxPerSec: Float, now: () -> Double, seek: (Double) -> Unit) =
    pointerInput(pxPerSec) { detectHorizontalDragGestures { ch, dx -> ch.consume(); seek(now() - dx / pxPerSec) } }

/* ───────────────────────── Grand staff ───────────────────────── */

/**
 * A grand staff that scrolls past a fixed playhead. Notes are placed by time (not engraved into measures),
 * each with a bar showing how long to hold it. Treble = right hand (from middle C up), bass = left hand.
 */
@Composable
fun StaffView(song: Song, notes: List<Note>, now: Double, pxPerSecDp: Float, onSeek: (Double) -> Unit, modifier: Modifier) {
    val face = musicTypeface()
    val pxPerSec = with(androidx.compose.ui.platform.LocalDensity.current) { pxPerSecDp.dp.toPx() }
    val nowState = rememberUpdatedState(now)
    val maxLen = remember(notes) { notes.maxOfOrNull { it.end - it.start } ?: 0.0 }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val key = song.keySharps
    Canvas(modifier.scrubX(pxPerSec, { nowState.value }, onSeek)) {
        val gap = (size.height / 24f).coerceIn(5.dp.toPx(), 15.dp.toPx())
        val half = gap / 2
        val staffH = 4 * gap
        val total = staffH * 2 + 5 * gap
        val top = (size.height - total) / 2
        val trebleTop = top; val trebleBottom = top + staffH
        val bassTop = trebleBottom + 5 * gap; val bassBottom = bassTop + staffH
        val S = gap / 0.244f // font size where one staff space = gap
        val keyW = kotlin.math.abs(key) * gap * 1.0f
        val leftArea = gap * 3.6f + keyW + gap * 0.8f
        val playX = leftArea + 28.dp.toPx()

        fun yFor(treble: Boolean, d: Int) = if (treble) trebleBottom - (d - 37) * half else bassBottom - (d - 25) * half
        fun staffLines(x0: Float, x1: Float) {
            for (i in 0..4) {
                drawLine(Palette.StaffLine, Offset(x0, trebleTop + i * gap), Offset(x1, trebleTop + i * gap), 1.2f)
                drawLine(Palette.StaffLine, Offset(x0, bassTop + i * gap), Offset(x1, bassTop + i * gap), 1.2f)
            }
        }

        drawRect(Palette.Paper)
        staffLines(0f, size.width)
        val t0 = now - (playX - leftArea) / pxPerSec
        val t1 = now + (size.width - playX) / pxPerSec
        fun xFor(t: Double) = playX + ((t - now) * pxPerSec).toFloat()

        // Bars and bar numbers
        paint.typeface = Typeface.DEFAULT; paint.textSize = gap * 1.05f; paint.color = Palette.Muted.toArgb(); paint.textAlign = Paint.Align.LEFT
        song.bars.forEachIndexed { i, b ->
            if (b < t0 - 1 || b > t1) return@forEachIndexed
            val x = xFor(b)
            drawLine(Palette.StaffLine, Offset(x, trebleTop), Offset(x, bassBottom), 1.2f)
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", x + 3f, trebleTop - gap * 0.8f, paint)
        }

        // Notes
        paint.typeface = face; paint.textSize = S; paint.textAlign = Paint.Align.CENTER
        val headW = gap * 1.3f; val headH = gap * 0.98f
        var i = firstVisible(notes, t0, maxLen)
        while (i < notes.size) {
            val n = notes[i]; i++
            if (n.start > t1) break
            if (n.end < t0) continue
            val treble = n.pitch >= 60
            val (d, acc) = Spelling.spell(n.pitch, key)
            val y = yFor(treble, d)
            val x = xFor(n.start); val xe = xFor(n.end)
            val active = now >= n.start && now < n.end
            val past = n.end <= now
            val hand = if (treble) Palette.Accent else Palette.Left
            // hold bar
            drawRoundRect(hand.copy(alpha = if (active) 0.55f else if (past) 0.12f else 0.28f),
                Offset(x, y - gap * 0.28f), Size(max(xe - x, headW), gap * 0.56f), CornerRadius(gap * 0.28f))
            // ledger lines
            val ledgerColor = Palette.StaffLine
            if (treble) {
                var ld = 35; while (ld >= d) { val ly = yFor(true, ld); drawLine(ledgerColor, Offset(x - headW * 0.75f, ly), Offset(x + headW * 0.75f, ly), 1.2f); ld -= 2 }
                ld = 47; while (ld <= d) { val ly = yFor(true, ld); drawLine(ledgerColor, Offset(x - headW * 0.75f, ly), Offset(x + headW * 0.75f, ly), 1.2f); ld += 2 }
            } else {
                var ld = 23; while (ld >= d) { val ly = yFor(false, ld); drawLine(ledgerColor, Offset(x - headW * 0.75f, ly), Offset(x + headW * 0.75f, ly), 1.2f); ld -= 2 }
                ld = 35; while (ld <= d) { val ly = yFor(false, ld); drawLine(ledgerColor, Offset(x - headW * 0.75f, ly), Offset(x + headW * 0.75f, ly), 1.2f); ld += 2 }
            }
            val headColor = when { active -> Palette.Now; past -> hand.copy(alpha = 0.4f); else -> hand }
            drawOval(headColor, Offset(x - headW / 2, y - headH / 2), Size(headW, headH))
            val keyAcc = Spelling.keyAccidental(d % 7, key)
            if (acc != keyAcc) {
                val glyph = when (acc) { 1 -> "♯"; -1 -> "♭"; else -> "♮" }
                val lift = when (acc) { -1 -> 0.08f; else -> 0.13f } * S
                paint.color = headColor.toArgb()
                drawContext.canvas.nativeCanvas.drawText(glyph, x - headW * 1.05f, y + lift, paint)
            }
        }

        drawLine(Palette.Now, Offset(playX, trebleTop - gap * 2), Offset(playX, bassBottom + gap * 2), 2.5f)

        // Fixed left side: clefs and key signature over the scrolling notes
        drawRect(Palette.Paper, Offset.Zero, Size(leftArea, size.height))
        staffLines(0f, leftArea)
        drawLine(Palette.StaffLine, Offset(gap * 0.4f, trebleTop), Offset(gap * 0.4f, bassBottom), 2f)
        paint.color = Palette.Text1.toArgb(); paint.textAlign = Paint.Align.LEFT
        val nc = drawContext.canvas.nativeCanvas
        nc.drawText("𝄞", gap * 0.8f, trebleBottom, paint)
        nc.drawText("𝄢", gap * 0.8f, bassBottom, paint)
        val trebleSharps = intArrayOf(45, 42, 46, 43, 40, 44, 41)
        val trebleFlats = intArrayOf(41, 44, 40, 43, 39, 42, 38)
        for (k in 0 until kotlin.math.abs(key)) {
            val td = if (key > 0) trebleSharps[k] else trebleFlats[k]
            val g = if (key > 0) "♯" else "♭"
            val lift = (if (key > 0) 0.13f else 0.08f) * S
            val x = gap * 3.7f + k * gap * 1.0f
            nc.drawText(g, x, yFor(true, td) + lift, paint)
            nc.drawText(g, x, yFor(false, td - 14) + lift, paint)
        }
    }
}

/* ───────────────────────── Falling notes ───────────────────────── */

private fun isBlack(p: Int) = (p % 12) in intArrayOf(1, 3, 6, 8, 10)

@Composable
fun FallingView(song: Song, notes: List<Note>, now: Double, onSeek: (Double) -> Unit, modifier: Modifier) {
    val nowState = rememberUpdatedState(now)
    val maxLen = remember(notes) { notes.maxOfOrNull { it.end - it.start } ?: 0.0 }
    val range = remember(notes) {
        var lo = (notes.minOfOrNull { it.pitch } ?: 48); var hi = (notes.maxOfOrNull { it.pitch } ?: 72)
        lo -= lo % 12; hi += 11 - hi % 12
        while (hi - lo < 35) { if (lo >= 24) lo -= 12 else hi += 12 }
        lo.coerceAtLeast(21) to hi.coerceAtMost(108)
    }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    val ppsHolder = remember { floatArrayOf(1f) }
    Canvas(modifier.pointerInput(Unit) { detectVerticalDragGestures { ch, dy -> ch.consume(); onSeek(nowState.value + dy / ppsHolder[0]) } }) {
        val (lo, hi) = range
        val whites = (lo..hi).count { !isBlack(it) }
        val ww = size.width / whites
        val bw = ww * 0.62f
        val kbH = min(size.height * 0.22f, ww * 5.2f)
        val hitY = size.height - kbH
        val pps = hitY / 3.2f
        ppsHolder[0] = pps
        fun keyX(p: Int): Pair<Float, Float> {
            val wIndex = (lo until p).count { !isBlack(it) }
            return if (isBlack(p)) (wIndex * ww - bw / 2) to bw else (wIndex * ww) to ww
        }
        drawRect(Palette.Paper)
        // octave guides and beat lines
        for (p in lo..hi) if (p % 12 == 0) { val (x, _) = keyX(p); drawLine(Palette.Line, Offset(x, 0f), Offset(x, hitY), 1f) }
        for (b in song.bars) {
            val y = hitY - ((b - now) * pps).toFloat()
            if (y < 0 || y > hitY) continue
            drawLine(Palette.Line, Offset(0f, y), Offset(size.width, y), 1.5f)
        }
        val active = BooleanArray(128)
        val tTop = now + hitY / pps
        var i = firstVisible(notes, now - 0.1, maxLen)
        paint.textSize = (ww * 0.42f).coerceAtMost(14.dp.toPx())
        while (i < notes.size) {
            val n = notes[i]; i++
            if (n.start > tTop) break
            if (n.end < now - 0.05 || n.pitch < lo || n.pitch > hi) continue
            val on = now >= n.start && now < n.end
            if (on) active[n.pitch] = true
            val (x, w) = keyX(n.pitch)
            val yb = hitY - ((n.start - now) * pps).toFloat()
            val yt = hitY - ((n.end - now) * pps).toFloat()
            val c = if (n.pitch >= 60) Palette.Accent else Palette.Left
            drawRoundRect(if (on) Palette.Now else c.copy(alpha = if (isBlack(n.pitch)) 0.8f else 1f),
                Offset(x + 1.5f, yt), Size(w - 3f, max(yb - yt, 4f)), CornerRadius(min(w * 0.25f, 6.dp.toPx())))
            if (yb - yt > paint.textSize * 1.6f && w > 12.dp.toPx() * 0.9f) {
                paint.color = Palette.OnAccent.toArgb()
                drawContext.canvas.nativeCanvas.drawText(Spelling.pcName(n.pitch % 12, song.keySharps), x + w / 2, yb - paint.textSize * 0.5f, paint)
            }
        }
        // keyboard
        drawRect(Palette.Now.copy(alpha = 0.8f), Offset(0f, hitY - 2f), Size(size.width, 3f))
        for (p in lo..hi) if (!isBlack(p)) {
            val (x, w) = keyX(p)
            drawRect(if (active[p]) Palette.Now else Color(0xFFEDEBF5), Offset(x + 0.5f, hitY + 1f), Size(w - 1f, kbH - 1f))
            if (p % 12 == 0) {
                paint.color = Color(0xFF6A6880).toArgb(); paint.textSize = (ww * 0.38f).coerceAtMost(12.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText("C${p / 12 - 1}", x + w / 2, size.height - 6f, paint)
            }
        }
        for (p in lo..hi) if (isBlack(p)) {
            val (x, w) = keyX(p)
            drawRect(if (active[p]) Palette.Now else Color(0xFF1C1B24), Offset(x, hitY + 1f), Size(w, kbH * 0.62f))
        }
    }
}

/* ───────────────────────── Guitar ───────────────────────── */

@Composable
fun ChordDiagram(chord: Chord?, key: Int, big: Boolean, modifier: Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD } }
    Canvas(modifier) {
        if (chord == null) {
            paint.color = Palette.Muted.toArgb(); paint.textSize = size.height * 0.14f
            drawContext.canvas.nativeCanvas.drawText("No chord", size.width / 2, size.height / 2, paint); return@Canvas
        }
        val frets = Chords.shape(chord)
        val nameH = size.height * (if (big) 0.22f else 0.24f)
        paint.color = (if (big) Palette.Text1 else Palette.Muted).toArgb(); paint.textSize = nameH * 0.85f
        drawContext.canvas.nativeCanvas.drawText(chord.name(key), size.width / 2, nameH * 0.85f, paint)
        val gridTop = nameH + size.height * 0.12f
        val gridH = size.height - gridTop - size.height * 0.04f
        val gridW = min(size.width * 0.78f, gridH * 0.9f)
        val left = (size.width - gridW) / 2 + gridW * 0.06f
        val sx = gridW * 0.88f / 5
        val fy = gridH / 5
        val fretted = frets.filter { it > 0 }
        val base = if (fretted.isEmpty() || fretted.max() <= 4) 1 else fretted.min()
        for (s in 0..5) drawLine(Palette.Muted, Offset(left + s * sx, gridTop), Offset(left + s * sx, gridTop + 5 * fy), 1.5f)
        for (f in 0..5) drawLine(Palette.Muted, Offset(left, gridTop + f * fy), Offset(left + 5 * sx, gridTop + f * fy), if (f == 0 && base == 1) 5f else 1.5f)
        if (base > 1) {
            paint.color = Palette.Muted.toArgb(); paint.textSize = fy * 0.55f; paint.textAlign = Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText("${base}fr", left - sx * 0.35f, gridTop + fy * 0.7f, paint)
            paint.textAlign = Paint.Align.CENTER
        }
        val r = min(sx, fy) * 0.36f
        for (s in 0..5) {
            val f = frets[s]; val x = left + s * sx
            when {
                f < 0 -> { val y = gridTop - fy * 0.45f; val a = r * 0.7f
                    drawLine(Palette.Muted, Offset(x - a, y - a), Offset(x + a, y + a), 2f); drawLine(Palette.Muted, Offset(x - a, y + a), Offset(x + a, y - a), 2f) }
                f == 0 -> drawCircle(Palette.Muted, r * 0.7f, Offset(x, gridTop - fy * 0.45f), style = Stroke(2f))
                else -> drawCircle(Palette.Accent, r, Offset(x, gridTop + (f - base + 0.5f) * fy))
            }
        }
    }
}

/** Tab: six strings (high E on top) scrolling past the playhead, with chord names above. */
@Composable
fun TabView(song: Song, tab: List<TabNote>, chords: List<Chord>, now: Double, pxPerSecDp: Float, onSeek: (Double) -> Unit, modifier: Modifier) {
    val pxPerSec = with(androidx.compose.ui.platform.LocalDensity.current) { pxPerSecDp.dp.toPx() }
    val nowState = rememberUpdatedState(now)
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) } }
    val maxLen = remember(tab) { tab.maxOfOrNull { it.end - it.start } ?: 0.0 }
    Canvas(modifier.scrubX(pxPerSec, { nowState.value }, onSeek)) {
        drawRect(Palette.Paper)
        val chordRow = 34.dp.toPx()
        val sp = ((size.height - chordRow - 16.dp.toPx()) / 6f).coerceAtMost(30.dp.toPx())
        val top = chordRow + sp * 0.5f
        val leftArea = 26.dp.toPx()
        val playX = leftArea + 40.dp.toPx()
        fun xFor(t: Double) = playX + ((t - now) * pxPerSec).toFloat()
        fun yFor(s: Int) = top + (5 - s) * sp
        val t0 = now - (playX - leftArea) / pxPerSec
        val t1 = now + (size.width - playX) / pxPerSec
        for (s in 0..5) drawLine(Palette.StaffLine, Offset(0f, yFor(s)), Offset(size.width, yFor(s)), 1.3f)
        for (b in song.bars) { if (b < t0 - 1 || b > t1) continue; val x = xFor(b); drawLine(Palette.StaffLine, Offset(x, yFor(5)), Offset(x, yFor(0)), 1.3f) }
        // chord names
        paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 15.dp.toPx(); paint.textAlign = Paint.Align.LEFT
        for (c in chords) {
            if (c.end < t0 || c.start > t1) continue
            val on = now >= c.start && now < c.end
            paint.color = (if (on) Palette.Now else Palette.Text1).toArgb()
            drawContext.canvas.nativeCanvas.drawText(c.name(song.keySharps), max(xFor(c.start), leftArea + 2f), chordRow * 0.7f, paint)
        }
        // notes
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); paint.textSize = sp * 0.62f; paint.textAlign = Paint.Align.CENTER
        var lo = 0; var hi = tab.size; val from = t0 - maxLen
        while (lo < hi) { val m = (lo + hi) / 2; if (tab[m].start < from) lo = m + 1 else hi = m }
        for (k in lo until tab.size) {
            val n = tab[k]
            if (n.start > t1) break
            if (n.end < t0) continue
            val x = xFor(n.start); val y = yFor(n.string)
            val on = now >= n.start && now < n.end
            val past = n.end <= now
            val c = if (on) Palette.Now else if (past) Palette.Accent.copy(alpha = 0.45f) else Palette.Accent
            drawLine(c.copy(alpha = 0.35f), Offset(x, y), Offset(xFor(n.end), y), sp * 0.18f)
            val label = n.fret.toString()
            val w = paint.measureText(label) + sp * 0.3f
            drawRoundRect(Palette.Paper, Offset(x - w / 2, y - sp * 0.38f), Size(w, sp * 0.76f), CornerRadius(4f))
            if (on) drawRoundRect(Palette.Now.copy(alpha = 0.25f), Offset(x - w / 2, y - sp * 0.38f), Size(w, sp * 0.76f), CornerRadius(4f))
            paint.color = c.toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, x, y + paint.textSize * 0.36f, paint)
        }
        drawLine(Palette.Now, Offset(playX, top - sp * 0.6f), Offset(playX, yFor(0) + sp * 0.6f), 2.5f)
        drawRect(Palette.Paper, Offset.Zero, Size(leftArea, size.height))
        paint.color = Palette.Muted.toArgb(); paint.textSize = sp * 0.5f
        val names = arrayOf("E", "A", "D", "G", "B", "e")
        for (s in 0..5) drawContext.canvas.nativeCanvas.drawText(names[s], leftArea / 2, yFor(s) + paint.textSize * 0.36f, paint)
    }
}

/* ───────────────────────── Karaoke lyrics ───────────────────────── */

@Composable
fun LyricsBar(lyrics: Lyrics, now: Double, modifier: Modifier) {
    val idx = lyrics.lineIndexAt(now)
    val curIdx = if (idx < 0) 0 else if (now > lyrics.lines[idx].end + 0.3 && idx + 1 < lyrics.lines.size) idx + 1 else idx
    val cur = lyrics.lines.getOrNull(curIdx) ?: return
    val next = lyrics.lines.getOrNull(curIdx + 1)
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(lit(cur, now), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        if (next != null) Text(next.text.trim(), color = Palette.Muted, fontSize = 16.sp, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

private fun lit(line: com.sirveillanceai.mixokeys.lyrics.LyricLine, now: Double): AnnotatedString = buildAnnotatedString {
    val on = SpanStyle(color = Palette.Now)
    val off = SpanStyle(color = Palette.Text1)
    for (s in line.syllables) {
        val frac = when {
            now >= s.end -> 1.0
            now <= s.start -> 0.0
            else -> (now - s.start) / (s.end - s.start).coerceAtLeast(0.01)
        }
        val n = (s.text.length * frac).toInt().coerceIn(0, s.text.length)
        pushStyle(on); append(s.text.substring(0, n)); pop()
        pushStyle(off); append(s.text.substring(n)); pop()
    }
}
