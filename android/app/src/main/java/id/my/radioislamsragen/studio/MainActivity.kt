package id.my.radioislamsragen.studio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Palet warna; gelap dan terang. */
data class Pal(
    val dark: Boolean,
    val bg: Color, val surface: Color, val line: Color, val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val lcd: Color, val lcdLine: Color, val inkHi: Color, val inkDim: Color, val onAir: Color, val wave: Color,
)

private val Gelap = Pal(
    true, Color(0xFF111416), Color(0xFF191D20), Color(0xFF262B2F), Color(0xFFE6E9EA), Color(0xFF8E979C), Color(0xFF656E73),
    Color(0xFF2BB3A8), Color(0xFF15292A), Color(0xFF0B1C1B),
    Color(0xFF0C2422), Color(0xFF1D3D3A), Color(0xFFC6FFF6), Color(0xFF6FD3C5), Color(0xFFFF6B6F), Color(0xFF3CC4B6),
)
private val Terang = Pal(
    false, Color(0xFFF3F5F5), Color(0xFFFFFFFF), Color(0xFFE2E7E8), Color(0xFF1B2326), Color(0xFF5F6B70), Color(0xFF7D888C),
    Color(0xFF0F8A81), Color(0xFFE1F3F1), Color(0xFFFFFFFF),
    Color(0xFFDCEBE6), Color(0xFFBCD3CC), Color(0xFF0E3833), Color(0xFF3F6D66), Color(0xFFC62F35), Color(0xFF16968C),
)
private val Red = Color(0xFFE5484D)
private val LocalPal = staticCompositionLocalOf { Gelap }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Studio.init(this)
        setContent {
            val s by Studio.ui.collectAsStateWithLifecycle()
            val p = if (s.dark) Gelap else Terang
            LaunchedEffect(s.dark) {
                val bar = if (s.dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
            }
            val scheme = if (s.dark) darkColorScheme(primary = p.accent, background = p.bg, surface = p.surface)
            else lightColorScheme(primary = p.accent, background = p.bg, surface = p.surface)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalPal provides p) { StudioApp(s) }
            }
        }
    }
}

@Composable
private fun StudioApp(s: UiState) {
    val p = LocalPal.current
    val ctx = LocalContext.current
    var settings by rememberSaveable { mutableStateOf(false) }
    var playlist by rememberSaveable { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) Studio.goOnAir()
        else Studio.msg("Izin mikrofon ditolak. Siaran butuh mikrofon.", true)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val tracks = uris.map { u ->
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Track(u.toString(), displayName(ctx, u))
        }
        if (tracks.isNotEmpty()) Studio.addTracks(tracks)
    }
    val pick: () -> Unit = { picker.launch(arrayOf("audio/*")) }

    LaunchedEffect(s.config.host, s.config.port) {
        while (true) {
            val cfg = Studio.ui.value.config
            Studio.setListeners(withContext(Dispatchers.IO) { Caster.listeners(cfg) })
            delay(15_000)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg).safeDrawingPadding()) {
        if (settings) {
            BackHandler { settings = false }
            SettingsScreen(s, onBack = { settings = false }, onSave = { Studio.saveConfig(it); settings = false })
        } else {
            StudioScreen(
                s,
                openSettings = { settings = true },
                openPlaylist = { playlist = true },
                pick = pick,
                airClick = {
                    if (s.status == AirStatus.STANDBY) {
                        if (s.config.pass.isBlank()) {
                            Studio.msg("Isi kata sandi server dulu.", true); settings = true
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            Studio.goOnAir()
                        } else {
                            val l = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
                            perm.launch(l.toTypedArray())
                        }
                    } else Studio.endAir()
                },
            )
        }
        if (playlist) PlaylistSheet(s, pick) { playlist = false }
    }
}

private fun displayName(ctx: android.content.Context, u: Uri): String {
    try {
        ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: "Audio"
        }
    } catch (_: Exception) {}
    return u.lastPathSegment ?: "Audio"
}

private fun fmt(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }
private fun clean(name: String) = name.substringBeforeLast('.')

