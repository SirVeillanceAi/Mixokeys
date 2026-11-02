package com.mixokeys.app.midi

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** One note, timed both in seconds (for drawing against playback) and in ticks. */
data class Note(val pitch: Int, val start: Double, val end: Double, val velocity: Int, val startTick: Long, val endTick: Long)

/** The notes of one track on one channel. Type-0 files are split by channel. */
data class Part(
    val id: String,
    val track: Int,
    val channel: Int,
    val name: String,
    val program: Int,
    val notes: List<Note>,
) {
    val isDrums get() = channel == 9
    val isBass get() = !isDrums && program in 32..39
    val isGuitar get() = !isDrums && program in 24..31
    val isPiano get() = !isDrums && program in 0..7
    val label get() = name.ifBlank { if (isDrums) "Drums" else GM_FAMILIES[program / 8] }
}

data class TextEvent(val tick: Long, val time: Double, val text: String)
data class TimeSig(val tick: Long, val num: Int, val den: Int)

/** One raw event, kept so the file can be written back out (slower, or with parts muted). */
class RawEvent(val tick: Long, val bytes: ByteArray) {
    val status get() = bytes[0].toInt() and 0xFF
}

class Song(
    val title: String,
    val division: Int,
    val smpte: Boolean,
    val parts: List<Part>,
    /** Lyric meta events (FF 05), in time order. */
    val lyricEvents: List<TextEvent>,
    /** Text meta events (FF 01), which .kar files use for their lyrics. */
    val textEvents: List<TextEvent>,
    val tempos: List<Pair<Long, Int>>,
    val timeSigs: List<TimeSig>,
    /** Key signature: number of sharps (positive) or flats (negative). */
    val keySharps: Int,
    val endTick: Long,
    val rawTracks: List<List<RawEvent>>,
    private val tickToSec: (Long) -> Double,
) {
    val duration: Double = tickToSec(endTick)
    fun seconds(tick: Long) = tickToSec(tick)

    /** Bar start times in seconds, from the time signatures. */
    val bars: List<Double> by lazy { grid(perBeat = false) }
    /** Beat times in seconds. */
    val beats: List<Double> by lazy { grid(perBeat = true) }

    private fun grid(perBeat: Boolean): List<Double> {
        val out = ArrayList<Double>()
        val sigs = if (timeSigs.isEmpty() || timeSigs[0].tick > 0) listOf(TimeSig(0, 4, 4)) + timeSigs else timeSigs
        val q = if (smpte) 480 else division
        var i = 0
        var tick = 0L
        while (tick <= endTick && out.size < 100_000) {
            while (i + 1 < sigs.size && sigs[i + 1].tick <= tick) i++
            val sig = sigs[i]
            val beat = (q * 4L / sig.den).coerceAtLeast(1)
            out += tickToSec(tick)
            tick += if (perBeat) beat else beat * sig.num
        }
        return out
    }
}

val GM_FAMILIES = listOf(
    "Piano", "Chromatic percussion", "Organ", "Guitar", "Bass", "Strings", "Ensemble", "Brass",
    "Reed", "Pipe", "Synth lead", "Synth pad", "Synth effects", "Ethnic", "Percussive", "Sound effects",
)

object Midi {

