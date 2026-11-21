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
 * és keresésenként csak a legolcsóbb akcióról szól.
 */
object Engine {
    val DOMAINS = linkedMapOf("hu" to "www.vinted.hu", "pl" to "www.vinted.pl", "ro" to "www.vinted.ro")
    internal val LANG = mapOf("hu" to "hu-HU,hu;q=0.9,en;q=0.6", "pl" to "pl-PL,pl;q=0.9,en;q=0.6",
        "ro" to "ro-RO,ro;q=0.9,en;q=0.6")
    internal const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Mobile Safari/537.36"
    private const val MAX_DEALS = 400
    private const val MAX_FEED = 150
    private val DEFAULT_WARN = listOf("doboz", "box", "pudełko", "cutie", "cutia", "fotó", "foto",
        "zdjęcie", "photo", "imagine", "replika", "replica", "copy")

    private lateinit var dir: File
    private val lock = Any()
    private var initialized = false

    var cfg = JSONObject()
    private val rates = HashMap<String, Double>()
    private val seen = HashMap<String, ArrayDeque<String>>()
    private val hist = HashMap<String, ArrayDeque<Double>>()
    private val alerted = LinkedHashSet<String>()
    private val sellers = HashMap<String, String>()  // eladó-azonosító -> országkód
    private val deals = ArrayList<JSONObject>()
    private val feed = ArrayDeque<JSONObject>()
    private val status = LinkedHashMap<String, JSONObject>()
    @Volatile private var runId = 0
    @Volatile private var running = false
    @Volatile var focused = true
    private var seq = 0
    private var checked = 0
    private var newItems = 0
    private var foreignSkipped = 0
    private var started = 0.0
    var notifier: ((JSONObject) -> Unit)? = null

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
        val s = cfg.optJSONArray("searches") ?: JSONArray().also { cfg.put("searches", it) }
        for (i in 0 until s.length()) s.getJSONObject(i).let { if (!it.has("enabled")) it.put("enabled", true) }
    }

    private fun writeAtomic(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
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
        val f = File(dir, "akciok.json")
        if (!f.exists()) return
        try {
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) { val d = a.getJSONObject(i); if (d.optString("seller_country").isNotEmpty() && deals.size < MAX_DEALS) deals.add(d) }
        }
        catch (_: Exception) { }
    }

    private fun saveDeals() {
        val text = synchronized(lock) { JSONArray(deals).toString() }
        try { writeAtomic("akciok.json", text) } catch (_: Exception) { }
    }

    // ------------------------------------------------------------ vezérlés
    fun isRunning() = running

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            val id = ++runId
            started = now()
            thread(isDaemon = true) { refreshRates() }
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

    private fun refreshRates() {
        for (url in listOf("https://api.frankfurter.dev/v1/latest?base=HUF&symbols=PLN,RON,EUR",
                           "https://api.frankfurter.app/latest?from=HUF&to=PLN,RON,EUR")) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "VintedFigyelo/1.0")
                val r = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("rates")
                synchronized(lock) { r.keys().forEach { rates[it] = 1.0 / r.getDouble(it) } }
                return
            } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------ Vinted-kliens
    private class HttpError(val code: Int) : Exception("HTTP $code")

    private class Client(val country: String) {
        val host = Engine.DOMAINS[country]!!
        private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        private var cookieTime = 0.0

        private fun get(url: String): String {
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

        fun newSession() { get("https://$host/"); cookieTime = System.currentTimeMillis() / 1000.0 }

        /** Az eladó országkódja (pl. "HU") a profiloldaláról; null, ha nem derül ki. */
        fun sellerCountry(userId: String): String? {
            if (userId.isEmpty()) return null
            return try {
                val html = get("https://$host/member/" + URLEncoder.encode(userId, "UTF-8"))
                Engine.COUNTRY_RE.find(html)?.groupValues?.get(1)?.uppercase()
            } catch (_: Exception) { null }
        }

        fun search(text: String, id: Int): List<JSONObject> {
            if (System.currentTimeMillis() / 1000.0 - cookieTime > 1800) newSession()
            val url = "https://$host/catalog?search_text=" + URLEncoder.encode(text, "UTF-8") + "&order=newest_first"
            for (attempt in 0 until 4) {
                try { return Engine.extractItems(get(url)) }
                catch (e: HttpError) {
                    when (e.code) {
                        401, 403 -> { Engine.sleep(2.0 + attempt * 3, id); newSession() }
                        429 -> Engine.sleep(30.0 * (attempt + 1), id)
                        else -> throw e
                    }
                }
            }
            return emptyList()
        }
    }

    internal val COUNTRY_RE = Regex("""country_iso_code\\?":\\?"([A-Za-z]{2})""")

    /** A katalógus-oldalba ágyazott (Next.js) hirdetés-objektumok kiolvasása, regex nélkül. */
    fun extractItems(html: String): List<JSONObject> {
        val sb = StringBuilder()
        val marker = "self.__next_f.push([1,\""
        var p = html.indexOf(marker)
        while (p >= 0) {
            val start = p + marker.length - 1  // a nyitó idézőjel
            var i = start + 1
            while (i < html.length) {
                val ch = html[i]
                if (ch == '\\') { i += 2; continue }
                if (ch == '"') break
                i++
            }
            if (i < html.length) {
                try { sb.append(JSONArray("[" + html.substring(start, i + 1) + "]").getString(0)) } catch (_: Exception) { }
            }
            p = html.indexOf(marker, i)
        }
        val text = sb.toString()
        val key = "\"productItem\":"
        val out = ArrayList<JSONObject>()
        val ids = HashSet<String>()
        var pos = 0
        while (true) {
            val k = text.indexOf(key, pos)
            if (k < 0) break
            var s = k + key.length
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
                val id = obj.optString("id")
                if (id.isNotEmpty() && ids.add(id)) out.add(obj)
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

    private fun score(it: Item, s: JSONObject, h: ArrayDeque<Double>, minHist: Int, warn: List<String>): JSONObject? {
        val (price, total) = landed(it) ?: return null
        if (!textOk(it.title, s)) return null
        if (price < s.optDouble("min_price_huf", 0.0)) return null
        val med = if (h.size >= minHist) h.sorted()[h.size / 2] else null
        h.addLast(total); while (h.size > 300) h.removeFirst()
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
        warn.firstOrNull { tl.contains(it) }?.let { w -> warnings.put("A címben szerepel: „$w”") }
        val priceLabel = fmt(it.price!!) + " " + (if (it.cur == "HUF") "Ft" else it.cur)
        return JSONObject()
            .put("id", it.id).put("title", it.title).put("url", it.url).put("photo", it.photo)
            .put("brand", it.brand).put("status", it.status).put("seller", it.seller)
            .put("country", it.country).put("search", s.optString("name"))
            .put("price", it.price).put("cur", it.cur).put("price_label", priceLabel)
            .put("price_huf", Math.round(price)).put("total_huf", Math.round(total))
            .put("median_huf", med?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("ratio", ratio?.let { Math.round(it * 1000) / 1000.0 } ?: JSONObject.NULL)
            .put("rating", rating).put("rating_label", label)
            .put("warnings", warnings).put("found", now())
            .put("cond_rank", conditionRank(it.status)).put("best", false)
            .put("target_huf", if (target > 0) Math.round(target) else JSONObject.NULL)
    }

    private fun process(country: String, s: JSONObject, q: String, items: List<JSONObject>, client: Client?, id: Int) {
        val key = "$country|${s.optString("name")}|$q"
        val target = s.optDouble("target_price_huf", 0.0)
        // 1. lépés (zárolva): új hirdetések kiértékelése
        val cands = ArrayList<JSONObject>()
        // Ezt a kulcsot (ország|keresés|szó) csak ennek az országnak a szála írja, így a két lépés között nem változhat.
        val seeded = synchronized(lock) { seen.containsKey(key) }
        synchronized(lock) {
            val sq = seen.getOrPut(key) { ArrayDeque() }
            val sset = HashSet(sq)
            val h = hist.getOrPut(key) { ArrayDeque() }
            val warn = strList(cfg.optJSONArray("warn_words"))
            val minHist = cfg.optInt("min_history", 15)
            for (raw in items.reversed()) {
                val it = normalize(raw, country)
                if (it.id.isEmpty() || it.id in sset) continue
                sq.addLast(it.id); sset.add(it.id); while (sq.size > 3000) sq.removeFirst()
                if (!seeded && target <= 0) {
                    landed(it)?.let { (price, total) ->
                        if (textOk(it.title, s) && price >= s.optDouble("min_price_huf", 0.0)) {
                            h.addLast(total); while (h.size > 300) h.removeFirst()
                        }
                    }
                    continue
                }
                if (seeded) newItems++
                score(it, s, h, minHist, warn)?.let { rec -> cands.add(rec) }
            }
        }
        if (cands.isEmpty()) return
        // 2. lépés (zárolás nélkül): csak a megadott országok eladóitól jöhet hirdetés
        val allowed = synchronized(lock) { strList(cfg.optJSONArray("allowed_seller_countries")).map { it.uppercase() }.toSet() }
            .ifEmpty { setOf("HU", "PL", "RO") }
        val kept = ArrayList<JSONObject>()
        for (rec in cands) {
            val seller = rec.optString("seller")
            var cc = synchronized(lock) { sellers[seller] }
            if (cc == null && client != null) {
                cc = client.sellerCountry(seller)
                if (cc != null) synchronized(lock) { sellers[seller] = cc }
                sleep(0.4, id)
            }
            if (cc != null && cc in allowed) { rec.put("seller_country", cc); kept.add(rec) }
            else synchronized(lock) { foreignSkipped++ }
        }
        // 3. lépés (zárolva): lista, akciók, értesítés
        val notify = ArrayList<JSONObject>()
        synchronized(lock) {
            val blocked = strList(cfg.optJSONArray("blocked_sellers")).toSet()
            val thr = s.optDouble("deal_threshold", cfg.optDouble("deal_threshold", 0.65))
            val app = cfg.optJSONObject("app") ?: JSONObject()
            val minCond = app.optInt("min_condition", 3)
            val mode = app.optString("notify_mode", "best")
            for (rec in kept) {
                if (seeded) { feed.addFirst(rec); while (feed.size > MAX_FEED) feed.removeLast() }
                val isDeal = if (target > 0) rec.getDouble("total_huf") <= target
                             else rec.opt("ratio").let { r -> r is Number && r.toDouble() <= thr }
                if (!isDeal || rec.optString("seller").lowercase() in blocked || rec.getInt("cond_rank") < minCond) continue
                if (!alerted.add(rec.optString("id"))) continue
                while (alerted.size > 20000) alerted.remove(alerted.first())
                val best = bestTotal(s.optString("name"))
                rec.put("best", best == null || rec.getDouble("total_huf") < best)
                rec.put("first_round", !seeded)
                deals.add(0, rec); while (deals.size > MAX_DEALS) deals.removeAt(deals.size - 1)
                seq++
                if (seeded && (mode == "all" || rec.getBoolean("best"))) notify.add(rec)
            }
        }
        if (notify.isNotEmpty()) {
            saveDeals()
            if (!focused) notify.forEach { notifier?.invoke(it) }
        }
    }

    private val TARGET_SUFFIX = Regex("""\s+\d{1,3}(?:\s\d{3})*\s*Ft alatt$""")

    /** Rövid értesítés, pl. „85 000 Ft · RTX 4070 · Nagyon jó · −45%”. */
    fun notificationText(rec: JSONObject): Pair<String, String> {
        val parts = arrayListOf(rec.optString("price_label"), TARGET_SUFFIX.replace(rec.optString("search"), "").trim())
        rec.optString("status").let { if (it.isNotEmpty()) parts.add(it) }
        val ratio = rec.opt("ratio")
        if (ratio is Number && ratio.toDouble() < 1) parts.add("−" + Math.round((1 - ratio.toDouble()) * 100) + "%")
        val ref = when {
            rec.opt("target_huf") is Number -> "Célár: " + fmt(rec.getDouble("target_huf")) + " Ft, díjjal " + fmt(rec.getDouble("total_huf")) + " Ft"
            rec.opt("median_huf") is Number -> "Piaci ár kb. " + fmt(rec.getDouble("median_huf")) + " Ft"
            else -> "Díjjal együtt " + fmt(rec.getDouble("total_huf")) + " Ft"
        }
        return parts.joinToString(" · ") to (rec.optString("title") + "\n" + ref + " · " + rec.optString("country").uppercase())
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
                    val items = cl.search(job.second, id)
                    if (items.isEmpty()) empty++
                    process(country, job.first, job.second, items, cl, id)
                    synchronized(lock) {
                        checked++
                        st.put("items", st.optInt("items", 0) + items.size)
                        if (firstRound) st.put("state", "ok").put("progress", "${n + 1}/${jobs.size}")
                            .put("msg", "Első kör: ${n + 1}/${jobs.size} keresés, ${st.optInt("items")} hirdetés beolvasva")
                    }
                    sleep(gap + Math.random() * 0.7, id)
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

    // ------------------------------------------------------------ felületnek
    fun snapshot(): String = synchronized(lock) {
        val counts = JSONObject()
        deals.forEach { counts.put(it.optString("search"), counts.optInt(it.optString("search"), 0) + 1) }
        val st = JSONObject(); status.forEach { (k, v) -> st.put(k, v) }
        JSONObject()
            .put("running", running).put("status", st)
            .put("deals", JSONArray(deals)).put("feed", JSONArray(feed.toList()))
            .put("counts", counts).put("seq", seq).put("now", now())
            .put("stats", JSONObject().put("checked", checked).put("new_items", newItems).put("foreign_skipped", foreignSkipped)
                .put("started", if (running) started else JSONObject.NULL))
            .toString()
    }

    fun hideDeal(id: String) { synchronized(lock) { deals.removeAll { it.optString("id") == id } }; saveDeals() }

    fun blockSeller(seller: String) {
        synchronized(lock) {
            val a = cfg.optJSONArray("blocked_sellers") ?: JSONArray().also { cfg.put("blocked_sellers", it) }
            if (strList(a).none { it == seller.lowercase() }) a.put(seller)
            deals.removeAll { it.optString("seller") == seller }
            writeAtomic("config.json", cfg.toString(2))
        }
        saveDeals()
    }

    fun clearDeals() { synchronized(lock) { deals.clear() }; saveDeals() }
}
