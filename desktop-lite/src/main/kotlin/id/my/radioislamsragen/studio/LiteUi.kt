package id.my.radioislamsragen.studio

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.filechooser.FileNameExtensionFilter
import javax.swing.plaf.basic.BasicSliderUI
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

/** Palet warna versi ringan (sama dengan versi 64-bit). */
class LTheme(
    val dark: Boolean, val bg: Color, val surface: Color, val line: Color, val text: Color, val sub: Color, val faint: Color,
    val accent: Color, val accentSoft: Color, val onAccent: Color,
    val lcd: Color, val lcdLine: Color, val inkHi: Color, val inkDim: Color, val onAir: Color, val onAirBg: Color,
    val meter: Color, val meterMid: Color, val recBg: Color,
)

private fun c(hex: Long) = Color((hex and 0xFFFFFF).toInt())
private val DARK = LTheme(true, c(0x0F1214), c(0x171B1E), c(0x252A2E), c(0xE6E9EA), c(0x8E979C), c(0x656E73),
    c(0x2BB3A8), c(0x15292A), c(0x0B1C1B), c(0x0B2321), c(0x1D3D3A), c(0xC6FFF6), c(0x5FBFB2), c(0xFF6B6F), c(0x3A1517),
    c(0x3CC4B6), c(0xE8D46A), c(0x2A1517))
private val LIGHT = LTheme(false, c(0xF3F5F5), c(0xFFFFFF), c(0xE2E7E8), c(0x1B2326), c(0x5F6B70), c(0x7D888C),
    c(0x0F8A81), c(0xE1F3F1), c(0xFFFFFF), c(0xE2EFEA), c(0xBFD5CE), c(0x0E3833), c(0x4A7770), c(0xC62F35), c(0xF6DCDD),
    c(0x16968C), c(0xC99A1A), c(0xFDECEC))
private val RED = c(0xE5484D)
private val AMBER = c(0xE0A93B)

var T: LTheme = DARK

fun fmtMs(ms: Long): String { val t = ms / 1000; return "%02d:%02d".format(t / 60, t % 60) }
fun fmtHms(secs: Long) = "%02d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
fun cleanName(name: String) = name.substringBeforeLast('.')
private val UI_FONT = if (GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.contains("Segoe UI")) "Segoe UI" else "Dialog"
private val MONO_FONT = if (GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.contains("Consolas")) "Consolas" else "Monospaced"
fun uiFont(size: Int, bold: Boolean = false) = Font(UI_FONT, if (bold) Font.BOLD else Font.PLAIN, size)
fun monoFont(size: Int) = Font(MONO_FONT, Font.PLAIN, size)

fun Graphics.smooth(): Graphics2D = (this as Graphics2D).also {
    it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
}

/** Panel berujung bulat. */
open class Card(private val radius: Int = 18, private val bg: () -> Color = { T.surface }, private val border: () -> Color? = { null }) : JPanel() {
    init { isOpaque = false }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        g2.color = bg()
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), radius * 2f, radius * 2f))
        border()?.let { g2.color = it; g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, radius * 2f, radius * 2f)) }
        g2.dispose()
    }
}

enum class Kind { PRIMARY, SOFT, GHOST, DANGER, WARN }

/** Tombol datar modern. */
class FlatButton(text: String, var kind: Kind = Kind.GHOST, private val radius: Int = 12, private val glyph: ((Graphics2D, Int, Int, Color) -> Unit)? = null) : JButton(text) {
    init {
        isContentAreaFilled = false; isBorderPainted = false; isFocusPainted = false; isOpaque = false
        font = uiFont(13, true)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = EmptyBorder(8, 14, 8, 14)
    }
    private fun colors(): Pair<Color, Color> = when (kind) {
        Kind.PRIMARY -> T.accent to T.onAccent
        Kind.SOFT -> T.accentSoft to T.accent
        Kind.GHOST -> T.surface to T.sub
        Kind.DANGER -> RED to Color.WHITE
        Kind.WARN -> AMBER to c(0x1B1405)
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        var (bg, fg) = colors()
        if (model.isPressed) bg = bg.darker() else if (model.isRollover) bg = if (T.dark) bg.brighter() else bg.darker()
        g2.color = bg
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), radius * 2f, radius * 2f))
        if (kind == Kind.GHOST) { g2.color = T.line; g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, radius * 2f, radius * 2f)) }
        g2.font = font
        val fm = g2.fontMetrics
        val tw = if (text.isNullOrEmpty()) 0 else fm.stringWidth(text)
        val iw = if (glyph != null) 18 else 0
        val gap = if (glyph != null && tw > 0) 8 else 0
        var x = (width - (tw + iw + gap)) / 2
        if (glyph != null) { glyph.invoke(g2, x, height / 2 - 9, fg); x += iw + gap }
        g2.color = fg
        if (tw > 0) g2.drawString(text, x, (height - fm.height) / 2 + fm.ascent)
        g2.dispose()
    }
}

