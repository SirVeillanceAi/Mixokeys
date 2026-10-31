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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import com.sirveillanceai.mixokeys.notation.El
import com.sirveillanceai.mixokeys.notation.Engraver
import com.sirveillanceai.mixokeys.notation.FlagEl
import com.sirveillanceai.mixokeys.notation.GlyphEl
import com.sirveillanceai.mixokeys.notation.LineEl
import com.sirveillanceai.mixokeys.notation.NameEl
import com.sirveillanceai.mixokeys.notation.Score
import com.sirveillanceai.mixokeys.notation.SystemLayout
import com.sirveillanceai.mixokeys.notation.TextEl
import com.sirveillanceai.mixokeys.notation.TieEl
import kotlin.math.abs
import kotlin.math.max

private class Pages(val systems: List<SystemLayout>, val pages: List<IntRange>)

private class Painters(music: Typeface) {
    val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = music }
    val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val lyric = Paint(Paint.ANTI_ALIAS_FLAG)
    val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
}

/**
 * Real sheet music for the shown parts, styled by [skin]: either one long line scrolling past a fixed
 * playhead, or pages that turn by themselves. Tap to jump; drag (scroll) or swipe (pages) to move.
 */
@Composable
fun SheetView(score: Score, lyrics: Lyrics?, now: Double, skin: Skin, spaceDp: Float, staves: List<Int>, onSeek: (Double) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val music = remember { ResourcesCompat.getFont(ctx, R.font.noto_music) ?: Typeface.DEFAULT }
    val p = remember { Painters(music) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.clipToBounds()) {
        val g = with(density) { spaceDp.dp.toPx() }
        val margin = with(density) { 12.dp.toPx() }
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        val layout = remember(score, g, wPx, hPx, skin.scroll, staves, skin.names) {
            val width = if (skin.scroll) 1e7f else (wPx - 2 * margin) / g
            val systems = Engraver.layout(score, width, staves, skin.names)
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
        val playX = margin + with(density) { 64.dp.toPx() }.coerceAtMost(wPx * 0.3f)

        if (skin.scroll) {
            val sys = layout.systems.firstOrNull() ?: return@BoxWithConstraints
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(layout) {
                        detectTapGestures { pos ->
                            val x0 = playX - sys.xAt(nowState.value) * g
                            onSeek(sys.timeAt((pos.x - x0) / g))
                        }
                    }
                    .pointerInput(layout) {
                        detectHorizontalDragGestures { ch, dx ->
                            ch.consume()
                            val x = sys.xAt(nowState.value) - dx / g
                            onSeek(sys.timeAt(x))
                        }
                    },
            ) {
                drawRect(skin.paper)
                p.glyph.textSize = g / 0.244f
                val t = nowState.value
                val top = (size.height - sys.height * g) / 2f - sys.top * g
                val ox = playX - sys.xAt(t) * g
                val visL = -ox / g - 4f; val visR = (size.width - ox) / g + 4f
                drawEls(sys, skin, p, g, ox, top, t, sys.headerEnd until sys.els.size, visL, visR)
                // playhead
                drawLine(skin.now, Offset(playX, top + (-2f) * g), Offset(playX, top + (sys.staffBottom + 2f) * g), 2.5f)
                drawLyrics(lyrics, sys, skin, p, g, ox, top, t, density.density)
                // fixed clef + key column at the left
                val hw = sys.headerW * g + margin
                if (!skin.ghostClefs) {
                    drawRect(skin.paper, Offset.Zero, Size(hw, size.height))
                    drawRect(Brush.horizontalGradient(listOf(skin.paper, skin.paper.copy(alpha = 0f)), startX = hw, endX = hw + 18f), Offset(hw, 0f), Size(18f, size.height))
                    for (i in 0 until sys.els.size) { val e = sys.els[i]; if (e is LineEl && e.w <= 0.1f && e.y1 == e.y2) drawLine(skin.lines, Offset(0f, top + e.y1 * g), Offset(hw + 18f, top + e.y1 * g), max(1f, e.w * g)) }
                }
                drawEls(sys, skin, p, g, margin, top, t, 0 until sys.headerEnd, -1e9f, 1e9f, alpha = if (skin.ghostClefs) 0.28f else 1f)
            }
        } else {
            fun systemAt(t: Double): Int = layout.systems.indexOfLast { it.start <= t + 1e-6 }.coerceAtLeast(0)
            val page = layout.pages.indexOfFirst { systemAt(now) in it }.coerceAtLeast(0)
            val pageState = rememberUpdatedState(page)
            fun tops(pg: Int): List<Pair<Int, Float>> {
                var y = margin
                return layout.pages.getOrNull(pg)?.map { si -> val s = layout.systems[si]; val top = y - s.top * g; y += (s.height + 1.2f) * g; si to top } ?: emptyList()
            }
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
                                val pg = (pageState.value + if (total < 0) 1 else -1).coerceIn(0, layout.pages.size - 1)
                                layout.pages.getOrNull(pg)?.let { onSeek(layout.systems[it.first].start) }
                            }
                        }) { ch, dx -> ch.consume(); total += dx }
                    },
            ) {
                drawRect(skin.paper)
                p.glyph.textSize = g / 0.244f
                val t = nowState.value
                for ((si, top) in tops(page)) {
                    val s = layout.systems[si]
                    val current = t >= s.start && t < s.end
                    if (current) {
                        val cx = margin + s.xAt(t) * g
                        drawRect(skin.now.copy(alpha = 0.10f), Offset(cx - 1.2f * g, top - 1.5f * g), Size(2.4f * g, (s.staffBottom + 3f) * g))
                    }
                    drawEls(s, skin, p, g, margin, top, t, s.els.indices, -1e9f, 1e9f)
                    if (current) {
                        val cx = margin + s.xAt(t) * g
                        drawLine(skin.now, Offset(cx, top - 1.5f * g), Offset(cx, top + (s.staffBottom + 1.5f) * g), 2.5f)
                    }
                    drawLyrics(lyrics, s, skin, p, g, margin, top, t, density.density)
                }
                p.text.typeface = Typeface.DEFAULT; p.text.textSize = 11f * density.density; p.text.color = skin.soft.toArgb(); p.text.textAlign = Paint.Align.RIGHT
                drawContext.canvas.nativeCanvas.drawText("Page ${page + 1} of ${layout.pages.size}", size.width - margin, size.height - 8f * density.density, p.text)
            }
        }
    }
}

