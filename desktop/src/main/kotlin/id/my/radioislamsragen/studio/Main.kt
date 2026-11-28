package id.my.radioislamsragen.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() {
    Studio.init()
    application {
        val state = rememberWindowState(width = 1120.dp, height = 760.dp)
        Window(
            onCloseRequest = { Studio.shutdown(); exitApplication() },
            title = "Rais Stream",
            icon = painterResource("icon.png"),
            state = state,
        ) {
            val s by Studio.ui.collectAsState()
            val p = if (s.dark) Gelap else Terang
            val scheme = if (s.dark) darkColorScheme(primary = p.accent, background = p.bg, surface = p.surface)
            else lightColorScheme(primary = p.accent, background = p.bg, surface = p.surface)
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(LocalPal provides p) { App(s, window) }
            }
        }
    }
}

private fun pickFiles(owner: Frame?): List<Track> {
    val d = FileDialog(owner, "Pilih file MP3", FileDialog.LOAD)
    d.isMultipleMode = true
    d.setFilenameFilter { _, n -> n.lowercase().endsWith(".mp3") }
    d.file = "*.mp3"
    d.isVisible = true
    return d.files.orEmpty().filter { it.name.lowercase().endsWith(".mp3") }.map { Track(it.absolutePath, it.name) }
}

@Composable
private fun App(s: UiState, owner: Frame?) {
    val p = LocalPal.current
    var settings by remember { mutableStateOf(false) }
    val pick: () -> Unit = { val t = pickFiles(owner); if (t.isNotEmpty()) Studio.addTracks(t) }

    LaunchedEffect(s.config.host, s.config.port) {
        while (true) {
            val cfg = Studio.ui.value.config
            Studio.setListeners(withContext(Dispatchers.IO) { Caster.listeners(cfg) })
            delay(15_000)
        }
    }

    Box(Modifier.fillMaxSize().background(p.bg)) {
        if (settings) SettingsScreen(s, onBack = { settings = false }, onSave = { Studio.saveConfig(it); settings = false })
        else Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // Kolom kiri: LCD dan kendali siaran
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(if (p.dark) "logo_gelap.png" else "logo_terang.png"), "Logo Rais Streaming", Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rais Stream", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    }
                    HeadBtn(if (p.dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Ganti tema") { Studio.setDark(!s.dark) }
                    Spacer(Modifier.width(8.dp))
                    HeadBtn(Icons.Filled.Tune, "Pengaturan") { settings = true }
                }
                val snap by Studio.meters.collectAsState()
                Lcd(s, snap, onMeter = { Studio.cycleMeter() }, meterHeight = 130.dp, titleSize = 22)
                TitleRow(s)
                Faders(s)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    val on = s.micOn
                    RoundKey("Mic", on, p.accent, p.accentSoft, { Studio.toggleMic() }) {
                        Icon(if (on) Icons.Filled.Mic else Icons.Filled.MicOff, if (on) "Matikan mic" else "Nyalakan mic", tint = if (on) p.accent else p.sub)
                    }
                    RoundKey("Rekam", s.recording, RedBtn, p.recBg, { Studio.toggleRec() }) {
                        if (s.recording) Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(RedBtn))
                        else Box(Modifier.size(18.dp).clip(CircleShape).background(RedBtn))
                    }
                    val (label, bg, fg) = when (s.status) {
                        AirStatus.STANDBY -> Triple("Mulai Siaran", p.accent, p.onAccent)
                        AirStatus.CONNECTING -> Triple("Batal", Color(0xFFE0A93B), Color(0xFF1B1405))
                        else -> Triple("Akhiri Siaran", RedBtn, Color.White)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = {
                                if (s.status == AirStatus.STANDBY) {
                                    if (s.config.pass.isBlank()) { Studio.msg("Isi kata sandi server dulu.", true); settings = true } else Studio.goOnAir()
                                } else Studio.endAir()
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(28.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
                        ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
                        Text("Siaran", color = p.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                Text(s.message, color = if (s.messageError) RedBtn else p.sub, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(16.dp))
            }
            // Kolom kanan: pemutar dan daftar putar
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PlayerCard(s, pick)
                Playlist(s, pick, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeadBtn(icon: ImageVector, desc: String, click: () -> Unit) {
    val p = LocalPal.current
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(p.surface).clickable(onClick = click), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = p.sub, modifier = Modifier.size(20.dp))
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
            colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent)) {
            Text("Kirim", fontWeight = FontWeight.SemiBold)
        }
    }
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
            Row(Modifier.width(70.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = p.sub, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Speaker", color = p.sub, fontSize = 12.sp)
            }
            Slider(s.speakerVol, Studio::setSpeakerVol, Modifier.weight(1f).height(32.dp), colors = sliderColors())
            Text("${(s.speakerVol * 100).toInt()}%", color = p.sub, fontSize = 12.sp, modifier = Modifier.width(70.dp), textAlign = TextAlign.End)
        }
        Text("Volume speaker hanya untuk komputer ini, tidak memengaruhi siaran.", color = p.faint, fontSize = 11.sp)
    }
}

@Composable
private fun FaderRow(left: String, right: String, v: Float, set: (Float) -> Unit) {
    val p = LocalPal.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(left, color = p.sub, fontSize = 12.sp, modifier = Modifier.width(70.dp), maxLines = 1)
        Slider(v, set, Modifier.weight(1f).height(32.dp), colors = sliderColors())
        Text(right, color = p.sub, fontSize = 12.sp, modifier = Modifier.width(70.dp), textAlign = TextAlign.End, maxLines = 1)
    }
}