/** Tombol bulat (Mic, Rekam) dengan label kecil di bawah. */
class RoundKey(private val label: String, private val drawIcon: (Graphics2D, Int, Int, Boolean) -> Unit) : JComponent() {
    var active = false
    var activeColor: () -> Color = { T.accent }
    var activeBg: () -> Color = { T.accentSoft }
    var onClick: () -> Unit = {}
    init {
        preferredSize = Dimension(64, 76); minimumSize = preferredSize; maximumSize = preferredSize
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick() } })
    }
    override fun paintComponent(g: Graphics) {
        val g2 = g.create().smooth()
        val d = 56; val x = (width - d) / 2
        g2.color = if (active) activeBg() else T.surface
        g2.fill(Ellipse2D.Float(x.toFloat(), 0f, d.toFloat(), d.toFloat()))
        g2.color = if (active) activeColor() else T.line
        g2.stroke = BasicStroke(1.6f)
        g2.draw(Ellipse2D.Float(x + 0.8f, 0.8f, d - 1.6f, d - 1.6f))
        drawIcon(g2, x + d / 2, d / 2, active)
        g2.font = uiFont(11); g2.color = T.faint
        val fm = g2.fontMetrics
        g2.drawString(label, (width - fm.stringWidth(label)) / 2, d + 16)
        g2.dispose()
    }
}

// ---------- Ikon sederhana ----------
object Ico {
    fun play(g: Graphics2D, cx: Int, cy: Int, c: Color) { g.color = c; g.fillPolygon(intArrayOf(cx - 5, cx - 5, cx + 7), intArrayOf(cy - 7, cy + 7, cy), 3) }
    fun pause(g: Graphics2D, cx: Int, cy: Int, c: Color) { g.color = c; g.fillRect(cx - 6, cy - 7, 4, 14); g.fillRect(cx + 2, cy - 7, 4, 14) }
    fun prev(g: Graphics2D, cx: Int, cy: Int, c: Color) { g.color = c; g.fillRect(cx - 7, cy - 6, 2, 12); g.fillPolygon(intArrayOf(cx + 6, cx + 6, cx - 4), intArrayOf(cy - 6, cy + 6, cy), 3) }
    fun next(g: Graphics2D, cx: Int, cy: Int, c: Color) { g.color = c; g.fillRect(cx + 5, cy - 6, 2, 12); g.fillPolygon(intArrayOf(cx - 6, cx - 6, cx + 4), intArrayOf(cy - 6, cy + 6, cy), 3) }
    fun mic(g: Graphics2D, cx: Int, cy: Int, c: Color, off: Boolean) {
        g.color = c; g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawRoundRect(cx - 4, cy - 10, 8, 13, 8, 8)
        g.drawArc(cx - 7, cy - 6, 14, 13, 180, 180)
        g.drawLine(cx, cy + 7, cx, cy + 10)
        if (off) g.drawLine(cx - 8, cy - 9, cx + 8, cy + 9)
    }
    fun plus(g: Graphics2D, x: Int, y: Int, c: Color) { g.color = c; g.stroke = BasicStroke(2f); g.drawLine(x + 9, y + 3, x + 9, y + 15); g.drawLine(x + 3, y + 9, x + 15, y + 9) }
}

/** Penggeser bertema. */
class ThemedSlider(v: Int) : JSlider(0, 100, v) {
    init {
        isOpaque = false; isFocusable = false
        setUI(object : BasicSliderUI(this) {
            override fun paintTrack(g: Graphics) {
                val g2 = g.create().smooth()
                val y = trackRect.y + trackRect.height / 2 - 2
                g2.color = T.line; g2.fillRoundRect(trackRect.x, y, trackRect.width, 4, 4, 4)
                g2.color = T.accent; g2.fillRoundRect(trackRect.x, y, thumbRect.x + thumbRect.width / 2 - trackRect.x, 4, 4, 4)
                g2.dispose()
            }
            override fun paintThumb(g: Graphics) {
                val g2 = g.create().smooth()
                g2.color = T.accent
                g2.fillOval(thumbRect.x + thumbRect.width / 2 - 8, thumbRect.y + thumbRect.height / 2 - 8, 16, 16)
                g2.dispose()
            }
            override fun paintFocus(g: Graphics) {}
            override fun getThumbSize() = Dimension(18, 18)
        })
    }
}

