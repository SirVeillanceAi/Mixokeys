package com.sirveillanceai.mixokeys

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sirveillanceai.mixokeys.ml.Models
import com.sirveillanceai.mixokeys.ml.Note
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0B0B10)
private val Card = Color(0xFF16161E)
private val Line = Color(0xFF262633)
private val Text1 = Color(0xFFF1F0F7)
private val Muted = Color(0xFFA3A1B5)
private val Accent = Color(0xFFA594FF)
private val OnAccent = Color(0xFF120E2B)
private val Ok = Color(0xFF6FE0B8)
private val Bad = Color(0xFFFF7A7A)

class MainActivity : ComponentActivity() {
    private val vm: PipelineViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedUri(intent)?.let { vm.pick(it) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = OnAccent, background = Bg, surface = Card)) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUri(intent)?.let { vm.pick(it) }
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

@Composable
private fun App(vm: PipelineViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LocalView.current.keepScreenOn = ui.running
    Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
        when {
            ui.result != null -> ResultScreen(ui.result!!, onBack = vm::reset)
            ui.running || ui.steps.isNotEmpty() -> WorkScreen(ui, onCancel = vm::cancel, onBack = vm::reset)
            else -> HomeScreen(ui, vm)
        }
    }
}

@Composable
private fun Title(text: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, color = Text1, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

@Composable
private fun BigButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun HomeScreen(ui: UiState, vm: PipelineViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.pick(uri) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("Mixokeys", "Test build · piano notes from any song, on this phone")
        Panel {
            Text("Song", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(ui.pickedName ?: "No song picked yet", color = Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (ui.pickedUri == null) "Pick an audio file" else "Pick a different file", color = Text1)
            }
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo piano recording", color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Turn on only if the file is piano alone. Otherwise the piano is separated from the band first, which is the most accurate.", color = Muted, fontSize = 12.5.sp)
                }
                Switch(checked = ui.soloPiano, onCheckedChange = vm::setSolo)
            }
        }
        Panel {
            Text("Listening models", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Models.ALL.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.title, color = Text1, fontSize = 14.sp)
                        Text("%.0f MB".format(m.approxBytes / 1e6), color = Muted, fontSize = 12.sp)
                    }
                    val ready = ui.modelsReady[m.id] == true
                    Text(if (ready) "On this phone" else "Downloads on first use", color = if (ready) Ok else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("About 235 MB, downloaded once over the internet (Wi-Fi recommended). Songs are never uploaded.", color = Muted, fontSize = 12.sp)
        }
        BigButton("Read the piano notes", enabled = ui.pickedUri != null) { vm.start() }
        Credits()
    }
}

