package com.mixokeys.app.audio

import android.content.Context
import android.media.AudioManager
import com.mixokeys.app.SongPlayer
import com.mixokeys.app.midi.Midi
import com.mixokeys.app.midi.Song
import java.io.File
import kotlin.math.abs

/**
 * FluidSynth with the GeneralUser GS soundfont: one synthesizer for the whole app.
 * Channels 0–15 play songs; channel 16 is the keyboard you play (on screen or a MIDI keyboard).
 */
object Synth {
    const val KEYS_CHANNEL = 16
    @Volatile var ready = false; private set
    /** The song player whose file FluidSynth has loaded. */
    var owner: Any? = null
    var error: String? = null; private set

    @JvmStatic private external fun nInit(sfPath: String, periodSize: Int): Boolean
    @JvmStatic external fun nNoteOn(ch: Int, key: Int, vel: Int)
    @JvmStatic external fun nNoteOff(ch: Int, key: Int)
    @JvmStatic external fun nProgram(ch: Int, prog: Int)
    @JvmStatic external fun nLoad(path: String): Boolean
    @JvmStatic external fun nPlay()
    @JvmStatic external fun nStop()
    @JvmStatic external fun nSeek(tick: Int): Boolean
    @JvmStatic external fun nTick(): Int
    @JvmStatic external fun nTotalTicks(): Int
    @JvmStatic external fun nStatus(): Int
    @JvmStatic external fun nTempo(mult: Double)

    /** Loads the native library and soundfont. Slow the first time (copies 32 MB); call off the main thread. */
    @Synchronized
    fun init(context: Context): Boolean {
        if (ready) return true
        try {
            System.loadLibrary("mixokeys_synth")
        } catch (e: Throwable) {
            error = "Sound engine not available (${e.message}); using Android's built-in sound."
            return false
        }
        val sf = File(context.filesDir, "GeneralUser-GS.sf2")
        try {
            val size = context.assets.openFd("GeneralUser-GS.sf2").use { it.length }
            if (!sf.exists() || (size > 0 && sf.length() != size)) {
                val tmp = File(context.filesDir, "sf2.tmp")
                context.assets.open("GeneralUser-GS.sf2").use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
                tmp.renameTo(sf)
            }
        } catch (e: Throwable) {
            // openFd fails for compressed assets; fall back to a plain copy if the file is missing
            if (!sf.exists()) runCatching {
                context.assets.open("GeneralUser-GS.sf2").use { inp -> sf.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            }.onFailure { error = "Soundfont missing: ${it.message}"; return false }
        }
        // Ask the phone for its preferred buffer size: the smallest that plays without glitches.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val frames = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192
        val period = frames.coerceIn(64, 512)
        ready = nInit(sf.absolutePath, period)
        if (!ready) error = "The sound engine didn't start; using Android's built-in sound."
        return ready
    }

    fun noteOn(key: Int, vel: Int = 96) { if (ready) nNoteOn(KEYS_CHANNEL, key, vel) }
    fun noteOff(key: Int) { if (ready) nNoteOff(KEYS_CHANNEL, key) }
}

/**
 * Plays a song through FluidSynth. Speed changes are live (tempo multiplier, no re-writing),
 * and the position comes straight from the synthesizer's tick counter, so it is exact.
 */
class FluidSongPlayer(context: Context, private val song: Song, private val onEnd: () -> Unit) : SongPlayer {
    override var speed = 1.0; private set
    override var muted: Set<String> = emptySet(); private set
    override var playing = false; private set
    override val engine = "FluidSynth · GeneralUser GS"

    private val file = File(context.cacheDir, "fluid-play.mid")
    private var paused = 0.0           // song time while not playing
    private var seekTarget = -1.0      // a seek the synth hasn't reached yet
    private var seekNanos = 0L
    private var retried = false
    private val totalTick = song.endTick

    init { load() }

    private fun load() {
        Midi.writeForPlayback(song, file, 1.0, muted)
        Synth.nLoad(file.absolutePath)
        Synth.nTempo(speed)
        Synth.owner = this
    }

    /** Song tick for song time [t] (inverse of the tempo map). */
    private fun tickOf(t: Double): Int {
        var lo = 0L; var hi = totalTick
        while (lo < hi) { val m = (lo + hi) / 2; if (song.seconds(m) < t) lo = m + 1 else hi = m }
        return lo.toInt().coerceIn(0, maxOf(0, totalTick.toInt() - 1))
    }

    private fun requestSeek(t: Double) {
        Synth.nSeek(tickOf(t))
        seekTarget = t; seekNanos = System.nanoTime(); retried = false
    }

    override fun position(): Double {
        if (!playing) return paused
        val t = song.seconds(Synth.nTick().toLong())
        if (seekTarget >= 0) {
            val waited = (System.nanoTime() - seekNanos) / 1e6
            if (abs(t - seekTarget) < 0.25) seekTarget = -1.0
            else if (waited > 150 && !retried) { Synth.nSeek(tickOf(seekTarget)); retried = true; return seekTarget }
            else if (waited < 400) return seekTarget
            else seekTarget = -1.0
        }
        if (Synth.nStatus() != 1 /* FLUID_PLAYER_PLAYING */) {
            playing = false; paused = song.duration; onEnd(); return paused
        }
        return t.coerceAtMost(song.duration)
    }

    override fun positionAt(nanos: Long): Double {
        val p = position()
        if (!playing) return p
        return (p - (System.nanoTime() - nanos) / 1e9 * speed).coerceAtLeast(0.0)
    }

    override fun play() {
        if (playing) return
        if (paused >= song.duration - 0.05) paused = 0.0
        if (Synth.owner !== this || (Synth.nStatus() != 1 && Synth.nTick() >= totalTick - 1)) load()  // another song was loaded, or finished
        Synth.nSeek(tickOf(paused))
        Synth.nPlay()
        seekTarget = paused; seekNanos = System.nanoTime(); retried = false
        playing = true
    }

    override fun pause() {
        if (!playing) return
        paused = position()
        if (Synth.owner === this) Synth.nStop()
        playing = false
    }

    override fun seek(t: Double) {
        val at = t.coerceIn(0.0, song.duration)
        if (playing) requestSeek(at) else paused = at
    }

    override fun setSpeed(s: Double) { speed = s; Synth.nTempo(s) }

    override fun setMuted(m: Set<String>) {
        if (m == muted) return
        val wasPlaying = playing
        val at = position()
        if (playing) { Synth.nStop(); playing = false }
        muted = m
        load()
        paused = at
        if (wasPlaying) play()
    }

    override fun release() { if (Synth.owner === this) Synth.nStop(); playing = false }
}