/** Layar LCD: status, rekam, timer, judul 3 baris, gelombang/spektrum, info server. */
class LcdPanel : JComponent() {
    var s = UiState()
    var snap = MeterSnap()
    private var meterBox = Rectangle()
    init {
        preferredSize = Dimension(520, 300)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (meterBox.contains(e.point)) Studio.cycleMeter() }
        })
    }

    private fun badge(g: Graphics2D, x: Int, y: Int, text: String, filled: Boolean): Int {
        g.font = monoFont(13)
        val w = g.fontMetrics.stringWidth(text) + 30
        if (filled) { g.color = T.onAirBg; g.fillRoundRect(x, y, w, 22, 10, 10) }
        else { g.color = T.onAir; g.drawRoundRect(x, y, w - 1, 21, 10, 10) }
        g.color = T.onAir; g.fillOval(x + 9, y + 7, 8, 8)
        g.drawString(text, x + 23, y + 16)
        return w
    }

    private fun wrap(g: Graphics2D, text: String, width: Int, maxLines: Int): List<String> {
        val fm = g.fontMetrics
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in text.split(" ")) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (fm.stringWidth(t) <= width) cur = t
            else { if (cur.isNotEmpty()) lines += cur; cur = w }
            if (lines.size == maxLines) break
        }
        if (lines.size < maxLines && cur.isNotEmpty()) lines += cur
        if (lines.size == maxLines && lines.joinToString(" ").length < text.length) {
            var last = lines.last()
            while (last.isNotEmpty() && fm.stringWidth("$last…") > width) last = last.dropLast(1)
            lines[maxLines - 1] = "$last…"
        }
        return lines
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create().smooth()
        val w = width; val h = height; val pad = 16
        g.color = T.lcd; g.fillRoundRect(0, 0, w - 1, h - 1, 40, 40)
        g.color = T.lcdLine; g.drawRoundRect(0, 0, w - 1, h - 1, 40, 40)
        val now = System.currentTimeMillis()

        // Baris status
        var x = pad
        val air = when (s.status) {
            AirStatus.STANDBY -> null; AirStatus.CONNECTING -> "MENYAMBUNG"
            AirStatus.ON_AIR -> "ON AIR"; AirStatus.RECONNECTING -> "TERPUTUS"
        }
        if (air != null) x += badge(g, x, pad, air, true) + 8
        else { g.font = monoFont(13); g.color = T.inkDim; g.drawString("STANDBY", x, pad + 16); x += 80 }
        if (s.recording) badge(g, x, pad, "REC " + fmtHms(((now - s.recSince) / 1000).coerceAtLeast(0)), false)
        g.font = monoFont(26); g.color = T.inkHi
        val secs = if (s.onAirSince > 0) (now - s.onAirSince) / 1000 else 0
        val timer = fmtHms(secs)
        g.drawString(timer, w - pad - g.fontMetrics.stringWidth(timer), pad + 22)

        // Judul 3 baris
        g.font = uiFont(20, true)
        val title = s.title.ifBlank { "Belum ada judul siaran" }
        g.color = if (s.title.isBlank()) T.inkDim else T.inkHi
        val lh = 26
        var y = pad + 40 + lh - 6
        for (l in wrap(g, title, w - pad * 2, 3)) { g.drawString(l, pad, y); y += lh }

        // Meter
        val my = pad + 40 + lh * 3 + 6
        val bottom = h - pad - 30
        val mh = bottom - my
        if (s.meter == "spektrum") drawSpectrum(g, pad, my, w - pad * 2, mh) else drawWave(g, pad, my, w - pad * 2, mh)

        // Info
        g.color = T.lcdLine; g.drawLine(pad, bottom + 6, w - pad, bottom + 6)
        g.font = monoFont(12); g.color = T.inkDim
        val server = when (s.status) {
            AirStatus.STANDBY -> "SIAGA"; AirStatus.CONNECTING -> "MENYAMBUNG"
            AirStatus.ON_AIR -> "SERVER OK"; AirStatus.RECONNECTING -> "ULANG..."
        }
        val iy = h - pad - 4
        g.drawString("${s.listeners ?: "–"} PENDENGAR", pad, iy)
        val kb = "${s.config.bitrate} KBPS"
        g.drawString(kb, pad + (w - pad * 2) / 3, iy)
        g.drawString(server, pad + (w - pad * 2) * 2 / 3 - 40, iy)
        val mname = (if (s.meter == "spektrum") "SPEKTRUM" else "GELOMBANG") + " ▾"
        val mw = g.fontMetrics.stringWidth(mname) + 14
        meterBox = Rectangle(w - pad - mw, iy - 16, mw, 22)
        g.color = T.lcdLine; g.drawRoundRect(meterBox.x, meterBox.y, meterBox.width, meterBox.height, 10, 10)
        g.color = T.inkHi; g.drawString(mname, meterBox.x + 7, iy)
        g.dispose()
    }

    private fun drawWave(g: Graphics2D, x0: Int, y0: Int, w: Int, h: Int) {
        val mid = y0 + h / 2
        g.color = T.lcdLine; g.drawLine(x0, mid, x0 + w, mid)
        val wv = snap.wave
        val step = w.toFloat() / wv.size
        g.color = T.meter
        for (i in wv.indices) {
            val a = sqrt(wv[i].coerceIn(0f, 1f))
            val hh = max(1, (a * (h / 2 - 2)).toInt())
            val x = (x0 + i * step).toInt()
            g.fillRect(x, mid - hh, max(1, (step * 0.7f).toInt()), hh * 2)
        }
    }

    private fun drawSpectrum(g: Graphics2D, x0: Int, y0: Int, w: Int, h: Int) {
        val n = snap.spec.size
        val gap = 2
        val bw = (w - gap * (n - 1)).toFloat() / n
        for (i in 0 until n) {
            val bh = (snap.spec[i] * h).toInt()
            val x = (x0 + i * (bw + gap)).toInt()
            val top = y0 + h - bh
            // warna menurut tinggi
            val hiCut = y0 + (h * 0.22f).toInt(); val midCut = y0 + (h * 0.45f).toInt()
            g.color = T.meter; g.fillRect(x, max(top, midCut), bw.toInt(), y0 + h - max(top, midCut))
            if (top < midCut) { g.color = T.meterMid; g.fillRect(x, max(top, hiCut), bw.toInt(), midCut - max(top, hiCut)) }
            if (top < hiCut) { g.color = T.onAir; g.fillRect(x, top, bw.toInt(), hiCut - top) }
            val py = y0 + h - (snap.specPeak[i] * h).toInt()
            g.color = T.inkHi; g.fillRect(x, max(y0, py - 2), bw.toInt(), 2)
        }
    }
}

