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
    @Volatile private var ticker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(applicationContext)
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.ongoing(this)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ONGOING_ID, n)
        setRunning(this, true)
        if (wake == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vintedfigyelo:monitor").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val ctx = applicationContext
        Engine.notifier = { rec -> Notifier.deal(ctx, rec) }
        Engine.plainNotifier = { t, b, u -> Notifier.plain(ctx, t, b, u) }
        Engine.start()
        if (ticker == null) {
            // az állandó értesítés félpercenként mutatja, hol tart a figyelés
            ticker = Thread {
                try { while (true) { Thread.sleep(30_000); Notifier.updateOngoing(ctx) } } catch (_: InterruptedException) { }
            }.apply { isDaemon = true; start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.interrupt(); ticker = null
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

        /** A felhasználó állította le: újraindítás után se induljon el magától. */
        fun stop(c: Context) {
            setRunning(c, false)
            c.stopService(Intent(c, MonitorService::class.java))
        }

        private fun setRunning(c: Context, on: Boolean) =
            c.getSharedPreferences("vf", Context.MODE_PRIVATE).edit().putBoolean("running", on).apply()

        fun wasRunning(c: Context) = c.getSharedPreferences("vf", Context.MODE_PRIVATE).getBoolean("running", false)
    }
}
