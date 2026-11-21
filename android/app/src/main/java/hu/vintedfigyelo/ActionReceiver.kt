package hu.vintedfigyelo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Az értesítések gombjai (Mentés, Elrejtés, Leállítás), és újraindítás után a figyelés visszakapcsolása. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val SAVE = "hu.vintedfigyelo.SAVE"
        const val HIDE = "hu.vintedfigyelo.HIDE"
        const val STOP = "hu.vintedfigyelo.STOP"
    }

    override fun onReceive(c: Context, intent: Intent) {
        val app = c.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Ha leállítás nélkül kapcsolt ki a telefon (vagy frissült az app), a figyelés magától folytatódik.
                if (MonitorService.wasRunning(app)) try { MonitorService.start(app) } catch (_: Exception) { }
            }
            STOP -> {
                Engine.init(app)
                Engine.stop()
                MonitorService.stop(app)
            }
            SAVE, HIDE -> {
                val id = intent.getStringExtra("id") ?: return
                val pending = goAsync()
                thread {
                    try {
                        Engine.init(app)
                        if (intent.action == SAVE) Engine.watchAdd(id) else Engine.hideDeal(id)
                        app.getSystemService(NotificationManager::class.java).cancel(Notifier.notifId(id))
                    } finally { pending.finish() }
                }
            }
        }
    }
}
