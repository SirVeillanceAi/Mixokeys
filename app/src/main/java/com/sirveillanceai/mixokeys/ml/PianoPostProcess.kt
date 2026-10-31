package com.sirveillanceai.mixokeys.ml

/** One transcribed note. Times in seconds, pitch as MIDI number, velocity 1–127. */
data class Note(val pitch: Int, val start: Double, val end: Double, val velocity: Int)

/**
 * Turns the model's frame-wise outputs into notes. A direct port of RegressionPostProcessor and
 * note_detection_with_onset_offset_regress from qiuqiangkong/piano_transcription_inference,
 * with the reference thresholds (onset 0.3, offset 0.3, frame 0.1).
 *
 * Arrays are [frames * 88], row-major (frame, key).
 */
object PianoPostProcess {
    const val FPS = 100.0
    const val KEYS = 88
    const val BEGIN_NOTE = 21
    const val ONSET_THRESHOLD = 0.3f
    const val OFFSET_THRESHOLD = 0.3f
    const val FRAME_THRESHOLD = 0.1f
    private const val MAX_NOTE_FRAMES = 600

    fun decode(regOnset: FloatArray, regOffset: FloatArray, frame: FloatArray, velocity: FloatArray, frames: Int): List<Note> {
        val (onset, onsetShift) = binarize(regOnset, frames, ONSET_THRESHOLD, neighbour = 2)
        val (offset, offsetShift) = binarize(regOffset, frames, OFFSET_THRESHOLD, neighbour = 4)
        val notes = ArrayList<Note>()
        for (k in 0 until KEYS) {
            detect(k, frames, frame, onset, onsetShift, offset, offsetShift, velocity) { bgn, fin, onShift, offShift, vel ->
                val start = (bgn + onShift) / FPS
                val end = (fin + offShift) / FPS
                val v = (vel * 128f).toInt().coerceIn(1, 127)
                if (end > start) notes += Note(k + BEGIN_NOTE, start, end, v)
            }
        }
        notes.sortWith(compareBy({ it.start }, { it.pitch }))
        return notes
    }

    /** Peaks in the regression curve above [threshold], with the paper's sub-frame shift (Sec. III-D). */
    internal fun binarize(reg: FloatArray, frames: Int, threshold: Float, neighbour: Int): Pair<BooleanArray, FloatArray> {
        val bin = BooleanArray(frames * KEYS)
        val shift = FloatArray(frames * KEYS)
        for (k in 0 until KEYS) {
            for (n in neighbour until frames - neighbour) {
                val x = reg[n * KEYS + k]
                if (x > threshold && isMonotonicNeighbour(reg, k, n, neighbour)) {
                    bin[n * KEYS + k] = true
                    val prev = reg[(n - 1) * KEYS + k]
                    val next = reg[(n + 1) * KEYS + k]
                    shift[n * KEYS + k] = if (prev > next) (next - prev) / (x - next) / 2f else (next - prev) / (x - prev) / 2f
                }
            }
        }
        return bin to shift
    }

    private fun isMonotonicNeighbour(reg: FloatArray, k: Int, n: Int, neighbour: Int): Boolean {
        for (i in 0 until neighbour) {
            if (reg[(n - i) * KEYS + k] < reg[(n - i - 1) * KEYS + k]) return false
            if (reg[(n + i) * KEYS + k] < reg[(n + i + 1) * KEYS + k]) return false
        }
        return true
    }

    private inline fun detect(
        k: Int, frames: Int, frame: FloatArray, onset: BooleanArray, onsetShift: FloatArray,
        offset: BooleanArray, offsetShift: FloatArray, velocity: FloatArray,
        emit: (bgn: Int, fin: Int, onShift: Float, offShift: Float, vel: Float) -> Unit,
    ) {
        var bgn = -1
        var frameDisappear = -1
        var offsetOccur = -1
        for (i in 0 until frames) {
            val idx = i * KEYS + k
            if (onset[idx]) {
                if (bgn >= 0) {
                    // Two onsets in a row (e.g. the pedal is held): close the first note just before.
                    val fin = maxOf(i - 1, 0)
                    emit(bgn, fin, onsetShift[bgn * KEYS + k], 0f, velocity[bgn * KEYS + k])
                    frameDisappear = -1; offsetOccur = -1
                }
                bgn = i
            }
            if (bgn >= 0 && i > bgn) {
                if (frame[idx] <= FRAME_THRESHOLD && frameDisappear < 0) frameDisappear = i
                if (offset[idx] && offsetOccur < 0) offsetOccur = i
                if (frameDisappear >= 0) {
                    val fin = if (offsetOccur >= 0 && offsetOccur - bgn > frameDisappear - offsetOccur) offsetOccur else frameDisappear
                    emit(bgn, fin, onsetShift[bgn * KEYS + k], offsetShift[fin * KEYS + k], velocity[bgn * KEYS + k])
                    bgn = -1; frameDisappear = -1; offsetOccur = -1
                }
                if (bgn >= 0 && (i - bgn >= MAX_NOTE_FRAMES || i == frames - 1)) {
                    emit(bgn, i, onsetShift[bgn * KEYS + k], offsetShift[i * KEYS + k], velocity[bgn * KEYS + k])
                    bgn = -1; frameDisappear = -1; offsetOccur = -1
                }
            }
        }
    }
}
