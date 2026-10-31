package com.sirveillanceai.mixokeys.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Splits a song into stems with HT-Demucs 6-stem (ONNX).
 * Input: stereo, 44.1 kHz. The model takes fixed 7.8 s windows; we slide it with 25 % overlap and
 * cross-fade with Demucs's own triangular weighting, exactly like `demucs.apply.apply_model`.
 */
class Separator(env: OrtEnvironment, modelFile: File, threads: Int) : Closeable {
    companion object {
        const val SAMPLE_RATE = 44_100
        const val SEGMENT = 343_980
        const val OVERLAP = 0.25
        val STEMS = listOf("drums", "bass", "other", "vocals", "guitar", "piano")
    }

    private val env = env
    private val session: OrtSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(threads)
        // Don't keep a growing memory pool between windows; phones have little headroom.
        setCPUArenaAllocator(false)
    })
    private val inputName = session.inputNames.first()

    private val weight: FloatArray = run {
        val half = SEGMENT / 2
        val w = FloatArray(SEGMENT) { i -> if (i < half) (i + 1).toFloat() else (SEGMENT - i).toFloat() }
        val max = w.max()
        for (i in w.indices) w[i] /= max
        w
    }

    /**
     * @param stereo two channels at 44.1 kHz
     * @param want indexes into [STEMS] to keep (others are discarded to save memory)
     * @return map stem name -> two channels
     */
    fun separate(stereo: Array<FloatArray>, want: List<Int>, beforeWindow: (Int, Int) -> Unit = { _, _ -> }, onProgress: (Float) -> Unit): Map<String, Array<FloatArray>> {
        val len = stereo[0].size
        // Normalise like the demucs CLI: by the mean/std of the mono mix.
        var mean = 0.0
        for (i in 0 until len) mean += (stereo[0][i] + stereo[1][i]) * 0.5
        mean /= len.coerceAtLeast(1)
        var varSum = 0.0
        for (i in 0 until len) { val d = (stereo[0][i] + stereo[1][i]) * 0.5 - mean; varSum += d * d }
        val std = sqrt(varSum / len.coerceAtLeast(1)).coerceAtLeast(1e-8)
        val fMean = mean.toFloat(); val fStd = std.toFloat()

        val outs = want.associateWith { Array(2) { FloatArray(len) } }
        val sumW = FloatArray(len)
        val stride = (SEGMENT * (1 - OVERLAP)).toInt()
        val starts = generateSequence(0) { it + stride }.takeWhile { it < len }.toList()
        val input = FloatBuffer.allocate(2 * SEGMENT)

        starts.forEachIndexed { si, offset ->
            val valid = minOf(SEGMENT, len - offset)
            input.clear()
            for (c in 0 until 2) {
                val ch = stereo[c]
                for (i in 0 until SEGMENT) input.put(if (i < valid) (ch[offset + i] - fMean) / fStd else 0f)
            }
            input.rewind()
            beforeWindow(si + 1, starts.size)
            OnnxTensor.createTensor(env, input, longArrayOf(1, 2, SEGMENT.toLong())).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val out = (result.get(0) as OnnxTensor).floatBuffer   // [1, 6, 2, SEGMENT]
                    for ((stem, dst) in outs) for (c in 0 until 2) {
                        val base = (stem * 2 + c) * SEGMENT
                        val d = dst[c]
                        for (i in 0 until valid) d[offset + i] += weight[i] * out.get(base + i)
                    }
                }
            }
            for (i in 0 until valid) sumW[offset + i] += weight[i]
            onProgress((si + 1f) / starts.size)
        }
        for ((_, dst) in outs) for (c in 0 until 2) {
            val d = dst[c]
            for (i in 0 until len) d[i] = d[i] / sumW[i].coerceAtLeast(1e-8f) * fStd + fMean
        }
        return outs.mapKeys { STEMS[it.key] }
    }

    override fun close() = session.close()
}