@Composable
private fun PlayerCard(s: UiState, pick: () -> Unit) {
    val p = LocalPal.current
    val cur = s.playlist.getOrNull(s.currentIndex)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.MusicNote, null, tint = p.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cur?.let { cleanName(it.name) } ?: if (s.playlist.isEmpty()) "Belum ada MP3" else "Siap diputar",
                    color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (s.playlist.isEmpty()) "Klik + untuk menambah file MP3"
                    else "Lagu ${maxOf(1, s.currentIndex + 1)} dari ${s.playlist.size} · crossfade ${s.config.crossfadeSec} dtk",
                    color = p.sub, fontSize = 12.sp)
            }
        }
        val prog = if (s.durMs > 0) (s.posMs.toFloat() / s.durMs).coerceIn(0f, 1f) else 0f
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtMs(s.posMs), color = p.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { prog }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(4.dp).clip(CircleShape),
                color = p.accent, trackColor = p.line)
            Text(fmtMs(s.durMs), color = p.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Studio.setShuffle(!s.shuffle) }) { Icon(Icons.Filled.Shuffle, "Acak", tint = if (s.shuffle) p.accent else p.faint) }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { Studio.prev() }) { Icon(Icons.Filled.SkipPrevious, "Sebelumnya", tint = p.text) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(50.dp).clip(CircleShape).background(p.text).clickable { Studio.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Jeda" else "Putar", tint = p.bg)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { Studio.next() }) { Icon(Icons.Filled.SkipNext, "Berikutnya", tint = p.text) }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { Studio.setRepeat(!s.repeat) }) { Icon(Icons.Filled.Repeat, "Ulang", tint = if (s.repeat) p.accent else p.faint) }
        }
    }
}

@Composable
private fun Playlist(s: UiState, pick: () -> Unit, modifier: Modifier) {
    val p = LocalPal.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(p.surface).padding(vertical = 10.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Daftar Putar (${s.playlist.size})", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = pick) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null, tint = p.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tambah MP3", color = p.accent)
            }
        }
        if (s.playlist.isEmpty()) {
            Box(Modifier.fillMaxSize().clickable(onClick = pick), contentAlignment = Alignment.Center) {
                Text("Belum ada MP3.\nKlik di sini untuk menambah.", color = p.sub, textAlign = TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 8.dp)) {
            itemsIndexed(s.playlist) { i, t ->
                val on = i == s.currentIndex
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) p.accentSoft else Color.Transparent)
                        .clickable { Studio.playIndex(i) }.padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("%02d".format(i + 1), color = if (on) p.accent else p.faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(28.dp))
                    Text(cleanName(t.name), color = if (on) p.accent else p.text, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { Studio.removeTrack(i) }) { Icon(Icons.Filled.Close, "Hapus dari daftar", tint = p.faint, modifier = Modifier.size(16.dp)) }
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
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surface).padding(horizontal = 16.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, v: Boolean, set: (Boolean) -> Unit) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.text, fontSize = 14.sp)
            Text(desc, color = p.faint, fontSize = 12.sp)
        }
        Switch(v, set, colors = switchColors())
    }
}

