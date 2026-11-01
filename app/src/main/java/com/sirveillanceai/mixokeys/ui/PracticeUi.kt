package com.sirveillanceai.mixokeys.ui

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirveillanceai.mixokeys.PracticePrefs
import com.sirveillanceai.mixokeys.input.MidiKeyboard
import com.sirveillanceai.mixokeys.midi.Midi
import com.sirveillanceai.mixokeys.practice.Strictness
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/* ───────────────────────── Keyboard + practice settings ───────────────────────── */

@Composable
fun KeyboardDialog(kb: MidiKeyboard, prefs: PracticePrefs, onResetScore: () -> Unit, onDismiss: () -> Unit) {
    var calibrating by remember { mutableStateOf(false) }
    val perms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res -> if (res.values.all { it }) kb.startScan() }
    if (calibrating) { CalibrationDialog(kb, prefs) { calibrating = false }; return }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Play with your keyboard") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val name = kb.connectedName
                Text(if (name != null) "Connected: $name" else "No keyboard connected", fontWeight = FontWeight.Bold,
                    color = if (name != null) Color(0xFF49D6A0) else Palette.Muted)
                if (!kb.supported) Text("This phone doesn't support MIDI.", color = Palette.Bad)
                if (name == null) Text("Plug your keyboard into the phone with a USB-OTG adapter (it connects by itself), or find it over Bluetooth.", fontSize = 13.sp, color = Palette.Muted)
                kb.devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { kb.open(d) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("USB / MIDI", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.width(76.dp))
                        Text(kb.nameOf(d), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                OutlinedButton(onClick = { askBt.launch(perms) }, enabled = !kb.scanning) { Text(if (kb.scanning) "Searching for Bluetooth keyboards…" else "Find Bluetooth keyboards") }
                kb.bleFound.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { kb.openBluetooth(d) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bluetooth", fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.width(76.dp))
                        Text(kb.bleName(d), fontSize = 15.sp, modifier = Modifier.weight(1f))
                    }
                }
                kb.lastError?.let { Text(it, color = Palette.Bad, fontSize = 13.sp) }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check my playing", fontWeight = FontWeight.Bold)
                        Text("Notes turn green when right, red when missed.", fontSize = 12.sp, color = Palette.Muted)
                    }
                    Switch(checked = prefs.enabled, onCheckedChange = prefs::setEnabled)
                }
                Text("How it plays", fontSize = 13.sp, color = Palette.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = prefs.waitMode, onClick = { prefs.setWait(true) }, label = { Text("Wait for me") })
                    FilterChip(selected = !prefs.waitMode, onClick = { prefs.setWait(false) }, label = { Text("Play along") })
                }
                Text(if (prefs.waitMode) "The music stops at each note until you play it." else "The music keeps going; each note is graded Perfect, Good or OK by timing.",
                    fontSize = 12.sp, color = Palette.Muted)
                if (!prefs.waitMode) {
                    Text("Timing", fontSize = 13.sp, color = Palette.Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Strictness.entries.forEach { s -> FilterChip(selected = prefs.strictness == s, onClick = { prefs.setStrict(s) }, label = { Text(s.label.substringBefore(' ')) }) }
                    }
                    Text("${prefs.strictness.label}: how early or late a note may be and still count. Perfect is within the inner 40%.", fontSize = 12.sp, color = Palette.Muted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mute my part while I play", modifier = Modifier.weight(1f))
                    Switch(checked = prefs.muteMine, onCheckedChange = prefs::setMuteMine)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Delay correction: ${(prefs.latency * 1000).roundToInt()} ms")
                        Text(if (prefs.calibrated) "Measured on this phone" else "A guess. Measure it for fair timing.", fontSize = 12.sp, color = Palette.Muted)
                    }
                    OutlinedButton(onClick = { calibrating = true }, enabled = name != null) { Text("Measure") }
                }
                TextButton(onClick = onResetScore) { Text("Reset my score for this song") }
            }
        },
    )
}

/* ───────────────────────── Delay calibration ───────────────────────── */

private const val CLICKS = 16
private const val COUNT_IN = 4
private const val BEAT = 0.6 // seconds (100 bpm)

/** A short MIDI file of woodblock clicks, played through the same synthesizer as the songs. */
private fun clickFile(ctx: Context): File {
    val body = ByteArrayOutputStream()
    fun ev(delta: Int, vararg b: Int) { body.write(Midi.vlqBytes(delta.toLong())); b.forEach { body.write(it) } }
    ev(0, 0xFF, 0x51, 3, 0x09, 0x27, 0xC0)                         // 600000 µs per beat
    for (i in 0 until CLICKS) {
        val note = if (i < COUNT_IN) 77 else 76
        ev(if (i == 0) 0 else 400, 0x99, note, 120)
        ev(80, 0x89, note, 0)
    }
    ev(0, 0xFF, 0x2F, 0)
    val out = ByteArrayOutputStream()
    out.write("MThd".toByteArray()); out.write(byteArrayOf(0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0.toByte()))
    out.write("MTrk".toByteArray()); val n = body.size()
    out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())); body.writeTo(out)
    return File(ctx.cacheDir, "clicks.mid").also { it.writeBytes(out.toByteArray()) }
}

