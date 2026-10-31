package com.sirveillanceai.mixokeys.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit PCM WAV so a separated stem can be played back with MediaPlayer. */
object WavWriter {
    fun write(file: File, channels: Array<FloatArray>, sampleRate: Int) {
        val ch = channels.size
        val frames = channels[0].size
        val dataBytes = frames * ch * 2
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt(36 + dataBytes); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(ch.toShort())
            h.putInt(sampleRate); h.putInt(sampleRate * ch * 2); h.putShort((ch * 2).toShort()); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes)
            out.write(h.array())
            val buf = ByteBuffer.allocate(4096 * ch * 2).order(ByteOrder.LITTLE_ENDIAN)
            var f = 0
            while (f < frames) {
                buf.clear()
                val end = minOf(frames, f + 4096)
                for (i in f until end) for (c in 0 until ch) {
                    val v = (channels[c][i].coerceIn(-1f, 1f) * 32767f).toInt()
                    buf.putShort(v.toShort())
                }
                out.write(buf.array(), 0, buf.position())
                f = end
            }
        }
    }
}
