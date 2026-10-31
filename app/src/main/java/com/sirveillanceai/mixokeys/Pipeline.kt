package com.sirveillanceai.mixokeys

import ai.onnxruntime.OrtEnvironment
import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirveillanceai.mixokeys.audio.AudioDecoder
import com.sirveillanceai.mixokeys.audio.Resampler
import com.sirveillanceai.mixokeys.audio.WavWriter
import com.sirveillanceai.mixokeys.midi.MidiWriter
import com.sirveillanceai.mixokeys.ml.ModelStore
import com.sirveillanceai.mixokeys.ml.Models
import com.sirveillanceai.mixokeys.ml.Note
import com.sirveillanceai.mixokeys.ml.PianoTranscriber
import com.sirveillanceai.mixokeys.ml.Separator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

enum class StepState { Waiting, Running, Done, Skipped, Failed }

data class Step(val key: String, val label: String, val state: StepState = StepState.Waiting, val progress: Float = 0f, val seconds: Double? = null, val detail: String? = null)

data class Result(
    val title: String,
    val songSeconds: Double,
    val notes: List<Note>,
    val midiFile: File,
    val pianoStem: File?,
    val sourceUri: Uri,
)

data class UiState(
    val pickedUri: Uri? = null,
    val pickedName: String? = null,
    val soloPiano: Boolean = false,
    val running: Boolean = false,
    val steps: List<Step> = emptyList(),
    val error: String? = null,
    val result: Result? = null,
    val modelsReady: Map<String, Boolean> = emptyMap(),
)

class PipelineViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app)
    private val _ui = MutableStateFlow(UiState(modelsReady = Models.ALL.associate { it.id to store.isReady(it) }))
    val ui: StateFlow<UiState> = _ui
    private var job: Job? = null

    fun pick(uri: Uri) {
        val name = runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Song"
        _ui.update { it.copy(pickedUri = uri, pickedName = name, error = null, result = null, steps = emptyList()) }
    }

    fun setSolo(v: Boolean) = _ui.update { it.copy(soloPiano = v) }
    fun reset() = _ui.update { it.copy(result = null, steps = emptyList(), error = null) }

    fun cancel() {
        job?.cancel()
        _ui.update { it.copy(running = false, error = "Stopped.") }
    }

    fun start() {
        val s = _ui.value
        val uri = s.pickedUri ?: return
        if (s.running) return
        val solo = s.soloPiano
        val steps = buildList {
            add(Step("models", "Get the listening models (first time only)"))
            add(Step("decode", "Read the audio file"))
            if (!solo) add(Step("separate", "Separate the piano from the band"))
            add(Step("prep", "Prepare audio for the piano model"))
            add(Step("notes", "Read the piano notes"))
        }
        _ui.update { it.copy(running = true, steps = steps, error = null, result = null) }
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                runPipeline(uri, solo, s.pickedName ?: "Song")
            } catch (e: Throwable) {
                if (isActive) {
                    _ui.update { st -> st.copy(running = false, error = e.message ?: e.toString(),
                        steps = st.steps.map { if (it.state == StepState.Running) it.copy(state = StepState.Failed) else it }) }
                }
            }
        }
    }

    private fun step(key: String, f: (Step) -> Step) = _ui.update { st -> st.copy(steps = st.steps.map { if (it.key == key) f(it) else it }) }

    private inline fun <T> timed(key: String, block: ((Float) -> Unit) -> T): T {
        step(key) { it.copy(state = StepState.Running, progress = 0f) }
        val t0 = System.nanoTime()
        var last = 0L
        val out = block { p ->
            val now = System.nanoTime()
            if (now - last > 100_000_000L || p >= 1f) { last = now; step(key) { it.copy(progress = p) } }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        step(key) { it.copy(state = StepState.Done, progress = 1f, seconds = secs) }
        return out
    }

    private fun runPipeline(uri: Uri, solo: Boolean, title: String) {
        val app = getApplication<Application>()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val needed = if (solo) listOf(Models.PIANO) else listOf(Models.DEMUCS_6S, Models.PIANO)

        val files = timed("models") { prog ->
            val totalBytes = needed.sumOf { it.approxBytes }.toFloat()
            var before = 0L
            needed.associate { spec ->
                val f = store.ensure(spec) { done, _ -> prog(((before + done) / totalBytes).coerceAtMost(1f)) }
                before += spec.approxBytes
                spec.id to f
            }
        }
        _ui.update { it.copy(modelsReady = Models.ALL.associate { m -> m.id to store.isReady(m) }) }

        val pcm = timed("decode") { prog -> AudioDecoder.decode(app, uri, prog) }
        step("decode") { it.copy(detail = "%.0f s of audio, %d Hz, %d ch".format(pcm.seconds, pcm.sampleRate, pcm.channels.size)) }

        val env = OrtEnvironment.getEnvironment()
        var stemFile: File? = null
        val mono16k: FloatArray = if (solo) {
            timed("prep") { prog -> Resampler.resample(pcm.mono(), pcm.sampleRate, PianoTranscriber.SAMPLE_RATE, prog) }
        } else {
            val piano = timed("separate") { prog ->
                val stereo44 = pcm.stereo().let { st ->
                    if (pcm.sampleRate == Separator.SAMPLE_RATE) st
                    else Array(2) { c -> Resampler.resample(st[c], pcm.sampleRate, Separator.SAMPLE_RATE) { p -> prog(0.05f * (c + p) / 2f) } }
                }
                Separator(env, files.getValue(Models.DEMUCS_6S.id), threads).use { sep ->
                    val stems = sep.separate(stereo44, listOf(Separator.STEMS.indexOf("piano"))) { p -> prog(0.05f + 0.95f * p) }
                    stems.getValue("piano")
                }
            }
            val sec = pcm.seconds
            step("separate") { it.copy(detail = "%.1f× real time".format(sec / (it.seconds ?: 1.0))) }
            timed("prep") { prog ->
                val dir = File(app.cacheDir, "exports").apply { mkdirs() }
                stemFile = File(dir, "piano-part.wav").also { WavWriter.write(it, piano, Separator.SAMPLE_RATE) }
                val mono = FloatArray(piano[0].size) { (piano[0][it] + piano[1][it]) * 0.5f }
                Resampler.resample(mono, Separator.SAMPLE_RATE, PianoTranscriber.SAMPLE_RATE, prog)
            }
        }

        val notes = timed("notes") { prog ->
            PianoTranscriber(env, files.getValue(Models.PIANO.id), threads).use { it.transcribe(mono16k, prog) }
        }
        val sec = pcm.seconds
        step("notes") { it.copy(detail = "%d notes · %.1f× real time".format(notes.size, sec / (it.seconds ?: 1.0))) }

        val dir = File(app.cacheDir, "exports").apply { mkdirs() }
        val midi = File(dir, title.substringBeforeLast('.').replace(Regex("[^\\w\\- ]+"), "").ifBlank { "song" } + " (piano).mid")
        MidiWriter.write(midi, notes, title)

        _ui.update { it.copy(running = false, result = Result(title, sec, notes, midi, stemFile, uri)) }
    }
}
