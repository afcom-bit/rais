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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private object C {
    val bg = Color(0xFF0B1410)
    val card = Color(0xFF101D16)
    val card2 = Color(0xFF0F1A14)
    val border = Color(0xFF23402F)
    val border2 = Color(0xFF1D3527)
    val text = Color(0xFFE8F1EC)
    val sub = Color(0xFF9FB5A8)
    val dim = Color(0xFF7EA08D)
    val green = Color(0xFF3DDC84)
    val red = Color(0xFFE5484D)
    val amber = Color(0xFFF5B84A)
    val hl = Color(0xFF17301F)
    val off = Color(0xFF1B2C22)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Studio.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.green, background = C.bg, surface = C.card)) {
                StudioApp()
            }
        }
    }
}

@Composable
private fun StudioApp() {
    val s by Studio.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var settings by rememberSaveable { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) Studio.goOnAir()
        else Studio.msg("Izin mikrofon ditolak. Siaran butuh mikrofon.", true)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val tracks = uris.mapNotNull { u ->
            try { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Track(u.toString(), displayName(ctx, u))
        }
        if (tracks.isNotEmpty()) Studio.addTracks(tracks)
    }

    LaunchedEffect(s.config.host, s.config.port) {
        while (true) {
            val cfg = Studio.ui.value.config
            val n = withContext(Dispatchers.IO) { Caster.listeners(cfg) }
            Studio.setListeners(n)
            delay(15_000)
        }
    }

    Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) {
        if (settings) {
            BackHandler { settings = false }
            SettingsScreen(s.config, onBack = { settings = false }, onSave = { Studio.saveConfig(it); settings = false })
        } else {
            StudioScreen(
                s,
                openSettings = { settings = true },
                pick = { picker.launch(arrayOf("audio/*")) },
                airClick = {
                    if (s.status == AirStatus.STANDBY) {
                        if (s.config.pass.isBlank()) {
                            Studio.msg("Isi kata sandi server dulu.", true); settings = true
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            Studio.goOnAir()
                        } else {
                            val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
                            perm.launch(p.toTypedArray())
                        }
                    } else Studio.endAir()
                },
            )
        }
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

private val cardShape = RoundedCornerShape(18.dp)
private fun Modifier.card(bg: Color = C.card, bd: Color = C.border) =
    this.clip(cardShape).background(bg).border(1.dp, bd, cardShape)

private fun fmt(ms: Long): String {
    val t = ms / 1000
    return "%02d:%02d".format(t / 60, t % 60)
}

@Composable
private fun StudioScreen(s: UiState, openSettings: () -> Unit, pick: () -> Unit, airClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Kepala
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF13261C))
                .border(1.dp, C.border, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("RAIS", color = C.green, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Studio Siaran", color = C.text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(s.config.station, color = C.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = openSettings) { Icon(Icons.Filled.Settings, "Pengaturan", tint = C.text) }
        }

        StatusCard(s)
        NowCard(s)
        Mixer(s)
        PlaylistCard(s, pick, Modifier.weight(1f))

        // Tombol bawah
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val micOn = s.micOn
            Button(
                onClick = { Studio.toggleMic() },
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (micOn) C.green else C.border),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF13261C), contentColor = if (micOn) C.green else C.sub),
            ) {
                Icon(if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff, null)
                Spacer(Modifier.width(6.dp))
                Text(if (micOn) "Mic Aktif" else "Mic Mati", fontWeight = FontWeight.Bold)
            }
            val (label, bg, fg) = when (s.status) {
                AirStatus.STANDBY -> Triple("Mulai Siaran", C.green, C.bg)
                AirStatus.CONNECTING -> Triple("Batal", C.amber, C.bg)
                else -> Triple("Akhiri Siaran", C.red, Color.White)
            }
            Button(
                onClick = airClick,
                modifier = Modifier.weight(1f).height(60.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg),
            ) {
                Icon(if (s.status == AirStatus.STANDBY) Icons.Filled.Radio else Icons.Filled.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text(label, fontWeight = FontWeight.Bold)
            }
        }
        if (s.message.isNotEmpty()) {
            Text(s.message, color = if (s.messageError) Color(0xFFF3A6A9) else C.sub, fontSize = 12.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusCard(s: UiState) {
    val now by produceState(System.currentTimeMillis(), s.onAirSince) {
        while (true) { value = System.currentTimeMillis(); delay(500) }
    }
    val secs = if (s.onAirSince > 0) (now - s.onAirSince) / 1000 else 0
    val timer = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
    val (badge, badgeBg, badgeFg) = when (s.status) {
        AirStatus.STANDBY -> Triple("STANDBY", Color(0xFF2A4234), C.sub)
        AirStatus.CONNECTING -> Triple("MENYAMBUNG", C.amber, C.bg)
        AirStatus.ON_AIR -> Triple("ON AIR", C.red, Color.White)
        AirStatus.RECONNECTING -> Triple("TERPUTUS", C.amber, C.bg)
    }
    val (srv, srvC) = when (s.status) {
        AirStatus.STANDBY -> "Siaga" to C.sub
        AirStatus.CONNECTING -> "Menyambung" to C.amber
        AirStatus.ON_AIR -> "Tersambung" to C.green
        AirStatus.RECONNECTING -> "Menyambung ulang" to C.amber
    }
    Column(Modifier.fillMaxWidth().card().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clip(CircleShape).background(badgeBg).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(badgeFg))
                Spacer(Modifier.width(7.dp))
                Text(badge, color = badgeFg, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.5.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(timer, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 24.sp)
        }
        Row {
            Stat("Pendengar", s.listeners?.toString() ?: "–", C.text, Modifier.weight(1f))
            Stat("Bitrate", "${s.config.bitrate} kbps", C.text, Modifier.weight(1f))
            Stat("Server", srv, srvC, Modifier.weight(1.2f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = C.sub, fontSize = 12.sp)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NowCard(s: UiState) {
    var draft by remember(s.title) { mutableStateOf(s.title) }
    Column(Modifier.fillMaxWidth().card(C.card2, C.border2).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("JUDUL DI PENDENGAR", color = C.dim, fontSize = 11.sp, letterSpacing = 1.2.sp, modifier = Modifier.weight(1f))
            Text("Otomatis", color = C.sub, fontSize = 11.sp)
            Spacer(Modifier.width(4.dp))
            Switch(s.autoTitle, Studio::setAutoTitle, Modifier.height(24.dp), colors = switchColors())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, singleLine = true,
                placeholder = { Text("Judul kajian atau pemateri", fontSize = 14.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, color = C.text),
                modifier = Modifier.weight(1f),
                colors = fieldColors(),
            )
            Button(onClick = { Studio.sendTitle(draft) }, shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.hl, contentColor = C.green)) { Text("Kirim") }
        }
        VuMeter()
    }
}

@Composable
private fun VuMeter() {
    val lv by Studio.levels.collectAsStateWithLifecycle()
    Canvas(Modifier.fillMaxWidth().height(26.dp)) {
        val segs = 20
        val gap = 3.dp.toPx()
        val rowH = (size.height - gap) / 2
        val w = (size.width - gap * (segs - 1)) / segs
        for (row in 0..1) {
            val on = (lv.getOrElse(row) { 0f } * segs * 1.4f).toInt()
            for (k in 0 until segs) {
                val c = when {
                    k >= on -> C.off
                    k >= 17 -> C.red
                    k >= 13 -> C.amber
                    else -> C.green
                }
                drawRoundRect(c, Offset(k * (w + gap), row * (rowH + gap)), Size(w, rowH), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = C.green, checkedThumbColor = Color.White,
    uncheckedTrackColor = Color(0xFF2A4234), uncheckedThumbColor = C.sub, uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = C.green, activeTrackColor = C.green, inactiveTrackColor = Color(0xFF2A4234))

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.green, unfocusedBorderColor = C.border, cursorColor = C.green,
    focusedTextColor = C.text, unfocusedTextColor = C.text,
    focusedPlaceholderColor = C.dim, unfocusedPlaceholderColor = C.dim,
    focusedLabelColor = C.green, unfocusedLabelColor = C.sub,
)

@Composable
private fun Mixer(s: UiState) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mode 1 penggeser (MP3 ⟷ Mic)", color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(s.oneFader, Studio::setOneFader, colors = switchColors())
        }
        if (s.oneFader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MP3", color = if (s.fader <= 0.5f) C.green else C.sub, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(38.dp))
                Slider(s.fader, Studio::setFader, Modifier.weight(1f), colors = sliderColors())
                Text("Mic", color = if (s.fader > 0.5f) C.green else C.sub, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.width(38.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 44.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("MP3 ${((1 - s.fader) * 100).toInt()}%", color = C.sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("Mic ${(s.fader * 100).toInt()}%", color = C.sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            VolRow("Musik", s.musicVol, Studio::setMusicVol)
            VolRow("Mic", s.micVol, Studio::setMicVol)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Talkover: musik mengecil saat bicara", color = C.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Switch(s.talkover, Studio::setTalkover, colors = switchColors())
            }
        }
    }
}

@Composable
private fun VolRow(label: String, v: Float, set: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = C.text, fontSize = 13.sp, modifier = Modifier.width(52.dp))
        Slider(v, set, Modifier.weight(1f).height(36.dp), colors = sliderColors())
        Text("${(v * 100).toInt()}%", color = C.sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun SmallToggle(icon: ImageVector, desc: String, on: Boolean, click: () -> Unit) {
    IconButton(onClick = click) { Icon(icon, desc, tint = if (on) C.green else C.dim) }
}

@Composable
private fun PlaylistCard(s: UiState, pick: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().card(C.card2, C.border2).padding(horizontal = 6.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Text("Daftar Putar (${s.playlist.size})", color = C.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            SmallToggle(Icons.Filled.Shuffle, "Acak", s.shuffle) { Studio.setShuffle(!s.shuffle) }
            SmallToggle(Icons.Filled.Repeat, "Ulang", s.repeat) { Studio.setRepeat(!s.repeat) }
            SmallToggle(Icons.Filled.Headphones, "Pantau musik di HP", s.monitor) { Studio.setMonitor(!s.monitor) }
            IconButton(onClick = pick) { Icon(Icons.Filled.Add, "Tambah MP3", tint = C.green) }
        }
        if (s.playlist.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = pick), contentAlignment = Alignment.Center) {
                Text("Belum ada MP3.\nKetuk di sini atau + untuk menambah dari HP.", color = C.sub, fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(s.playlist) { i, t ->
                    val on = i == s.currentIndex
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) C.hl else Color.Transparent)
                            .clickable { Studio.playIndex(i) }.padding(start = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(i + 1), color = if (on) C.green else Color(0xFF6F8A7B), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(26.dp))
                        Text(t.name.substringBeforeLast('.'), color = if (on) C.green else Color(0xFFCFE0D6), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { Studio.removeTrack(i) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, "Hapus dari daftar", tint = C.dim, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        val prog = if (s.durMs > 0) (s.posMs.toFloat() / s.durMs).coerceIn(0f, 1f) else 0f
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(fmt(s.posMs), color = C.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            LinearProgressIndicator(progress = { prog }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                color = C.green, trackColor = Color(0xFF2A4234))
            Text(fmt(s.durMs), color = C.sub, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { Studio.prev() }) { Icon(Icons.Filled.SkipPrevious, "Sebelumnya", tint = C.text) }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(50.dp).clip(CircleShape).background(C.text).clickable { Studio.togglePlay() }, contentAlignment = Alignment.Center) {
                Icon(if (s.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (s.playing) "Jeda" else "Putar", tint = C.bg)
            }
            Spacer(Modifier.width(12.dp))
            IconButton(onClick = { Studio.next() }) { Icon(Icons.Filled.SkipNext, "Berikutnya", tint = C.text) }
        }
    }
}

@Composable
private fun SettingsScreen(cfg: StationConfig, onBack: () -> Unit, onSave: (StationConfig) -> Unit) {
    var host by remember { mutableStateOf(cfg.host) }
    var port by remember { mutableStateOf(cfg.port.toString()) }
    var user by remember { mutableStateOf(cfg.user) }
    var pass by remember { mutableStateOf(cfg.pass) }
    var showPass by remember { mutableStateOf(false) }
    var station by remember { mutableStateOf(cfg.station) }
    var bitrate by remember { mutableIntStateOf(cfg.bitrate) }
    var xf by remember { mutableFloatStateOf(cfg.crossfadeSec.toFloat()) }
    var boost by remember { mutableFloatStateOf(cfg.micBoost) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = C.text) }
            Text("Pengaturan Server", color = C.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Field("Address", host) { host = it.trim() }
        Field("Port", port, KeyboardType.Number) { port = it.filter(Char::isDigit).take(5) }
        Field("User", user) { user = it.trim() }
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Pass") }, singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Tampilkan sandi", tint = C.sub)
                }
            },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        )
        Field("Nama radio", station) { station = it }

        Text("Bitrate", color = C.sub, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(32, 48, 64, 96, 128).forEach { b ->
                FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text("$b") },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = C.hl, selectedLabelColor = C.green, labelColor = C.sub))
            }
        }
        Text("Di bawah 64 kbps dikirim mono (lebih jernih untuk suara).", color = C.dim, fontSize = 12.sp)

        Text("Crossfade antar-lagu: ${xf.toInt()} detik", color = C.sub, fontSize = 13.sp)
        Slider(xf, { xf = it }, valueRange = 0f..10f, steps = 9, colors = sliderColors())
        Text("Penguat mic: ${"%.1f".format(boost)}×", color = C.sub, fontSize = 13.sp)
        Slider(boost, { boost = it }, valueRange = 0.5f..3f, colors = sliderColors())

        Button(
            onClick = {
                onSave(cfg.copy(host = host.ifBlank { cfg.host }, port = port.toIntOrNull()?.coerceIn(1, 65534) ?: cfg.port,
                    user = user, pass = pass, station = station.ifBlank { cfg.station }, bitrate = bitrate,
                    crossfadeSec = xf.toInt(), micBoost = boost))
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = C.green, contentColor = C.bg),
        ) { Text("Simpan", fontWeight = FontWeight.Bold) }
        Text("Data ini hanya tersimpan di HP ini.", color = C.dim, fontSize = 12.sp)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth(), colors = fieldColors())
}
