package id.my.radioislamsragen.studio

import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ConnErr(msg: String, val kind: String) : Exception(msg)

class Upstream(val socket: Socket, val mode: String, val port: Int, val mount: String = "/stream")

/** Sambungan sumber ke Shoutcast/Icecast, urutannya sama seperti plugin DSP Winamp. */
object Caster {
    private const val TIMEOUT = 6500

    private fun b64(s: String): String = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
    private fun scType(c: StationConfig) = if (c.codec == "mp3") "audio/mpeg" else "audio/aacp"
    private fun iceType(c: StationConfig) = if (c.codec == "mp3") "audio/mpeg" else "audio/aac"

    private fun open(host: String, port: Int): Socket {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), TIMEOUT)
        } catch (e: SocketTimeoutException) {
            s.close(); throw ConnErr("Waktu sambung ke port $port habis.", "network")
        } catch (e: IOException) {
            s.close(); throw ConnErr("Tidak dapat menjangkau $host:$port.", "network")
        }
        s.soTimeout = TIMEOUT
        s.tcpNoDelay = true
        return s
    }

    private fun readLine(s: Socket, port: Int, max: Int = 2048): String {
        val ins = s.getInputStream()
        val sb = StringBuilder()
        try {
            while (true) {
                val c = ins.read()
                if (c < 0) throw ConnErr("Sambungan port $port ditutup oleh server.", "protocol")
                sb.append(c.toChar())
                if (c == '\n'.code && sb.trim().isNotEmpty()) break
                if (sb.length > max) throw ConnErr("Respon server siaran tidak dikenal.", "protocol")
            }
        } catch (e: SocketTimeoutException) {
            throw ConnErr("Server tidak menjawab di port $port.", "network")
        }
        return sb.toString().trim()
    }

    private fun tryShoutcast(c: StationConfig, port: Int, auth: String): Upstream {
        val s = open(c.host, port)
        try {
            s.getOutputStream().apply { write("$auth\r\n".toByteArray()); flush() }
            val line = readLine(s, port)
            val low = line.lowercase()
            when {
                low.startsWith("http/") -> throw ConnErr("Port $port menerima HTTP, bukan sumber Shoutcast.", "http")
                low.contains("invalid") -> throw ConnErr("User atau kata sandi siaran salah.", "auth")
                low.contains("already in use") -> throw ConnErr("Slot siaran sedang dipakai.", "busy")
                line.startsWith("OK") -> {
                    val h = listOf(
                        "content-type:${scType(c)}",
                        "icy-name:${c.station}",
                        "icy-genre:${c.genre}",
                        "icy-url:https://www.radioislamsragen.my.id",
                        "icy-pub:1",
                        "icy-br:${c.bitrate}",
                        "", ""
                    ).joinToString("\r\n")
                    s.getOutputStream().apply { write(h.toByteArray()); flush() }
                    s.soTimeout = 0
                    return Upstream(s, "Shoutcast", port)
                }
                else -> throw ConnErr("Server menolak sambungan: $line", "protocol")
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    private fun tryIcecast(c: StationConfig, port: Int, user: String, mount: String): Upstream {
        val s = open(c.host, port)
        try {
            val auth = b64("$user:${c.pass}")
            val req = listOf(
                "SOURCE $mount HTTP/1.0",
                "Host: ${c.host}:$port",
                "Authorization: Basic $auth",
                "User-Agent: RaisStream/1.0",
                "Content-Type: ${iceType(c)}",
                "ice-name: ${c.station}",
                "ice-genre: ${c.genre}",
                "ice-public: 1",
                "ice-bitrate: ${c.bitrate}",
                "", ""
            ).joinToString("\r\n")
            s.getOutputStream().apply { write(req.toByteArray()); flush() }
            val line = readLine(s, port, 4096)
            when {
                Regex("^HTTP/1\\.[01]\\s+200\\b", RegexOption.IGNORE_CASE).containsMatchIn(line) -> {
                    s.soTimeout = 0
                    return Upstream(s, "Icecast", port, mount)
                }
                line.contains("401") || line.contains("403") -> throw ConnErr("User atau kata sandi Icecast salah.", "auth")
                line.contains("404") -> throw ConnErr("Mountpoint tidak ditemukan.", "notfound")
                else -> throw ConnErr("Server menolak: $line", "protocol")
            }
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

    fun connect(c: StationConfig): Upstream {
        val user = c.user.trim()
        val auths = linkedSetOf<String>().apply {
            if (user.isNotEmpty()) add("$user:${c.pass}")
            add(c.pass); add("1:${c.pass}")
        }
        var busy = false; var authFail = false; var netErr: ConnErr? = null

        fun shoutcastOn(port: Int): Upstream? {
            for (a in auths) {
                try { return tryShoutcast(c, port, a) } catch (e: ConnErr) {
                    when (e.kind) {
                        "busy" -> { busy = true; return null }
                        "auth" -> { authFail = true }
                        "network" -> { netErr = e; return null }
                        else -> return null
                    }
                }
            }
            return null
        }

        if (c.port + 1 <= 65535) shoutcastOn(c.port + 1)?.let { return it }

        var spokeHttp = false
        outer@ for (u in linkedSetOf(user.ifEmpty { "source" }, "source")) {
            for (m in linkedSetOf("/stream", "/")) {
                try { return tryIcecast(c, c.port, u, m) } catch (e: ConnErr) {
                    when (e.kind) {
                        "auth" -> { spokeHttp = true; authFail = true; continue@outer }
                        "notfound" -> { spokeHttp = true }
                        "network" -> { netErr = e; break@outer }
                        else -> break@outer
                    }
                }
            }
        }
        if (!spokeHttp) shoutcastOn(c.port)?.let { return it }

        throw when {
            busy -> ConnErr("Slot siaran sedang dipakai (mungkin AutoDJ atau penyiar lain masih tersambung).", "busy")
            authFail -> ConnErr("User atau kata sandi salah. Periksa di Pengaturan.", "auth")
            netErr != null -> ConnErr("Tidak dapat menjangkau server di port ${c.port} atau ${c.port + 1}. Periksa internet dan alamat.", "network")
            else -> ConnErr("Gagal tersambung ke server siaran.", "protocol")
        }
    }

    /** Memperbarui judul siaran yang tampil di pendengar. */
    fun updateTitle(c: StationConfig, up: Upstream, title: String): Boolean {
        val song = URLEncoder.encode(title.take(180), "UTF-8")
        val tries = if (up.mode == "Icecast") listOf(
            "/admin/metadata?mount=${URLEncoder.encode(up.mount, "UTF-8")}&mode=updinfo&song=$song" to "${c.user.ifEmpty { "source" }}:${c.pass}"
        ) else linkedSetOf(c.pass, "${c.user}:${c.pass}").map {
            "/admin.cgi?pass=${URLEncoder.encode(it, "UTF-8")}&mode=updinfo&song=$song&sid=1" to "admin:$it"
        }
        for ((path, auth) in tries) {
            try {
                val con = URL("http://${c.host}:${c.port}$path").openConnection() as HttpURLConnection
                con.connectTimeout = 5000; con.readTimeout = 5000
                con.setRequestProperty("User-Agent", "Mozilla/5.0 RaisStream/1.0")
                con.setRequestProperty("Authorization", "Basic " + b64(auth))
                val code = con.responseCode
                con.disconnect()
                if (code in 200..399) return true
            } catch (_: Exception) {}
        }
        return false
    }

    /** Jumlah pendengar saat ini (null bila tidak terbaca). */
    fun listeners(c: StationConfig): Int? {
        try {
            val m = Regex("\"currentlisteners\"\\s*:\\s*(\\d+)").find(httpGet("http://${c.host}:${c.port}/stats?sid=1&json=1"))
            if (m != null) return m.groupValues[1].toInt()
        } catch (_: Exception) {}
        try {
            val all = Regex("\"listeners\"\\s*:\\s*(\\d+)").findAll(httpGet("http://${c.host}:${c.port}/status-json.xsl")).toList()
            if (all.isNotEmpty()) return all.sumOf { it.groupValues[1].toInt() }
        } catch (_: Exception) {}
        return null
    }

    private fun httpGet(url: String): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = 6000; con.readTimeout = 6000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 RaisStream/1.0")
        return try { con.inputStream.bufferedReader().readText() } finally { con.disconnect() }
    }
}

/** Mengirim paket audio ke server; menyambung ulang sendiri bila putus. */
class Sender(private val cfg: StationConfig, private val cb: Callbacks) : Thread("rais-sender") {
    interface Callbacks {
        fun connected(up: Upstream)
        fun lost()
        fun failed(msg: String)
    }

    private val q = LinkedBlockingQueue<ByteArray>(600)
    @Volatile private var running = true
    @Volatile private var sock: Socket? = null
    @Volatile var link: Upstream? = null
        private set
    @Volatile var bytes = 0L
        private set
    private var fullSince = 0L

    fun offer(b: ByteArray) {
        if (link == null) return
        if (q.offer(b)) { fullSince = 0; return }
        val now = System.currentTimeMillis()
        if (fullSince == 0L) fullSince = now
        else if (now - fullSince > 10_000) {
            fullSince = 0
            try { sock?.close() } catch (_: Exception) {}
        }
    }

    override fun run() {
        var first = true
        while (running) {
            try {
                val up = Caster.connect(cfg)
                if (!running) { up.socket.close(); break }
                sock = up.socket
                q.clear()
                link = up
                first = false
                cb.connected(up)
                val os = BufferedOutputStream(up.socket.getOutputStream(), 16384)
                while (running) {
                    val b = q.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    os.write(b); bytes += b.size
                    while (true) {
                        val m = q.poll() ?: break
                        os.write(m); bytes += m.size
                    }
                    os.flush()
                }
            } catch (e: Exception) {
                link = null
                try { sock?.close() } catch (_: Exception) {}
                if (!running) break
                if (first) {
                    running = false
                    cb.failed(e.message ?: "Gagal tersambung ke server siaran.")
                    break
                }
                cb.lost()
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
        }
        link = null
        try { sock?.close() } catch (_: Exception) {}
    }

    fun shutdown() {
        running = false
        link = null
        interrupt()
        try { sock?.close() } catch (_: Exception) {}
    }
}