private fun DrawScope.drawEls(s: SystemLayout, skin: Skin, p: Painters, g: Float, ox: Float, top: Float, t: Double, range: IntRange, visL: Float, visR: Float, alpha: Float = 1f) {
    val nc = drawContext.canvas.nativeCanvas
    fun X(v: Float) = ox + v * g
    fun Y(v: Float) = top + v * g
    for (i in range) {
        val e: El = s.els[i]
        val ex = when (e) { is LineEl -> min2(e.x1, e.x2); is GlyphEl -> e.x; is BeamEl -> e.x1; is TieEl -> e.x1; is DotEl -> e.x; is TextEl -> e.x; is FlagEl -> e.x; is NameEl -> e.x }
        val exR = when (e) { is LineEl -> max(e.x1, e.x2); is BeamEl -> e.x2; is TieEl -> e.x2; else -> ex + 3f }
        if (exR < visL || ex > visR) continue
        val lit = e.on >= 0 && t >= e.on && t < e.off
        val c = (if (lit) skin.now else skin.ink).copy(alpha = alpha)
        when (e) {
            is LineEl -> drawLine(if (e.w <= 0.1f && !lit) skin.lines.copy(alpha = skin.lines.alpha * alpha) else c, Offset(X(e.x1), Y(e.y1)), Offset(X(e.x2), Y(e.y2)), max(1f, e.w * g))
            is GlyphEl -> { p.glyph.color = c.toArgb(); nc.drawText(e.text, X(e.x), Y(e.y), p.glyph) }
            is BeamEl -> drawPath(Path().apply { moveTo(X(e.x1), Y(e.y1)); lineTo(X(e.x2), Y(e.y2)); lineTo(X(e.x2), Y(e.y2 + e.t)); lineTo(X(e.x1), Y(e.y1 + e.t)); close() }, skin.ink)
            is TieEl -> {
                val d = if (e.above) -0.7f else 0.7f
                drawPath(Path().apply {
                    moveTo(X(e.x1), Y(e.y))
                    cubicTo(X(e.x1 + 0.6f), Y(e.y + d), X(e.x2 - 0.6f), Y(e.y + d), X(e.x2), Y(e.y))
                    cubicTo(X(e.x2 - 0.6f), Y(e.y + d * 0.65f), X(e.x1 + 0.6f), Y(e.y + d * 0.65f), X(e.x1), Y(e.y))
                    close()
                }, skin.ink)
            }
            is DotEl -> drawCircle(c, 0.2f * g, Offset(X(e.x), Y(e.y)))
            is TextEl -> {
                p.text.typeface = if (e.serif) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT
                p.text.textSize = e.size * g; p.text.color = (if (e.size < 2f) skin.soft else skin.ink).copy(alpha = alpha).toArgb()
                p.text.textAlign = if (e.center) Paint.Align.CENTER else Paint.Align.LEFT
                nc.drawText(e.text, X(e.x), Y(e.y), p.text)
            }
            is FlagEl -> {
                val dir = if (e.up) 1f else -1f
                for (k in 0 until e.count) {
                    val y0 = e.y + dir * k * 0.8f
                    drawPath(Path().apply {
                        moveTo(X(e.x), Y(y0))
                        cubicTo(X(e.x + 0.15f), Y(y0 + dir * 1.1f), X(e.x + 1.35f), Y(y0 + dir * 1.5f), X(e.x + 0.85f), Y(y0 + dir * 3.0f))
                    }, c, style = Stroke(width = 0.32f * g, cap = StrokeCap.Round))
                }
            }
            is NameEl -> {
                p.name.textSize = 0.95f * g; p.name.color = skin.paper.toArgb()
                nc.drawText(e.text, X(e.x), Y(e.y) + 0.34f * g, p.name)
            }
        }
    }
}

