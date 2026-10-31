package com.sirveillanceai.mixokeys.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Decoded audio as one float array per channel, values in [-1, 1]. */
class PcmAudio(val channels: Array<FloatArray>, val sampleRate: Int) {
    val frames: Int get() = channels.firstOrNull()?.size ?: 0
    val seconds: Double get() = frames.toDouble() / sampleRate

    fun mono(): FloatArray {
        if (channels.size == 1) return channels[0]
        val out = FloatArray(frames)
        val k = 1f / channels.size
        for (ch in channels) for (i in out.indices) out[i] += ch[i] * k
        return out
    }

    /** Exactly two channels (mono is duplicated, extra channels are dropped). */
    fun stereo(): Array<FloatArray> = when {
        channels.size >= 2 -> arrayOf(channels[0], channels[1])
        else -> arrayOf(channels[0], channels[0].copyOf())
    }
}

/** Growable float buffer so we never need to know the length up front. */
internal class FloatList(initial: Int = 1 shl 20) {
    var data = FloatArray(initial)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray(): FloatArray = data.copyOf(size)
}

object AudioDecoder {
    /** Decodes any audio file Android can read (MP3, M4A/AAC, WAV, FLAC, OGG, Opus…) to PCM. */
    fun decode(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): PcmAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var track = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) { track = i; break }
        }
        require(track >= 0) { "That file has no audio track." }
        extractor.selectTrack(track)
        val inFormat = extractor.getTrackFormat(track)
        val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L

        var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inFormat, null, null, 0)
        codec.start()

        val interleaved = FloatList()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val t = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, n, t, 0)
                            if (durationUs > 0) onProgress((t.toDouble() / durationUs).toFloat().coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channelCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            floatPcm = f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val out = codec.getOutputBuffer(outIndex)!!
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.slice().order(ByteOrder.nativeOrder())
                            if (floatPcm) {
                                val fb = ordered.asFloatBuffer()
                                while (fb.hasRemaining()) interleaved.add(fb.get())
                            } else {
                                val sb = ordered.asShortBuffer()
                                while (sb.hasRemaining()) interleaved.add(sb.get() / 32768f)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
        onProgress(1f)

        val total = interleaved.size / channelCount
        val chans = Array(channelCount) { FloatArray(total) }
        val d = interleaved.data
        var i = 0
        for (f in 0 until total) for (c in 0 until channelCount) chans[c][f] = d[i++]
        return PcmAudio(chans, sampleRate)
    }
}
