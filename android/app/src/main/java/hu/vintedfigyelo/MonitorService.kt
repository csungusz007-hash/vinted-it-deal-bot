package hu.vintedfigyelo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Előtérszolgáltatás: a figyelés zárolt képernyővel és háttérben is fut, amíg le nem állítod. */
class MonitorService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(applicationContext)
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.ongoing(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
        if (wake == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vintedfigyelo:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val ctx = applicationContext
        Engine.notifier = { rec -> Notifier.deal(ctx, rec) }
        Engine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        Engine.stop()
        try { wake?.release() } catch (_: Exception) { }
        wake = null
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) {
            val i = Intent(c, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, MonitorService::class.java))
        }
    }
}