@Composable
private fun StudioScreen(s: UiState, openSettings: () -> Unit, openPlaylist: () -> Unit, pick: () -> Unit, airClick: () -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(if (p.dark) R.drawable.logo_gelap else R.drawable.logo_terang), "Logo Rais Streaming", Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Studio RAIS", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(s.config.station, color = p.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeadBtn(if (p.dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Ganti tema") { Studio.setDark(!s.dark) }
            Spacer(Modifier.width(8.dp))
            HeadBtn(Icons.Filled.Tune, "Pengaturan", openSettings)
        }

        Lcd(s)
        TitleRow(s)
        PlayerCard(s, openPlaylist, pick)
        Faders(s)
      }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val on = s.micOn
            Box(
                Modifier.size(58.dp).clip(CircleShape).background(if (on) p.accentSoft else p.surface)
                    .border(1.5.dp, if (on) p.accent else p.line, CircleShape).clickable { Studio.toggleMic() },
                contentAlignment = Alignment.Center,
            ) { Icon(if (on) Icons.Filled.Mic else Icons.Filled.MicOff, if (on) "Matikan mic" else "Nyalakan mic", tint = if (on) p.accent else p.sub) }
            val (label, bg, fg) = when (s.status) {
                AirStatus.STANDBY -> Triple("Mulai Siaran", p.accent, p.onAccent)
                AirStatus.CONNECTING -> Triple("Batal", Color(0xFFE0A93B), Color(0xFF1B1405))
                else -> Triple("Akhiri Siaran", Red, Color.White)
            }
            Button(onClick = airClick, modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(29.dp),
                colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
        if (s.message.isNotEmpty()) {
            Text(s.message, color = if (s.messageError) Red else p.sub, fontSize = 12.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HeadBtn(icon: ImageVector, desc: String, click: () -> Unit) {
    val p = LocalPal.current
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(p.surface).clickable(onClick = click), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = p.sub, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Lcd(s: UiState) {
    val p = LocalPal.current
    val now by produceState(System.currentTimeMillis(), s.onAirSince) { while (true) { value = System.currentTimeMillis(); delay(500) } }
    val secs = if (s.onAirSince > 0) (now - s.onAirSince) / 1000 else 0
    val (state, stateC) = when (s.status) {
        AirStatus.STANDBY -> "STANDBY" to p.inkDim
        AirStatus.CONNECTING -> "MENYAMBUNG" to p.inkHi
        AirStatus.ON_AIR -> "ON AIR" to p.onAir
        AirStatus.RECONNECTING -> "TERPUTUS" to p.onAir
    }
    val server = when (s.status) {
        AirStatus.STANDBY -> "SIAGA"
        AirStatus.CONNECTING -> "MENYAMBUNG"
        AirStatus.ON_AIR -> "SERVER OK"
        AirStatus.RECONNECTING -> "ULANG..."
    }
    val shape = RoundedCornerShape(18.dp)
    val mono = FontFamily.Monospace
    Column(Modifier.fillMaxWidth().clip(shape).background(p.lcd).border(1.dp, p.lcdLine, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(stateC))
            Spacer(Modifier.width(8.dp))
            Text(state, color = stateC, fontFamily = mono, fontSize = 15.sp, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
            Text("%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60), color = p.inkHi, fontFamily = mono, fontSize = 28.sp)
        }
        Text(s.title.ifBlank { "Belum ada judul" }, color = if (s.title.isBlank()) p.inkDim else p.inkHi, fontFamily = mono,
            fontSize = 19.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Waveform()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${s.listeners ?: "–"} PENDENGAR", color = p.inkDim, fontFamily = mono, fontSize = 13.sp)
            Text("${s.config.bitrate} KBPS", color = p.inkDim, fontFamily = mono, fontSize = 13.sp)
            Text(server, color = p.inkDim, fontFamily = mono, fontSize = 13.sp)
        }
    }
}

/** Gelombang suara bergulir seperti WavePad (cermin atas-bawah). */
@Composable
private fun Waveform() {
    val p = LocalPal.current
    val w by Studio.wave.collectAsStateWithLifecycle()
    Canvas(Modifier.fillMaxWidth().height(62.dp)) {
        val mid = size.height / 2
        drawLine(p.lcdLine, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val n = w.size
        val step = size.width / n
        val sw = maxOf(1f, step * 0.7f)
        for (i in 0 until n) {
            val a = sqrt(w[i].coerceIn(0f, 1f))
            val h = maxOf(1f, a * (mid - 2))
            val x = i * step + step / 2
            drawLine(p.wave, Offset(x, mid - h), Offset(x, mid + h), sw, StrokeCap.Butt)
        }
    }
}

@Composable
private fun TitleRow(s: UiState) {
    val p = LocalPal.current
    var draft by remember(s.title) { mutableStateOf(s.title) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            placeholder = { Text("Judul kajian atau pemateri", fontSize = 14.sp) },
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f), colors = fieldColors(),
        )
        Button(onClick = { Studio.sendTitle(draft) }, shape = RoundedCornerShape(12.dp), modifier = Modifier.height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accentSoft, contentColor = p.accent)) {
            Text("Kirim", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlayerCard(s: UiState, openPlaylist: () -> Unit, pick: () -> Unit) {
    val p = LocalPal.current
    val cur = s.playlist.getOrNull(s.currentIndex)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, null, tint = p.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(cur?.let { clean(it.name) } ?: if (s.playlist.isEmpty()) "Belum ada MP3" else "Siap diputar",
                    color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (s.playlist.isEmpty()) "Ketuk + untuk menambah dari HP"
                    else "Lagu ${maxOf(1, s.currentIndex + 1)} dari ${s.playlist.size} · crossfade ${s.config.crossfadeSec} dtk",
                    color = p.sub, fontSize = 12.sp,
                )
            }
        }
        val prog = if (s.durMs > 0) (s.posMs.toFloat() / s.durMs).coerceIn(0f, 1f) else 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(s.posMs), color = p.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { prog }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(4.dp).clip(CircleShape),
                color = p.accent, trackColor = p.line)
            Text(fmt(s.durMs), color = p.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openPlaylist) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Daftar putar", tint = p.sub) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { Studio.prev() }) { Icon(Icons.Filled.SkipPrevious, "Sebelumnya", tint = p.text) }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(46.dp).clip(CircleShape).background(p.text).clickable { Studio.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Jeda" else "Putar", tint = p.bg)
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = { Studio.next() }) { Icon(Icons.Filled.SkipNext, "Berikutnya", tint = p.text) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = pick) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Tambah MP3", tint = p.accent) }
        }
        if (!s.shuffle && s.playlist.size > 1) {
            HorizontalDivider(color = p.line)
            val n = s.playlist.size
            val start = if (s.currentIndex < 0) 0 else s.currentIndex + 1
            val ups = (0 until minOf(2, n - 1)).mapNotNull { k ->
                val i = start + k
                if (i < n) s.playlist[i] else if (s.repeat) s.playlist[i % n] else null
            }
            ups.forEachIndexed { k, t ->
                Text((if (k == 0) "Berikutnya · " else "") + clean(t.name), color = p.sub, fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun sliderColors(): SliderColors {
    val p = LocalPal.current
    return SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line)
}

@Composable
private fun switchColors(): SwitchColors {
    val p = LocalPal.current
    return SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = Color.White,
        uncheckedTrackColor = p.line, uncheckedThumbColor = p.sub, uncheckedBorderColor = Color.Transparent)
}

@Composable
private fun fieldColors(): TextFieldColors {
    val p = LocalPal.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.accent, unfocusedBorderColor = p.line, cursorColor = p.accent,
        focusedTextColor = p.text, unfocusedTextColor = p.text,
        focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
        focusedPlaceholderColor = p.faint, unfocusedPlaceholderColor = p.faint,
        focusedLabelColor = p.accent, unfocusedLabelColor = p.sub,
    )
}

@Composable
private fun Faders(s: UiState) {
    val p = LocalPal.current
    Column {
        if (s.oneFader) {
            FaderRow("MP3 ${((1 - s.fader) * 100).toInt()}%", "Mic ${(s.fader * 100).toInt()}%", s.fader, Studio::setFader)
        } else {
            FaderRow("Musik", "${(s.musicVol * 100).toInt()}%", s.musicVol, Studio::setMusicVol)
            FaderRow("Mic", "${(s.micVol * 100).toInt()}%", s.micVol, Studio::setMicVol)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(62.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = p.sub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("HP", color = p.sub, fontSize = 12.sp)
            }
            Slider(s.speakerVol, Studio::setSpeakerVol, Modifier.weight(1f).height(32.dp), colors = sliderColors())
            Text("${(s.speakerVol * 100).toInt()}%", color = p.sub, fontSize = 12.sp, modifier = Modifier.width(62.dp), textAlign = TextAlign.End)
        }
        Text("Volume HP hanya untuk speaker HP ini, tidak memengaruhi siaran.", color = p.faint, fontSize = 11.sp,
            modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun FaderRow(left: String, right: String, v: Float, set: (Float) -> Unit) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = p.sub, fontSize = 12.sp, modifier = Modifier.width(62.dp), maxLines = 1)
        Slider(v, set, Modifier.weight(1f).height(32.dp), colors = sliderColors())
        Text(right, color = p.sub, fontSize = 12.sp, modifier = Modifier.width(62.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistSheet(s: UiState, pick: () -> Unit, close: () -> Unit) {
    val p = LocalPal.current
    ModalBottomSheet(onDismissRequest = close, containerColor = p.surface) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Daftar Putar (${s.playlist.size})", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = pick) { Text("+ Tambah MP3", color = p.accent) }
        }
        if (s.playlist.isEmpty()) {
            Text("Belum ada MP3.", color = p.sub, fontSize = 14.sp, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            itemsIndexed(s.playlist) { i, t ->
                val on = i == s.currentIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) p.accentSoft else Color.Transparent)
                        .clickable { Studio.playIndex(i) }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) p.accent else p.line))
                    Spacer(Modifier.width(12.dp))
                    Text(clean(t.name), color = if (on) p.accent else p.text, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Studio.removeTrack(i) }) { Icon(Icons.Filled.Close, "Hapus dari daftar", tint = p.faint, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = p.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 16.dp, vertical = 6.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, v: Boolean, set: (Boolean) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 14.sp)
            Text(desc, color = p.faint, fontSize = 12.sp)
        }
        Switch(v, set, colors = switchColors())
    }
}

@Composable
private fun SettingsScreen(s: UiState, onBack: () -> Unit, onSave: (StationConfig) -> Unit) {
    val p = LocalPal.current
    val cfg = s.config
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port.toString()) }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var showPass by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf(cfg.station) }
    var bitrate by remember { mutableIntStateOf(cfg.bitrate) }
    var xf by remember { mutableFloatStateOf(cfg.crossfadeSec.toFloat()) }
    var boost by remember { mutableFloatStateOf(cfg.micBoost) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = p.text) }
            Text("Pengaturan", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        }

        Section("SERVER") {
            Spacer(Modifier.height(6.dp))
            Field("Address", host) { host = it.trim() }
            Field("Port", port, KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) }
            Field("User", user) { user = it.trim() }
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, label = { Text("Pass") }, singleLine = true,
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Tampilkan sandi", tint = p.sub)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = fieldColors(),
            )
            Field("Nama radio", station) { station = it }
            Text("Bitrate (kbps)", color = p.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(24, 32, 48, 64, 96, 128).forEach { b ->
                    val on = bitrate == b
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) p.accentSoft else p.bg)
                        .clickable { bitrate = b }, contentAlignment = Alignment.Center) {
                        Text("$b", color = if (on) p.accent else p.sub, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Text("Di bawah 64 kbps dikirim mono, lebih jernih untuk suara.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        Section("SUARA") {
            ToggleRow("Mode 1 penggeser", "Kiri MP3, kanan Mic", s.oneFader, Studio::setOneFader)
            ToggleRow("Talkover", "Musik mengecil saat bicara (mode 2 volume)", s.talkover, Studio::setTalkover)
            ToggleRow("Pantau musik di HP", "Musik terdengar di speaker/earphone HP", s.monitor, Studio::setMonitor)
            Text("Penguat mic: ${"%.1f".format(boost)}×", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Slider(boost, { boost = it }, valueRange = 0.5f..3f, colors = sliderColors())
        }

        Section("DAFTAR PUTAR & JUDUL") {
            Text("Crossfade antar-lagu: ${xf.toInt()} detik", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            Slider(xf, { xf = it }, valueRange = 0f..10f, steps = 9, colors = sliderColors())
            ToggleRow("Acak", "Putar daftar secara acak", s.shuffle, Studio::setShuffle)
            ToggleRow("Ulang", "Kembali ke awal setelah lagu terakhir", s.repeat, Studio::setRepeat)
            ToggleRow("Judul otomatis", "Nama file jadi judul di pendengar", s.autoTitle, Studio::setAutoTitle)
        }

        Section("TAMPILAN") {
            ToggleRow("Mode terang", "Tampilan latar terang", !s.dark) { Studio.setDark(!it) }
        }

        Button(
            onClick = {
                onSave(cfg.copy(host = host.ifBlank { cfg.host }, port = port.toIntOrNull()?.coerceIn(1, 65534) ?: cfg.port,
                    user = user, pass = pass, station = station.ifBlank { cfg.station }, bitrate = bitrate,
                    crossfadeSec = xf.toInt(), micBoost = boost))
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
        ) { Text("Simpan", fontWeight = FontWeight.SemiBold) }
        Text("Data server hanya tersimpan di HP ini.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 16.dp))
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = fieldColors())
}
