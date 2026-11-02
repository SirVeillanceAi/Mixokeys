package com.mixokeys.app.lyrics

import com.mixokeys.app.midi.Song
import kotlin.math.max

/** A piece of a line that lights up at [start] and is fully sung by [end]. */
data class Syllable(val text: String, val start: Double, val end: Double)
data class LyricLine(val start: Double, val end: Double, val syllables: List<Syllable>) {
    val text get() = syllables.joinToString("") { it.text }
}

class Lyrics(val lines: List<LyricLine>, val source: String, val timed: Boolean = true) {
    /** Plain text lines, used only while lyrics have no timings yet. */
    var untimedLines: List<String> = emptyList()

    fun lineIndexAt(t: Double): Int {
        var idx = -1
        for (i in lines.indices) if (lines[i].start <= t) idx = i else break
        return idx
    }

    /** As LRC text (word timings included as enhanced-LRC tags), for saving. */
    fun toLrc(): String = buildString {
        for (l in lines) {
            append('[').append(stamp(l.start)).append(']')
            if (l.syllables.size > 1) for (s in l.syllables) append('<').append(stamp(s.start)).append('>').append(s.text)
            else append(l.text)
            append('\n')
        }
    }

    companion object {
        fun untimed(text: String, source: String): Lyrics = Lyrics(emptyList(), source, timed = false).also {
            it.untimedLines = text.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() && !Regex("""^\[[a-zA-Z]+:.*]$""").matches(l) }
        }

        private fun stamp(t: Double): String {
            val cs = Math.round(t * 100).coerceAtLeast(0)
            return "%02d:%02d.%02d".format(cs / 6000, cs / 100 % 60, cs % 100)
        }

        /** Lyrics stored inside the MIDI file: lyric events, or the text events of a .kar file. */
        fun fromMidi(song: Song): Lyrics? {
            val lyr = song.lyricEvents.filter { it.text.isNotBlank() }
            val kar = song.textEvents.filter { !it.text.startsWith("@") && it.text.isNotEmpty() }
            val (events, src) = when {
                lyr.size >= 8 -> lyr to "From the MIDI file"
                kar.size >= 8 -> kar to "From the karaoke file"
                else -> return null
            }
            val lines = ArrayList<LyricLine>()
            var cur = ArrayList<Pair<Double, String>>()
            fun flush() { if (cur.any { it.second.isNotBlank() }) lines += build(cur); cur = ArrayList() }
            for (e in events) {
                var t = e.text
                // .kar: "\" starts a new verse and "/" a new line. Lyric events: a trailing CR/LF ends the line.
                if (t.startsWith("\\") || t.startsWith("/")) { flush(); t = t.substring(1) }
                val endsLine = t.endsWith("\r") || t.endsWith("\n")
                t = t.trimEnd('\r', '\n').replace("\r", "").replace("\n", "")
                if (t.isNotEmpty()) cur += e.time to t
                if (endsLine) flush()
            }
            flush()
            // Space between lines: a line ends when the next begins (but not more than a few seconds after its last syllable).
            val fixed = lines.mapIndexed { i, l ->
                val nextStart = lines.getOrNull(i + 1)?.start ?: (l.syllables.last().start + 3.0)
                val end = minOf(nextStart, l.syllables.last().start + 3.0).coerceAtLeast(l.syllables.last().start + 0.2)
                val syl = l.syllables.mapIndexed { j, s -> s.copy(end = l.syllables.getOrNull(j + 1)?.start ?: minOf(end, s.start + 1.5)) }
                LyricLine(l.start, end, syl)
            }
            return Lyrics(fixed, src)
        }

        private fun build(parts: List<Pair<Double, String>>): LyricLine {
            val syl = parts.map { Syllable(it.second, it.first, it.first) }
            return LyricLine(syl.first().start, syl.last().start, syl)
        }

        private val TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
        private val WORD = Regex("""<(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)>""")
        private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

        private fun secs(m: String, s: String) = m.toInt() * 60 + s.replace(':', '.').toDouble()

        /** Returns timed lyrics from LRC text, or untimed lyrics if the text has no timings. */
        fun parse(text: String, source: String, songLength: Double): Lyrics {
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0) ?: 0.0
            data class Raw(val t: Double, val body: String)
            val raws = ArrayList<Raw>()
            for (line in text.lines()) {
                val tags = TAG.findAll(line).toList()
                if (tags.isEmpty()) continue
                val body = line.substring(tags.last().range.last + 1)
                for (tg in tags) raws += Raw(secs(tg.groupValues[1], tg.groupValues[2]) - offset, body)
            }
            if (raws.isEmpty()) return untimed(text, source)
            raws.sortBy { it.t }
            val lines = ArrayList<LyricLine>()
            for ((i, r) in raws.withIndex()) {
                val next = raws.getOrNull(i + 1)?.t ?: max(songLength, r.t + 4.0)
                val words = WORD.findAll(r.body).toList()
                if (r.body.isBlank()) continue
                val syl: List<Syllable> = if (words.isNotEmpty()) {
                    val pieces = ArrayList<Pair<Double, String>>()
                    val lead = r.body.substring(0, words.first().range.first)
                    if (lead.isNotBlank()) pieces += r.t to lead
                    words.forEachIndexed { k, w ->
                        val endIdx = words.getOrNull(k + 1)?.range?.first ?: r.body.length
                        val txt = r.body.substring(w.range.last + 1, endIdx)
                        if (txt.isNotEmpty()) pieces += (secs(w.groupValues[1], w.groupValues[2]) - offset) to txt
                    }
                    pieces.mapIndexed { k, p -> Syllable(p.second, p.first, pieces.getOrNull(k + 1)?.first ?: minOf(next, p.first + 1.5)) }
                } else spread(r.body.trim(), r.t, minOf(next, r.t + 0.35 * r.body.length + 1.0))
                if (syl.isNotEmpty()) lines += LyricLine(r.t, minOf(next, syl.last().end + 0.5), syl)
            }
            return Lyrics(lines, source)
        }

        /** Without word timings, words are spread over the line in proportion to their length. */
        fun spread(line: String, start: Double, end: Double): List<Syllable> {
            val words = Regex("""\S+\s*""").findAll(line).map { it.value }.toList()
            if (words.isEmpty()) return emptyList()
            val total = words.sumOf { it.trim().length + 1 }.toDouble()
            var t = start
            return words.map { w ->
                val d = (end - start) * (w.trim().length + 1) / total
                Syllable(w, t, t + d).also { t += d }
            }
        }

        /** Lines timed by tapping along: each line starts at its tap and ends at the next. */
        fun fromTaps(lines: List<String>, taps: List<Double>, songLength: Double): Lyrics {
            val out = lines.indices.filter { it < taps.size }.map { i ->
                val start = taps[i]
                val end = taps.getOrNull(i + 1) ?: minOf(songLength, start + 0.35 * lines[i].length + 1.0)
                LyricLine(start, end, spread(lines[i], start, end))
            }
            return Lyrics(out, "Timed by tapping")
        }
    }
}
