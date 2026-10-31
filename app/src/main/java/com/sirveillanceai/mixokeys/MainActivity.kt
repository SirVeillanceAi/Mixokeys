package com.sirveillanceai.mixokeys

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sirveillanceai.mixokeys.lyrics.Lyrics
import com.sirveillanceai.mixokeys.midi.Part
import com.sirveillanceai.mixokeys.midi.Song
import com.sirveillanceai.mixokeys.music.Chords
import com.sirveillanceai.mixokeys.music.Tab
import com.sirveillanceai.mixokeys.ui.ChordDiagram
import com.sirveillanceai.mixokeys.ui.FallingView
import com.sirveillanceai.mixokeys.ui.LyricsBar
import com.sirveillanceai.mixokeys.ui.Palette
import com.sirveillanceai.mixokeys.ui.SheetView
import com.sirveillanceai.mixokeys.ui.Sheet
import com.sirveillanceai.mixokeys.notation.Score
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.sirveillanceai.mixokeys.ui.TabView

class MainActivity : ComponentActivity() {
    private val vm: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        enableEdgeToEdge()
        incoming(intent)?.let { vm.open(it, persist = false) }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Accent, onPrimary = Palette.OnAccent, background = Palette.Bg, surface = Palette.Card)) {
                App(vm)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming(intent)?.let { vm.open(it, persist = false) }
    }

    /** A MIDI file shared to the app, or opened with it from a file manager. */
    private fun incoming(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_VIEW) return intent.data
        if (intent.action != Intent.ACTION_SEND) return null
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }
}

@Composable
private fun App(vm: LibraryViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Palette.Bg).safeDrawingPadding()) {
        val song = s.song
        if (song != null) LessonScreen(song, s.lyrics, vm) else HomeScreen(s, vm)
        s.error?.let { msg ->
            AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                title = { Text("Something went wrong") }, text = { Text(msg) })
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Palette.Card, RoundedCornerShape(16.dp)).border(1.dp, Palette.Line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
    )
}

private val MIDI_TYPES = arrayOf("audio/midi", "audio/x-midi", "audio/mid", "audio/sp-midi", "application/x-midi", "audio/x-mid", "application/octet-stream", "*/*")

/* ───────────────────────── Home ───────────────────────── */

@Composable
private fun HomeScreen(s: AppState, vm: LibraryViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri, persist = true) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Mixokeys", color = Palette.Text1, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text("Learn songs on piano or guitar from MIDI files, with karaoke lyrics.", color = Palette.Muted, fontSize = 13.sp)
        }
        s.lastCrash?.let { report ->
            val clip = LocalClipboardManager.current
            Panel {
                Text("The app closed unexpectedly last time", color = Palette.Bad, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(report, color = Palette.Text1, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(report)) }) { Text("Copy report", color = Palette.Text1) }
                    OutlinedButton(onClick = vm::dismissCrash) { Text("Dismiss", color = Palette.Muted) }
                }
            }
        }
        Button(
            onClick = { picker.launch(MIDI_TYPES) }, enabled = !s.loading,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
        ) { Text(if (s.loading) "Opening…" else "Open a MIDI file", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold) }
        Text(".mid, .midi and .kar files. Karaoke files (.kar) bring their lyrics with them; for other songs you can add lyrics afterwards.",
            color = Palette.Muted, fontSize = 12.5.sp)
        if (s.recent.isNotEmpty()) Panel {
            Text("Recent", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            s.recent.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { vm.open(Uri.parse(r.uri), persist = false) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("♪", color = Palette.Accent, fontSize = 18.sp, modifier = Modifier.width(28.dp))
                    Text(r.name, color = Palette.Text1, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("✕", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.clickable { vm.forget(r) }.padding(8.dp))
                }
            }
        }
        Text("Music symbols: Noto Music (SIL Open Font License). Sound: Android's built-in MIDI synthesizer.", color = Palette.Muted, fontSize = 11.sp)
    }
}

/* ───────────────────────── Lesson ───────────────────────── */

private enum class Mode(val label: String) { Sheet("Sheet"), Falling("Falling"), Guitar("Guitar") }

private fun defaultShown(parts: List<Part>): Set<String> {
    val piano = parts.filter { it.isPiano && it.notes.isNotEmpty() }
    if (piano.isNotEmpty()) return piano.map { it.id }.toSet()
    return listOfNotNull(Tab.defaultPart(parts)?.id).toSet()
}

