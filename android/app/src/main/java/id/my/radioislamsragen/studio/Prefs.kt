package id.my.radioislamsragen.studio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun putStr(k: String, v: String) = sp.edit().putString(k, v).apply()
    fun float(k: String, d: Float) = sp.getFloat(k, d)
    fun putFloat(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
}
