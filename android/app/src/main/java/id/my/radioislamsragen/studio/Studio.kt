package id.my.radioislamsragen.studio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

private const val FRAME = 1024

/** Mesin studio: mencampur mic + MP3, memantau, meng-encode, dan mengirim ke server. */
@SuppressLint("StaticFieldLeak")
object Studio {
    private lateinit var app: Context
    private lateinit var prefs: Prefs
    val ui = MutableStateFlow(UiState())
    val levels = MutableStateFlow(floatArrayOf(0f, 0f))

    private val cmds = ConcurrentLinkedQueue<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    // Hanya disentuh oleh thread mesin
    private lateinit var player: Player
    private var recorder: AudioRecord? = null
    private var encoder: AacEncoder? = null
    private var monitorTrack: AudioTrack? = null
    @Volatile private var sender: Sender? = null
    @Volatile private var airActive = false
    private var lastNeed = false
    private var lastAir = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        ui.value = UiState(
            config = prefs.loadConfig(),
            playlist = prefs.loadPlaylist(),
            oneFader = prefs.bool("oneFader", true),
            fader = prefs.float("fader", 0.3f),
            musicVol = prefs.float("musicVol", 0.35f),
            micVol = prefs.float("micVol", 0.9f),
            talkover = prefs.bool("talkover", true),
            shuffle = prefs.bool("shuffle", false),
            repeat = prefs.bool("repeat", true),
            monitor = prefs.bool("monitor", true),
            autoTitle = prefs.bool("autoTitle", true),
        )
        player = Player()
        thread(name = "rais-engine", isDaemon = true, priority = Thread.MAX_PRIORITY) { engine() }
    }

    private fun update(f: (UiState) -> UiState) = ui.update(f)
    private fun post(f: () -> Unit) { cmds.add(f) }

    fun msg(text: String, error: Boolean = false) = update { it.copy(message = text, messageError = error) }

    // ---------- Pengaturan dari layar ----------
    fun setOneFader(v: Boolean) { update { it.copy(oneFader = v) }; prefs.putBool("oneFader", v) }
    fun setFader(v: Float) { update { it.copy(fader = v) }; prefs.putFloat("fader", v) }
    fun setMusicVol(v: Float) { update { it.copy(musicVol = v) }; prefs.putFloat("musicVol", v) }
    fun setMicVol(v: Float) { update { it.copy(micVol = v) }; prefs.putFloat("micVol", v) }
    fun setTalkover(v: Boolean) { update { it.copy(talkover = v) }; prefs.putBool("talkover", v) }
    fun setShuffle(v: Boolean) { update { it.copy(shuffle = v) }; prefs.putBool("shuffle", v) }
    fun setRepeat(v: Boolean) { update { it.copy(repeat = v) }; prefs.putBool("repeat", v) }
    fun setMonitor(v: Boolean) { update { it.copy(monitor = v) }; prefs.putBool("monitor", v) }
    fun setAutoTitle(v: Boolean) { update { it.copy(autoTitle = v) }; prefs.putBool("autoTitle", v) }
    fun toggleMic() = update { it.copy(micOn = !it.micOn) }
    fun setListeners(n: Int?) = update { it.copy(listeners = n) }

    fun saveConfig(c: StationConfig) {
        prefs.saveConfig(c)
        update { it.copy(config = c) }
        msg(if (airActive) "Pengaturan disimpan. Berlaku saat siaran berikutnya." else "Pengaturan disimpan.")
    }

    // ---------- Daftar putar ----------
    fun addTracks(list: List<Track>) = post {
        update { it.copy(playlist = it.playlist + list) }
        prefs.savePlaylist(ui.value.playlist)
        msg("${list.size} file ditambahkan.")
    }
    fun removeTrack(i: Int) = post { player.remove(i) }
    fun playIndex(i: Int) = post { player.play(i) }
    fun togglePlay() = post { player.toggle() }
    fun next() = post { player.skip(1) }
    fun prev() = post { player.skip(-1) }

    // ---------- Siaran ----------
    fun goOnAir() = post { startAir() }
    fun endAir() = post { stopAir("Siaran diakhiri.", false) }

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

    private fun startAir() {
        if (airActive) return
        val cfg = ui.value.config
        if (cfg.pass.isBlank()) { msg("Isi kata sandi server di Pengaturan dulu.", true); return }
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            msg("Izin mikrofon belum diberikan.", true); return
        }
        val rec = openRecorder()
        if (rec == null) { msg("Mikrofon tidak bisa dibuka. Tutup aplikasi lain yang memakai mic.", true); return }
        val enc = try { AacEncoder(cfg.bitrate, if (cfg.bitrate < 64) 1 else 2) } catch (e: Exception) {
            rec.release(); msg("Encoder AAC tidak tersedia di HP ini.", true); return
        }
        recorder = rec; encoder = enc
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
        encoder?.release(); encoder = null
        recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        recorder = null
        update { it.copy(status = AirStatus.STANDBY, onAirSince = 0, serverText = "Belum tersambung", message = text, messageError = error) }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(min * 4, FRAME * 2 * 8))
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); null } else { r.startRecording(); r }
    } catch (e: Exception) { null }

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(max(min * 2, FRAME * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    private fun closeTrack() {
        monitorTrack?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        monitorTrack = null
    }

    private fun syncService() {
        val need = airActive || player.isPlaying
        val air = airActive
        if (need == lastNeed && air == lastAir) return
        lastNeed = need; lastAir = air
        main.post { StudioService.sync(app, need, air) }
    }

    // ---------- Putaran mesin audio ----------
    private fun engine() {
        val music = FloatArray(FRAME * 2)
        val micS = ShortArray(FRAME)
        val monS = ShortArray(FRAME * 2)
        val encS = ShortArray(FRAME * 2)
        var duck = 1f
        var lastVoice = 0L
        var nextTick = System.nanoTime()
        var lastUi = 0L
        var lastLv = 0L
        while (true) {
            try {
                while (true) { val c = cmds.poll() ?: break; c() }
                syncService()
                val s = ui.value
                if (!airActive && !player.isPlaying) {
                    closeTrack()
                    if (levels.value[0] != 0f || levels.value[1] != 0f) levels.value = floatArrayOf(0f, 0f)
                    Thread.sleep(40)
                    nextTick = System.nanoTime()
                    continue
                }

                // Mikrofon (sekaligus jam utama saat siaran)
                val rec = recorder
                var micOk = false
                if (rec != null) {
                    var got = 0
                    while (got < FRAME) { val r = rec.read(micS, got, FRAME - got); if (r <= 0) break; got += r }
                    micOk = got == FRAME
                }

                val hasMusic = player.read(music, FRAME, s)

                // Penguatan
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

                // Campur
                var pl = 0f; var pr = 0f
                for (i in 0 until FRAME) {
                    val m = if (micOk) micS[i] / 32768f * cg else 0f
                    val ml = music[i * 2] * mg
                    val mr = music[i * 2 + 1] * mg
                    val l = (ml + m).coerceIn(-1f, 1f)
                    val r = (mr + m).coerceIn(-1f, 1f)
                    pl = max(pl, abs(l)); pr = max(pr, abs(r))
                    encS[i * 2] = (l * 32767).toInt().toShort()
                    encS[i * 2 + 1] = (r * 32767).toInt().toShort()
                    monS[i * 2] = (ml.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    monS[i * 2 + 1] = (mr.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                }
                if (now - lastLv > 40) { lastLv = now; levels.value = floatArrayOf(pl, pr) }

                // Pantau musik di HP (tanpa suara mic agar tidak berdengung)
                if (s.monitor && hasMusic) {
                    val t = monitorTrack ?: openTrack().also { monitorTrack = it }
                    t.write(monS, 0, FRAME * 2, if (rec != null) AudioTrack.WRITE_NON_BLOCKING else AudioTrack.WRITE_BLOCKING)
                } else if (monitorTrack != null && (!s.monitor || !player.isPlaying)) closeTrack()

                // Encode & kirim
                val enc = encoder
                val snd = sender
                if (enc != null && snd != null) {
                    val count = if (enc.channels == 1) {
                        for (i in 0 until FRAME) encS[i] = ((encS[i * 2].toInt() + encS[i * 2 + 1].toInt()) / 2).toShort()
                        FRAME
                    } else FRAME * 2
                    enc.encode(encS, count) { snd.offer(it) }
                }

                // Jam cadangan saat tidak ada mic & tidak memantau
                if (rec == null && monitorTrack == null) {
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
                Log.e("Studio", "engine", t)
                msg("Kesalahan audio: ${t.message}", true)
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
    }

    // ---------- Pemutar daftar putar dengan crossfade ----------
    private class Player {
        private var cur: Deck? = null
        private var curIdx = -1
        private var inc: Deck? = null
        private var incIdx = -1
        private var fadePos = 0
        private var fadeLen = 0
        @Volatile var isPlaying = false
        private val a = FloatArray(FRAME * 2)
        private val b = FloatArray(FRAME * 2)
        val posMs get() = (cur?.positionUs ?: 0L) / 1000
        val durMs get() = (cur?.durationUs ?: 0L) / 1000

        private fun list() = ui.value.playlist

        private fun open(i: Int): Deck? {
            val t = list().getOrNull(i) ?: return null
            return try { Deck(app, Uri.parse(t.uri)) } catch (e: Exception) {
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
            prefs.savePlaylist(ui.value.playlist)
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
