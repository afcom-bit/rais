package id.my.radioislamsragen.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Palet warna; gelap dan terang. */
data class Pal(
    val dark: Boolean,
    val bg: Color, val surface: Color, val line: Color, val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val lcd: Color, val lcdLine: Color, val inkHi: Color, val inkDim: Color, val onAir: Color, val onAirBg: Color,
    val meter: Color, val meterMid: Color, val meterOff: Color, val dial: Color, val needle: Color, val recBg: Color,
)

val Gelap = Pal(
    true, Color(0xFF0F1214), Color(0xFF171B1E), Color(0xFF252A2E), Color(0xFFE6E9EA), Color(0xFF8E979C), Color(0xFF656E73),
    Color(0xFF2BB3A8), Color(0xFF15292A), Color(0xFF0B1C1B),
    Color(0xFF0B2321), Color(0xFF1D3D3A), Color(0xFFC6FFF6), Color(0xFF5FBFB2), Color(0xFFFF6B6F), Color(0xFF3A1517),
    Color(0xFF3CC4B6), Color(0xFFE8D46A), Color(0xFF163A36), Color(0xFF10302D), Color(0xFFF3F7F6), Color(0xFF2A1517),
)
val Terang = Pal(
    false, Color(0xFFF3F5F5), Color(0xFFFFFFFF), Color(0xFFE2E7E8), Color(0xFF1B2326), Color(0xFF5F6B70), Color(0xFF7D888C),
    Color(0xFF0F8A81), Color(0xFFE1F3F1), Color(0xFFFFFFFF),
    Color(0xFFE2EFEA), Color(0xFFBFD5CE), Color(0xFF0E3833), Color(0xFF4A7770), Color(0xFFC62F35), Color(0xFFF6DCDD),
    Color(0xFF16968C), Color(0xFFC99A1A), Color(0xFFCFE0DA), Color(0xFFF4F1E2), Color(0xFF1B2326), Color(0xFFFDECEC),
)
val RedBtn = Color(0xFFE5484D)
val LocalPal = staticCompositionLocalOf { Gelap }

val METERS = listOf("analog", "led", "gelombang", "spektrum")
fun meterName(m: String) = when (m) {
    "led" -> "LED VU"; "gelombang" -> "GELOMBANG"; "spektrum" -> "SPEKTRUM"; else -> "VU ANALOG"
}
fun nextMeter(m: String) = METERS[(METERS.indexOf(m) + 1).mod(METERS.size)]

fun fmtMs(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }
fun fmtHms(secs: Long) = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
fun cleanName(name: String) = name.substringBeforeLast('.')

@Composable
fun rememberNow(key: Any?): Long {
    val now by produceState(System.currentTimeMillis(), key) { while (true) { value = System.currentTimeMillis(); delay(500) } }
    return now
}

@Composable
private fun Badge(text: String, filled: Boolean) {
    val p = LocalPal.current
    val shape = RoundedCornerShape(6.dp)
    val m = if (filled) Modifier.background(p.onAirBg, shape) else Modifier.border(1.dp, p.onAir, shape)
    Row(m.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(p.onAir))
        Spacer(Modifier.width(6.dp))
        Text(text, color = p.onAir, fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 1.sp)
    }
}

