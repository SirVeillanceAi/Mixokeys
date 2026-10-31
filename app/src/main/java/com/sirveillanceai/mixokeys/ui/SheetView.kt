package com.sirveillanceai.mixokeys.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.sirveillanceai.mixokeys.R
import com.sirveillanceai.mixokeys.lyrics.Lyrics
import com.sirveillanceai.mixokeys.notation.BeamEl
import com.sirveillanceai.mixokeys.notation.DotEl
import com.sirveillanceai.mixokeys.notation.Engraver
import com.sirveillanceai.mixokeys.notation.FlagEl
import com.sirveillanceai.mixokeys.notation.GlyphEl
import com.sirveillanceai.mixokeys.notation.LineEl
import com.sirveillanceai.mixokeys.notation.Score
import com.sirveillanceai.mixokeys.notation.SystemLayout
import com.sirveillanceai.mixokeys.notation.TextEl
import com.sirveillanceai.mixokeys.notation.TieEl
import kotlin.math.abs
import kotlin.math.max

object Sheet {
    val Paper = Color(0xFFF6F3EA)
    val Ink = Color(0xFF17161C)
    val Now = Color(0xFFE0701A)
    val Soft = Color(0xFF6E6B7A)
}

/** Pages of systems that fit the screen height. */
private class Pages(val systems: List<SystemLayout>, val pages: List<IntRange>)

/**
 * Real sheet music for the shown parts, a page at a time. The page turns by itself as the music moves on;
 * tap a note or bar to jump there, swipe sideways to turn the page by hand.
 */