/** Bilah progres lagu. */
class ProgressBar : JComponent() {
    var pos = 0L; var dur = 0L
    init { preferredSize = Dimension(300, 20); maximumSize = Dimension(Int.MAX_VALUE, 20) }
    override fun paintComponent(g0: Graphics) {
        val g = g0.create().smooth()
        g.font = monoFont(11); g.color = T.sub
        val a = fmtMs(pos); val b = fmtMs(dur)
        val fm = g.fontMetrics
        g.drawString(a, 0, 14); g.drawString(b, width - fm.stringWidth(b), 14)
        val x0 = fm.stringWidth(a) + 10; val x1 = width - fm.stringWidth(b) - 10
        g.color = T.line; g.fillRoundRect(x0, 8, x1 - x0, 4, 4, 4)
        val p = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        g.color = T.accent; g.fillRoundRect(x0, 8, ((x1 - x0) * p).toInt(), 4, 4, 4)
        g.dispose()
    }
}

/** Jendela utama versi ringan. */
class MainWindow : JFrame("Rais Stream Ringan") {
    private val logo = JLabel()
    private val logoDark = loadImg("/logo_gelap.png", 46)
    private val logoLight = loadImg("/logo_terang.png", 46)
    private val appName = JLabel("Rais Stream")
    private val themeBtn = FlatButton("Terang")
    private val settingsBtn = FlatButton("Pengaturan")
    private val lcd = LcdPanel()
    private val titleField = JTextField()
    private val sendBtn = FlatButton("Kirim", Kind.PRIMARY)
    private val faderA = ThemedSlider(30)
    private val faderB = ThemedSlider(90)
    private val speaker = ThemedSlider(60)
    private val faderALeft = JLabel(); private val faderARight = JLabel()
    private val faderBLeft = JLabel("Mic"); private val faderBRight = JLabel()
    private val speakerLeft = JLabel("Speaker"); private val speakerRight = JLabel()
    private val rowB = JPanel(BorderLayout(10, 0))
    private val micKey = RoundKey("Mic") { g, cx, cy, a -> Ico.mic(g, cx, cy, if (a) T.accent else T.sub, !a) }
    private val recKey = RoundKey("Rekam") { g, cx, cy, a ->
        g.color = RED
        if (a) g.fillRoundRect(cx - 8, cy - 8, 16, 16, 6, 6) else g.fillOval(cx - 9, cy - 9, 18, 18)
    }
    private val airBtn = FlatButton("Mulai Siaran", Kind.PRIMARY, 26)
    private val msgLabel = JLabel(" ")
    private val openFolderBtn = FlatButton("Buka folder", Kind.SOFT)
    private val trackName = JLabel("Belum ada MP3")
    private val trackSub = JLabel(" ")
    private val progress = ProgressBar()
    private val playBtn = FlatButton("", Kind.PRIMARY, 22) { g, x, y, c -> if (Studio.ui.value.playing) Ico.pause(g, x + 9, y + 9, c) else Ico.play(g, x + 9, y + 9, c) }
    private val prevBtn = FlatButton("", Kind.GHOST, 18) { g, x, y, c -> Ico.prev(g, x + 9, y + 9, T.text) }
    private val nextBtn = FlatButton("", Kind.GHOST, 18) { g, x, y, c -> Ico.next(g, x + 9, y + 9, T.text) }
    private val shuffleBtn = FlatButton("Acak", Kind.GHOST)
    private val repeatBtn = FlatButton("Ulang", Kind.GHOST)
    private val listTitle = JLabel("Daftar Putar")
    private val addBtn = FlatButton("Tambah MP3", Kind.SOFT, 12) { g, x, y, c -> Ico.plus(g, x, y, c) }
    private val removeBtn = FlatButton("Hapus", Kind.GHOST)
    private val listModel = DefaultListModel<String>()
    private val list = JList(listModel)
    private var last = UiState()
    private var first = true
    private var renameOpen = false

