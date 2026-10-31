package com.sirveillanceai.mixokeys.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Band-limited resampler: a Blackman-windowed sinc, looked up from a table so it stays fast
 * on a phone. Good enough that it doesn't affect transcription (passband ripple ≪ 0.1 dB).
 */
object Resampler {
    private const val ZERO_CROSSINGS = 24        // half-width of the filter, in input samples at full band
    private const val TABLE_RES = 512            // table entries per unit

    fun resample(input: FloatArray, fromRate: Int, toRate: Int, onProgress: (Float) -> Unit = {}): FloatArray {
        if (fromRate == toRate) return input.copyOf()
        val ratio = toRate.toDouble() / fromRate
        val cutoff = min(1.0, ratio) * 0.94                   // keep a little below Nyquist
        val halfWidth = ZERO_CROSSINGS / cutoff               // in input samples
        val tableLen = (halfWidth * TABLE_RES).toInt() + 2
        val table = FloatArray(tableLen) { i ->
            val x = i.toDouble() / TABLE_RES                  // distance in input samples
            val s = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            val w = 0.42 + 0.5 * cos(PI * x / halfWidth) + 0.08 * cos(2 * PI * x / halfWidth)
            (cutoff * s * if (x <= halfWidth) w else 0.0).toFloat()
        }
        val outLen = floor(input.size * ratio).toInt()
        val out = FloatArray(outLen)
        val hw = halfWidth.toInt() + 1
        val step = (outLen / 100).coerceAtLeast(1)
        for (n in 0 until outLen) {
            val t = n / ratio
            val center = floor(t).toInt()
            var acc = 0.0f
            val lo = maxOf(0, center - hw + 1)
            val hi = minOf(input.size - 1, center + hw)
            for (k in lo..hi) {
                val pos = abs(t - k) * TABLE_RES
                val idx = pos.toInt()
                if (idx >= tableLen - 1) continue
                val frac = (pos - idx).toFloat()
                val c = table[idx] + (table[idx + 1] - table[idx]) * frac
                acc += input[k] * c
            }
            out[n] = acc
            if (n % step == 0) onProgress(n.toFloat() / outLen)
        }
        onProgress(1f)
        return out
    }
}
