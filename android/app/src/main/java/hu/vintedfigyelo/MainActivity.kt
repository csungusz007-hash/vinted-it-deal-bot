package hu.vintedfigyelo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.init(applicationContext)
        Notifier.ensureChannels(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidApi")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openUrl(request.url.toString()); return true
            }
        }
        web.loadUrl("file:///android_asset/app_ui.html")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        askBatteryExemption()
        MonitorService.start(this)
    }

    /** Akkumulátor-optimalizálás alóli kivétel: enélkül az Android leállíthatja a háttérfigyelést. */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("vf", MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery", false)) return
        prefs.edit().putBoolean("asked_battery", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) { }
    }

    fun openUrl(url: String) {
        if (!url.startsWith("https://")) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
    }

    override fun onResume() { super.onResume(); Engine.focused = true }
    override fun onPause() { Engine.focused = false; super.onPause() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A vissza gomb csak háttérbe teszi az appot, a figyelés fut tovább.
        moveTaskToBack(true)
    }

    /** A felület (app_ui.html) ezen keresztül hívja a motort: AndroidApi.call(név, JSON-argumentumok). */
    inner class Bridge {
        @JavascriptInterface
        fun call(name: String, argsJson: String): String {
            val args = try { JSONArray(argsJson) } catch (_: Exception) { JSONArray() }
            return try {
                when (name) {
                    "snapshot" -> Engine.snapshot(if (args.length() > 0 && !args.isNull(0)) args.optInt(0, -1) else null)
                    "refresh_rates" -> { Engine.refreshRates(); Engine.snapshot(Engine.currentSeq()) }
                    "price_history" -> Engine.priceHistory(args.optString(0))
                    "watch_add" -> Engine.watchAdd(args.optString(0)).toString()
                    "watch_remove" -> Engine.watchRemove(args.optString(0)).toString()
                    "watch_check" -> { Thread { Engine.checkWatch() }.start(); "true" }
                    "start" -> { runOnUiThread { MonitorService.start(this@MainActivity) }; "true" }
                    "stop" -> { Engine.stop(); runOnUiThread { MonitorService.stop(this@MainActivity) }; "true" }
                    "get_config" -> Engine.configJson()
                    "save_config" -> {
                        val wasRunning = Engine.isRunning()
                        Engine.saveConfig(args.getJSONObject(0))
                        if (wasRunning) { Engine.stop(); Engine.start() }
                        "true"
                    }
                    "open_url" -> { val u = args.optString(0); runOnUiThread { openUrl(u) }; "true" }
                    "hide_deal" -> { Engine.hideDeal(args.optString(0)); "true" }
                    "block_seller" -> { Engine.blockSeller(args.optString(0)); "true" }
                    "clear_deals" -> { Engine.clearDeals(); "true" }
                    "set_focus" -> { Engine.focused = args.optBoolean(0, true); "true" }
                    "test_notification" -> {
                        Notifier.deal(this@MainActivity, JSONObject()
                            .put("id", "proba").put("title", "Próba értesítés: így fog kinézni egy akció")
                            .put("search", "RTX 4070 100 000 Ft alatt").put("price_label", "89 000 Ft")
                            .put("status", "Nagyon jó").put("target_huf", 100000).put("total_huf", 95230)
                            .put("country", "hu").put("url", "https://www.vinted.hu/"))
                        "true"
                    }
                    else -> "null"
                }
            } catch (e: Exception) {
                JSONObject().put("error", e.toString()).toString()
            }
        }
    }
}
