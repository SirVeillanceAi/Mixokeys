package com.sirveillanceai.mixokeys

import android.content.Context
import android.media.MediaPlayer
import com.sirveillanceai.mixokeys.midi.Midi
import com.sirveillanceai.mixokeys.midi.Song
import java.io.File
import kotlin.math.abs

/**
 * Plays a song with Android's built-in MIDI synthesizer. Slower practice speeds and muted parts are made
 * by writing a changed copy of the file, so the sound never stretches or warbles.
 * Positions are in song seconds (the song's own timeline), whatever the speed.
 */
class MidiPlayer(private val context: Context, private val song: Song, private val onEnd: () -> Unit) {
    var speed = 1.0
        private set
    var muted: Set<String> = emptySet()
        private set
    var playing = false
        private set

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
            setOnCompletionListener { this@MidiPlayer.playing = false; anchorSong = song.duration; this@MidiPlayer.onEnd() }
            seekTo((at / speed * 1000).toInt())
        }
        anchorSong = at
        anchorNanos = System.nanoTime()
    }

    /** Smooth playback position: a steady clock, corrected now and then against the synthesizer. */
    fun position(): Double {
        val p = mp ?: return anchorSong
        if (!playing) return anchorSong
        val now = System.nanoTime()
        val est = anchorSong + (now - anchorNanos) / 1e9 * speed
        if (now - lastCheck > 250_000_000L) {
            lastCheck = now
            val real = p.currentPosition / 1000.0 * speed
            if (abs(real - est) > 0.08) { anchorSong = real; anchorNanos = now; return real }
        }
        return est.coerceAtMost(song.duration)
    }

    fun play() {
        if (playing) return
        if (anchorSong >= song.duration - 0.05) seek(0.0)
        mp?.start()
        playing = true
        anchorNanos = System.nanoTime()
    }

    fun pause() {
        if (!playing) return
        anchorSong = position()
        mp?.pause()
        playing = false
    }

    fun seek(t: Double) {
        val at = t.coerceIn(0.0, song.duration)
        mp?.seekTo((at / speed * 1000).toInt())
        anchorSong = at
        anchorNanos = System.nanoTime()
    }

    fun setSpeed(s: Double) = change { speed = s }
    fun setMuted(m: Set<String>) = change { muted = m }

    private inline fun change(block: () -> Unit) {
        val wasPlaying = playing
        val at = position()
        if (playing) { mp?.pause(); playing = false }
        block()
        rebuild(at)
        if (wasPlaying) play()
    }

    fun release() { mp?.release(); mp = null; playing = false }
}
