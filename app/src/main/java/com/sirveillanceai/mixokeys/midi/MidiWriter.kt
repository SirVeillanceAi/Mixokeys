package com.sirveillanceai.mixokeys.midi

import com.sirveillanceai.mixokeys.ml.Note
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToLong

/** Writes a standard MIDI file (type 0, 480 ticks per beat at 120 BPM, so 960 ticks = 1 second). */
object MidiWriter {
    private const val PPQ = 480
    private const val TICKS_PER_SEC = 960.0

    fun write(file: File, notes: List<Note>, title: String) {
        data class Ev(val tick: Long, val order: Int, val bytes: ByteArray)
        val evs = ArrayList<Ev>()
        for (n in notes) {
            val on = (n.start * TICKS_PER_SEC).roundToLong()
            val off = maxOf(on + 1, (n.end * TICKS_PER_SEC).roundToLong())
            evs += Ev(on, 1, byteArrayOf(0x90.toByte(), n.pitch.toByte(), n.velocity.toByte()))
            evs += Ev(off, 0, byteArrayOf(0x80.toByte(), n.pitch.toByte(), 0))
        }
        evs.sortWith(compareBy({ it.tick }, { it.order }))

        val track = ByteArrayOutputStream()
        fun vlq(v: Long) {
            var buffer = v and 0x7F
            var x = v shr 7
            while (x > 0) { buffer = (buffer shl 8) or ((x and 0x7F) or 0x80); x = x shr 7 }
            while (true) { track.write((buffer and 0xFF).toInt()); if (buffer and 0x80 != 0L) buffer = buffer shr 8 else break }
        }
        val name = title.take(100).toByteArray()
        vlq(0); track.write(byteArrayOf(0xFF.toByte(), 0x03, name.size.toByte())); track.write(name)
        vlq(0); track.write(byteArrayOf(0xFF.toByte(), 0x51, 0x03, 0x07, 0xA1.toByte(), 0x20)) // 500000 µs per beat
        vlq(0); track.write(byteArrayOf(0xC0.toByte(), 0x00))                                     // Acoustic Grand Piano
        var last = 0L
        for (e in evs) { vlq(e.tick - last); track.write(e.bytes); last = e.tick }
        vlq(0); track.write(byteArrayOf(0xFF.toByte(), 0x2F, 0x00))
        val body = track.toByteArray()

        file.outputStream().use { out ->
            out.write("MThd".toByteArray()); out.write(int32(6)); out.write(int16(0)); out.write(int16(1)); out.write(int16(PPQ))
            out.write("MTrk".toByteArray()); out.write(int32(body.size)); out.write(body)
        }
    }

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun int16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}
