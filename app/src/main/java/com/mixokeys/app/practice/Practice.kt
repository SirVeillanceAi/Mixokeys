package com.mixokeys.app.practice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mixokeys.app.midi.Note
import kotlin.math.abs

enum class Grade { Perfect, Good, Ok, Missed }

enum class Strictness(val label: String, val window: Double) {
    Strict("Strict ±60 ms", 0.06),
    Normal("Normal ±100 ms", 0.10),
    Relaxed("Relaxed ±160 ms", 0.16),
}

/** One note the player should play. [key] matches the note head on the sheet: unit * 128 + pitch. */
class Target(val pitch: Int, val time: Double, val key: Long) { var done = false }
class Step(val time: Double, val targets: List<Target>)

/**
 * Judges what is played on a MIDI keyboard against the shown notes.
 *  - Wait mode: the music stops at each note or chord until you play it.
 *  - Play-along: the music keeps going; each note is graded by how close in time it was.
 * Times are in song seconds; the caller converts each key's own timestamp to song time,
 * minus the measured output latency, so judging is as precise as the keyboard itself.
 */
class Practice(notes: List<Note>, unitKey: (Note) -> Long) {
    val steps: List<Step>
    init {
        val ts = notes.map { Target(it.pitch, it.start, unitKey(it)) }.distinctBy { it.key }.sortedBy { it.time }
        val out = ArrayList<Step>()
        var cur = ArrayList<Target>()
        for (t in ts) {
            if (cur.isNotEmpty() && t.time - cur[0].time > 0.03) { out += Step(cur[0].time, cur); cur = ArrayList() }
            cur += t
        }
        if (cur.isNotEmpty()) out += Step(cur[0].time, cur)
        steps = out
    }

    val marks = mutableStateMapOf<Long, Grade>()
    var perfect by mutableIntStateOf(0); private set
    var good by mutableIntStateOf(0); private set
    var ok by mutableIntStateOf(0); private set
    var missed by mutableIntStateOf(0); private set
    var wrong by mutableIntStateOf(0); private set
    var lastWrongNanos by mutableLongStateOf(0L); private set
    var lastGrade by mutableStateOf<Grade?>(null); private set
    var held by mutableStateOf<Set<Int>>(emptySet()); private set

    /** Wait mode: index of the step the music is waiting on. */
    var cursor by mutableIntStateOf(0); private set
    private var sweep = 0

    val accuracy: Int get() {
        val total = perfect + good + ok + missed + wrong
        return if (total == 0) 100 else ((perfect + good + ok) * 100.0 / total).toInt()
    }
    val current: Step? get() = steps.getOrNull(cursor)

    /** Start again from song time [t]: clear grades from there on. */
    fun restartAt(t: Double) {
        cursor = steps.indexOfFirst { it.time >= t - 0.01 }.let { if (it < 0) steps.size else it }
        sweep = cursor
        for (s in steps.subList(cursor, steps.size)) for (n in s.targets) { n.done = false; marks.remove(n.key) }
    }

    fun resetScore() { perfect = 0; good = 0; ok = 0; missed = 0; wrong = 0; lastGrade = null }

    private fun grade(n: Target, g: Grade) {
        n.done = true; marks[n.key] = g; lastGrade = g
        when (g) { Grade.Perfect -> perfect++; Grade.Good -> good++; Grade.Ok -> ok++; Grade.Missed -> missed++ }
    }

    private fun wrongNote(nanos: Long) { wrong++; lastWrongNanos = nanos; lastGrade = null }

    fun keyUp(pitch: Int) { held = held - pitch }

    /** Wait mode: is the music allowed past song time [pos]? false = pause and wait. */
    fun waitAllows(pos: Double): Boolean {
        val s = current ?: return true
        return pos < s.time - 0.005 || s.targets.all { it.done }
    }

    /** Wait mode: a key went down. Returns true when the waited-for step is now complete. */
    fun keyDownWaiting(pitch: Int, nanos: Long): Boolean {
        held = held + pitch
        val s = current ?: return false
        val t = s.targets.firstOrNull { it.pitch == pitch && !it.done }
        if (t == null) {
            // re-striking a note of this or the last chord is fine; anything else is a wrong note
            val prev = steps.getOrNull(cursor - 1)
            if (s.targets.none { it.pitch == pitch } && prev?.targets?.none { it.pitch == pitch } != false) wrongNote(nanos)
            return false
        }
        grade(t, Grade.Perfect)
        if (s.targets.all { it.done }) { cursor++; sweep = cursor; return true }
        return false
    }

    /** Play-along: a key went down at song time [t]. */
    fun keyDownAlong(pitch: Int, nanos: Long, t: Double, window: Double) {
        held = held + pitch
        var best: Target? = null; var bestD = Double.MAX_VALUE
        var i = (sweep - 2).coerceAtLeast(0)
        while (i < steps.size && steps[i].time <= t + window) {
            for (n in steps[i].targets) if (!n.done && n.pitch == pitch) {
                val d = abs(n.time - t)
                if (d <= window && d < bestD) { best = n; bestD = d }
            }
            i++
        }
        val n = best ?: return wrongNote(nanos)
        grade(n, when { bestD <= window * 0.4 -> Grade.Perfect; bestD <= window * 0.7 -> Grade.Good; else -> Grade.Ok })
    }

    /** Play-along: notes whose window has passed without being played become Missed. */
    fun sweepMissed(pos: Double, window: Double) {
        while (sweep < steps.size && steps[sweep].time < pos - window) {
            for (n in steps[sweep].targets) if (!n.done) grade(n, Grade.Missed)
            sweep++
        }
    }
}
