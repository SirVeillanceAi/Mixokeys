package com.sirveillanceai.mixokeys.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * High-resolution piano transcription (Kong et al.) on 16 kHz mono audio.
 * The model sees fixed 10 s windows; like the reference code we slide by half a window and keep
 * the middle half of each one (first/last keep their outer quarter too), then decode notes.
 */
class PianoTranscriber(env: OrtEnvironment, modelFile: File, threads: Int) : Closeable {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val SEGMENT = 160_000
        const val SEG_FRAMES = 1001
    }

    private val env = env
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
    })
    private val inputName = session.inputNames.first()
    private val outNames = listOf("reg_onset_output", "reg_offset_output", "frame_output", "velocity_output")

    fun transcribe(mono16k: FloatArray, onProgress: (Float) -> Unit): List<Note> {
        val audioLen = mono16k.size
        val padded = ((audioLen + SEGMENT - 1) / SEGMENT).coerceAtLeast(1) * SEGMENT
        val starts = generateSequence(0) { it + SEGMENT / 2 }.takeWhile { it + SEGMENT <= padded }.toList()
        val n = starts.size
        val K = PianoPostProcess.KEYS
        val keep = SEG_FRAMES - 1                               // reference drops the last frame of each window
        val totalFrames = if (n == 1) SEG_FRAMES else (keep * 3 / 4) * 2 + (n - 2) * (keep / 2)
        val outs = Array(4) { FloatArray(totalFrames * K) }
        val input = FloatBuffer.allocate(SEGMENT)
        var written = 0

        starts.forEachIndexed { si, start ->
            input.clear()
            for (i in 0 until SEGMENT) { val j = start + i; input.put(if (j < audioLen) mono16k[j] else 0f) }
            input.rewind()
            // Which frames of this window to keep (deframe in the reference code).
            val (from, to) = when {
                n == 1 -> 0 to SEG_FRAMES
                si == 0 -> 0 to keep * 3 / 4
                si == n - 1 -> keep / 4 to keep
                else -> keep / 4 to keep * 3 / 4
            }
            OnnxTensor.createTensor(env, input, longArrayOf(1, SEGMENT.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    outNames.forEachIndexed { oi, name ->
                        val buf = (result.get(name).get() as OnnxTensor).floatBuffer  // [1, 1001, 88]
                        val dst = outs[oi]
                        var w = written * K
                        for (f in from until to) for (k in 0 until K) dst[w++] = buf.get(f * K + k)
                    }
                }
            }
            written += to - from
            onProgress((si + 1f) / n)
        }
        val frames = minOf(written, (audioLen / (SAMPLE_RATE / 100.0)).toInt() + 1)
        return PianoPostProcess.decode(outs[0], outs[1], outs[2], outs[3], frames)
    }

    override fun close() = session.close()
}
