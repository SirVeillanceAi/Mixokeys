package com.sirveillanceai.mixokeys

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sirveillanceai.mixokeys.lyrics.Lyrics
import com.sirveillanceai.mixokeys.midi.Midi
import com.sirveillanceai.mixokeys.midi.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Recent(val uri: String, val name: String)

data class AppState(
    val song: Song? = null,
    /** Identifies the song for saved lyrics. */
    val songKey: String? = null,
    val lyrics: Lyrics? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val recent: List<Recent> = emptyList(),
    val lastCrash: String? = null,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("library", 0)
    private val _s = MutableStateFlow(AppState(recent = loadRecent(), lastCrash = CrashReport.takeReport(app)))
    val state: StateFlow<AppState> = _s

    private fun lyricsDir() = File(getApplication<Application>().filesDir, "lyrics").apply { mkdirs() }
    private fun lyricsFile(key: String, ext: String) = File(lyricsDir(), key + ext)

    private fun loadRecent(): List<Recent> = prefs.getString("recent", "")!!.lines().mapNotNull {
        val p = it.split('\t'); if (p.size == 2) Recent(p[0], p[1]) else null
    }

    private fun saveRecent(list: List<Recent>) = prefs.edit().putString("recent", list.joinToString("\n") { "${it.uri}\t${it.name}" }).apply()

    fun dismissCrash() = _s.update { it.copy(lastCrash = null) }
    fun dismissError() = _s.update { it.copy(error = null) }
    fun close() = _s.update { it.copy(song = null, lyrics = null, songKey = null) }

    private fun displayName(uri: Uri): String = runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Song"

    fun open(uri: Uri, persist: Boolean) {
        val app = getApplication<Application>()
        _s.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                if (persist) runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val name = displayName(uri)
                val (song, key) = withContext(Dispatchers.IO) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file.")
                    val title = name.substringBeforeLast('.')
                    Midi.parse(bytes, title) to (title.replace(Regex("[^\\w\\- ]+"), "_").take(60) + "-" + bytes.size)
                }
                require(song.parts.any { it.notes.isNotEmpty() }) { "This MIDI file has no notes in it." }
                val saved = withContext(Dispatchers.IO) { loadSavedLyrics(key, song.duration) }
                val lyrics = saved ?: Lyrics.fromMidi(song)
                val recent = (listOf(Recent(uri.toString(), name)) + _s.value.recent.filter { it.uri != uri.toString() }).take(10)
                saveRecent(recent)
                _s.update { it.copy(song = song, songKey = key, lyrics = lyrics, loading = false, recent = recent) }
            } catch (e: Throwable) {
                _s.update { it.copy(loading = false, error = e.message ?: "Couldn't read that file.") }
            }
        }
    }

    fun forget(r: Recent) {
        val list = _s.value.recent.filter { it != r }
        saveRecent(list); _s.update { it.copy(recent = list) }
    }

    private fun loadSavedLyrics(key: String, len: Double): Lyrics? {
        lyricsFile(key, ".lrc").takeIf { it.exists() }?.let { return Lyrics.parse(it.readText(), "Your lyrics", len) }
        lyricsFile(key, ".txt").takeIf { it.exists() }?.let { return Lyrics.untimed(it.readText(), "Your lyrics") }
        return null
    }

    /** Lyrics from an .lrc or .txt file. */
    fun loadLyrics(uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { app.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } } ?: return@launch
                useLyricsText(text, displayName(uri))
            } catch (e: Throwable) {
                _s.update { it.copy(error = "Couldn't read the lyrics: ${e.message}") }
            }
        }
    }

    fun useLyricsText(text: String, source: String = "Your lyrics") {
        val st = _s.value
        val song = st.song ?: return
        val l = Lyrics.parse(text, source, song.duration)
        if (l.timed && l.lines.isEmpty() || !l.timed && l.untimedLines.isEmpty()) { _s.update { it.copy(error = "No lyrics found in that text.") }; return }
        save(l)
        _s.update { it.copy(lyrics = l) }
    }

    fun setLyrics(l: Lyrics) { save(l); _s.update { it.copy(lyrics = l) } }

    private fun save(l: Lyrics) {
        val key = _s.value.songKey ?: return
        lyricsFile(key, ".lrc").delete(); lyricsFile(key, ".txt").delete()
        if (l.timed) lyricsFile(key, ".lrc").writeText(l.toLrc()) else lyricsFile(key, ".txt").writeText(l.untimedLines.joinToString("\n"))
    }

    /** Remove your own lyrics and go back to the ones inside the file (if any). */
    fun resetLyrics() {
        val st = _s.value
        val key = st.songKey ?: return
        lyricsFile(key, ".lrc").delete(); lyricsFile(key, ".txt").delete()
        _s.update { it.copy(lyrics = st.song?.let { s -> Lyrics.fromMidi(s) }) }
    }

    fun lrcExport(): File? {
        val st = _s.value
        val l = st.lyrics ?: return null
        if (!l.timed) return null
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        return File(dir, (st.song?.title ?: "lyrics").replace(Regex("[^\\w\\- ]+"), "_") + ".lrc").also { it.writeText(l.toLrc()) }
    }
}