@Composable
private fun Credits() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Credits", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Models.ALL.forEach { Text(it.credit, color = Muted, fontSize = 11.sp) }
        Text("ONNX Runtime by Microsoft, MIT licence.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun WorkScreen(ui: UiState, onCancel: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Title(if (ui.running) "Working…" else if (ui.error != null) "Stopped" else "Done", ui.pickedName)
        Panel {
            ui.steps.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val (bg, fg, mark) = when (s.state) {
                        StepState.Done -> Triple(Color(0xFF183229), Ok, "✓")
                        StepState.Running -> Triple(Color(0xFF2A2640), Accent, "${i + 1}")
                        StepState.Failed -> Triple(Color(0xFF3A1A1A), Bad, "!")
                        else -> Triple(Color(0xFF1E1E28), Muted, "${i + 1}")
                    }
                    Box(Modifier.size(28.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) { Text(mark, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(s.label, color = Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        if (s.state == StepState.Running) LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Line)
                        s.detail?.let { Text(it, color = Muted, fontSize = 12.sp) }
                    }
                    s.seconds?.let { Text(fmtSecs(it), color = Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        ui.error?.let { Panel { Text(it, color = Bad, fontSize = 14.sp) } }
        if (ui.running) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("Stop", color = Text1) }
        else BigButton("Back") { onBack() }
    }
}

private fun fmtSecs(s: Double) = if (s < 60) "%.1f s".format(s) else "%d:%02d".format((s / 60).toInt(), (s % 60).toInt())

@Composable
private fun ResultScreen(r: Result, onBack: () -> Unit) {
    val context = LocalContext.current
    var source by remember { mutableStateOf(if (r.pianoStem != null) "piano" else "song") }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableIntStateOf(0) }
    val player = remember(source) {
        MediaPlayer().apply {
            if (source == "piano" && r.pianoStem != null) setDataSource(r.pianoStem.absolutePath) else setDataSource(context, r.sourceUri)
            prepare()
            setOnCompletionListener { playing = false }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) {
        if (playing) { player.start(); while (playing) { positionMs = player.currentPosition; delay(30) } } else if (player.isPlaying) player.pause()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(r.title.substringBeforeLast('.'), "%d piano notes · %s long".format(r.notes.size, fmtSecs(r.songSeconds)))
        PianoRoll(r.notes, r.songSeconds, positionMs / 1000.0, Modifier.weight(1f).fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.pianoStem != null) {
                FilterChip(selected = source == "piano", onClick = { playing = false; source = "piano" }, label = { Text("Piano part") })
                FilterChip(selected = source == "song", onClick = { playing = false; source = "song" }, label = { Text("Whole song") })
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(56.dp).background(Accent, CircleShape).border(0.dp, Accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(onClick = { playing = !playing }) { Text(if (playing) "❚❚" else "▶", color = OnAccent, fontSize = 18.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", r.midiFile)
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "audio/midi"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share MIDI"))
            }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Share MIDI", color = Text1) }
            OutlinedButton(onClick = { player.release(); onBack() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("New song", color = Text1) }
        }
    }
}

/** Notes over time, scrolling to follow playback. Low notes at the bottom, like a piano roll. */
@Composable
private fun PianoRoll(notes: List<Note>, seconds: Double, now: Double, modifier: Modifier) {
    val pxPerSec = 90f
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val lo = (notes.minOfOrNull { it.pitch } ?: 48) - 2
    val hi = (notes.maxOfOrNull { it.pitch } ?: 84) + 2
    val widthDp = with(density) { (seconds.toFloat() * pxPerSec + 400f).toDp() }
    LaunchedEffect(now) {
        val target = (now * pxPerSec).toInt() - 120
        if (target > 0 && kotlin.math.abs(scroll.value - target) > 4) scroll.scrollTo(target.coerceAtMost(scroll.maxValue))
    }
    Box(modifier.background(Color(0xFF111119), RoundedCornerShape(16.dp)).border(1.dp, Line, RoundedCornerShape(16.dp)).horizontalScroll(scroll)) {
        Canvas(Modifier.width(widthDp).fillMaxHeight()) {
            val rows = (hi - lo + 1).coerceAtLeast(1)
            val rowH = size.height / rows
            for (p in lo..hi) {
                if (p % 12 == 0) drawRect(Color(0xFF1E1E2A), Offset(0f, (hi - p) * rowH), Size(size.width, rowH))
            }
            var sec = 0
            while (sec < seconds + 4) {
                drawRect(Color(0xFF1C1C26), Offset(sec * pxPerSec, 0f), Size(1f, size.height)); sec += 1
            }
            for (n in notes) {
                val x = (n.start * pxPerSec).toFloat()
                val w = ((n.end - n.start) * pxPerSec).toFloat().coerceAtLeast(3f)
                val y = (hi - n.pitch) * rowH
                val sounding = now >= n.start && now < n.end
                val a = 0.45f + 0.55f * (n.velocity / 127f)
                drawRoundRect(
                    color = if (sounding) Color(0xFFF0A43A) else Accent.copy(alpha = a),
                    topLeft = Offset(x, y + 1f), size = Size(w, (rowH - 2f).coerceAtLeast(2f)), cornerRadius = CornerRadius(3f, 3f),
                )
            }
            drawRect(Color(0xFFF0A43A), Offset((now * pxPerSec).toFloat(), 0f), Size(2f, size.height))
        }
    }
}