@Composable
private fun Choices(items: List<String>, selected: String, labelOf: (String) -> String = { it }, pick: (String) -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { it ->
            val on = it == selected
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) p.accentSoft else p.bg).clickable { pick(it) }
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) p.accent else p.line))
                Spacer(Modifier.width(10.dp))
                Text(labelOf(it), color = if (on) p.accent else p.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SettingsScreen(s: UiState, onBack: () -> Unit, onSave: (StationConfig) -> Unit) {
    val p = LocalPal.current
    val cfg = s.config
    var pass by remember { mutableStateOf(cfg.pass) }
    var showPass by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf(cfg.station) }
    var bitrate by remember { mutableIntStateOf(cfg.bitrate) }
    var xf by remember { mutableFloatStateOf(cfg.crossfadeSec.toFloat()) }
    var boost by remember { mutableFloatStateOf(cfg.micBoost) }
    val inputs by Studio.inputs.collectAsState()
    val outputs by Studio.outputs.collectAsState()
    val micDev by Studio.micDevice.collectAsState()
    val outDev by Studio.outDevice.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = p.text) }
            Text("Pengaturan", color = p.text, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    onSave(cfg.copy(pass = pass, station = station.ifBlank { cfg.station }, bitrate = bitrate,
                        crossfadeSec = xf.toInt(), micBoost = boost))
                },
                shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = p.accent, contentColor = p.onAccent),
            ) { Text("Simpan", fontWeight = FontWeight.SemiBold) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Section("SERVER") {
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
                    Text("Bitrate MP3 (kbps)", color = p.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24, 32, 48, 64, 96, 128).forEach { b ->
                            val on = bitrate == b
                            Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) p.accentSoft else p.bg)
                                .clickable { bitrate = b }, contentAlignment = Alignment.Center) {
                                Text("$b", color = if (on) p.accent else p.sub, fontSize = 13.sp)
                            }
                        }
                    }
                    Text("Di bawah 64 kbps dikirim mono.", color = p.faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Section("TAMPILAN") {
                    ToggleRow("Mode terang", "Tampilan latar terang", !s.dark) { Studio.setDark(!it) }
                    Text("Tampilan meter", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Choices(METERS, s.meter, { meterName(it) }) { Studio.setMeter(it) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Section("PERANGKAT SUARA") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Masukan (mic / mixer / line-in)", color = p.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { Studio.refreshDevices() }) { Text("Muat ulang", color = p.accent) }
                    }
                    Choices(listOf("") + inputs, micDev, { it.ifEmpty { "Bawaan Windows" } }) { Studio.setMicDevice(it) }
                    Text("Keluaran (speaker / headphone)", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    Choices(listOf("") + outputs, outDev, { it.ifEmpty { "Bawaan Windows" } }) { Studio.setOutDevice(it) }
                }
                Section("SUARA") {
                    ToggleRow("Mode 1 penggeser", "Kiri MP3, kanan Mic", s.oneFader, Studio::setOneFader)
                    ToggleRow("Talkover", "Musik mengecil saat bicara (mode 2 volume)", s.talkover, Studio::setTalkover)
                    ToggleRow("Pantau musik di speaker", "Musik terdengar di speaker komputer", s.monitor, Studio::setMonitor)
                    Text("Penguat mic: ${"%.1f".format(boost)}×", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(boost, { boost = it }, valueRange = 0.5f..3f, colors = sliderColors())
                }
                Section("DAFTAR PUTAR & JUDUL") {
                    Text("Crossfade antar-lagu: ${xf.toInt()} detik", color = p.text, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    Slider(xf, { xf = it }, valueRange = 0f..10f, steps = 9, colors = sliderColors())
                    ToggleRow("Judul otomatis", "Nama file jadi judul di pendengar", s.autoTitle, Studio::setAutoTitle)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Rekaman tersimpan di folder Musik\\RaisStream (MP3).", color = p.faint, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { Studio.openRecFolder() }) { Text("Buka folder rekaman", color = p.accent) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = fieldColors())
}
