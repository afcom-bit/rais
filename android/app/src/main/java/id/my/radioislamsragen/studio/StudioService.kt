package id.my.radioislamsragen.studio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Menjaga siaran tetap jalan saat layar mati / aplikasi di latar belakang. */
class StudioService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val air = intent?.getBooleanExtra("air", false) ?: false
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Siaran", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (air) "ON AIR · Radio Islam Sragen" else "Studio RAIS")
            .setContentText(if (air) "Siaran langsung sedang berjalan" else "Memutar daftar putar")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (air && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, 7, n, type)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (air) lock() else unlock()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun lock() {
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rais:siaran").apply { acquire(12 * 60 * 60 * 1000L) }
        if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "rais:siaran").apply { acquire() }
    }

    private fun unlock() {
        wake?.let { if (it.isHeld) it.release() }; wake = null
        wifi?.let { if (it.isHeld) it.release() }; wifi = null
    }

    override fun onDestroy() { unlock(); super.onDestroy() }

    companion object {
        private const val CH = "siaran"
        fun sync(ctx: Context, need: Boolean, air: Boolean) {
            val i = Intent(ctx, StudioService::class.java).putExtra("air", air)
            try {
                if (need) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
            } catch (_: Exception) {}
        }
    }
}
