package com.mixokeys.app

import android.content.Context
import android.media.MediaPlayer
import com.mixokeys.app.midi.Midi
import com.mixokeys.app.midi.Song
import java.io.File
import kotlin.math.abs

/**
 * Plays a song with Android's built-in MIDI synthesizer. Slower practice speeds and muted parts are made
 * by writing a changed copy of the file, so the sound never stretches or warbles.
 * Positions are in song seconds (the song's own timeline), whatever the speed.
 */
/** Plays a song; positions are in song seconds whatever the speed. */
interface SongPlayer {
    val speed: Double
    val muted: Set<String>
    val playing: Boolean
    /** Which synthesizer is making the sound, for Settings. */
    val engine: String
    fun position(): Double
    fun positionAt(nanos: Long): Double
    fun play()
    fun pause()
    fun seek(t: Double)
    fun setSpeed(s: Double)
    fun setMuted(m: Set<String>)
    fun release()
}

/** FluidSynth when it's available (better sound, live speed), otherwise Android's built-in synthesizer. */
fun MidiPlayer(context: Context, song: Song, onEnd: () -> Unit): SongPlayer =
    if (com.mixokeys.app.audio.Synth.ready) com.mixokeys.app.audio.FluidSongPlayer(context, song, onEnd)
    else SonivoxPlayer(context, song, onEnd)

class SonivoxPlayer(private val context: Context, private val song: Song, private val onEnd: () -> Unit) : SongPlayer {
    override var speed = 1.0
        private set
    override var muted: Set<String> = emptySet()
        private set
    override var playing = false
        private set
    override val engine = "Android built-in"

    private var mp: MediaPlayer? = null
    private var version = 0
    private var anchorSong = 0.0
    private var anchorNanos = 0L
    private var lastCheck = 0L

    init { rebuild(0.0) }

    private fun rebuild(at: Double) {
        mp?.release()
        val f = File(context.cacheDir, "play-${version++ % 2}.mid")
        Midi.writeForPlayback(song, f, speed, muted)
        mp = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            setOnCompletionListener { this@SonivoxPlayer.playing = false; anchorSong = song.duration; this@SonivoxPlayer.onEnd() }
            seekTo((at / speed * 1000).toInt())
        }
        anchorSong = at
        anchorNanos = System.nanoTime()
    }

    /** Smooth playback position: a steady clock, corrected now and then against the synthesizer. */
    override fun position(): Double {
        val p = mp ?: return anchorSong
        if (!playing) return anchorSong
        val now = System.nanoTime()
        val est = anchorSong + (now - anchorNanos) / 1e9 * speed
        if (now - lastCheck > 250_000_000L) {
            lastCheck = now
            val real = p.currentPosition / 1000.0 * speed
            if (abs(real - est) > 0.08) { anchorSong = real; anchorNanos = now; return real }
            // small differences: ease the clock toward the synthesizer so it never jumps
            anchorSong += (real - est) * 0.15
        }
        return est.coerceAtMost(song.duration)
    }

    /** Song time at [nanos] (System.nanoTime base), e.g. when a key was pressed. */
    override fun positionAt(nanos: Long): Double {
        if (!playing) return anchorSong
        return (anchorSong + (nanos - anchorNanos) / 1e9 * speed).coerceIn(0.0, song.duration)
    }

    override fun play() {
        if (playing) return
        if (anchorSong >= song.duration - 0.05) seek(0.0)
        mp?.start()
        playing = true
        anchorNanos = System.nanoTime()
    }

    override fun pause() {
        if (!playing) return
        anchorSong = position()
        mp?.pause()
        playing = false
    }

    override fun seek(t: Double) {
        val at = t.coerceIn(0.0, song.duration)
        mp?.seekTo((at / speed * 1000).toInt())
        anchorSong = at
        anchorNanos = System.nanoTime()
    }

    override fun setSpeed(s: Double) = change { speed = s }
    override fun setMuted(m: Set<String>) = change { muted = m }

    private inline fun change(block: () -> Unit) {
        val wasPlaying = playing
        val at = position()
        if (playing) { mp?.pause(); playing = false }
        block()
        rebuild(at)
        if (wasPlaying) play()
    }

    override fun release() { mp?.release(); mp = null; playing = false }
}