    fun parse(bytes: ByteArray, title: String): Song {
        val r = Reader(bytes)
        // Some files (RIFF RMID) wrap the MIDI data; find the header.
        val start = indexOf(bytes, "MThd".toByteArray())
        require(start >= 0) { "This doesn't look like a MIDI file." }
        r.pos = start + 4
        val hlen = r.u32().toInt()
        r.u16() // format
        val ntrks = r.u16()
        val division = r.u16()
        r.pos = start + 8 + hlen
        val smpte = division and 0x8000 != 0
        val ticksPerSecSmpte = if (smpte) (256 - (division shr 8)) * (division and 0xFF) else 0

        val rawTracks = ArrayList<List<RawEvent>>()
        val tempos = ArrayList<Pair<Long, Int>>()
        val sigs = ArrayList<TimeSig>()
        var key = 0
        var keySet = false
        val names = HashMap<Int, String>()
        val lyricsRaw = ArrayList<Pair<Long, ByteArray>>()
        val textsRaw = ArrayList<Pair<Long, ByteArray>>()
        var endTick = 0L

        for (t in 0 until ntrks) {
            if (r.pos + 8 > bytes.size) break
            val id = String(bytes, r.pos, 4, Charsets.ISO_8859_1)
            r.pos += 4
            val len = r.u32().toInt()
            val trackEnd = (r.pos + len).coerceAtMost(bytes.size)
            if (id != "MTrk") { r.pos = trackEnd; continue }
            val events = ArrayList<RawEvent>()
            var tick = 0L
            var running = 0
            while (r.pos < trackEnd) {
                tick += r.vlq()
                if (r.pos >= trackEnd) break
                var status = bytes[r.pos].toInt() and 0xFF
                if (status < 0x80) status = running else r.pos++
                when {
                    status == 0xFF -> {
                        val type = r.u8()
                        val l = r.vlq().toInt()
                        val data = bytes.copyOfRange(r.pos, (r.pos + l).coerceAtMost(trackEnd))
                        r.pos += l
                        when (type) {
                            0x51 -> if (data.size >= 3) tempos += tick to (((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF))
                            0x58 -> if (data.size >= 2) sigs += TimeSig(tick, data[0].toInt() and 0xFF, 1 shl (data[1].toInt() and 0x0F))
                            0x59 -> if (data.isNotEmpty() && !keySet) { key = data[0].toInt(); keySet = true }
                            0x03 -> if (names[t] == null) names[t] = decode(data).trim()
                            0x05 -> lyricsRaw += tick to data
                            0x01 -> textsRaw += tick to data
                        }
                        if (type != 0x2F) events += RawEvent(tick, byteArrayOf(0xFF.toByte(), type.toByte()) + vlqBytes(data.size.toLong()) + data)
                    }
                    status == 0xF0 || status == 0xF7 -> {
                        val l = r.vlq().toInt()
                        val data = bytes.copyOfRange(r.pos, (r.pos + l).coerceAtMost(trackEnd))
                        r.pos += l
                        events += RawEvent(tick, byteArrayOf(status.toByte()) + vlqBytes(data.size.toLong()) + data)
                    }
                    status >= 0x80 -> {
                        running = status
                        val n = if (status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0) 1 else 2
                        val ev = ByteArray(n + 1)
                        ev[0] = status.toByte()
                        for (k in 1..n) ev[k] = bytes[(r.pos++).coerceAtMost(bytes.size - 1)]
                        events += RawEvent(tick, ev)
                    }
                    else -> r.pos++ // data byte with no running status: skip it
                }
            }
            r.pos = trackEnd
            endTick = maxOf(endTick, tick)
            rawTracks += events
        }

        // Tempo map: tick -> seconds
        tempos.sortBy { it.first }
        val tmap = if (tempos.isEmpty() || tempos[0].first > 0) listOf(0L to 500_000) + tempos else tempos
        val segTick = LongArray(tmap.size) { tmap[it].first }
        val segUs = IntArray(tmap.size) { tmap[it].second }
        val segSec = DoubleArray(tmap.size)
        val q = division.toDouble()
        for (i in 1 until tmap.size) segSec[i] = segSec[i - 1] + (segTick[i] - segTick[i - 1]) * segUs[i - 1] / 1e6 / q
        val toSec: (Long) -> Double = if (smpte) { tk -> tk.toDouble() / ticksPerSecSmpte } else { tk ->
            var lo = 0; var hi = segTick.size - 1
            while (lo < hi) { val m = (lo + hi + 1) / 2; if (segTick[m] <= tk) lo = m else hi = m - 1 }
            segSec[lo] + (tk - segTick[lo]) * segUs[lo] / 1e6 / q
        }

        // Notes, grouped into parts by (track, channel)
        val parts = ArrayList<Part>()
        rawTracks.forEachIndexed { t, events ->
            val byChannel = HashMap<Int, MutableList<Note>>()
            val programs = HashMap<Int, Int>()
            val open = HashMap<Int, ArrayDeque<Pair<Long, Int>>>() // key = ch*128+pitch
            for (e in events) {
                val s = e.status
                if (s < 0x80 || s >= 0xF0) continue
                val ch = s and 0x0F
                when (s and 0xF0) {
                    0xC0 -> if (programs[ch] == null) programs[ch] = e.bytes[1].toInt() and 0x7F
                    0x90, 0x80 -> {
                        val p = e.bytes[1].toInt() and 0x7F
                        val v = e.bytes[2].toInt() and 0x7F
                        val k = ch * 128 + p
                        if (s and 0xF0 == 0x90 && v > 0) {
                            open.getOrPut(k) { ArrayDeque() }.addLast(e.tick to v)
                        } else {
                            val on = open[k]?.removeFirstOrNull() ?: continue
                            byChannel.getOrPut(ch) { ArrayList() } += Note(p, toSec(on.first), toSec(e.tick), on.second, on.first, e.tick)
                        }
                    }
                }
            }
            // Notes never switched off: end them at the end of the song.
            for ((k, q2) in open) for (on in q2) {
                val ch = k / 128
                byChannel.getOrPut(ch) { ArrayList() } += Note(k % 128, toSec(on.first), toSec(endTick), on.second, on.first, endTick)
            }
            val split = byChannel.size > 1
            for ((ch, notes) in byChannel.toSortedMap()) {
                notes.sortWith(compareBy({ it.startTick }, { it.pitch }))
                val base = names[t] ?: ""
                parts += Part("t${t}c$ch", t, ch, if (split && base.isNotBlank()) "$base (ch ${ch + 1})" else base, programs[ch] ?: 0, notes)
            }
        }

        fun texts(list: List<Pair<Long, ByteArray>>) = list.sortedBy { it.first }.map { (tk, d) -> TextEvent(tk, toSec(tk), decode(d)) }

        sigs.sortBy { it.tick }
        return Song(
            title = title, division = division, smpte = smpte, parts = parts,
            lyricEvents = texts(lyricsRaw), textEvents = texts(textsRaw),
            tempos = tmap, timeSigs = sigs, keySharps = key.coerceIn(-7, 7), endTick = endTick,
            rawTracks = rawTracks, tickToSec = toSec,
        )
    }

    /**
     * Writes the song back out for playback: tempos scaled by [speed], and the notes of [muted] parts removed.
     * Played with Android's built-in MIDI synthesizer.
     */
    fun writeForPlayback(song: Song, file: File, speed: Double, muted: Set<String>) {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) { out.write(v ushr 24); out.write(v ushr 16 and 0xFF); out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        out.write("MThd".toByteArray()); u32(6); u16(1); u16(song.rawTracks.size.coerceAtLeast(1)); u16(song.division)
        val hasTempoAtZero = song.rawTracks.any { tr -> tr.any { it.tick == 0L && it.status == 0xFF && it.bytes[1].toInt() == 0x51 } }

        song.rawTracks.forEachIndexed { t, events ->
            val body = ByteArrayOutputStream()
            var last = 0L
            fun emit(tick: Long, b: ByteArray) { body.write(vlqBytes(tick - last)); body.write(b); last = tick }
            if (t == 0 && !hasTempoAtZero && !song.smpte) emit(0, tempoBytes((500_000 / speed).toInt()))
            for (e in events) {
                val s = e.status
                if (s in 0x80..0xEF) {
                    val type = s and 0xF0
                    if ((type == 0x90 || type == 0x80) && "t${t}c${s and 0x0F}" in muted) continue
                }
                if (s == 0xFF && e.bytes[1].toInt() == 0x51 && !song.smpte) {
                    val data = e.bytes.copyOfRange(e.bytes.size - 3, e.bytes.size)
                    val us = ((data[0].toInt() and 0xFF) shl 16) or ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                    emit(e.tick, tempoBytes((us / speed).toInt().coerceIn(1, 0xFFFFFF)))
                } else emit(e.tick, e.bytes)
            }
            body.write(vlqBytes(0)); body.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            out.write("MTrk".toByteArray()); u32(body.size()); body.writeTo(out)
        }
        file.writeBytes(out.toByteArray())
    }

    private fun tempoBytes(us: Int) = byteArrayOf(0xFF.toByte(), 0x51, 3, (us shr 16).toByte(), (us shr 8).toByte(), us.toByte())

    fun vlqBytes(value: Long): ByteArray {
        var v = value.coerceAtLeast(0)
        val tmp = ArrayList<Byte>()
        tmp += (v and 0x7F).toByte()
        v = v shr 7
        while (v > 0) { tmp += ((v and 0x7F) or 0x80).toByte(); v = v shr 7 }
        return tmp.reversed().toByteArray()
    }

    /** Lyrics in old files are often Windows-1252; newer ones UTF-8. */
    fun decode(data: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(data)).toString() } catch (_: Exception) {
            String(data, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private class Reader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int = if (pos < b.size) b[pos++].toInt() and 0xFF else 0
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()
        fun vlq(): Long {
            var v = 0L
            for (i in 0 until 4) {
                val c = u8()
                v = (v shl 7) or (c and 0x7F).toLong()
                if (c and 0x80 == 0) break
            }
            return v
        }
    }
}
