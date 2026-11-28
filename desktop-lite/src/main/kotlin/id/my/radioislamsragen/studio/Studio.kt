package id.my.radioislamsragen.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.prefs.Preferences
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private const val FRAME = 1024

/** Mesin studio versi Windows: mic + MP3, siaran MP3 langsung ke Shoutcast, rekam MP3. */
object Studio {
    private val prefs: Preferences = Preferences.userRoot().node("raisstream-ringan")
    val ui = MutableStateFlow(UiState())
    val meters = MutableStateFlow(MeterSnap())
    private val meter = Meters()
    val inputs = MutableStateFlow<List<String>>(emptyList())
    val outputs = MutableStateFlow<List<String>>(emptyList())
    val micDevice = MutableStateFlow("")
    val outDevice = MutableStateFlow("")
    val recDir = MutableStateFlow("")
    val autoRecName = MutableStateFlow(true)

    private val cmds = ConcurrentLinkedQueue<() -> Unit>()
    private lateinit var player: Player
    private var mic: TargetDataLine? = null
    private var speaker: SourceDataLine? = null
    private var encoder: Mp3Encoder? = null
    @Volatile private var sender: Sender? = null
    @Volatile private var airActive = false
    @Volatile private var recActive = false
    private var recEnc: Mp3Encoder? = null
    private var recOut: OutputStream? = null
    private var recPath = ""
    @Volatile private var running = true