private fun min2(a: Float, b: Float) = if (a < b) a else b

/** Lyrics under the top staff, lit word by word. */
private fun DrawScope.drawLyrics(lyrics: Lyrics?, s: SystemLayout, skin: Skin, p: Painters, g: Float, ox: Float, top: Float, t: Double, dens: Float) {
    if (lyrics == null || !lyrics.timed) return
    val nc = drawContext.canvas.nativeCanvas
    p.lyric.textSize = (1.55f * g).coerceAtLeast(12f * dens)
    val y = top + (if (s.staffBottom > 5f) Engraver.LYRICS else s.staffBottom + 3.4f) * g
    var right = -1e9f
    for (line in lyrics.lines) {
        if (line.end < s.start || line.start >= s.end) continue
        for (syl in line.syllables) {
            if (syl.start < s.start || syl.start >= s.end) continue
            val word = syl.text.trim()
            if (word.isEmpty()) continue
            val x = max(ox + s.xAt(syl.start) * g - 0.5f * g, right + 0.6f * g)
            if (x > size.width || x < -400f) { right = x + p.lyric.measureText(word); continue }
            val lit = t >= syl.start && t < syl.end
            p.lyric.typeface = if (lit) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            p.lyric.color = (if (lit) skin.now else if (t >= syl.end) skin.soft else skin.ink).toArgb()
            nc.drawText(word, x, y, p.lyric)
            right = x + p.lyric.measureText(word)
        }
    }
}
