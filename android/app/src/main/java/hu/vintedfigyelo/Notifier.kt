package hu.vintedfigyelo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Notifier {
    private const val CH_DEALS = "akciok"
    private const val CH_DEALS_QUIET = "akciok_csendes"
    private const val CH_MONITOR = "figyeles"
    private const val GROUP = "hu.vintedfigyelo.AKCIOK"
    const val ONGOING_ID = 1

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_DEALS) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_DEALS, "Akciók", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Értesítés hanggal, ha egy keresésben új legjobb ajánlat jelenik meg"
                enableVibration(true)
            })
        }
        if (nm.getNotificationChannel(CH_DEALS_QUIET) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_DEALS_QUIET, "Akciók (hang nélkül)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Ugyanaz, mint az Akciók, csak hang és rezgés nélkül (ha a beállításokban kikapcsolod a hangot)"
                setSound(null, null)
                enableVibration(false)
            })
        }
        if (nm.getNotificationChannel(CH_MONITOR) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_MONITOR, "Figyelés fut", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Állandó értesítés, amíg a figyelés a háttérben fut"
                setShowBadge(false)
            })
        }
    }

    private fun action(c: Context, act: String, req: Int, id: String = ""): PendingIntent =
        PendingIntent.getBroadcast(c, req, Intent(c, ActionReceiver::class.java).setAction(act).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Állandó értesítés élő állapottal és Leállítás gombbal. */
    fun ongoing(c: Context, text: String = "Vinted HU · PL · RO, új akcióknál értesítést kapsz"): Notification {
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(c, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Figyelés fut")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "Leállítás", action(c, ActionReceiver.STOP, 900)).build())
            .build()
    }

    fun updateOngoing(c: Context) {
        try { c.getSystemService(NotificationManager::class.java).notify(ONGOING_ID, ongoing(c, Engine.statusLine())) }
        catch (_: SecurityException) { }
    }

    /** A hirdetés fotója az értesítésbe (háttérszálon fut, legfeljebb pár másodperc). */
    private fun photo(url: String): Bitmap? {
        if (!url.startsWith("https://")) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000; conn.readTimeout = 4000
            conn.setRequestProperty("User-Agent", Engine.UA)
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null }
    }

    fun notifId(id: String) = ("deal:" + id).hashCode()

    fun deal(c: Context, rec: JSONObject) {
        if (!Engine.notifyEnabled()) return
        val (title, body) = Engine.notificationText(rec)
        val rid = rec.optString("id")
        val id = notifId(rid)
        val view = PendingIntent.getActivity(c, id, Intent(Intent.ACTION_VIEW, Uri.parse(rec.optString("url"))),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(c, if (Engine.soundEnabled()) CH_DEALS else CH_DEALS_QUIET)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(rec.optString("title_hu").ifEmpty { rec.optString("title") })
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(view)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .addAction(Notification.Action.Builder(null, "Megnyitás", view).build())
        if (rid.isNotEmpty() && rid != "proba") {
            b.addAction(Notification.Action.Builder(null, "Mentés", action(c, ActionReceiver.SAVE, id + 1, rid)).build())
            b.addAction(Notification.Action.Builder(null, "Elrejtés", action(c, ActionReceiver.HIDE, id + 2, rid)).build())
        }
        photo(rec.optString("photo"))?.let { b.setLargeIcon(it) }
        val nm = c.getSystemService(NotificationManager::class.java)
        try {
            nm.notify(id, b.build())
            // több akció egy csoportban, hogy ne árassza el az értesítési sávot
            nm.notify(GROUP.hashCode(), Notification.Builder(c, if (Engine.soundEnabled()) CH_DEALS else CH_DEALS_QUIET)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle("Vinted akciók")
                .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true)
                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).build())
        } catch (_: SecurityException) { }
    }

    /** Egyszerű értesítés a mentett hirdetésekről (eladva, eltűnt, árcsökkenés); koppintásra megnyitja. */
    fun plain(c: Context, title: String, body: String, url: String) {
        if (!Engine.notifyEnabled()) return
        val id = (url + title).hashCode()
        val view = PendingIntent.getActivity(c, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(c, if (Engine.soundEnabled()) CH_DEALS else CH_DEALS_QUIET)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(view)
            .setAutoCancel(true)
            .build()
        try { c.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }
}