@Composable
fun SheetView(score: Score, lyrics: Lyrics?, now: Double, spaceDp: Float, onSeek: (Double) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val music = remember { ResourcesCompat.getFont(ctx, R.font.noto_music) ?: Typeface.DEFAULT }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val g = with(density) { spaceDp.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val layout = remember(score, g, wPx, hPx) {
            val systems = Engraver.layout(score, (wPx - 2 * margin) / g)
            val pages = ArrayList<IntRange>()
            var i = 0
            while (i < systems.size) {
                var h = margin; var j = i
                while (j < systems.size && (j == i || h + systems[j].height * g <= hPx - margin)) { h += (systems[j].height + 1.2f) * g; j++ }
                pages += i until j; i = j
            }
            Pages(systems, pages)
        }
        val nowState = rememberUpdatedState(now)
        fun systemAt(t: Double): Int = layout.systems.indexOfLast { it.start <= t + 1e-6 }.coerceAtLeast(0)
        val page = layout.pages.indexOfFirst { systemAt(now) in it }.coerceAtLeast(0)
        val pageState = rememberUpdatedState(page)

        // y of each system on the current page
        fun tops(p: Int): List<Pair<Int, Float>> {
            var y = margin
            return layout.pages.getOrNull(p)?.map { si -> val s = layout.systems[si]; val top = y - s.top * g; y += (s.height + 1.2f) * g; si to top } ?: emptyList()
        }

        val glyph = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        val text = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        val lyric = remember { Paint(Paint.ANTI_ALIAS_FLAG) }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(layout) {
                    detectTapGestures { pos ->
                        for ((si, top) in tops(pageState.value)) {
                            val s = layout.systems[si]
                            if (pos.y >= top + s.top * g && pos.y <= top + s.bottom * g) { onSeek(s.timeAt((pos.x - margin) / g)); break }
                        }
                    }
                }
                .pointerInput(layout) {
                    var total = 0f
                    detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                        if (abs(total) > 80) {
                            val p = (pageState.value + if (total < 0) 1 else -1).coerceIn(0, layout.pages.size - 1)
                            layout.pages.getOrNull(p)?.let { onSeek(layout.systems[it.first].start) }
                        }
                    }) { ch, dx -> ch.consume(); total += dx }
                },
        ) {
            drawRect(Sheet.Paper)
            glyph.typeface = music; glyph.textSize = g / 0.244f
            val nc = drawContext.canvas.nativeCanvas
            val t = nowState.value
            for ((si, top) in tops(page)) {
                val s = layout.systems[si]
                fun X(v: Float) = margin + v * g
                fun Y(v: Float) = top + v * g
                val current = t >= s.start && t < s.end
                if (current) {
                    val cx = X(s.xAt(t))
                    drawRect(Sheet.Now.copy(alpha = 0.10f), Offset(cx - 1.2f * g, Y(-1.5f)), androidx.compose.ui.geometry.Size(2.4f * g, (Engraver.BASS_BOTTOM + 3f) * g))
                }
                for (e in s.els) {
                    val lit = e.on >= 0 && t >= e.on && t < e.off
                    val c = if (lit) Sheet.Now else Sheet.Ink
                    when (e) {
                        is LineEl -> drawLine(c, Offset(X(e.x1), Y(e.y1)), Offset(X(e.x2), Y(e.y2)), max(1f, e.w * g))
                        is GlyphEl -> { glyph.color = c.toArgb(); nc.drawText(e.text, X(e.x), Y(e.y), glyph) }
                        is BeamEl -> {
                            val p = Path().apply { moveTo(X(e.x1), Y(e.y1)); lineTo(X(e.x2), Y(e.y2)); lineTo(X(e.x2), Y(e.y2 + e.t)); lineTo(X(e.x1), Y(e.y1 + e.t)); close() }
                            drawPath(p, Sheet.Ink)
                        }
                        is TieEl -> {
                            val d = if (e.above) -0.7f else 0.7f
                            val p = Path().apply {
                                moveTo(X(e.x1), Y(e.y))
                                cubicTo(X(e.x1 + 0.6f), Y(e.y + d), X(e.x2 - 0.6f), Y(e.y + d), X(e.x2), Y(e.y))
                                cubicTo(X(e.x2 - 0.6f), Y(e.y + d * 0.65f), X(e.x1 + 0.6f), Y(e.y + d * 0.65f), X(e.x1), Y(e.y))
                                close()
                            }
                            drawPath(p, Sheet.Ink)
                        }
                        is DotEl -> drawCircle(c, 0.2f * g, Offset(X(e.x), Y(e.y)))
                        is TextEl -> {
                            text.typeface = if (e.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT
                            text.textSize = e.size * g; text.color = (if (e.size < 2f) Sheet.Soft else Sheet.Ink).toArgb()
                            text.textAlign = if (e.center) Paint.Align.CENTER else Paint.Align.LEFT
                            nc.drawText(e.text, X(e.x), Y(e.y), text)
                        }
                        is FlagEl -> {
                            val dir = if (e.up) 1f else -1f
                            for (k in 0 until e.count) {
                                val y0 = e.y + dir * k * 0.8f
                                val p = Path().apply {
                                    moveTo(X(e.x), Y(y0))
                                    cubicTo(X(e.x + 0.15f), Y(y0 + dir * 1.1f), X(e.x + 1.35f), Y(y0 + dir * 1.5f), X(e.x + 0.85f), Y(y0 + dir * 3.0f))
                                }
                                drawPath(p, c, style = Stroke(width = 0.32f * g, cap = StrokeCap.Round))
                            }
                        }
                    }
                }
                if (current) {
                    val cx = X(s.xAt(t))
                    drawLine(Sheet.Now, Offset(cx, Y(-1.5f)), Offset(cx, Y(Engraver.BASS_BOTTOM + 1.5f)), 2.5f)
                }
                // lyrics under the treble staff, lit word by word
                if (lyrics != null && lyrics.timed) {
                    lyric.textSize = (1.55f * g).coerceAtLeast(with(density) { 12.dp.toPx() })
                    var right = -1e9f
                    for (line in lyrics.lines) {
                        if (line.end < s.start || line.start >= s.end) continue
                        for (syl in line.syllables) {
                            if (syl.start < s.start || syl.start >= s.end) continue
                            val word = syl.text.trim()
                            if (word.isEmpty()) continue
                            val lit = t >= syl.start && t < syl.end
                            lyric.typeface = if (lit) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                            lyric.color = (if (lit) Sheet.Now else if (t >= syl.end) Sheet.Soft else Sheet.Ink).toArgb()
                            val x = max(X(s.xAt(syl.start)) - 0.5f * g, right + 0.6f * g)
                            nc.drawText(word, x, Y(Engraver.LYRICS), lyric)
                            right = x + lyric.measureText(word)
                        }
                    }
                }
            }
            // page indicator
            text.typeface = Typeface.DEFAULT; text.textSize = with(density) { 11.dp.toPx() }; text.color = Sheet.Soft.toArgb(); text.textAlign = Paint.Align.RIGHT
            nc.drawText("Page ${page + 1} of ${layout.pages.size}", size.width - margin, size.height - with(density) { 8.dp.toPx() }, text)
        }
    }
}