    private fun loadImg(path: String, size: Int): ImageIcon? = try {
        ImageIcon(ImageIO.read(javaClass.getResource(path)).getScaledInstance(size, size, Image.SCALE_SMOOTH))
    } catch (_: Exception) { null }

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) { Studio.shutdown(); dispose(); System.exit(0) }
        })
        try { iconImage = ImageIO.read(javaClass.getResource("/icon.png")) } catch (_: Exception) {}
        minimumSize = Dimension(900, 620)
        size = Dimension(1040, 700)
        setLocationRelativeTo(null)

        val root = JPanel(GridBagLayout()).apply { border = EmptyBorder(16, 16, 12, 16) }
        contentPane = root
        val gc = GridBagConstraints().apply { fill = GridBagConstraints.BOTH; weighty = 1.0 }
        gc.gridx = 0; gc.weightx = 1.15; gc.insets = Insets(0, 0, 0, 16)
        root.add(leftColumn(), gc)
        gc.gridx = 1; gc.weightx = 1.0; gc.insets = Insets(0, 0, 0, 0)
        root.add(rightColumn(), gc)

        wire()
        applyTheme()
        Timer(66) { tick() }.start()
        thread(isDaemon = true, name = "rais-stats") {
            while (true) {
                val n = try { Caster.listeners(Studio.ui.value.config) } catch (_: Exception) { null }
                Studio.setListeners(n)
                Thread.sleep(15_000)
            }
        }
    }

    private fun stretch(c: JComponent, h: Int): JComponent {
        c.alignmentX = LEFT_ALIGNMENT
        c.maximumSize = Dimension(Int.MAX_VALUE, h); c.preferredSize = Dimension(c.preferredSize.width, h)
        return c
    }

    private fun leftColumn(): JComponent {
        val col = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        val header = JPanel(BorderLayout(12, 0)).apply { isOpaque = false }
        header.add(logo, BorderLayout.WEST)
        appName.font = uiFont(19, true)
        header.add(appName, BorderLayout.CENTER)
        header.add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { isOpaque = false; add(themeBtn); add(settingsBtn) }, BorderLayout.EAST)
        col.add(stretch(header, 48)); col.add(Box.createVerticalStrut(12))
        col.add(stretch(lcd, 300)); col.add(Box.createVerticalStrut(12))

        val titleRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        titleField.font = uiFont(14)
        titleField.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(T.line), EmptyBorder(6, 10, 6, 10))
        titleRow.add(titleField, BorderLayout.CENTER); titleRow.add(sendBtn, BorderLayout.EAST)
        col.add(stretch(titleRow, 40)); col.add(Box.createVerticalStrut(12))

        fun row(l: JLabel, s: JSlider, r: JLabel) = JPanel(BorderLayout(10, 0)).apply {
            isOpaque = false
            l.font = uiFont(12); r.font = uiFont(12)
            l.preferredSize = Dimension(70, 20); r.preferredSize = Dimension(70, 20); r.horizontalAlignment = SwingConstants.RIGHT
            add(l, BorderLayout.WEST); add(s, BorderLayout.CENTER); add(r, BorderLayout.EAST)
        }
        col.add(stretch(row(faderALeft, faderA, faderARight), 30))
        rowB.isOpaque = false
        faderBLeft.font = uiFont(12); faderBRight.font = uiFont(12)
        faderBLeft.preferredSize = Dimension(70, 20); faderBRight.preferredSize = Dimension(70, 20); faderBRight.horizontalAlignment = SwingConstants.RIGHT
        rowB.add(faderBLeft, BorderLayout.WEST); rowB.add(faderB, BorderLayout.CENTER); rowB.add(faderBRight, BorderLayout.EAST)
        col.add(stretch(rowB, 30))
        col.add(stretch(row(speakerLeft, speaker, speakerRight), 30))
        col.add(Box.createVerticalGlue())

        val footer = JPanel(BorderLayout(12, 0)).apply { isOpaque = false }
        footer.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false; add(micKey); add(recKey) }, BorderLayout.WEST)
        airBtn.font = uiFont(16, true)
        footer.add(JPanel(BorderLayout()).apply { isOpaque = false; border = EmptyBorder(0, 0, 18, 0); add(airBtn) }, BorderLayout.CENTER)
        col.add(stretch(footer, 76))

        val status = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        msgLabel.font = uiFont(12)
        status.add(msgLabel, BorderLayout.CENTER); status.add(openFolderBtn, BorderLayout.EAST)
        col.add(stretch(status, 32))
        return col
    }

    private fun rightColumn(): JComponent {
        val col = JPanel(BorderLayout(0, 14)).apply { isOpaque = false }
        val player = Card().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = EmptyBorder(14, 16, 12, 16) }
        trackName.font = uiFont(15, true); trackSub.font = uiFont(12)
        for (c in listOf<JComponent>(trackName, trackSub, progress)) c.alignmentX = LEFT_ALIGNMENT
        player.add(trackName); player.add(trackSub); player.add(Box.createVerticalStrut(8)); player.add(progress)
        player.add(Box.createVerticalStrut(8))
        playBtn.preferredSize = Dimension(48, 44); prevBtn.preferredSize = Dimension(44, 40); nextBtn.preferredSize = Dimension(44, 40)
        player.add(JPanel(FlowLayout(FlowLayout.CENTER, 10, 0)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            add(shuffleBtn); add(prevBtn); add(playBtn); add(nextBtn); add(repeatBtn)
        })
        col.add(player, BorderLayout.NORTH)

        val pl = Card().apply { layout = BorderLayout(0, 8); border = EmptyBorder(12, 12, 12, 12) }
        val head = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }
        listTitle.font = uiFont(15, true)
        head.add(listTitle, BorderLayout.CENTER)
        head.add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false; add(removeBtn); add(addBtn) }, BorderLayout.EAST)
        pl.add(head, BorderLayout.NORTH)
        list.font = uiFont(14)
        list.fixedCellHeight = 34
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, sel: Boolean, f: Boolean): Component {
                val c = super.getListCellRendererComponent(l, v, i, sel, false) as JLabel
                val cur = i == Studio.ui.value.currentIndex
                c.border = EmptyBorder(0, 10, 0, 10)
                c.text = "%02d   %s".format(i + 1, v)
                c.isOpaque = true
                c.background = when { sel -> T.line; cur -> T.accentSoft; else -> T.surface }
                c.foreground = if (cur) T.accent else T.text
                return c
            }
        }
        val sp = JScrollPane(list).apply { border = BorderFactory.createEmptyBorder(); viewport.isOpaque = false; isOpaque = false }
        pl.add(sp, BorderLayout.CENTER)
        col.add(pl, BorderLayout.CENTER)
        return col
    }

    private fun wire() {
        themeBtn.addActionListener { Studio.setDark(!Studio.ui.value.dark) }
        settingsBtn.addActionListener { SettingsDialog(this).isVisible = true }
        sendBtn.addActionListener { Studio.sendTitle(titleField.text) }
        titleField.addActionListener { Studio.sendTitle(titleField.text) }
        faderA.addChangeListener { val s = Studio.ui.value; if (s.oneFader) Studio.setFader(faderA.value / 100f) else Studio.setMusicVol(faderA.value / 100f) }
        faderB.addChangeListener { Studio.setMicVol(faderB.value / 100f) }
        speaker.addChangeListener { Studio.setSpeakerVol(speaker.value / 100f) }
        micKey.onClick = { Studio.toggleMic() }
        recKey.activeColor = { RED }; recKey.activeBg = { T.recBg }
        recKey.onClick = { Studio.toggleRec() }
        airBtn.addActionListener {
            val s = Studio.ui.value
            if (s.status == AirStatus.STANDBY) {
                if (s.config.pass.isBlank()) { Studio.msg("Isi kata sandi server dulu.", true); SettingsDialog(this).isVisible = true }
                else Studio.goOnAir()
            } else Studio.endAir()
        }
        openFolderBtn.addActionListener { Studio.openRecFolder() }
        playBtn.addActionListener { Studio.togglePlay() }
        prevBtn.addActionListener { Studio.prev() }
        nextBtn.addActionListener { Studio.next() }
        shuffleBtn.addActionListener { Studio.setShuffle(!Studio.ui.value.shuffle) }
        repeatBtn.addActionListener { Studio.setRepeat(!Studio.ui.value.repeat) }
        addBtn.addActionListener { pickFiles() }
        removeBtn.addActionListener { val i = list.selectedIndex; if (i >= 0) Studio.removeTrack(i) }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) { val i = list.locationToIndex(e.point); if (i >= 0) Studio.playIndex(i) } }
        })
        val s = Studio.ui.value
        faderA.value = ((if (s.oneFader) s.fader else s.musicVol) * 100).toInt()
        faderB.value = (s.micVol * 100).toInt()
        speaker.value = (s.speakerVol * 100).toInt()
    }

    private fun pickFiles() {
        val fc = JFileChooser()
        fc.isMultiSelectionEnabled = true
        fc.fileFilter = FileNameExtensionFilter("File MP3", "mp3")
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            val t = fc.selectedFiles.filter { it.name.lowercase().endsWith(".mp3") }.map { Track(it.absolutePath, it.name) }
            if (t.isNotEmpty()) Studio.addTracks(t)
        }
    }

    fun applyTheme() {
        T = if (Studio.ui.value.dark) DARK else LIGHT
        contentPane.background = T.bg
        logo.icon = if (T.dark) logoDark else logoLight
        appName.foreground = T.text
        themeBtn.text = if (T.dark) "Terang" else "Gelap"
        titleField.background = T.surface; titleField.foreground = T.text; titleField.caretColor = T.accent
        titleField.border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(T.line), EmptyBorder(6, 10, 6, 10))
        for (l in listOf(faderALeft, faderARight, faderBLeft, faderBRight, speakerLeft, speakerRight, trackSub)) l.foreground = T.sub
        trackName.foreground = T.text; listTitle.foreground = T.text
        list.background = T.surface
        repaint()
    }

    private fun tick() {
        val s = Studio.ui.value
        lcd.s = s; lcd.snap = Studio.meters.value; lcd.repaint()
        if (s.dark != last.dark) applyTheme()
        if (s.oneFader != last.oneFader || first) {
            first = false
            rowB.isVisible = !s.oneFader
            faderA.value = ((if (s.oneFader) s.fader else s.musicVol) * 100).toInt()
        }
        if (s.oneFader) { faderALeft.text = "MP3 ${((1 - s.fader) * 100).toInt()}%"; faderARight.text = "Mic ${(s.fader * 100).toInt()}%" }
        else { faderALeft.text = "Musik"; faderARight.text = "${(s.musicVol * 100).toInt()}%"; faderBRight.text = "${(s.micVol * 100).toInt()}%" }
        speakerRight.text = "${(s.speakerVol * 100).toInt()}%"
        if (s.title != last.title && !titleField.isFocusOwner) titleField.text = s.title
        micKey.active = s.micOn; recKey.active = s.recording; micKey.repaint(); recKey.repaint()
        when (s.status) {
            AirStatus.STANDBY -> { airBtn.text = "Mulai Siaran"; airBtn.kind = Kind.PRIMARY }
            AirStatus.CONNECTING -> { airBtn.text = "Batal"; airBtn.kind = Kind.WARN }
            else -> { airBtn.text = "Akhiri Siaran"; airBtn.kind = Kind.DANGER }
        }
        msgLabel.text = s.message.ifEmpty { " " }
        msgLabel.foreground = if (s.messageError) RED else T.sub
        openFolderBtn.isVisible = !s.recording && s.lastRecPath.isNotEmpty()
        val cur = s.playlist.getOrNull(s.currentIndex)
        trackName.text = cur?.let { cleanName(it.name) } ?: if (s.playlist.isEmpty()) "Belum ada MP3" else "Siap diputar"
        trackSub.text = if (s.playlist.isEmpty()) "Klik Tambah MP3 untuk mengisi daftar putar" else "Lagu ${max(1, s.currentIndex + 1)} dari ${s.playlist.size}"
        progress.pos = s.posMs; progress.dur = s.durMs; progress.repaint()
        shuffleBtn.kind = if (s.shuffle) Kind.SOFT else Kind.GHOST
        repeatBtn.kind = if (s.repeat) Kind.SOFT else Kind.GHOST
        if (s.playlist != last.playlist) {
            listModel.clear(); s.playlist.forEach { listModel.addElement(cleanName(it.name)) }
        }
        listTitle.text = "Daftar Putar (${s.playlist.size})"
        if (s.currentIndex != last.currentIndex || s.playing != last.playing) { list.repaint(); playBtn.repaint() }
        if (s.askRecName && !renameOpen) {
            renameOpen = true
            SwingUtilities.invokeLater {
                val name = JOptionPane.showInputDialog(this, "Ketik nama file rekaman (tanpa .mp3):", "Simpan rekaman",
                    JOptionPane.PLAIN_MESSAGE, null, null, File(s.lastRecPath).nameWithoutExtension) as String?
                if (name != null) Studio.renameLastRec(name) else Studio.keepRecName()
                renameOpen = false
            }
        }
        shuffleBtn.repaint(); repeatBtn.repaint(); airBtn.repaint()
        last = s
    }
}

