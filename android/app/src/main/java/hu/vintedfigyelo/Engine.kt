package hu.vintedfigyelo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * A figyelő motor Androidra. Ugyanazt csinálja, mint az asztali app.py:
 * a Vinted keresési oldalát olvassa, piaci árhoz vagy célárhoz méri a hirdetéseket,
 * csak HU/PL/RO eladókat enged át, és keresésenként csak a legolcsóbb akcióról szól.
 * Papp Zétény ötletei (asztali PR #1) itt is: árcsökkenés, mentett hirdetések figyelése,
 * élő árfolyam, magyar címek és állapotok, ártörténet, várakozás kijelzése.
 */
object Engine {
    val DOMAINS = linkedMapOf("hu" to "www.vinted.hu", "pl" to "www.vinted.pl", "ro" to "www.vinted.ro")
    internal val LANG = mapOf("hu" to "hu-HU,hu;q=0.9,en;q=0.6", "pl" to "pl-PL,pl;q=0.9,en;q=0.6",
        "ro" to "ro-RO,ro;q=0.9,en;q=0.6")
    internal const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val MAX_DEALS = 400
    private const val MAX_FEED = 150
    private const val WATCH_EVERY_SEC = 10 * 60.0
    private val DEFAULT_WARN = listOf("doboz", "box", "pudełko", "cutie", "cutia", "fotó", "foto",
        "zdjęcie", "photo", "imagine", "replika", "replica", "copy")

    private lateinit var dir: File
    private val lock = Any()
    private var initialized = false

    var cfg = JSONObject()
    private val rates = HashMap<String, Double>()
    private var ratesInfo = JSONObject().put("live", false)
    private val seen = HashMap<String, ArrayDeque<String>>()
    private val hist = HashMap<String, ArrayDeque<Double>>()
    private val alerted = LinkedHashSet<String>()
    private val sellers = HashMap<String, String>()          // eladó-azonosító -> országkód
    private val lastTotal = HashMap<String, Double>()         // hirdetés -> utoljára látott teljes ár (árcsökkenéshez)
    private val trCache = HashMap<String, String>()           // cím -> magyar fordítás
    private var history = JSONObject()                        // keresés -> [[idő, piaci ár], ...]
    private val deals = ArrayList<JSONObject>()
    private val feed = ArrayDeque<JSONObject>()
    private var watch = ArrayList<JSONObject>()
    private val status = LinkedHashMap<String, JSONObject>()
    @Volatile private var runId = 0
    @Volatile private var running = false
    @Volatile var focused = true
    private var seq = 0
    private var checked = 0
    private var newItems = 0
    private var foreignSkipped = 0
    private var waits = 0
    private var started = 0.0
    var notifier: ((JSONObject) -> Unit)? = null
    var plainNotifier: ((String, String, String) -> Unit)? = null   // cím, szöveg, link

    private fun now() = System.currentTimeMillis() / 1000.0

    // ------------------------------------------------------------ betöltés, mentés
    fun init(ctx: Context) {
        synchronized(lock) {
            if (initialized) return
            dir = ctx.filesDir
            val cfgFile = File(dir, "config.json")
            cfg = if (cfgFile.exists()) JSONObject(cfgFile.readText())
            else JSONObject(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
            applyDefaults()
            rates.clear(); rates["HUF"] = 1.0
            cfg.optJSONObject("to_huf")?.let { o -> o.keys().forEach { rates[it.uppercase()] = o.getDouble(it) } }
            loadState()
            loadDeals()
            watch = loadList("watch.json")
            history = try { JSONObject(File(dir, "history.json").readText()) } catch (_: Exception) { JSONObject() }
            DOMAINS.keys.forEach { status[it] = JSONObject().put("state", "stopped").put("msg", "") }
            initialized = true
        }
    }

    private fun applyDefaults() {
        if (!cfg.has("blocked_sellers")) cfg.put("blocked_sellers", JSONArray())
        if (!cfg.has("allowed_seller_countries")) cfg.put("allowed_seller_countries", JSONArray(listOf("HU", "PL", "RO")))
        if (!cfg.has("warn_words")) cfg.put("warn_words", JSONArray(DEFAULT_WARN))
        val app = cfg.optJSONObject("app") ?: JSONObject().also { cfg.put("app", it) }
        if (!app.has("windows_notify")) app.put("windows_notify", true)
        if (!app.has("sound")) app.put("sound", true)
        if (!app.has("notify_mode")) app.put("notify_mode", "best")
        if (!app.has("min_condition")) app.put("min_condition", 3)
        if (!app.has("translate_titles")) app.put("translate_titles", true)
        val s = cfg.optJSONArray("searches") ?: JSONArray().also { cfg.put("searches", it) }
        for (i in 0 until s.length()) s.getJSONObject(i).let { if (!it.has("enabled")) it.put("enabled", true) }
    }

    private fun writeAtomic(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }

    private fun loadList(name: String): ArrayList<JSONObject> {
        val out = ArrayList<JSONObject>()
        try { val a = JSONArray(File(dir, name).readText()); for (i in 0 until a.length()) out.add(a.getJSONObject(i)) }
        catch (_: Exception) { }
        return out
    }

    fun configJson(): String = synchronized(lock) { cfg.toString() }

    fun saveConfig(newCfg: JSONObject) {
        synchronized(lock) {
            cfg = newCfg
            applyDefaults()
            writeAtomic("config.json", cfg.toString(2))
        }
    }

    private fun loadState() {
        val f = File(dir, "state.json")
        if (!f.exists()) return
        try {
            val d = JSONObject(f.readText())
            d.optJSONObject("seen")?.let { o -> o.keys().forEach { k ->
                val a = o.getJSONArray(k); seen[k] = ArrayDeque<String>().also { q -> for (i in 0 until a.length()) q.add(a.getString(i)) } } }
            d.optJSONObject("hist")?.let { o -> o.keys().forEach { k ->
                val a = o.getJSONArray(k); hist[k] = ArrayDeque<Double>().also { q -> for (i in 0 until a.length()) q.add(a.getDouble(i)) } } }
            d.optJSONArray("alerted")?.let { a -> for (i in 0 until a.length()) alerted.add(a.getString(i)) }
            d.optJSONObject("sellers")?.let { o -> o.keys().forEach { k -> sellers[k] = o.getString(k) } }
        } catch (_: Exception) { }
    }

    private fun saveState() {
        val text = synchronized(lock) {
            val s = JSONObject(); seen.forEach { (k, v) -> s.put(k, JSONArray(v.toList())) }
            val h = JSONObject(); hist.forEach { (k, v) -> h.put(k, JSONArray(v.map { Math.round(it) })) }
            val sl = JSONObject(); sellers.forEach { (k, v) -> sl.put(k, v) }
            JSONObject().put("seen", s).put("hist", h).put("alerted", JSONArray(alerted.toList())).put("sellers", sl).toString()
        }
        try { writeAtomic("state.json", text) } catch (_: Exception) { }
    }

    private fun loadDeals() {
        // Csak az ellenőrzött országú eladók akciói maradnak (a régiek között külföldi is lehetett).
        for (d in loadList("akciok.json")) {
            if (d.optString("seller_country").isEmpty() || deals.size >= MAX_DEALS) continue
            d.put("status", statusHu(d.optString("status")))
            deals.add(d)
        }
    }

    private fun saveDeals() {
        val text = synchronized(lock) { JSONArray(deals).toString() }
        try { writeAtomic("akciok.json", text) } catch (_: Exception) { }
    }

    private fun saveWatch() {
        val text = synchronized(lock) { JSONArray(watch).toString() }
        try { writeAtomic("watch.json", text) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ vezérlés
    fun isRunning() = running

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val id = ++runId
            started = now()
            thread(isDaemon = true, name = "vf-hourly") { hourlyLoop(id) }
            thread(isDaemon = true, name = "vf-watch") { watchLoop(id) }
            thread(isDaemon = true, name = "vf-translate") { translateLoop(id) }
            val countries = cfg.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?: DOMAINS.keys.toList()
            for (c in countries.filter { DOMAINS.containsKey(it) }) {
                status[c] = JSONObject().put("state", "starting").put("msg", "Kapcsolódás…")
                thread(isDaemon = true, name = "vf-$c") { worker(c, id) }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            running = false
            runId++
            status.values.forEach { it.put("state", "stopped").put("msg", "").remove("progress") }
        }
        thread { saveState() }
    }

    private fun alive(id: Int) = running && id == runId

    internal fun sleep(sec: Double, id: Int) {
        var left = (sec * 1000).toLong()
        while (left > 0 && alive(id)) { Thread.sleep(minOf(left, 250)); left -= 250 }
    }

    // ------------------------------------------------------------ árfolyam (élő EKB, óránként)
    fun refreshRates(): JSONObject {
        for (url in listOf("https://api.frankfurter.dev/v1/latest?base=HUF&symbols=PLN,RON,EUR",
                           "https://api.frankfurter.app/latest?from=HUF&to=PLN,RON,EUR")) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "VintedFigyelo/1.0")
                val d = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val r = d.getJSONObject("rates")
                synchronized(lock) {
                    r.keys().forEach { rates[it] = 1.0 / r.getDouble(it) }
                    ratesInfo = JSONObject().put("live", true).put("date", d.optString("date")).put("time", now())
                }
                return ratesInfo
            } catch (_: Exception) { }
        }
        synchronized(lock) {
            // sikertelen lekérésnél a korábbi élő árfolyam marad
            if (ratesInfo.optBoolean("live")) ratesInfo.put("error_time", now())
            else ratesInfo = JSONObject().put("live", false).put("time", now())
        }
        return ratesInfo
    }

    private fun hourlyLoop(id: Int) {
        while (alive(id)) {
            refreshRates()
            logMedians()
            sleep(3600.0, id)
        }
    }

    /** Keresésenként feljegyzi a piaci árat (az összes ország mediánja), ebből lesz a grafikon. */
    private fun logMedians() {
        val text = synchronized(lock) {
            val minHist = cfg.optInt("min_history", 15)
            val t = Math.round(now())
            val a = cfg.optJSONArray("searches") ?: JSONArray()
            for (i in 0 until a.length()) {
                val name = a.getJSONObject(i).optString("name")
                val prices = hist.filterKeys { it.split("|").getOrNull(1) == name }.values.flatten().sorted()
                if (prices.size < minHist) continue
                val pts = history.optJSONArray(name) ?: JSONArray().also { history.put(name, it) }
                pts.put(JSONArray().put(t).put(Math.round(prices[prices.size / 2])))
                while (pts.length() > 2000) pts.remove(0)
            }
            history.toString()
        }
        try { writeAtomic("history.json", text) } catch (_: Exception) { }
    }

    fun priceHistory(name: String): String = synchronized(lock) {
        val pts = history.optJSONArray(name) ?: JSONArray()
        val ds = JSONArray()
        deals.filter { it.optString("search") == name }.sortedBy { it.optDouble("found") }
            .forEach { ds.put(JSONArray().put(Math.round(it.optDouble("found"))).put(it.optLong("total_huf"))) }
        JSONObject().put("points", pts).put("deals", ds).toString()
    }

    // ------------------------------------------------------------ Vinted-kliens
    private class HttpError(val code: Int) : Exception("HTTP $code")

    private class Client(val country: String) {
        val host = Engine.DOMAINS[country]!!
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private var cookieTime = 0.0

        private fun fetch(url: String): String {
            val uri = URI(url)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 25000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", Engine.UA)
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            c.setRequestProperty("Accept-Language", Engine.LANG[country])
            c.setRequestProperty("Referer", "https://$host/")
            val ck = cookies.cookieStore.get(uri).joinToString("; ") { "${it.name}=${it.value}" }
            if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
            val code = c.responseCode
            try { cookies.put(uri, c.headerFields) } catch (_: Exception) { }
            if (code >= 400) { try { c.errorStream?.close() } catch (_: Exception) { }; throw HttpError(code) }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        fun newSession() { fetch("https://$host/"); cookieTime = System.currentTimeMillis() / 1000.0 }

        /** HTML letöltése 401/403/429 esetén várakozással (ez látszik az országoknál) és újrapróbálással. */
        fun get(url: String, id: Int): String? {
            if (System.currentTimeMillis() / 1000.0 - cookieTime > 1800) newSession()
            for (attempt in 0 until 4) {
                try { return fetch(url) }
                catch (e: HttpError) {
                    val wait = when (e.code) {
                        401, 403 -> 2.0 + attempt * 3
                        429 -> 30.0 * (attempt + 1)
                        else -> throw e
                    }
                    Engine.onWait(country, wait, e.code)
                    Engine.sleep(wait, id)
                    if (e.code != 429) newSession()
                }
            }
            return null
        }

        fun search(text: String, id: Int): List<JSONObject> {
            val html = get("https://$host/catalog?search_text=" + URLEncoder.encode(text, "UTF-8") + "&order=newest_first", id)
            return if (html == null) emptyList() else Engine.extractItems(html)
        }

        /** Az eladó országkódja (pl. "HU") a profiloldaláról; null, ha nem derül ki. */
        fun sellerCountry(userId: String, id: Int): String? {
            if (userId.isEmpty()) return null
            return try {
                val html = get("https://$host/member/" + URLEncoder.encode(userId, "UTF-8"), id) ?: return null
                Engine.COUNTRY_RE.find(html)?.groupValues?.get(1)?.uppercase()
            } catch (_: Exception) { null }
        }

        /** Egy hirdetés aktuális állapota: eltűnt / eladva / foglalt / jelenlegi ár. */
        fun itemStatus(url: String, id: Int): JSONObject? {
            val html = try { get(url, id) } catch (e: HttpError) {
                if (e.code == 404 || e.code == 410) return JSONObject().put("gone", true)
                throw e
            } ?: return null
            val price = Engine.PRICE_RE.find(html)?.groupValues?.get(1)?.toDoubleOrNull()
            return JSONObject().put("gone", false)
                .put("closed", Regex("""is_closed\\?":true""").containsMatchIn(html))
                .put("reserved", Regex("""is_reserved\\?":true""").containsMatchIn(html))
                .put("price", price ?: JSONObject.NULL)
        }
    }

    internal val COUNTRY_RE = Regex("""country_iso_code\\?":\\?"([A-Za-z]{2})""")
    internal val PRICE_RE = Regex("""\\?"price\\?":\{\\?"amount\\?":\\?"([\d.]+)""")

    internal fun onWait(country: String, sec: Double, code: Int) {
        synchronized(lock) {
            waits++
            val why = if (code == 429) "Túl sok kérés (429)" else "A Vinted elutasította a kérést ($code)"
            status[country]?.put("state", "wait")?.put("msg", "$why, ${Math.round(sec)} mp szünet, utána folytatja")
        }
    }

    /** A JS-szöveg (Next.js push) végét keresi a nyitó idézőjeltől; -1, ha nincs lezárva. */
    private fun endOfJsString(html: String, start: Int): Int {
        var i = start + 1
        while (i < html.length) {
            val ch = html[i]
            if (ch == '\\') { i += 2; continue }
            if (ch == '"') return i
            i++
        }
        return -1
    }

    /** A katalógus-oldalba ágyazott (Next.js) hirdetés-objektumok kiolvasása, regex nélkül.
     *  Csak a hirdetéseket tartalmazó darabokat dekódolja (Zétény ötlete: sokkal gyorsabb). */
    fun extractItems(html: String): List<JSONObject> {
        val marker = "self.__next_f.push([1,\""
        val sb = StringBuilder()
        val starts = LinkedHashSet<Int>()
        var k = html.indexOf("productItem")
        while (k >= 0) {
            val s = html.lastIndexOf(marker, k)
            if (s < 0) break
            starts.add(s)
            val e = endOfJsString(html, s + marker.length - 1)
            k = if (e < 0) -1 else html.indexOf("productItem", e)
        }
        if (starts.isEmpty()) {  // tartalék: minden darab, ha a Vinted átrendezi a script-tageket
            var p = html.indexOf(marker)
            while (p >= 0) { starts.add(p); p = html.indexOf(marker, p + marker.length) }
        }
        for (s in starts) {
            val q = s + marker.length - 1
            val e = endOfJsString(html, q)
            if (e < 0) continue
            try { sb.append(JSONArray("[" + html.substring(q, e + 1) + "]").getString(0)) } catch (_: Exception) { }
        }
        val text = sb.toString()
        val key = "\"productItem\":"
        val out = ArrayList<JSONObject>()
        val ids = HashSet<String>()
        var pos = 0
        while (true) {
            val i = text.indexOf(key, pos)
            if (i < 0) break
            var s = i + key.length
            while (s < text.length && text[s].isWhitespace()) s++
            if (s >= text.length || text[s] != '{') { pos = s; continue }
            var depth = 0; var inStr = false; var esc = false; var j = s
            while (j < text.length) {
                val ch = text[j]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false }
                else if (ch == '"') inStr = true
                else if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) break }
                j++
            }
            try {
                val obj = JSONObject(text.substring(s, minOf(j + 1, text.length)))
                val oid = obj.optString("id")
                if (oid.isNotEmpty() && ids.add(oid)) out.add(obj)
            } catch (_: Exception) { }
            pos = j + 1
        }
        return out
    }

    // ------------------------------------------------------------ feldolgozás
    private fun money(v: Any?): Pair<Double?, String?> {
        if (v is JSONObject) {
            val amt = v.optString("amount").replace(",", ".").toDoubleOrNull()
            val cur = v.optString("currencyCode").ifEmpty { v.optString("currency_code") }.ifEmpty { null }
            return amt to cur
        }
        return (v?.toString()?.replace(",", ".")?.toDoubleOrNull()) to null
    }

    private fun clean(s: String) = if (s.startsWith("$")) "" else s

    private data class Item(val id: String, val title: String, val price: Double?, val cur: String,
                            val total: Double?, val url: String, val brand: String, val status: String,
                            val country: String, val photo: String, val seller: String)

    private fun normalize(raw: JSONObject, country: String): Item {
        val (price, cur) = money(raw.opt("price"))
        val (total, _) = money(raw.opt("totalItemPrice"))
        val box = raw.optJSONObject("itemBox") ?: JSONObject()
        var url = raw.optString("url").ifEmpty { "/items/" + raw.optString("id") }
        if (url.startsWith("/")) url = "https://" + DOMAINS[country] + url
        return Item(raw.optString("id"), raw.optString("title"), price, (cur ?: "").uppercase(), total, url,
            clean(box.optString("firstLine")), clean(box.optString("secondLine")), country,
            raw.optString("thumbnailUrl"), raw.optJSONObject("user")?.optString("id") ?: "")
    }

    private fun strList(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i).lowercase() } } ?: emptyList()

    private fun textOk(title: String, s: JSONObject): Boolean {
        val t = title.lowercase()
        if ((strList(cfg.optJSONArray("exclude_words")) + strList(s.optJSONArray("exclude_words"))).any { t.contains(it) }) return false
        val must = strList(s.optJSONArray("must_contain"))
        if (must.isNotEmpty() && must.none { t.contains(it) }) return false
        val all = strList(s.optJSONArray("must_all"))
        if (all.isNotEmpty() && !all.all { t.contains(it) || t.replace(" ", "").contains(it) }) return false
        return true
    }

    fun queriesFor(s: JSONObject, country: String): List<String> {
        val q = s.opt("queries")
        val v: Any? = if (q is JSONObject) (q.opt(country) ?: q.opt("all")) else q
        return when (v) { is JSONArray -> (0 until v.length()).map { v.getString(it) }; is String -> listOf(v); else -> emptyList() }
    }

    fun conditionRank(status: String): Int {
        val s = status.lowercase()
        if (s.isEmpty()) return 3
        val table = listOf(
            5 to listOf("új", "nowy", "nou", "new"),
            4 to listOf("kiváló", "doskonały", "excelent", "excellent"),
            4 to listOf("nagyon jó", "bardzo dobry", "foarte bun", "very good"),
            2 to listOf("megfelelő", "zadowalający", "satisfăcător", "satisfacator", "satisfactory"),
            3 to listOf("jó", "dobry", "bun", "good"))
        for ((rank, words) in table) if (words.any { s.contains(it) }) return rank
        return 3
    }

    /** Lengyel/román/angol állapotfeliratok magyarul (mint az asztali appban). */
    fun statusHu(status: String): String {
        val s = status.lowercase()
        val table = listOf(
            listOf("címkével", "z metką", "cu etichet", "with tags") to "Új, címkével",
            listOf("címke nélkül", "bez metki", "fără etichet", "fara etichet", "without tags") to "Új, címke nélkül",
            listOf("bardzo dobry", "foarte bun", "very good") to "Nagyon jó",
            listOf("zadowalający", "satisf") to "Megfelelő",
            listOf("dobry", "bun", "good") to "Jó",
            listOf("nowy", "nou", "new") to "Új")
        return table.firstOrNull { (words, _) -> words.any { s.contains(it) } }?.second ?: status
    }

    private fun fmt(v: Double): String {
        val n = Math.round(v).toString()
        val sb = StringBuilder()
        for ((i, ch) in n.withIndex()) { if (i > 0 && (n.length - i) % 3 == 0) sb.append(' '); sb.append(ch) }
        return sb.toString()
    }

    private fun landed(it: Item): Pair<Double, Double>? {
        val rate = rates[it.cur] ?: return null
        val price = it.price ?: return null
        if (price <= 0) return null
        return price * rate to (it.total ?: price) * rate
    }

    private fun bestTotal(search: String): Double? {
        val cutoff = now() - 24 * 3600
        return deals.filter { it.optString("search") == search && it.optDouble("found") >= cutoff }
            .minOfOrNull { it.optDouble("total_huf") }
    }

    private fun score(it: Item, s: JSONObject, h: ArrayDeque<Double>, minHist: Int, warn: List<String>, addHist: Boolean): JSONObject? {
        val (price, total) = landed(it) ?: return null
        if (!textOk(it.title, s)) return null
        if (price < s.optDouble("min_price_huf", 0.0)) return null
        val med = if (h.size >= minHist) h.sorted()[h.size / 2] else null
        if (addHist) { h.addLast(total); while (h.size > 300) h.removeFirst() }
        val mx = s.optDouble("max_price_huf", 0.0)
        if (mx > 0 && price > mx) return null
        val ratio = med?.let { total / it }
        val target = s.optDouble("target_price_huf", 0.0)
        val (rating, label) = when {
            ratio == null && target > 0 && total <= target -> "target" to "Célár alatt"
            ratio == null -> "new" to "Új minta"
            ratio <= 0.5 -> "great" to "Kiváló ár"
            ratio <= 0.7 -> "good" to "Jó ár"
            ratio <= 0.9 -> "fair" to "Korrekt ár"
            else -> "high" to "Átlag feletti"
        }
        val warnings = JSONArray()
        if (ratio != null && ratio < 0.3) warnings.put("Gyanúsan olcsó, nézd meg a leírást")
        val tl = it.title.lowercase()
        warn.firstOrNull { w -> tl.contains(w) }?.let { w -> warnings.put("A címben szerepel: „$w”") }
        val priceLabel = fmt(it.price!!) + " " + (if (it.cur == "HUF") "Ft" else it.cur)
        return JSONObject()
            .put("id", it.id).put("title", it.title).put("url", it.url).put("photo", it.photo)
            .put("brand", it.brand).put("status", statusHu(it.status)).put("seller", it.seller)
            .put("country", it.country).put("search", s.optString("name"))
            .put("price", it.price).put("cur", it.cur).put("price_label", priceLabel)
            .put("price_huf", Math.round(price)).put("total_huf", Math.round(total))
            .put("median_huf", med?.let { m -> Math.round(m) } ?: JSONObject.NULL)
            .put("ratio", ratio?.let { r -> Math.round(r * 1000) / 1000.0 } ?: JSONObject.NULL)
            .put("rating", rating).put("rating_label", label)
            .put("warnings", warnings).put("found", now())
            .put("cond_rank", conditionRank(it.status)).put("best", false)
            .put("target_huf", if (target > 0) Math.round(target) else JSONObject.NULL)
    }

    private class Cand(val rec: JSONObject, val dropFrom: Double?)

    private fun process(country: String, s: JSONObject, q: String, items: List<JSONObject>, client: Client?, id: Int) {
        val key = "$country|${s.optString("name")}|$q"
        val target = s.optDouble("target_price_huf", 0.0)
        // Ezt a kulcsot (ország|keresés|szó) csak ennek az országnak a szála írja, így a lépések között nem változhat.
        val seeded = synchronized(lock) { seen.containsKey(key) }
        // 1. lépés (zárolva): új és olcsóbb lett hirdetések kiértékelése
        val cands = ArrayList<Cand>()
        synchronized(lock) {
            val sq = seen.getOrPut(key) { ArrayDeque() }
            val sset = HashSet(sq)
            val h = hist.getOrPut(key) { ArrayDeque() }
            val warn = strList(cfg.optJSONArray("warn_words"))
            val minHist = cfg.optInt("min_history", 15)
            if (lastTotal.size > 100000) lastTotal.clear()
            for (raw in items.reversed()) {
                val it = normalize(raw, country)
                if (it.id.isEmpty()) continue
                val total = landed(it)?.second
                val old = lastTotal[it.id]
                if (total != null) lastTotal[it.id] = total
                var dropFrom: Double? = null
                if (it.id in sset) {
                    // Már látott hirdetés: csak akkor érdekes, ha legalább 5%-kal olcsóbb lett.
                    if (!(seeded && old != null && total != null && total <= old * 0.95)) continue
                    dropFrom = old
                } else {
                    sq.addLast(it.id); sset.add(it.id); while (sq.size > 3000) sq.removeFirst()
                }
                if (!seeded && target <= 0) {
                    landed(it)?.let { (price, t) ->
                        if (textOk(it.title, s) && price >= s.optDouble("min_price_huf", 0.0)) {
                            h.addLast(t); while (h.size > 300) h.removeFirst()
                        }
                    }
                    continue
                }
                if (seeded && dropFrom == null) newItems++
                val rec = score(it, s, h, minHist, warn, dropFrom == null) ?: continue
                if (dropFrom != null) {
                    rec.put("drop_from", Math.round(dropFrom))
                    val w = JSONArray().put("Árcsökkenés: " + fmt(dropFrom) + " Ft → " + fmt(rec.getDouble("total_huf")) + " Ft")
                    val old2 = rec.getJSONArray("warnings")
                    for (i in 0 until old2.length()) w.put(old2.get(i))
                    rec.put("warnings", w)
                }
                cands.add(Cand(rec, dropFrom))
            }
        }
        if (cands.isEmpty()) return
        // 2. lépés (zárolás nélkül): csak a megadott országok eladóitól jöhet hirdetés
        val allowed = synchronized(lock) { strList(cfg.optJSONArray("allowed_seller_countries")).map { it.uppercase() }.toSet() }
            .ifEmpty { setOf("HU", "PL", "RO") }
        val kept = ArrayList<Cand>()
        for (c in cands) {
            val seller = c.rec.optString("seller")
            var cc = synchronized(lock) { sellers[seller] }
            if (cc == null && client != null) {
                cc = client.sellerCountry(seller, id)
                if (cc != null) synchronized(lock) { sellers[seller] = cc }
                sleep(0.4, id)
            }
            if (cc != null && cc in allowed) { c.rec.put("seller_country", cc); kept.add(c) }
            else synchronized(lock) { foreignSkipped++ }
        }
        // 3. lépés (zárolva): lista, akciók, értesítés
        val notify = ArrayList<JSONObject>()
        synchronized(lock) {
            val blocked = strList(cfg.optJSONArray("blocked_sellers")).toSet()
            val thr = s.optDouble("deal_threshold", cfg.optDouble("deal_threshold", 0.65))
            val app = cfg.optJSONObject("app") ?: JSONObject()
            val minCond = s.optInt("min_condition", app.optInt("min_condition", 3))  // keresésenként felülírható
            val mode = app.optString("notify_mode", "best")
            for (c in kept) {
                val rec = c.rec
                val rid = rec.optString("id")
                if (seeded) { feed.addFirst(rec); while (feed.size > MAX_FEED) feed.removeLast(); seq++ }
                val isDeal = if (target > 0) rec.getDouble("total_huf") <= target
                             else rec.opt("ratio").let { r -> r is Number && r.toDouble() <= thr }
                if (!isDeal || rec.optString("seller").lowercase() in blocked || rec.getInt("cond_rank") < minCond) continue
                // árcsökkenésnél az új ár számít új jelzésnek, ugyanarra az árra nem szólunk kétszer
                val akey = if (c.dropFrom != null) rid + "@" + rec.optLong("total_huf") else rid
                if (!alerted.add(akey)) continue
                while (alerted.size > 20000) alerted.remove(alerted.first())
                val best = bestTotal(s.optString("name"))
                rec.put("best", best == null || rec.getDouble("total_huf") < best)
                rec.put("first_round", !seeded)
                deals.removeAll { d -> d.optString("id") == rid }
                deals.add(0, rec); while (deals.size > MAX_DEALS) deals.removeAt(deals.size - 1)
                seq++
                // Az első kör (már fent lévő hirdetések) csak a listába kerül, értesítés az újakról jön.
                if (seeded && (mode == "all" || rec.getBoolean("best"))) notify.add(rec)
            }
        }
        if (notify.isNotEmpty()) {
            saveDeals()
            if (!focused) notify.forEach { r -> notifier?.invoke(r) }
        }
    }

    private val TARGET_SUFFIX = Regex("""\s+\d{1,3}(?:\s\d{3})*\s*Ft alatt$""")

    /** Rövid értesítés, pl. „85 000 Ft · RTX 4070 · Nagyon jó · −45%”. */
    fun notificationText(rec: JSONObject): Pair<String, String> {
        val parts = arrayListOf(rec.optString("price_label"), TARGET_SUFFIX.replace(rec.optString("search"), "").trim())
        rec.optString("status").let { if (it.isNotEmpty()) parts.add(it) }
        val ratio = rec.opt("ratio")
        if (ratio is Number && ratio.toDouble() < 1) parts.add("−" + Math.round((1 - ratio.toDouble()) * 100) + "%")
        if (rec.opt("drop_from") is Number) parts.add(0, "Árcsökkenés")
        val ref = when {
            rec.opt("target_huf") is Number -> "Célár: " + fmt(rec.getDouble("target_huf")) + " Ft, díjjal " + fmt(rec.getDouble("total_huf")) + " Ft"
            rec.opt("median_huf") is Number -> "Piaci ár kb. " + fmt(rec.getDouble("median_huf")) + " Ft"
            else -> "Díjjal együtt " + fmt(rec.getDouble("total_huf")) + " Ft"
        }
        val title = rec.optString("title_hu").ifEmpty { rec.optString("title") }
        return parts.joinToString(" · ") to (title + "\n" + ref + " · " + rec.optString("country").uppercase())
    }

    // ------------------------------------------------------------ figyelő szál
    private fun worker(country: String, id: Int) {
        var client: Client? = null
        while (alive(id)) {
            val st = synchronized(lock) { status[country]!! }
            try {
                val cl = client ?: run {
                    synchronized(lock) { st.put("state", "starting").put("msg", "Kapcsolódás…") }
                    Client(country).also { it.newSession(); client = it }
                }
                val t0 = now()
                val (searches, gap) = synchronized(lock) {
                    val a = cfg.optJSONArray("searches") ?: JSONArray()
                    (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("enabled", true) } to
                        cfg.optDouble("request_gap_sec", 1.5)
                }
                val jobs = searches.flatMap { s -> queriesFor(s, country).map { s to it } }
                val firstRound = synchronized(lock) { !st.has("last_ok") }
                var empty = 0
                for ((n, job) in jobs.withIndex()) {
                    if (!alive(id)) return
                    val tReq = now()
                    val items = cl.search(job.second, id)
                    synchronized(lock) { if (st.optString("state") == "wait") st.put("state", "ok").put("msg", "") }
                    if (items.isEmpty()) empty++
                    process(country, job.first, job.second, items, cl, id)
                    synchronized(lock) {
                        checked++
                        st.put("items", st.optInt("items", 0) + items.size)
                        if (firstRound) st.put("state", "ok").put("progress", "${n + 1}/${jobs.size}")
                            .put("msg", "Első kör: ${n + 1}/${jobs.size} keresés, ${st.optInt("items")} hirdetés beolvasva")
                    }
                    // a szünet a kérés indulásától számít, a letöltés/feldolgozás ideje beleszámít
                    sleep(maxOf(0.0, gap + Math.random() * 0.7 - (now() - tReq)), id)
                }
                if (jobs.isNotEmpty() && empty == jobs.size) {
                    synchronized(lock) {
                        st.put("state", "error").put("msg", "A Vinted minden keresésre üres oldalt adott. " +
                            "Lehet, hogy átalakították az oldalt, frissítés kellhet.")
                    }
                    sleep(30.0, id); continue
                }
                synchronized(lock) {
                    st.put("state", "ok").put("msg", "").remove("progress")
                    st.put("last_ok", now()).put("round_sec", Math.round((now() - t0) * 10) / 10.0)
                }
                saveState()
                sleep(cfg.optDouble("poll_interval_sec", 3.0), id)
            } catch (e: Exception) {
                val m = e.message ?: e.toString()
                val msg = when {
                    m.contains("403") -> "A Vinted ideiglenesen blokkolja a lekérést (403). Lassabb tempóval újrapróbálom."
                    m.contains("429") -> "Túl sok kérés (429). Várok, aztán folytatom."
                    m.contains("404") -> "A Vinted megváltoztatta az oldal címét (404). Frissítés kellhet."
                    e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                        "Nincs kapcsolat a Vinteddel. Ellenőrizd az internetet."
                    else -> "Hiba: $m"
                }
                synchronized(lock) { st.put("state", "error").put("msg", msg) }
                client = null
                sleep(20.0, id)
            }
        }
    }

    // ------------------------------------------------------------ mentett hirdetések figyelése
    private fun watchLoop(id: Int) {
        while (alive(id)) {
            sleep(WATCH_EVERY_SEC, id)
            if (alive(id)) checkWatch()
        }
    }

    /** Eladva / eltűnt / foglalt / új ár: a mentett hirdetések oldalát nézi meg. */
    fun checkWatch() {
        val (todo, gap) = synchronized(lock) {
            watch.filter { !it.optBoolean("gone") && !it.optBoolean("sold") } to cfg.optDouble("request_gap_sec", 1.5)
        }
        val clients = HashMap<String, Client>()
        val wid = runId
        for (w in todo) {
            val c = w.optString("country", "hu")
            val r = try {
                val cl = clients.getOrPut(c) { Client(c).also { it.newSession() } }
                cl.itemStatus(w.optString("url"), wid)
            } catch (_: Exception) { clients.remove(c); null }
            if (r != null) applyWatch(w, r)
            Thread.sleep(((gap + Math.random() * 0.7) * 1000).toLong())
        }
    }

    private fun applyWatch(w: JSONObject, r: JSONObject) {
        var toast: Pair<String, String>? = null
        synchronized(lock) {
            w.put("checked", now())
            val title = w.optString("title_hu").ifEmpty { w.optString("title") }
            if (r.optBoolean("gone") || r.optBoolean("closed")) {
                w.put("sold", r.optBoolean("closed")); w.put("gone", r.optBoolean("gone"))
                toast = (if (r.optBoolean("closed")) "Eladták" else "Eltűnt a hirdetés") to title
            } else {
                w.put("reserved", r.optBoolean("reserved"))
                val price = r.opt("price")
                val prev = if (w.has("now_price")) w.optDouble("now_price") else w.optDouble("price")
                if (price is Number && price.toDouble() != prev) {
                    val cur = w.optString("cur")
                    w.put("now_price", price.toDouble())
                    w.put("now_label", fmt(price.toDouble()) + " " + (if (cur == "HUF") "Ft" else cur))
                    if (price.toDouble() < prev) toast = ("Árcsökkenés: " + w.optString("now_label")) to title
                }
            }
            seq++
        }
        saveWatch()
        toast?.let { (t, b) -> plainNotifier?.invoke(t, b, w.optString("url")) }
    }

    fun watchAdd(itemId: String): Boolean {
        synchronized(lock) {
            val d = (deals + feed).firstOrNull { it.optString("id") == itemId } ?: return false
            if (watch.any { it.optString("id") == itemId }) return false
            watch.add(0, JSONObject(d.toString()).put("saved_at", now()))
            seq++
        }
        saveWatch()
        return true
    }

    fun watchRemove(itemId: String): Boolean {
        synchronized(lock) { watch.removeAll { it.optString("id") == itemId }; seq++ }
        saveWatch()
        return true
    }

    // ------------------------------------------------------------ címek fordítása magyarra
    private fun translateHu(texts: List<String>): List<String> {
        val q = texts.joinToString("\n") { it.replace("\n", " ") }
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=hu&dt=t&q=" +
            URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        val data = JSONArray(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val segs = data.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segs.length()) segs.optJSONArray(i)?.optString(0)?.let { sb.append(it) }
        val out = sb.toString().split("\n")
        if (out.size != texts.size) {  // a sortörés elcsúszott: egyesével
            return if (texts.size == 1) texts else texts.map { translateHu(listOf(it))[0] }
        }
        return out.map { it.trim() }
    }

    private fun translateLoop(id: Int) {
        while (alive(id)) {
            sleep(4.0, id)
            if (!alive(id)) return
            val on = synchronized(lock) { cfg.optJSONObject("app")?.optBoolean("translate_titles", true) ?: true }
            if (!on) continue
            val recs = synchronized(lock) {
                (deals + feed + watch).filter { it.optString("country") != "hu" && !it.has("title_hu") }
            }
            val todo = recs.map { it.optString("title") }.distinct().filter { it !in trCache }.take(25)
            if (todo.isNotEmpty()) {
                try { trCache.putAll(todo.zip(translateHu(todo))) }
                catch (_: Exception) { sleep(60.0, id); continue }  // a fordító nem elérhető, később újra
            }
            var changed = 0
            synchronized(lock) {
                val warn = strList(cfg.optJSONArray("warn_words"))
                for (d in recs) {
                    val tr = trCache[d.optString("title")] ?: continue
                    d.put("title_hu", tr); changed++
                    // pl. „Krabička” = „Doboz”: a figyelmeztető szavak a fordításban is számítanak
                    val hit = warn.firstOrNull { tr.lowercase().contains(it) } ?: continue
                    val ws = d.optJSONArray("warnings") ?: JSONArray().also { d.put("warnings", it) }
                    if ((0 until ws.length()).none { ws.optString(it).contains(hit) }) ws.put("A címben szerepel: „$hit”")
                }
                if (changed > 0) seq++
            }
            if (changed > 0) { saveDeals(); saveWatch() }
        }
    }

    // ------------------------------------------------------------ felületnek
    /** A listákat csak akkor küldi, ha változtak (seq), így a másodpercenkénti frissítés kicsi marad. */
    fun snapshot(since: Int?): String = synchronized(lock) {
        val st = JSONObject(); status.forEach { (k, v) -> st.put(k, v) }
        val rt = JSONObject(); rates.forEach { (k, v) -> if (k != "HUF") rt.put(k, Math.round(v * 100) / 100.0) }
        val o = JSONObject()
            .put("running", running).put("status", st).put("seq", seq).put("now", now())
            .put("rates", rt).put("rates_info", ratesInfo)
            .put("stats", JSONObject().put("checked", checked).put("new_items", newItems)
                .put("foreign_skipped", foreignSkipped).put("waits", waits)
                .put("started", if (running) started else JSONObject.NULL))
        if (since != seq) {
            o.put("deals", JSONArray(deals)).put("feed", JSONArray(feed.toList())).put("watch", JSONArray(watch))
        }
        o.toString()
    }

    fun currentSeq(): Int = synchronized(lock) { seq }

    fun hideDeal(id: String) { synchronized(lock) { deals.removeAll { it.optString("id") == id }; seq++ }; saveDeals() }

    fun blockSeller(seller: String) {
        synchronized(lock) {
            val a = cfg.optJSONArray("blocked_sellers") ?: JSONArray().also { cfg.put("blocked_sellers", it) }
            if (strList(a).none { it == seller.lowercase() }) a.put(seller)
            deals.removeAll { it.optString("seller") == seller }
            seq++
            writeAtomic("config.json", cfg.toString(2))
        }
        saveDeals()
    }

    fun clearDeals() { synchronized(lock) { deals.clear(); seq++ }; saveDeals() }
}