    private fun pb(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun pf(k: String, d: Float) = prefs.getFloat(k, d)
    private fun ps(k: String, d: String) = prefs.get(k, d)

    fun init() {
        val d = StationConfig()
        val cfg = StationConfig(
            host = d.host, port = d.port, user = d.user, pass = ps("pass", ""),
            station = ps("station", d.station), genre = d.genre, bitrate = prefs.getInt("bitrate", 48).coerceIn(24, 48),
            crossfadeSec = 0, micBoost = pf("micBoost", 1.0f), codec = "mp3",
        )
        val pl = ps("playlist", "").split("\n").filter { it.contains('|') }.map { val k = it.indexOf('|'); Track(it.substring(0, k), it.substring(k + 1)) }
        ui.value = UiState(
            config = cfg, playlist = pl,
            oneFader = pb("oneFader", true), fader = pf("fader", 0.3f), musicVol = pf("musicVol", 0.35f), micVol = pf("micVol", 0.9f),
            talkover = pb("talkover", true), shuffle = pb("shuffle", false), repeat = pb("repeat", true), monitor = pb("monitor", true),
            autoTitle = pb("autoTitle", true), speakerVol = pf("speakerVol", 0.6f), dark = pb("dark", true), meter = ps("meter", "gelombang").let { if (it == "spektrum") it else "gelombang" },
        )
        micDevice.value = ps("micDevice", "")
        outDevice.value = ps("outDevice", "")
        recDir.value = ps("recDir", defaultRecDir().absolutePath)
        autoRecName.value = pb("autoRecName", true)
        refreshDevices()
        player = Player()
        thread(name = "rais-engine", isDaemon = true, priority = Thread.MAX_PRIORITY) { engine() }
    }

    fun refreshDevices() {
        inputs.value = try { Devices.inputs() } catch (_: Exception) { emptyList() }
        outputs.value = try { Devices.outputs() } catch (_: Exception) { emptyList() }
    }

    /** Folder Dokumen asli Windows (ikut OneDrive bila dialihkan) + RaisStream. */
    private fun defaultRecDir(): File {
        val docs = try { javax.swing.filechooser.FileSystemView.getFileSystemView().defaultDirectory } catch (_: Exception) { null }
            ?: File(System.getProperty("user.home"), "Documents")
        return File(docs, "RaisStream")
    }

    fun setAutoRecName(v: Boolean) { autoRecName.value = v; prefs.putBoolean("autoRecName", v) }

    fun chooseRecDir() {
        val fc = javax.swing.JFileChooser(File(recDir.value).let { if (it.exists()) it else it.parentFile })
        fc.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
        fc.dialogTitle = "Pilih folder simpan rekaman"
        if (fc.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
            val d = fc.selectedFile.absolutePath
            recDir.value = d; prefs.put("recDir", d)
            msg("Folder rekaman: $d")
        }
    }

    fun openRecFolder() {
        val dir = File(recDir.value).apply { mkdirs() }
        try { java.awt.Desktop.getDesktop().open(dir) } catch (e: Exception) { msg("Folder rekaman: ${dir.absolutePath}") }
    }

    private fun uniqueFile(dir: File, base: String): File {
        var f = File(dir, "$base.mp3"); var n = 2
        while (f.exists()) { f = File(dir, "$base-$n.mp3"); n++ }
        return f
    }

    /** Mengganti nama rekaman terakhir (bila nama otomatis dimatikan). */
    fun renameLastRec(name: String) {
        val old = File(ui.value.lastRecPath)
        val clean = name.trim().removeSuffix(".mp3").replace(Regex("[\\\\/:*?\"<>|]"), "-")
        if (clean.isEmpty() || !old.exists()) { update { it.copy(askRecName = false) }; return }
        val target = uniqueFile(old.parentFile, clean)
        if (old.renameTo(target)) update { it.copy(askRecName = false, lastRecPath = target.absolutePath, message = "Tersimpan: ${target.name}", messageError = false) }
        else update { it.copy(askRecName = false, message = "Nama gagal diganti, tetap: ${old.name}", messageError = true) }
    }

    fun keepRecName() = update { it.copy(askRecName = false) }

    fun shutdown() {
        post { stopRec(null); stopAir("Siaran diakhiri.", false) }
        Thread.sleep(300)
        running = false
    }

    private fun update(f: (UiState) -> UiState) = ui.update(f)
    private fun post(f: () -> Unit) { cmds.add(f) }
    fun msg(text: String, error: Boolean = false) = update { it.copy(message = text, messageError = error) }

    fun setOneFader(v: Boolean) { update { it.copy(oneFader = v) }; prefs.putBoolean("oneFader", v) }
    fun setFader(v: Float) { update { it.copy(fader = v) }; prefs.putFloat("fader", v) }
    fun setMusicVol(v: Float) { update { it.copy(musicVol = v) }; prefs.putFloat("musicVol", v) }
    fun setMicVol(v: Float) { update { it.copy(micVol = v) }; prefs.putFloat("micVol", v) }
    fun setTalkover(v: Boolean) { update { it.copy(talkover = v) }; prefs.putBoolean("talkover", v) }
    fun setShuffle(v: Boolean) { update { it.copy(shuffle = v) }; prefs.putBoolean("shuffle", v) }
    fun setRepeat(v: Boolean) { update { it.copy(repeat = v) }; prefs.putBoolean("repeat", v) }
    fun setMonitor(v: Boolean) { update { it.copy(monitor = v) }; prefs.putBoolean("monitor", v) }
    fun setAutoTitle(v: Boolean) { update { it.copy(autoTitle = v) }; prefs.putBoolean("autoTitle", v) }
    fun setSpeakerVol(v: Float) { update { it.copy(speakerVol = v) }; prefs.putFloat("speakerVol", v) }
    fun setDark(v: Boolean) { update { it.copy(dark = v) }; prefs.putBoolean("dark", v) }
    fun setMeter(v: String) { update { it.copy(meter = v) }; prefs.put("meter", v) }
    fun cycleMeter() = setMeter(if (ui.value.meter == "spektrum") "gelombang" else "spektrum")
    fun toggleMic() = update { it.copy(micOn = !it.micOn) }
    fun setListeners(n: Int?) = update { it.copy(listeners = n) }
    fun setMicDevice(n: String) { micDevice.value = n; prefs.put("micDevice", n); post { if (mic != null) { closeMic(); ensureMic(true) } } }
    fun setOutDevice(n: String) { outDevice.value = n; prefs.put("outDevice", n); post { closeSpeaker() } }

    fun saveConfig(c: StationConfig) {
        prefs.put("pass", c.pass)
        prefs.put("station", c.station); prefs.putInt("bitrate", c.bitrate); prefs.putInt("crossfade", c.crossfadeSec); prefs.putFloat("micBoost", c.micBoost)
        update { it.copy(config = c.copy(bitrate = c.bitrate.coerceIn(24, 48), crossfadeSec = 0)) }
        msg(if (airActive) "Pengaturan disimpan. Berlaku saat siaran berikutnya." else "Pengaturan disimpan.")
    }

    private fun savePlaylist() { prefs.put("playlist", ui.value.playlist.joinToString("\n") { it.uri + "|" + it.name }) }

    fun addTracks(list: List<Track>) = post {
        update { it.copy(playlist = it.playlist + list) }
        savePlaylist()
        msg("${list.size} file ditambahkan.")
    }
    fun removeTrack(i: Int) = post { player.remove(i) }
    fun playIndex(i: Int) = post { player.play(i) }
    fun togglePlay() = post { player.toggle() }
    fun next() = post { player.skip(1) }
    fun prev() = post { player.skip(-1) }
    fun goOnAir() = post { startAir() }
    fun endAir() = post { stopAir("Siaran diakhiri.", false) }
    fun toggleRec() = post { if (recActive) stopRec(null) else startRec() }

    fun sendTitle(t: String) {
        val title = t.trim()
        update { it.copy(title = title) }
        if (title.isEmpty()) return
        val link = sender?.link
        if (link == null) { msg("Judul disimpan, dikirim saat siaran tersambung."); return }
        val cfg = ui.value.config
        thread {
            val ok = Caster.updateTitle(cfg, link, title)
            msg(if (ok) "Judul terkirim: $title" else "Judul belum diterima server.", !ok)
        }
    }

    private fun closeMic() { mic?.let { try { it.stop(); it.close() } catch (_: Exception) {} }; mic = null }
    private fun closeSpeaker() { speaker?.let { try { it.stop(); it.close() } catch (_: Exception) {} }; speaker = null }

    private fun ensureMic(need: Boolean): Boolean {
        if (need && mic == null) mic = try { Devices.openInput(micDevice.value) } catch (e: Exception) { null }
        if (!need && mic != null) closeMic()
        return !need || mic != null
    }

    private fun startAir() {
        if (airActive) return
        val cfg = ui.value.config
        if (cfg.pass.isBlank()) { msg("Isi kata sandi server di Pengaturan dulu.", true); return }
        if (!ensureMic(true)) { msg("Mikrofon / line-in tidak bisa dibuka. Periksa perangkat di Pengaturan.", true); return }
        encoder = Mp3Encoder(cfg.bitrate.coerceIn(24, 48), 1)
        airActive = true
        update { it.copy(status = AirStatus.CONNECTING, serverText = "Menyambung...", sentBytes = 0, message = "Menyambung ke ${cfg.host}...", messageError = false) }
        sender = Sender(cfg, object : Sender.Callbacks {
            override fun connected(up: Upstream) {
                update {
                    it.copy(status = AirStatus.ON_AIR, onAirSince = if (it.onAirSince == 0L) System.currentTimeMillis() else it.onAirSince,
                        serverText = "${up.mode} port ${up.port}", message = "ON AIR. Siaran langsung berjalan.", messageError = false)
                }
                val t = ui.value.title
                if (t.isNotBlank()) sendTitle(t)
            }
            override fun lost() = update { it.copy(status = AirStatus.RECONNECTING, serverText = "Terputus", message = "Sinyal putus, menyambung ulang...", messageError = true) }
            override fun failed(msg: String) = post { stopAir(msg, true) }
        }).also { it.start() }
    }

    private fun stopAir(text: String, error: Boolean) {
        if (!airActive) return
        airActive = false
        sender?.shutdown(); sender = null
        encoder?.finish { }; encoder = null
        ensureMic(recActive)
        update { it.copy(status = AirStatus.STANDBY, onAirSince = 0, serverText = "Belum tersambung", message = text, messageError = error) }
    }

    private fun startRec() {
        if (recActive) return
        if (!ensureMic(true)) { msg("Mikrofon / line-in tidak bisa dibuka.", true); return }
        val dir = File(recDir.value).apply { mkdirs() }
        val file = uniqueFile(dir, recBaseName())
        val name = file.name
        recPath = file.absolutePath
        try {
            recOut = BufferedOutputStream(FileOutputStream(file), 65536)
            recEnc = Mp3Encoder(128, 2)
        } catch (e: Exception) {
            ensureMic(airActive); msg("Rekaman gagal dibuat: ${e.message}", true); return
        }
        recActive = true
        update { it.copy(recording = true, recSince = System.currentTimeMillis(), recFile = name, message = "Merekam: $name", messageError = false) }
    }

    private fun stopRec(error: String?) {
        if (!recActive) return
        recActive = false
        val o = recOut
        recEnc?.finish { try { o?.write(it) } catch (_: Exception) {} }
        try { o?.flush(); o?.close() } catch (_: Exception) {}
        recEnc = null; recOut = null
        ensureMic(airActive)
        val name = ui.value.recFile
        val ask = error == null && !autoRecName.value
        update { it.copy(recording = false, recSince = 0, lastRecPath = recPath, askRecName = ask,
            message = error ?: "Tersimpan: $name", messageError = error != null) }
    }

    private fun engine() {
        val music = FloatArray(FRAME * 2)
        val micB = ByteArray(FRAME * 2)
        val micS = ShortArray(FRAME)
        val monB = ByteArray(FRAME * 4)
        val encS = ShortArray(FRAME * 2)
        val mixF = FloatArray(FRAME * 2)
        var duck = 1f
        var lastVoice = 0L
        var nextTick = System.nanoTime()
        var lastUi = 0L
        var lastLv = 0L
        while (running) {
            try {
                while (true) { val c = cmds.poll() ?: break; c() }
                val s = ui.value
                if (!airActive && !recActive && !player.isPlaying) {
                    closeSpeaker()
                    if (meters.value.peakL != 0f || meters.value.vuL != 0f) { meter.reset(); meters.value = MeterSnap() }
                    Thread.sleep(40)
                    nextTick = System.nanoTime()
                    continue
                }

                val m0 = mic
                var micOk = false
                if (m0 != null) {
                    var got = 0
                    while (got < micB.size) { val r = m0.read(micB, got, micB.size - got); if (r <= 0) break; got += r }
                    if (got == micB.size) {
                        micOk = true
                        for (i in 0 until FRAME) micS[i] = ((micB[i * 2].toInt() and 0xFF) or (micB[i * 2 + 1].toInt() shl 8)).toShort()
                    }
                }

                val hasMusic = player.read(music, FRAME, s)

                var mg: Float
                var cg: Float
                if (s.oneFader) { mg = 1f - s.fader; cg = s.fader } else { mg = s.musicVol; cg = s.micVol }
                cg *= if (s.micOn && micOk) s.config.micBoost else 0f

                var sum = 0.0
                if (micOk) for (i in 0 until FRAME) { val v = micS[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / FRAME).toFloat()
                val now = System.currentTimeMillis()
                val talk = !s.oneFader && s.talkover && cg > 0f
                if (talk && rms > 0.03f) lastVoice = now
                val target = if (talk && now - lastVoice < 700) 0.25f else 1f
                duck += (target - duck) * (if (target < duck) 0.5f else 0.06f)
                mg *= duck

                val sv = s.speakerVol
                for (i in 0 until FRAME) {
                    val m = if (micOk) micS[i] / 32768f * cg else 0f
                    val ml = music[i * 2] * mg
                    val mr = music[i * 2 + 1] * mg
                    val l = (ml + m).coerceIn(-1f, 1f)
                    val r = (mr + m).coerceIn(-1f, 1f)
                    mixF[i * 2] = l; mixF[i * 2 + 1] = r
                    encS[i * 2] = (l * 32767).toInt().toShort()
                    encS[i * 2 + 1] = (r * 32767).toInt().toShort()
                    val a = ((ml * sv).coerceIn(-1f, 1f) * 32767).toInt()
                    val b = ((mr * sv).coerceIn(-1f, 1f) * 32767).toInt()
                    monB[i * 4] = (a and 0xFF).toByte(); monB[i * 4 + 1] = (a shr 8).toByte()
                    monB[i * 4 + 2] = (b and 0xFF).toByte(); monB[i * 4 + 3] = (b shr 8).toByte()
                }
                meter.process(mixF, FRAME, s.meter == "spektrum")
                if (now - lastLv > 66) { lastLv = now; meters.value = meter.snapshot() }

                // Rekam (tidak bergantung pada siaran)
                val re = recEnc
                if (re != null) {
                    val o = recOut
                    try { re.encode(encS, FRAME * 2) { o?.write(it) } } catch (e: Exception) { stopRec("Rekaman berhenti: ${e.message}") }
                }

                // Pantau musik di speaker komputer
                var spk: SourceDataLine? = null
                if (s.monitor && hasMusic) {
                    spk = speaker ?: try { Devices.openOutput(outDevice.value) } catch (_: Exception) { null }
                    speaker = spk
                    if (spk != null) {
                        if (m0 == null) spk.write(monB, 0, monB.size)
                        else if (spk.available() >= monB.size) spk.write(monB, 0, monB.size)
                    }
                } else if (speaker != null && (!s.monitor || !player.isPlaying)) closeSpeaker()

                val enc = encoder
                val snd = sender
                if (enc != null && snd != null) {
                    val count = if (enc.channels == 1) {
                        for (i in 0 until FRAME) encS[i] = ((encS[i * 2].toInt() + encS[i * 2 + 1].toInt()) / 2).toShort()
                        FRAME
                    } else FRAME * 2
                    enc.encode(encS, count) { snd.offer(it) }
                }

                if (m0 == null && spk == null) {
                    nextTick += FRAME * 1_000_000_000L / RATE
                    val wait = nextTick - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    else if (wait < -200_000_000) nextTick = System.nanoTime()
                } else nextTick = System.nanoTime()

                if (now - lastUi > 250) {
                    lastUi = now
                    val sent = sender?.bytes ?: s.sentBytes
                    update { it.copy(posMs = player.posMs, durMs = player.durMs, sentBytes = sent, playing = player.isPlaying) }
                }
            } catch (t: Throwable) {
                msg("Kesalahan audio: ${t.message}", true)
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
    }

    // ---------- Pemutar daftar putar dengan crossfade ----------
    private class Player {
        private var cur: MpDeck? = null
        private var curIdx = -1
        private var inc: MpDeck? = null
        private var incIdx = -1
        private var fadePos = 0
        private var fadeLen = 0
        @Volatile var isPlaying = false
        private val a = FloatArray(FRAME * 2)
        private val b = FloatArray(FRAME * 2)
        val posMs get() = (cur?.positionUs ?: 0L) / 1000
        val durMs get() = (cur?.durationUs ?: 0L) / 1000

        private fun list() = ui.value.playlist

        private fun open(i: Int): MpDeck? {
            val t = list().getOrNull(i) ?: return null
            return try { MpDeck(t.uri) } catch (e: Exception) {
                msg("Tidak bisa membuka: ${t.name}", true); null
            }
        }

        private fun nextIndex(from: Int, dir: Int): Int {
            val n = list().size
            if (n == 0) return -1
            val s = ui.value
            if (s.shuffle && dir > 0 && n > 1) {
                var r: Int
                do { r = Random.nextInt(n) } while (r == from)
                return r
            }
            val i = from + dir
            return when {
                i >= n -> if (s.repeat) 0 else -1
                i < 0 -> if (s.repeat) n - 1 else 0
                else -> i
            }
        }

        private fun onTrack(i: Int) {
            curIdx = i
            update { it.copy(currentIndex = i, playing = isPlaying) }
            val t = list().getOrNull(i) ?: return
            if (ui.value.autoTitle) sendTitle(t.name.substringBeforeLast('.'))
        }

        fun play(i: Int) {
            inc?.release(); inc = null
            var idx = i
            var tries = 0
            while (tries < list().size) {
                val d = open(idx)
                if (d != null) {
                    cur?.release(); cur = d; isPlaying = true; onTrack(idx); return
                }
                idx = nextIndex(idx, 1); tries++
                if (idx < 0) break
            }
            stopAll()
        }

        fun toggle() {
            if (cur == null) { if (list().isNotEmpty()) play(max(0, ui.value.currentIndex).coerceAtMost(list().size - 1)); return }
            isPlaying = !isPlaying
            update { it.copy(playing = isPlaying) }
        }

        fun skip(dir: Int) {
            if (list().isEmpty()) return
            val n = nextIndex(if (curIdx < 0) 0 else curIdx, dir)
            if (n >= 0) play(n)
        }

        fun remove(i: Int) {
            val pl = list()
            if (i !in pl.indices) return
            if (i == curIdx) stopAll()
            if (i == incIdx) { inc?.release(); inc = null; incIdx = -1 }
            if (i < curIdx) curIdx--
            if (i < incIdx) incIdx--
            update { it.copy(playlist = pl.filterIndexed { k, _ -> k != i }, currentIndex = curIdx) }
            savePlaylist()
        }

        private fun stopAll() {
            cur?.release(); cur = null
            inc?.release(); inc = null
            isPlaying = false
            update { it.copy(playing = false, posMs = 0, durMs = 0) }
        }

        fun read(out: FloatArray, frames: Int, s: UiState): Boolean {
            val c = cur
            if (!isPlaying || c == null) { out.fill(0f); return false }
            val n = c.read(a, frames)
            if (n < frames) a.fill(0f, n * 2, frames * 2)

            val xf = s.config.crossfadeSec
            if (inc == null && xf > 0 && c.durationUs > 0 && c.durationUs - c.positionUs <= xf * 1_000_000L) {
                val ni = nextIndex(curIdx, 1)
                if (ni >= 0) {
                    inc = open(ni)
                    if (inc != null) { incIdx = ni; fadePos = 0; fadeLen = xf * RATE }
                }
            }

            val i2 = inc
            if (i2 != null) {
                val m = i2.read(b, frames)
                if (m < frames) b.fill(0f, m * 2, frames * 2)
                for (k in 0 until frames) {
                    val t = min(1f, (fadePos + k).toFloat() / fadeLen)
                    out[k * 2] = a[k * 2] * (1 - t) + b[k * 2] * t
                    out[k * 2 + 1] = a[k * 2 + 1] * (1 - t) + b[k * 2 + 1] * t
                }
                fadePos += frames
                if (fadePos >= fadeLen || n < frames) {
                    c.release(); cur = i2; inc = null
                    onTrack(incIdx); incIdx = -1
                }
            } else {
                System.arraycopy(a, 0, out, 0, frames * 2)
                if (n < frames) {
                    val ni = nextIndex(curIdx, 1)
                    if (ni >= 0) play(ni) else stopAll()
                }
            }
            return true
        }
    }
}
