package com.sirveillanceai.mixokeys.ml

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A model the app downloads once and then keeps on the phone. */
data class ModelSpec(
    val id: String,
    val title: String,
    val url: String,
    val fileName: String,
    val approxBytes: Long,
    val sha256: String? = null,
    val credit: String,
)

object Models {
    /** Hybrid Transformer Demucs, 6 stems (drums, bass, other, vocals, guitar, piano). MIT licence. */
    val DEMUCS_6S = ModelSpec(
        id = "demucs6s",
        title = "Instrument separator (Demucs 6-stem)",
        url = "https://huggingface.co/adowu/htdemucs-6s-onnx/resolve/main/htdemucs_6s_fp16weights.onnx",
        fileName = "htdemucs_6s_fp16weights.onnx",
        approxBytes = 136_000_000L,
        credit = "HT-Demucs by Meta AI Research (Défossez et al.), MIT licence. ONNX export by adowu.",
    )

    /** High-resolution piano transcription (Kong et al., ByteDance), notes only. CC BY 4.0. */
    val PIANO = ModelSpec(
        id = "piano",
        title = "Piano note reader (Kong high-resolution)",
        url = "https://huggingface.co/Clement11800/pianino-model/resolve/main/piano-notes-v1.onnx",
        fileName = "piano-notes-v1.onnx",
        approxBytes = 98_689_388L,
        sha256 = "9e33ffc0382fb023e0b1aac2abd0c3cb26a5d56fbcc246d7f2c0fc33c569f2ba",
        credit = "High-resolution piano transcription by Q. Kong, B. Li, X. Song, Y. Wan and Y. Wang (ByteDance), CC BY 4.0. ONNX conversion by Clement11800.",
    )

    val ALL = listOf(DEMUCS_6S, PIANO)
}

class ModelStore(context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }

    fun file(spec: ModelSpec) = File(dir, spec.fileName)
    fun isReady(spec: ModelSpec) = file(spec).let { it.exists() && it.length() > spec.approxBytes * 0.9 }

    /** Downloads [spec] if needed, resuming a partial download. Throws with a plain message on failure. */
    fun ensure(spec: ModelSpec, onProgress: (done: Long, total: Long) -> Unit) : File {
        val target = file(spec)
        if (isReady(spec)) return target
        val part = File(dir, spec.fileName + ".part")
        var attempt = 0
        while (true) {
            try {
                download(spec, part, onProgress)
                break
            } catch (e: Exception) {
                attempt++
                if (attempt >= 3) throw IllegalStateException("Couldn't download ${spec.title}: ${e.message}. Check the connection and try again.", e)
                Thread.sleep(1500L * attempt)
            }
        }
        spec.sha256?.let { expected ->
            val got = sha256(part)
            if (!got.equals(expected, ignoreCase = true)) {
                part.delete()
                throw IllegalStateException("${spec.title} downloaded but didn't pass its integrity check. Please try again.")
            }
        }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save ${spec.title}.")
        return target
    }

    private fun download(spec: ModelSpec, part: File, onProgress: (Long, Long) -> Unit) {
        var url = URL(spec.url)
        var redirects = 0
        while (true) {
            val have = if (part.exists()) part.length() else 0L
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Mixokeys/0.1")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("redirect without a location")
                url = URL(url, loc)
                conn.disconnect()
                if (++redirects > 8) throw IllegalStateException("too many redirects")
                continue
            }
            if (code == 416) { conn.disconnect(); return } // already complete
            if (code != 200 && code != 206) throw IllegalStateException("server answered $code")
            val append = code == 206
            val total = if (append) have + conn.contentLengthLong else conn.contentLengthLong
            var done = if (append) have else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) { onProgress(done, total.coerceAtLeast(spec.approxBytes)); lastReport = done }
                    }
                }
            }
            onProgress(done, total.coerceAtLeast(done))
            conn.disconnect()
            return
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