/** Jendela Pengaturan. */
class SettingsDialog(owner: JFrame) : JDialog(owner, "Pengaturan", true) {
    init {
        val s = Studio.ui.value
        val cfg = s.config
        val form = JPanel(GridBagLayout()).apply { background = T.bg; border = EmptyBorder(16, 20, 16, 20) }
        val gc = GridBagConstraints().apply { gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 0, 4, 0); gridwidth = 2 }
        fun add(c: JComponent) { form.add(c, gc); gc.gridy++ }
        fun head(t: String) { add(JLabel(t).apply { font = uiFont(12, true); foreground = T.accent; border = EmptyBorder(10, 0, 2, 0) }) }
        fun label(t: String) = JLabel(t).apply { font = uiFont(13); foreground = T.text }
        fun styleField(f: JTextField) = f.apply {
            font = uiFont(13); background = T.surface; foreground = T.text; caretColor = T.accent
            border = BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(T.line), EmptyBorder(5, 8, 5, 8))
        }
        fun pair(t: String, c: JComponent) {
            val p = JPanel(BorderLayout(12, 0)).apply { isOpaque = false }
            p.add(label(t).apply { preferredSize = Dimension(190, 26) }, BorderLayout.WEST); p.add(c, BorderLayout.CENTER); add(p)
        }
        fun check(t: String, v: Boolean, set: (Boolean) -> Unit) = JCheckBox(t, v).apply {
            font = uiFont(13); foreground = T.text; isOpaque = false; addActionListener { set(isSelected) }
        }
        fun combo(items: List<String>, sel: String, labelOf: (String) -> String = { it }) = JComboBox(items.toTypedArray()).apply {
            font = uiFont(13); selectedItem = sel
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(l: JList<*>?, v: Any?, i: Int, se: Boolean, f: Boolean): Component =
                    super.getListCellRendererComponent(l, labelOf(v as? String ?: ""), i, se, f)
            }
        }

        head("SERVER")
        val pass = styleField(JPasswordField(cfg.pass))
        pair("Pass", pass)
        val station = styleField(JTextField(cfg.station))
        pair("Nama radio", station)
        val bitrate = combo(listOf("24", "32", "48"), cfg.bitrate.coerceIn(24, 48).toString())
        pair("Bitrate siaran (kbps, mono)", bitrate)

        head("PERANGKAT SUARA")
        Studio.refreshDevices()
        val micBox = combo(listOf("") + Studio.inputs.value, Studio.micDevice.value) { it.ifEmpty { "Bawaan Windows" } }
        micBox.addActionListener { Studio.setMicDevice(micBox.selectedItem as String) }
        pair("Masukan (mic / mixer)", micBox)
        val outBox = combo(listOf("") + Studio.outputs.value, Studio.outDevice.value) { it.ifEmpty { "Bawaan Windows" } }
        outBox.addActionListener { Studio.setOutDevice(outBox.selectedItem as String) }
        pair("Keluaran (speaker)", outBox)

        head("SUARA")
        add(check("Mode 1 penggeser (kiri MP3, kanan Mic)", s.oneFader, Studio::setOneFader))
        add(check("Talkover: musik mengecil saat bicara (mode 2 volume)", s.talkover, Studio::setTalkover))
        add(check("Pantau musik di speaker komputer", s.monitor, Studio::setMonitor))
        val boost = ThemedSlider((cfg.micBoost * 10).toInt()).apply { minimum = 5; maximum = 30; value = (cfg.micBoost * 10).toInt() }
        pair("Penguat mic (0.5×–3×)", boost)

        head("DAFTAR PUTAR & JUDUL")
        add(check("Judul otomatis dari nama file", s.autoTitle, Studio::setAutoTitle))
        add(check("Acak", s.shuffle, Studio::setShuffle))
        add(check("Ulang dari awal setelah lagu terakhir", s.repeat, Studio::setRepeat))

        head("REKAMAN (MP3 128 kbps stereo)")
        val dirLabel = JLabel(Studio.recDir.value).apply { font = uiFont(12); foreground = T.sub }
        add(dirLabel)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(FlatButton("Pilih folder…", Kind.SOFT).apply { addActionListener { Studio.chooseRecDir(); dirLabel.text = Studio.recDir.value } })
            add(Box.createHorizontalStrut(8))
            add(FlatButton("Buka folder", Kind.GHOST).apply { addActionListener { Studio.openRecFolder() } })
        })
        add(check("Nama otomatis (contoh: ${recBaseName()}.mp3)", Studio.autoRecName.value, Studio::setAutoRecName))

        head("TAMPILAN")
        add(check("Mode terang", !s.dark) { Studio.setDark(!it) })
        val meterBox = combo(listOf("gelombang", "spektrum"), s.meter) { if (it == "spektrum") "Spektrum" else "Gelombang" }
        meterBox.addActionListener { Studio.setMeter(meterBox.selectedItem as String) }
        pair("Tampilan meter", meterBox)

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { background = T.bg; border = EmptyBorder(10, 20, 14, 20) }
        buttons.add(FlatButton("Batal", Kind.GHOST).apply { addActionListener { dispose() } })
        buttons.add(FlatButton("Simpan", Kind.PRIMARY).apply {
            addActionListener {
                Studio.saveConfig(cfg.copy(pass = String((pass as JPasswordField).password), station = station.text.ifBlank { cfg.station },
                    bitrate = (bitrate.selectedItem as String).toInt(), micBoost = boost.value / 10f))
                dispose()
            }
        })
        contentPane = JPanel(BorderLayout()).apply {
            background = T.bg
            add(JScrollPane(form).apply { border = BorderFactory.createEmptyBorder(); verticalScrollBar.unitIncrement = 16 }, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
        size = Dimension(620, 640)
        setLocationRelativeTo(owner)
    }
}

fun main() {
    System.setProperty("sun.java2d.d3d", "false")
    System.setProperty("awt.useSystemAAFontSettings", "on")
    try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
    Studio.init()
    SwingUtilities.invokeLater { MainWindow().isVisible = true }
}