/** Layar LCD: status, rekam, timer, judul 3 baris, meter pilihan, info server. */
@Composable
fun Lcd(s: UiState, snap: MeterSnap, onMeter: () -> Unit, modifier: Modifier = Modifier, meterHeight: Dp = 92.dp, titleSize: Int = 18) {
    val p = LocalPal.current
    val now = rememberNow(s.onAirSince + s.recSince)
    val mono = FontFamily.Monospace
    val shape = RoundedCornerShape(22.dp)
    val air = when (s.status) {
        AirStatus.STANDBY -> null
        AirStatus.CONNECTING -> "MENYAMBUNG"
        AirStatus.ON_AIR -> "ON AIR"
        AirStatus.RECONNECTING -> "TERPUTUS"
    }
    val secs = if (s.onAirSince > 0) (now - s.onAirSince) / 1000 else 0
    val server = when (s.status) {
        AirStatus.STANDBY -> "SIAGA"; AirStatus.CONNECTING -> "MENYAMBUNG"
        AirStatus.ON_AIR -> "SERVER OK"; AirStatus.RECONNECTING -> "ULANG..."
    }
    Column(modifier.fillMaxWidth().clip(shape).background(p.lcd).border(1.dp, p.lcdLine, shape).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (air != null) Badge(air, true)
            else Text("STANDBY", color = p.inkDim, fontFamily = mono, fontSize = 13.sp, letterSpacing = 1.5.sp)
            if (s.recording) Badge("REC " + fmtHms(((now - s.recSince) / 1000).coerceAtLeast(0)), false)
            Spacer(Modifier.weight(1f))
            Text(fmtHms(secs), color = p.inkHi, fontFamily = mono, fontSize = 26.sp)
        }
        Box(Modifier.fillMaxWidth().height((titleSize * 1.32f * 3).dp)) {
            Text(s.title.ifBlank { "Belum ada judul siaran" }, color = if (s.title.isBlank()) p.inkDim else p.inkHi,
                fontWeight = FontWeight.SemiBold, fontSize = titleSize.sp, lineHeight = (titleSize * 1.32f).sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(meterHeight), contentAlignment = Alignment.Center) {
            when (s.meter) {
                "led" -> LedVu(snap)
                "gelombang" -> WaveMeter(snap)
                "spektrum" -> SpectrumMeter(snap)
                else -> AnalogVu(snap)
            }
        }
        HorizontalDivider(color = p.lcdLine)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.listeners ?: "–"} PENDENGAR", color = p.inkDim, fontFamily = mono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text("${s.config.bitrate} KBPS", color = p.inkDim, fontFamily = mono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(server, color = p.inkDim, fontFamily = mono, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, p.lcdLine, RoundedCornerShape(6.dp)).clickable(onClick = onMeter)
                .padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(meterName(s.meter) + " ▾", color = p.inkHi, fontFamily = mono, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun WaveMeter(snap: MeterSnap) {
    val p = LocalPal.current
    Canvas(Modifier.fillMaxSize()) {
        val mid = size.height / 2
        drawLine(p.lcdLine, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        val w = snap.wave
        val step = size.width / w.size
        val sw = max(1f, step * 0.7f)
        for (i in w.indices) {
            val h = max(1f, sqrt(w[i].coerceIn(0f, 1f)) * (mid - 2))
            val x = i * step + step / 2
            drawLine(p.meter, Offset(x, mid - h), Offset(x, mid + h), sw, StrokeCap.Butt)
        }
    }
}

private fun dbfs(v: Float) = 20 * log10(max(1e-5f, v))

@Composable
fun LedVu(snap: MeterSnap) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        listOf("L" to snap.peakL, "R" to snap.peakR).forEach { (ch, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(ch, color = p.inkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.width(14.dp))
                Canvas(Modifier.weight(1f).height(20.dp)) {
                    val segs = 32
                    val gap = 2.dp.toPx()
                    val w = (size.width - gap * (segs - 1)) / segs
                    val on = (((dbfs(v) + 40f) / 40f).coerceIn(0f, 1f) * segs).toInt()
                    for (k in 0 until segs) {
                        val c = when {
                            k >= on -> p.meterOff
                            k >= 28 -> p.onAir
                            k >= 23 -> p.meterMid
                            else -> p.meter
                        }
                        drawRoundRect(c, Offset(k * (w + gap), 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("-40", "-30", "-20", "-12", "-6", "-3", "0").forEach {
                Text(it, color = p.inkDim, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

private fun DrawScope.dial(p: Pal, level: Float, label: String) {
    val r = size.width * 0.52f
    val cx = size.width / 2
    val cy = size.height + r * 0.18f
    fun pt(deg: Float, rr: Float): Offset {
        val a = (deg - 90f) * PI.toFloat() / 180f
        return Offset(cx + rr * cos(a), cy + rr * sin(a))
    }
    val rad = r - 6.dp.toPx()
    drawArc(p.inkDim, -140f, 100f, false, Offset(cx - rad, cy - rad), Size(rad * 2, rad * 2), style = Stroke(1.5.dp.toPx()))
    drawArc(p.onAir, -65f, 25f, false, Offset(cx - rad, cy - rad), Size(rad * 2, rad * 2), style = Stroke(3.dp.toPx()))
    for (i in 0..10) {
        val d = -50f + i * 10f
        drawLine(if (d >= 25f) p.onAir else p.inkDim, pt(d, rad), pt(d, rad - if (i % 5 == 0) 12.dp.toPx() else 7.dp.toPx()), 1.5.dp.toPx())
    }
    // 0 VU = -18 dBFS RMS; skala -20..+3 VU
    val vu = dbfs(level) + 18f
    val deg = -50f + ((vu + 20f) / 23f).coerceIn(0f, 1f) * 100f
    drawLine(p.needle, Offset(cx, cy), pt(deg, rad - 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
}

@Composable
fun AnalogVu(snap: MeterSnap) {
    val p = LocalPal.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("L" to snap.vuL, "R" to snap.vuR).forEach { (ch, v) ->
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(p.dial)
                .border(1.dp, p.lcdLine, RoundedCornerShape(10.dp))) {
                Canvas(Modifier.fillMaxSize()) { dial(p, v, ch) }
                Text("VU $ch", color = p.inkDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
            }
        }
    }
}

@Composable
fun SpectrumMeter(snap: MeterSnap) {
    val p = LocalPal.current
    Canvas(Modifier.fillMaxSize()) {
        val n = snap.spec.size
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val brush = Brush.verticalGradient(0f to p.onAir, 0.22f to p.meterMid, 0.45f to p.meter, 1f to p.meter, startY = 0f, endY = size.height)
        for (i in 0 until n) {
            val h = snap.spec[i] * size.height
            val x = i * (w + gap)
            drawRect(brush, Offset(x, size.height - h), Size(w, h))
            val py = size.height - snap.specPeak[i] * size.height
            drawRect(p.inkHi, Offset(x, (py - 2.dp.toPx()).coerceAtLeast(0f)), Size(w, 2.dp.toPx()))
        }
    }
}

@Composable
fun sliderColors(): SliderColors {
    val p = LocalPal.current
    return SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent, inactiveTrackColor = p.line)
}

@Composable
fun switchColors(): SwitchColors {
    val p = LocalPal.current
    return SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = Color.White,
        uncheckedTrackColor = p.line, uncheckedThumbColor = p.sub, uncheckedBorderColor = Color.Transparent)
}

@Composable
fun fieldColors(): TextFieldColors {
    val p = LocalPal.current
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor = p.accent, unfocusedBorderColor = p.line, cursorColor = p.accent,
        focusedTextColor = p.text, unfocusedTextColor = p.text,
        focusedContainerColor = p.surface, unfocusedContainerColor = p.surface,
        focusedPlaceholderColor = p.faint, unfocusedPlaceholderColor = p.faint,
        focusedLabelColor = p.accent, unfocusedLabelColor = p.sub,
    )
}

/** Tombol bulat berlabel (Mic, Rekam). */
@Composable
fun RoundKey(label: String, active: Boolean, activeColor: Color, activeBg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPal.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (active) activeBg else p.surface)
                .border(1.5.dp, if (active) activeColor else p.line, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(label, color = p.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
    }
}
