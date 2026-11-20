package hu.vintedfigyelo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

object Notifier {
    private const val CH_DEALS = "akciok"
    private const val CH_MONITOR = "figyeles"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_DEALS) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_DEALS, "Akciók", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Értesítés, ha egy keresésben új legjobb ajánlat jelenik meg"
                enableVibration(true)
            })
        }
        if (nm.getNotificationChannel(CH_MONITOR) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Figyelés fut", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Állandó értesítés, amíg a figyelés a háttérben fut"
            })
        }
    }

    fun ongoing(c: Context): Notification {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(c, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Figyelés fut")
            .setContentText("Vinted HU · PL · RO, új akcióknál értesítést kapsz")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    fun deal(c: Context, rec: JSONObject) {
        val (title, body) = Engine.notificationText(rec)
        val id = rec.optString("id").hashCode()
        val view = PendingIntent.getActivity(c, id, Intent(Intent.ACTION_VIEW, Uri.parse(rec.optString("url"))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, CH_DEALS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(rec.optString("title"))
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(view)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }
}
