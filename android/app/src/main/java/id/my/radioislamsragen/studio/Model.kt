package id.my.radioislamsragen.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class AirStatus { STANDBY, CONNECTING, ON_AIR, RECONNECTING }

data class Track(val uri: String, val name: String)

data class StationConfig(
    val host: String = "streamy1.radioislam.my.id",
    val port: Int = 9210,
    val user: String = "sragen",
    val pass: String = "",
    val station: String = "Radio Islam Sragen",
    val genre: String = "Islamic",
    val bitrate: Int = 48,
    val crossfadeSec: Int = 3,
    val micBoost: Float = 1.5f,
)

data class UiState(
    val status: AirStatus = AirStatus.STANDBY,
    val onAirSince: Long = 0L,
    val serverText: String = "Belum tersambung",
    val listeners: Int? = null,
    val sentBytes: Long = 0L,
    val message: String = "",
    val messageError: Boolean = false,
    val playlist: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val posMs: Long = 0L,
    val durMs: Long = 0L,
    val micOn: Boolean = true,
    val oneFader: Boolean = true,
    val fader: Float = 0.3f,
    val musicVol: Float = 0.35f,
    val micVol: Float = 0.9f,
    val talkover: Boolean = true,
    val shuffle: Boolean = false,
    val repeat: Boolean = true,
    val monitor: Boolean = true,
    val autoTitle: Boolean = true,
    val speakerVol: Float = 0.6f,
    val dark: Boolean = true,
    val title: String = "",
    val config: StationConfig = StationConfig(),
)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("studio", Context.MODE_PRIVATE)

    fun loadConfig(): StationConfig {
        val d = StationConfig()
        return StationConfig(
            host = sp.getString("host", d.host) ?: d.host,
            port = sp.getInt("port", d.port),
            user = sp.getString("user", d.user) ?: d.user,
            pass = sp.getString("pass", d.pass) ?: "",
            station = sp.getString("station", d.station) ?: d.station,
            genre = sp.getString("genre", d.genre) ?: d.genre,
            bitrate = sp.getInt("bitrate", d.bitrate),
            crossfadeSec = sp.getInt("crossfade", d.crossfadeSec),
            micBoost = sp.getFloat("micBoost", d.micBoost),
        )
    }

    fun saveConfig(c: StationConfig) {
        sp.edit()
            .putString("host", c.host).putInt("port", c.port).putString("user", c.user)
            .putString("pass", c.pass).putString("station", c.station).putString("genre", c.genre)
            .putInt("bitrate", c.bitrate).putInt("crossfade", c.crossfadeSec).putFloat("micBoost", c.micBoost)
            .apply()
    }

    fun loadPlaylist(): List<Track> = try {
        val a = JSONArray(sp.getString("playlist", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Track(o.getString("u"), o.getString("n")) }
    } catch (e: Exception) { emptyList() }

    fun savePlaylist(list: List<Track>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("u", it.uri).put("n", it.name)) }
        sp.edit().putString("playlist", a.toString()).apply()
    }

    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun float(k: String, d: Float) = sp.getFloat(k, d)
    fun putFloat(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
}