/**
 * Measures the total delay between a sound leaving the phone and your key press arriving
 * (speaker/headphone delay + keyboard + your reaction to a steady beat). Tap along to 12 clicks.
 */
@Composable
fun CalibrationDialog(kb: MidiKeyboard, prefs: PracticePrefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var startNanos by remember { mutableLongStateOf(0L) }
    val offsets = remember { mutableStateListOf<Double>() }
    var result by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var beatNow by remember { mutableIntStateOf(0) }
    val mp = remember { MediaPlayer() }
    DisposableEffect(Unit) { kb.exclusive = true; onDispose { mp.release(); kb.exclusive = false } }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        kb.events.clear(); offsets.clear(); result = null
        mp.reset(); mp.setDataSource(clickFile(ctx).absolutePath); mp.prepare()
        mp.start(); startNanos = System.nanoTime()
        val end = startNanos + ((CLICKS + 1) * BEAT * 1e9).toLong()
        while (System.nanoTime() < end) {
            while (true) {
                val e = kb.events.poll() ?: break
                if (!e.down) continue
                val t = (e.nanos - startNanos) / 1e9
                val beat = (t / BEAT).roundToInt()
                if (beat >= COUNT_IN && beat < CLICKS) {
                    val off = t - beat * BEAT
                    if (abs(off) < BEAT * 0.45) offsets += off
                }
            }
            beatNow = ((System.nanoTime() - startNanos) / 1e9 / BEAT).toInt()
            delay(10)
        }
        running = false
        if (offsets.size >= 5) {
            val s = offsets.sorted()
            val med = s[s.size / 2]
            val spread = (s[(s.size * 3) / 4] - s[s.size / 4]) / 2
            result = med to spread
        }
    }

    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = {
            val r = result
            if (r != null) TextButton(onClick = { prefs.setLatency(r.first.coerceIn(-0.05, 0.4)); onDone() }) { Text("Use ${(r.first * 1000).roundToInt()} ms") }
            else TextButton(onClick = { running = true }, enabled = !running) { Text(if (running) "Listening…" else "Start") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
        title = { Text("Measure the delay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use the same speaker or headphones you practise with. After 4 count-in clicks, press any key exactly with each click (12 presses).", fontSize = 14.sp)
                if (running) {
                    val beat = beatNow.coerceAtMost(CLICKS)
                    LinearProgressIndicator(progress = { beat / CLICKS.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(if (beat < COUNT_IN) "Count-in ${beat + 1}…" else "Press with the click · ${offsets.size} so far", color = Palette.Muted)
                }
                result?.let { (med, spread) ->
                    Text("Delay: ${(med * 1000).roundToInt()} ms", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Your presses varied by about ±${(spread * 1000).roundToInt()} ms.", color = Palette.Muted, fontSize = 13.sp)
                }
                if (!running && result == null && offsets.isNotEmpty()) Text("Not enough presses were caught. Try again.", color = Palette.Bad)
            }
        },
    )
}

/* ───────────────────────── Key strip ───────────────────────── */

private fun isBlackKey(p: Int) = (p % 12) in intArrayOf(1, 3, 6, 8, 10)

/** A slim keyboard: keys you are holding (blue) and the keys to play now (orange outline). */
@Composable
fun KeyStrip(lo: Int, hi: Int, held: Set<Int>, expected: Set<Int>, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF16161E))) {
        val whites = (lo..hi).count { !isBlackKey(it) }.coerceAtLeast(1)
        val ww = size.width / whites; val bw = ww * 0.6f
        fun x(p: Int): Float { val w = (lo until p).count { !isBlackKey(it) }; return if (isBlackKey(p)) w * ww - bw / 2 else w * ww }
        val heldC = Color(0xFF4D96FF); val wantC = Color(0xFFE0701A)
        for (p in lo..hi) if (!isBlackKey(p)) {
            val c = when { p in held && p in expected -> Color(0xFF49D6A0); p in held -> heldC; p in expected -> Color(0xFFFFE2C7); else -> Color(0xFFEDEBF5) }
            drawRoundRect(c, Offset(x(p) + 1f, 0f), Size(ww - 2f, size.height), CornerRadius(4f))
            if (p in expected) drawRoundRect(wantC, Offset(x(p) + 1f, size.height - 6f), Size(ww - 2f, 6f), CornerRadius(3f))
        }
        for (p in lo..hi) if (isBlackKey(p)) {
            val c = when { p in held && p in expected -> Color(0xFF2FB07F); p in held -> heldC; p in expected -> wantC; else -> Color(0xFF1C1B24) }
            drawRoundRect(c, Offset(x(p), 0f), Size(bw, size.height * 0.6f), CornerRadius(3f))
        }
    }
}
