package id.my.radioislamsragen.studio

enum class AirStatus { STANDBY, CONNECTING, ON_AIR, RECONNECTING }

data class Track(val uri: String, val name: String)

data class StationConfig(
    val host: String = "streamy1.radioislam.my.id",
    val port: Int = 9215,
    val user: String = "sragen",
    val pass: String = "",
    val station: String = "Radio Islam Sragen",
    val genre: String = "Islamic",
    val bitrate: Int = 48,
    val crossfadeSec: Int = 3,
    val micBoost: Float = 1.5f,
    val codec: String = "aac",
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
    val meter: String = "analog",
    val recording: Boolean = false,
    val recSince: Long = 0L,
    val recFile: String = "",
    val lastRecPath: String = "",
    val askRecName: Boolean = false,
    val title: String = "",
    val config: StationConfig = StationConfig(),
)


/** Nama rekaman otomatis, mis. RAIS-261128-06.03PM (tahun, bulan, tanggal, jam 12). */
fun recBaseName(d: java.util.Date = java.util.Date()): String =
    "RAIS-" + java.text.SimpleDateFormat("yyMMdd-hh.mma", java.util.Locale.US).format(d)