private fun fmt(t: Double): String { val s = t.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

private val MAJOR = mapOf(0 to "C", 1 to "G", 2 to "D", 3 to "A", 4 to "E", 5 to "B", 6 to "F♯", 7 to "C♯", -1 to "F", -2 to "B♭", -3 to "E♭", -4 to "A♭", -5 to "D♭", -6 to "G♭", -7 to "C♭")
private val MINOR = mapOf(0 to "A", 1 to "E", 2 to "B", 3 to "F♯", 4 to "C♯", 5 to "G♯", 6 to "D♯", 7 to "A♯", -1 to "D", -2 to "G", -3 to "C", -4 to "F", -5 to "B♭", -6 to "E♭", -7 to "A♭")

private fun songInfo(song: Song): String {
    val ts = song.timeSigs.firstOrNull()?.let { "${it.num}/${it.den}" } ?: "4/4"
    return "${MAJOR[song.keySharps]} / ${MINOR[song.keySharps]}m · $ts · ${fmt(song.duration)}"
}

@Composable
private fun IconBtn(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(44.dp).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun PlayButton(playing: Boolean, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).background(Palette.Accent, CircleShape).clickable(onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center) { Text(if (playing) "❚❚" else "▶", color = Palette.OnAccent, fontSize = (size / 3).sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).border(1.dp, Color(0xFF33334A), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) { Text(text, color = Palette.Text1, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun LessonScreen(song: Song, lyrics: Lyrics?, vm: LibraryViewModel) {
    val context = LocalContext.current
    val parts = remember(song) { song.parts.filter { it.notes.isNotEmpty() } }
    var mode by rememberSaveable(song) { mutableStateOf(Mode.Sheet) }
    var shown by remember(song) { mutableStateOf(defaultShown(parts)) }
    var tabPartId by remember(song) { mutableStateOf(Tab.defaultPart(parts)?.id) }
    var space by rememberSaveable { mutableFloatStateOf(7f) }   // sheet size: dp per staff space
    var tabZoom by remember { mutableFloatStateOf(120f) }
    var playing by remember { mutableStateOf(false) }
    var now by remember { mutableDoubleStateOf(0.0) }
    var showParts by remember { mutableStateOf(false) }
    var showLyricsMenu by remember { mutableStateOf(false) }
    var tapping by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val player = remember(song) { MidiPlayer(context, song) { playing = false } }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause(); playing = false }
    LaunchedEffect(player) { while (true) { withFrameNanos { }; now = player.position(); playing = player.playing } }
    BackHandler { if (tapping) tapping = false else vm.close() }

    val shownParts = remember(song, shown) { parts.filter { it.id in shown } }
    val shownNotes = remember(shownParts) { shownParts.flatMap { it.notes }.sortedBy { it.start } }
    val score = remember(shownParts) { if (shownParts.isEmpty()) null else Score.build(song, shownParts) }
    val chords = remember(song) { Chords.recognise(song, parts) }
    val tab = remember(song, tabPartId) { parts.firstOrNull { it.id == tabPartId }?.let { Tab.arrange(it.notes) } ?: emptyList() }
    val seek: (Double) -> Unit = { t -> player.seek(t); now = t }
    val togglePlay = { if (player.playing) player.pause() else player.play(); playing = player.playing }
    val zoomIn = { if (mode == Mode.Guitar) tabZoom = (tabZoom * 1.25f).coerceAtMost(400f) else space = (space * 1.15f).coerceAtMost(13f) }
    val zoomOut = { if (mode == Mode.Guitar) tabZoom = (tabZoom / 1.25f).coerceAtLeast(40f) else space = (space / 1.15f).coerceAtLeast(4.5f) }
    val nextSpeed = { val sp = when (player.speed) { 1.0 -> 0.5; 0.5 -> 0.75; else -> 1.0 }; player.setSpeed(sp); playing = player.playing }
    val speedLabel = "${(player.speed * 100).toInt()}%"

    @Composable
    fun LessonView(modifier: Modifier) {
        val sheet = mode == Mode.Sheet
        Box(modifier.background(if (sheet) Sheet.Paper else Palette.Paper, RoundedCornerShape(16.dp)).border(1.dp, Palette.Line, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))) {
            when (mode) {
                Mode.Sheet -> if (score == null) Hint("Pick a part to show under Parts.") else
                    SheetView(score, lyrics, now, space, seek, Modifier.fillMaxSize())
                Mode.Falling -> if (shownNotes.isEmpty()) Hint("Pick a part to show under Parts.") else
                    FallingView(song, shownNotes, now, seek, Modifier.fillMaxSize())
                Mode.Guitar -> GuitarPane(song, parts, chords, tab, tabPartId, now, tabZoom, seek) { tabPartId = it }
            }
        }
    }

    @Composable
    fun MoreMenu() {
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Parts (show / hear)") }, onClick = { menu = false; showParts = true })
            DropdownMenuItem(text = { Text("Lyrics") }, onClick = { menu = false; showLyricsMenu = true })
            DropdownMenuItem(text = { Text("Bigger notes") }, onClick = { zoomIn() })
            DropdownMenuItem(text = { Text("Smaller notes") }, onClick = { zoomOut() })
            DropdownMenuItem(text = { Text("Back 5 seconds") }, onClick = { menu = false; seek(now - 5) })
        }
    }

    @Composable
    fun LyricsExtras() {
        // The sheet shows lyrics under the notes; the other views get a karaoke line.
        if (lyrics != null && lyrics.timed && lyrics.lines.isNotEmpty() && !tapping && mode != Mode.Sheet) LyricsBar(lyrics, now, Modifier.fillMaxWidth())
        if (lyrics != null && !lyrics.timed && !tapping) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("These lyrics have no timings yet.", color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { tapping = true }) { Text("Tap to time them", color = Palette.Accent) }
        }
        if (tapping && lyrics != null) TapSync(lyrics, player, onDone = { l -> vm.setLyrics(l); tapping = false }, onCancel = { tapping = false })
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(76.dp).fillMaxHeight().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
                    IconBtn("Back", vm::close) { Text("‹", color = Palette.Text1, fontSize = 26.sp) }
                    SmallButton(mode.label, Modifier.width(64.dp)) { mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size] }
                    PlayButton(playing, 56, togglePlay)
                    SmallButton(speedLabel, Modifier.width(64.dp)) { nextSpeed() }
                    Box { IconBtn("More", { menu = true }) { Text("⋮", color = Palette.Text1, fontSize = 22.sp) }; MoreMenu() }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 6.dp, end = 8.dp, bottom = 2.dp)) {
                    LessonView(Modifier.weight(1f).fillMaxWidth())
                    LyricsExtras()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(fmt(now), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Slider(value = (now / song.duration.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f), onValueChange = { seek(it * song.duration) }, modifier = Modifier.weight(1f).height(32.dp))
                        Text(fmt(song.duration), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn("Back", vm::close) { Text("‹", color = Palette.Text1, fontSize = 26.sp) }
                    Column(Modifier.weight(1f)) {
                        Text(song.title.replace('_', ' '), color = Palette.Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(songInfo(song), color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = { showParts = true }) { Text("Parts", color = Palette.Accent) }
                    TextButton(onClick = { showLyricsMenu = true }) { Text("Lyrics", color = Palette.Accent) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Palette.Card, RoundedCornerShape(14.dp)).border(1.dp, Palette.Line, RoundedCornerShape(14.dp)).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Mode.entries.forEach { m ->
                        val sel = mode == m
                        Box(Modifier.weight(1f).height(40.dp).background(if (sel) Palette.Text1 else Color.Transparent, RoundedCornerShape(10.dp)).clickable { mode = m },
                            contentAlignment = Alignment.Center) { Text(m.label, color = if (sel) Palette.Bg else Palette.Muted, fontSize = 14.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.SemiBold) }
                    }
                }
                LessonView(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
                LyricsExtras()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(fmt(now), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Slider(value = (now / song.duration.coerceAtLeast(0.1)).toFloat().coerceIn(0f, 1f), onValueChange = { seek(it * song.duration) }, modifier = Modifier.weight(1f))
                    Text(fmt(song.duration), color = Palette.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SmallButton(speedLabel) { nextSpeed() }
                    IconBtn("Back 5 seconds", { seek(now - 5) }) { Text("↺5", color = Palette.Text1, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    PlayButton(playing, 64, togglePlay)
                    SmallButton("−") { zoomOut() }
                    SmallButton("+") { zoomIn() }
                }
            }
        }
    }

    if (showParts) PartsDialog(parts, shown, player.muted, { shown = it }, { player.setMuted(it) }) { showParts = false }
    if (showLyricsMenu) LyricsMenu(lyrics, vm, onTap = { tapping = true }) { showLyricsMenu = false }
}

@Composable
private fun BoxScope.Hint(text: String) {
    Text(text, color = Palette.Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(24.dp))
}

@Composable
private fun GuitarPane(
    song: Song, parts: List<Part>, chords: List<com.sirveillanceai.mixokeys.music.Chord>, tab: List<com.sirveillanceai.mixokeys.music.TabNote>,
    tabPartId: String?, now: Double, zoom: Float, seek: (Double) -> Unit, onPart: (String) -> Unit,
) {
    val ci = chords.indexOfLast { it.start <= now + 0.02 }
    val cur = chords.getOrNull(ci)?.takeIf { now < it.end + 0.5 }
    val next = chords.getOrNull(if (cur == null) chords.indexOfFirst { it.start > now } else ci + 1)
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(150.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChordDiagram(cur, song.keySharps, big = true, modifier = Modifier.weight(1.3f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text("Next", color = Palette.Muted, fontSize = 11.sp)
                ChordDiagram(next, song.keySharps, big = false, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
        Box(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { menu = true }) {
                Text("Tab: " + (parts.firstOrNull { it.id == tabPartId }?.label ?: "pick a part") + "  ▾", color = Palette.Accent, fontSize = 13.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                parts.filter { !it.isDrums }.forEach { p ->
                    DropdownMenuItem(text = { Text("${p.label} · ${p.notes.size} notes") }, onClick = { onPart(p.id); menu = false })
                }
            }
        }
        if (tab.isEmpty() && chords.isEmpty()) Box(Modifier.fillMaxSize()) { Hint("No notes to show for guitar.") }
        else TabView(song, tab, chords, now, zoom, seek, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun PartsDialog(parts: List<Part>, shown: Set<String>, muted: Set<String>, onShown: (Set<String>) -> Unit, onMuted: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    var sh by remember { mutableStateOf(shown) }
    var mu by remember { mutableStateOf(muted) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onShown(sh); if (mu != muted) onMuted(mu); onDismiss() }) { Text("Done") } },
        title = { Text("Parts") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Show = draw on the staff and falling notes. Sound = hear it (turn off the part you're playing).", color = Palette.Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                parts.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.notes.size} notes", color = Palette.Muted, fontSize = 11.sp)
                        }
                        if (!p.isDrums) Checkbox(checked = p.id in sh, onCheckedChange = { sh = if (it) sh + p.id else sh - p.id })
                        Switch(checked = p.id !in mu, onCheckedChange = { mu = if (it) mu - p.id else mu + p.id })
                    }
                }
            }
        },
    )
}

@Composable
private fun LyricsMenu(lyrics: Lyrics?, vm: LibraryViewModel, onTap: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pasting by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { vm.loadLyrics(uri); onDismiss() } }
    if (pasting) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { vm.useLyricsText(text); onDismiss() }, enabled = text.isNotBlank()) { Text("Use these lyrics") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            title = { Text("Paste lyrics") },
            text = {
                Column {
                    Text("Plain lyrics (one line per line) — you'll time them by tapping along. Or paste LRC text with [00:12.34] timings.", color = Palette.Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
                }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Lyrics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(lyrics?.let { "Now using: ${it.source}" + if (it.timed) " · ${it.lines.size} lines" else " (not timed yet)" } ?: "This song has no lyrics yet.",
                    color = Palette.Muted, fontSize = 13.sp)
                MenuItem("Open an .lrc or .txt file") { picker.launch(arrayOf("*/*")) }
                MenuItem("Paste lyrics") { pasting = true }
                if (lyrics != null && (lyrics.lines.isNotEmpty() || lyrics.untimedLines.isNotEmpty())) MenuItem("Time them by tapping along") {
                    if (lyrics.timed) vm.setLyrics(Lyrics.untimed(lyrics.lines.joinToString("\n") { it.text.trim() }, "Your lyrics"))
                    onTap(); onDismiss()
                }
                if (lyrics?.timed == true) MenuItem("Share as .lrc") {
                    vm.lrcExport()?.let { f ->
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Share lyrics"))
                    }
                    onDismiss()
                }
                MenuItem("Use the lyrics inside the MIDI file") { vm.resetLyrics(); onDismiss() }
            }
        },
    )
}

@Composable
private fun MenuItem(text: String, onClick: () -> Unit) {
    Text(text, color = Palette.Text1, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp))
}

/** Time plain lyrics: play the song and tap as each line starts. */
@Composable
private fun TapSync(lyrics: Lyrics, player: MidiPlayer, onDone: (Lyrics) -> Unit, onCancel: () -> Unit) {
    val lines = lyrics.untimedLines
    val taps = remember { mutableStateListOf<Double>() }
    Panel(Modifier.padding(horizontal = 12.dp)) {
        Text("Play the song and tap as each line starts.", color = Palette.Muted, fontSize = 12.sp)
        Text(lines.getOrNull(taps.size) ?: "All lines timed.", color = Palette.Text1, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2)
        lines.getOrNull(taps.size + 1)?.let { Text(it, color = Palette.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    if (!player.playing) player.play()
                    if (taps.size < lines.size) taps += player.position()
                    if (taps.size == lines.size) onDone(Lyrics.fromTaps(lines, taps.toList(), Double.MAX_VALUE))
                },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.OnAccent),
            ) { Text(if (taps.isEmpty() && !player.playing) "Play and tap line 1" else "TAP  (${taps.size + 1}/${lines.size})", fontWeight = FontWeight.ExtraBold) }
            OutlinedButton(onClick = { if (taps.isNotEmpty()) { taps.removeAt(taps.size - 1); player.seek((taps.lastOrNull() ?: 0.0)) } }) { Text("Undo", color = Palette.Text1) }
            TextButton(onClick = {
                if (taps.isNotEmpty()) onDone(Lyrics.fromTaps(lines.take(taps.size), taps.toList(), Double.MAX_VALUE)) else onCancel()
            }) { Text(if (taps.isEmpty()) "Cancel" else "Save", color = Palette.Muted) }
        }
    }
}
