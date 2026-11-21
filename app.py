# -*- coding: utf-8 -*-
"""
Vinted IT-figyelő – Windows asztali app.

A figyelő motor (vinted_bot.py) köré épített ablakos felület (pywebview +
Edge WebView2). A találatok kártyákon jelennek meg, a háttérben futó
figyelés pedig Windows-értesítést küld, ha az app nincs előtérben.

Indítás fejlesztéshez:  python app.py
Önteszt (ablak nélkül): python app.py --selftest
"""
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import webbrowser
from collections import deque
from datetime import datetime

import vinted_bot as core

APP_NAME = "Vinted IT-figyelő"
DEALS_PATH = os.path.join(core.BASE, "akciok.json")
WATCH_PATH = os.path.join(core.BASE, "figyelolista.json")
HISTORY_PATH = os.path.join(core.BASE, "arnaplo.json")
WATCH_EVERY_SEC = 10 * 60
MAX_DEALS = 400
MAX_FEED = 150

DEFAULT_WARN_WORDS = ["doboz", "box", "pudełko", "cutie", "cutia", "fotó", "foto",
                      "zdjęcie", "photo", "imagine", "replika", "replica", "copy"]


def _load_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return default


def _save_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


def translate_hu(texts):
    """Címek magyarra a Google Fordító ingyenes végpontjával (soronként egy cím, egy kérésben).
    shortcut: nem hivatalos végpont, ha megszűnik, a címek eredeti nyelven maradnak."""
    q = "\n".join(t.replace("\n", " ") for t in texts)
    url = "https://translate.googleapis.com/translate_a/single?" + core.urllib.parse.urlencode(
        {"client": "gtx", "sl": "auto", "tl": "hu", "dt": "t", "q": q})
    req = core.urllib.request.Request(url, headers={"User-Agent": core.UA})
    with core.urllib.request.urlopen(req, timeout=15) as r:
        data = json.loads(r.read().decode("utf-8"))
    out = "".join(seg[0] for seg in data[0] if seg and seg[0]).split("\n")
    if len(out) != len(texts):  # a sortörés elcsúszott: egyesével
        return texts if len(texts) == 1 else [translate_hu([t])[0] for t in texts]
    return [o.strip() for o in out]


def ui_path():
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, "app_ui.html")


# Állapot-rangsor a Vinted HU/PL/RO/EN feliratai alapján (nagyobb = jobb).
# A sorrend számít: a hosszabb kifejezés ("nagyon jó") előbb jön, mint a rövidebb ("jó").
CONDITION_WORDS = [
    (5, ["új", "nowy", "nou", "new"]),
    (4, ["kiváló", "doskonały", "excelent", "excellent"]),
    (4, ["nagyon jó", "bardzo dobry", "foarte bun", "very good"]),
    (2, ["megfelelő", "zadowalający", "satisfăcător", "satisfacator", "satisfactory"]),
    (3, ["jó", "dobry", "bun", "good"]),
]
CONDITION_NAMES = {5: "Új", 4: "Nagyon jó vagy jobb", 3: "Jó vagy jobb", 2: "Megfelelő vagy jobb", 0: "Bármilyen"}


def condition_rank(status):
    s = (status or "").lower()
    if not s:
        return 3  # ismeretlen állapot: nem szűrjük ki
    for rank, words in CONDITION_WORDS:
        if any(w in s for w in words):
            return rank
    return 3


# Vinted állapotfeliratok magyarra (PL/RO/EN). A sorrend számít: a hosszabb előbb.
STATUS_HU = [
    (["címkével", "z metką", "cu etichet", "with tags"], "Új, címkével"),
    (["címke nélkül", "bez metki", "fără etichet", "fara etichet", "without tags"], "Új, címke nélkül"),
    (["bardzo dobry", "foarte bun", "very good"], "Nagyon jó"),
    (["zadowalający", "satisf"], "Megfelelő"),
    (["dobry", "bun", "good"], "Jó"),
    (["nowy", "nou", "new"], "Új"),
]


def status_hu(status):
    s = (status or "").lower()
    return next((hu for words, hu in STATUS_HU if any(w in s for w in words)), status)


def rating_for(ratio, target_hit=False):
    if ratio is None:
        return ("target", "Célár alatt") if target_hit else ("new", "Új minta")
    if ratio <= 0.5:
        return "great", "Kiváló ár"
    if ratio <= 0.7:
        return "good", "Jó ár"
    if ratio <= 0.9:
        return "fair", "Korrekt ár"
    return "high", "Átlag feletti"


def notification_text(rec):
    """Rövid értesítés, pl. „85 000 Ft · RTX 4070 · Nagyon jó · −45%”."""
    name = re.sub(r"\s+\d{1,3}(?:\s\d{3})*\s*Ft alatt$", "", rec["search"]).strip()
    parts = [rec["price_label"], name]
    if rec["status"]:
        parts.append(rec["status"])
    if rec.get("ratio") is not None and rec["ratio"] < 1:
        parts.append(f"−{round((1 - rec['ratio']) * 100)}%")
    if rec.get("drop_from"):
        parts.insert(0, "Árcsökkenés")
    if rec.get("target_huf"):
        ref = f"Célár: {core.fmt_num(rec['target_huf'])} Ft, díjjal {core.fmt_num(rec['total_huf'])} Ft"
    elif rec.get("median_huf"):
        ref = f"Piaci ár kb. {core.fmt_num(rec['median_huf'])} Ft"
    else:
        ref = f"Díjjal együtt {core.fmt_num(rec['total_huf'])} Ft"
    return " · ".join(parts), f"{rec['title']}\n{ref} · {rec['country'].upper()}"


# ---------------------------------------------------------------- Windows-értesítés
def windows_toast(title, body):
    """Natív Windows értesítés PowerShellen keresztül, külső csomag nélkül."""
    if os.name != "nt":
        return
    esc = lambda s: (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                     .replace('"', "&quot;").replace("'", "&apos;"))
    xml = (f"<toast><visual><binding template='ToastGeneric'>"
           f"<text>{esc(title)}</text><text>{esc(body)}</text>"
           f"</binding></visual></toast>")
    ps = (
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] > $null;"
        "[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType=WindowsRuntime] > $null;"
        "$x = New-Object Windows.Data.Xml.Dom.XmlDocument;"
        f"$x.LoadXml('{xml.replace(chr(39), chr(39) * 2)}');"
        "$t = [Windows.UI.Notifications.ToastNotification]::new($x);"
        "$id = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';"
        "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($id).Show($t);"
    )
    try:
        subprocess.Popen(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps],
                         creationflags=0x08000000)  # CREATE_NO_WINDOW
    except Exception:
        pass


# ---------------------------------------------------------------- Motor
class Engine:
    def __init__(self):
        self.lock = threading.RLock()
        self.cfg = self._load_config()
        self.rates = {"HUF": 1.0, **{k.upper(): float(v) for k, v in self.cfg.get("to_huf", {}).items()}}
        self.rates_info = {"live": False, "date": None, "time": None}
        self.state = core.State(core.STATE_PATH)
        self.deals = self._load_deals()
        self.watch = _load_json(WATCH_PATH, [])
        self.history = _load_json(HISTORY_PATH, {})
        self.last_total = {}  # hirdetés id -> utoljára látott teljes ár (Ft), az árcsökkenéshez
        self.tr_cache = {}
        self.feed = deque(maxlen=MAX_FEED)
        self.status = {c: {"state": "stopped", "msg": "", "last_ok": None, "round_sec": None}
                       for c in core.DOMAINS}
        self.stop_event = threading.Event()
        self.threads = []
        self.focused = True
        self.seq = 0
        self.stats = {"checked": 0, "new_items": 0, "started": None}

    # -- konfiguráció
    def _load_config(self):
        with open(core.CONFIG_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
        cfg.setdefault("blocked_sellers", [])
        cfg.setdefault("allowed_seller_countries", ["HU", "PL", "RO"])
        cfg.setdefault("warn_words", DEFAULT_WARN_WORDS)
        app_cfg = cfg.setdefault("app", {})
        app_cfg.setdefault("windows_notify", True)
        app_cfg.setdefault("sound", True)
        app_cfg.setdefault("notify_mode", "best")   # "best": csak a keresés legolcsóbb akciójáról
        app_cfg.setdefault("min_condition", 3)      # 3 = "Jó" vagy jobb
        app_cfg.setdefault("translate_titles", True)
        app_cfg.pop("only_great", None)
        for s in cfg.get("searches", []):
            s.setdefault("enabled", True)
        return cfg

    def save_config(self, cfg):
        with self.lock:
            self.cfg = cfg
            tmp = core.CONFIG_PATH + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(cfg, f, ensure_ascii=False, indent=2)
            os.replace(tmp, core.CONFIG_PATH)

    def _load_deals(self):
        try:
            with open(DEALS_PATH, encoding="utf-8") as f:
                # Csak az ellenőrzött országú eladók akciói maradnak (a régiek között külföldi is lehetett).
                deals = [d for d in json.load(f) if d.get("seller_country")][:MAX_DEALS]
            for d in deals:
                d["status"] = status_hu(d.get("status"))
            return deals
        except Exception:
            return []

    def _save_deals(self):
        with self.lock:
            data = list(self.deals)
        tmp = DEALS_PATH + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
        os.replace(tmp, DEALS_PATH)

    # -- vezérlés
    @property
    def running(self):
        return any(t.is_alive() for t in self.threads)

    def start(self):
        if self.running:
            return
        self.stop_event = threading.Event()
        for loop in (self._hourly_loop, self._watch_loop, self._translate_loop):
            threading.Thread(target=loop, args=(self.stop_event,), daemon=True).start()
        countries = [c for c in self.cfg.get("countries", ["hu", "pl", "ro"]) if c in core.DOMAINS]
        self.stats["started"] = time.time()
        self.threads = []
        for c in countries:
            self.status[c] = {"state": "starting", "msg": "Kapcsolódás…", "last_ok": None, "round_sec": None}
            t = threading.Thread(target=self._worker, args=(c, self.stop_event), daemon=True)
            t.start()
            self.threads.append(t)

    def stop(self):
        self.stop_event.set()
        for c in self.status:
            self.status[c]["state"] = "stopped"
            self.status[c]["msg"] = ""
        threading.Thread(target=self.state.save, daemon=True).start()

    def refresh_rates(self):
        try:
            r = core.load_rates(self.cfg)
            with self.lock:
                # sikertelen lekérésnél ne írjuk felül a korábbi élő árfolyamot a mentettel
                if core.RATES_INFO["live"] or not self.rates_info.get("live"):
                    self.rates.update(r)
                    self.rates_info = dict(core.RATES_INFO)
                else:
                    self.rates_info["error_time"] = time.time()
        except Exception:
            pass
        return self.rates_info

    # -- figyelés
    def _worker(self, country, stop):
        client = None
        while not stop.is_set():
            try:
                if client is None:
                    self.status[country].update(state="starting", msg="Kapcsolódás…")
                    client = core.VintedClient(country)
                    client.on_wait = lambda sec, code, c=country: self._on_wait(c, sec, code)
                t0 = time.time()
                with self.lock:
                    searches = [s for s in self.cfg.get("searches", []) if s.get("enabled", True)]
                    gap = float(self.cfg.get("request_gap_sec", 1.5))
                jobs = [(s, q) for s in searches for q in core.queries_for(s, country)]
                st = self.status[country]
                first_round = st.get("last_ok") is None
                empty = 0
                for n, (search, q) in enumerate(jobs, 1):
                    if stop.is_set():
                        return
                    t_req = time.time()
                    items = client.search(q)
                    if st.get("state") == "wait":
                        st.update(state="ok", msg="")
                    empty += 0 if items else 1
                    self._prefetch_countries(country, search, q, items, client)
                    self._process(country, search, q, items)
                    self.stats["checked"] += 1
                    st["items"] = st.get("items", 0) + len(items)
                    if first_round:
                        st.update(state="ok", progress=f"{n}/{len(jobs)}",
                                  msg=f"Első kör: {n}/{len(jobs)} keresés, {st['items']} hirdetés beolvasva")
                    # a szünet a kérés indulásától számít, a letöltés/feldolgozás ideje beleszámít
                    stop.wait(max(0.0, gap + random.uniform(0, 0.7) - (time.time() - t_req)))
                if jobs and empty == len(jobs):
                    st.update(state="error", msg="A Vinted minden keresésre üres oldalt adott. "
                                                  "Lehet, hogy átalakították az oldalt, frissítés kellhet.")
                    stop.wait(30)
                    continue
                st.update(state="ok", msg="", progress=None, last_ok=time.time(),
                          round_sec=round(time.time() - t0, 1))
                self.state.save()
                stop.wait(float(self.cfg.get("poll_interval_sec", 5)))
            except Exception as e:
                msg = str(e)
                if "403" in msg:
                    msg = "A Vinted ideiglenesen blokkolja a lekérést (403). Lassabb tempóval újrapróbálom."
                elif "429" in msg:
                    msg = "Túl sok kérés (429). Várok, aztán folytatom."
                elif "404" in msg:
                    msg = "A Vinted megváltoztatta az oldal címét (404). Frissítés kellhet."
                elif "timed out" in msg or "urlopen error" in msg:
                    msg = "Nincs kapcsolat a Vinteddel. Ellenőrizd az internetet."
                self.status[country].update(state="error", msg=msg)
                client = None
                stop.wait(20)

    def _on_wait(self, country, sec, code):
        self.stats["waits"] = self.stats.get("waits", 0) + 1
        why = "Túl sok kérés (429)" if code == 429 else f"A Vinted elutasította a kérést ({code})"
        self.status[country].update(state="wait", msg=f"{why}, {sec} mp szünet, utána folytatja")

    # -- óránkénti feladatok: árfolyam + piaci ár napló
    def _hourly_loop(self, stop):
        while not stop.is_set():
            self.refresh_rates()
            self._log_medians()
            stop.wait(3600)  # az ECB naponta egyszer frissít, óránként bőven elég

    def _log_medians(self):
        """Keresésenként feljegyzi a piaci árat (az összes ország mediánja), ebből lesz a grafikon."""
        with self.lock:
            min_hist = int(self.cfg.get("min_history", 15))
            now = round(time.time())
            for s in self.cfg.get("searches", []):
                prices = [p for k, h in self.state.hist.items() if k.split("|")[1] == s["name"] for p in h]
                if len(prices) >= min_hist:
                    pts = self.history.setdefault(s["name"], [])
                    pts.append([now, round(sorted(prices)[len(prices) // 2])])
                    del pts[:-2000]  # ~3 hónap óránként
            _save_json(HISTORY_PATH, self.history)

    def price_history(self, name):
        with self.lock:
            pts = list(self.history.get(name, []))
            deals = sorted((d for d in self.deals if d["search"] == name), key=lambda d: d["found"])
            return {"points": pts, "deals": [[round(d["found"]), d["total_huf"]] for d in deals]}

    # -- figyelőlista: eladva / eltűnt / árváltozás
    def _watch_loop(self, stop):
        while not stop.wait(WATCH_EVERY_SEC):
            self.check_watch(stop)

    def check_watch(self, stop):
        with self.lock:
            todo = [w for w in self.watch if not (w.get("gone") or w.get("sold"))]
            gap = float(self.cfg.get("request_gap_sec", 1.5))
        clients = {}
        for w in todo:
            if stop.is_set():
                return
            try:
                c = clients.get(w["country"]) or clients.setdefault(w["country"], core.VintedClient(w["country"]))
                r = c.item_status(w["url"])
            except Exception:
                clients.pop(w["country"], None)
                continue
            if r:
                self._apply_watch(w, r)
            stop.wait(gap + random.uniform(0, 0.7))

    def _apply_watch(self, w, r):
        toast = None
        with self.lock:
            w["checked"] = time.time()
            if r["gone"] or r["closed"]:
                w["sold"] = r["closed"]
                w["gone"] = r["gone"]
                toast = ("Eladták" if r["closed"] else "Eltűnt a hirdetés", w["title"])
            else:
                w["reserved"] = r["reserved"]
                if r["price"] and r["price"] != w.get("now_price", w["price"]):
                    prev = w.get("now_price", w["price"])
                    w["now_price"] = r["price"]
                    w["now_label"] = f"{core.fmt_num(r['price'])} {'Ft' if w['cur'] == 'HUF' else w['cur']}"
                    if r["price"] < prev:
                        toast = (f"Árcsökkenés: {w['now_label']}", w["title"])
            self.seq += 1
        self._save_watch()
        if toast and self.cfg.get("app", {}).get("windows_notify", True):
            windows_toast(*toast)

    def _save_watch(self):
        with self.lock:
            data = list(self.watch)
        _save_json(WATCH_PATH, data)

    # -- címek fordítása magyarra
    def _translate_loop(self, stop):
        while not stop.wait(4):
            if not self.cfg.get("app", {}).get("translate_titles", True):
                continue
            with self.lock:
                recs = [d for d in list(self.deals) + list(self.feed) + self.watch
                        if d["country"] != "hu" and "title_hu" not in d]
            todo = list(dict.fromkeys(d["title"] for d in recs if d["title"] not in self.tr_cache))[:25]
            if todo:
                try:
                    self.tr_cache.update(zip(todo, translate_hu(todo)))
                except Exception:
                    stop.wait(60)  # a fordító nem elérhető, később újra
                    continue
            changed = 0
            with self.lock:
                warn_words = [w.lower() for w in self.cfg.get("warn_words", [])]
                for d in recs:
                    if d["title"] in self.tr_cache:
                        d["title_hu"] = self.tr_cache[d["title"]]
                        changed += 1
                        # pl. „Krabička” = „Doboz”: a figyelmeztető szavak a fordításban is számítanak
                        hit = next((w for w in warn_words if w in d["title_hu"].lower()), None)
                        if hit and not any(hit in x for x in d["warnings"]):
                            d["warnings"].append(f"A címben szerepel: „{hit}”")
                self.seq += 1 if changed else 0
            if changed:
                self._save_deals()
                self._save_watch()

    def _prefetch_countries(self, country, search, q, items, client):
        """A szóba jöhető új hirdetések eladóinak országát lekéri a profiloldalukról.
        Zárolás nélkül fut (hogy a felület ne akadjon meg), és megjegyzi az eladókat."""
        if client is None or not hasattr(client, "seller_country"):
            return
        key = f"{country}|{search['name']}|{q}"
        with self.lock:
            seen_set = set(self.state.seen.get(key, ()))
            seeded = key in self.state.seen
        target = search.get("target_price_huf")
        mx = search.get("max_price_huf")
        todo = []
        for raw in items:
            it = core.normalize(raw, country)
            seller = it["seller"]
            if not seller or seller in self.state.sellers or seller in todo:
                continue
            price, total = self._landed_huf(it)
            if price is None or not core.text_ok(it["title"], search, self.cfg):
                continue
            if price < search.get("min_price_huf", 0) or (mx and price > mx):
                continue
            if it["id"] in seen_set:
                old = self.last_total.get(it["id"])
                if not (seeded and old and total and total <= old * 0.95):
                    continue
            elif not seeded and not (target and total <= target):
                continue
            todo.append(seller)
        for seller in todo:
            cc = client.seller_country(seller)
            if cc:
                with self.state.lock:
                    self.state.sellers[seller] = cc
            time.sleep(0.4)

    def _process(self, country, search, q, items):
        key = f"{country}|{search['name']}|{q}"
        notify = []
        with self.lock:
            seeded = key in self.state.seen
            seen = self.state.seen_for(key)
            seen_set = set(seen)
            hist = self.state.hist_for(key)
            blocked = set(map(str, self.cfg.get("blocked_sellers", [])))
            warn_words = [w.lower() for w in self.cfg.get("warn_words", [])]
            min_hist = int(self.cfg.get("min_history", 15))
            thr = float(search.get("deal_threshold", self.cfg.get("deal_threshold", 0.65)))
            min_cond = int(search.get("min_condition", self.cfg.get("app", {}).get("min_condition", 3)))  # keresésenként felülírható
            mode = self.cfg.get("app", {}).get("notify_mode", "best")
            allowed = {c.upper() for c in self.cfg.get("allowed_seller_countries", ["HU", "PL", "RO"])}
            if len(self.last_total) > 100000:
                self.last_total.clear()  # shortcut: csak memóriában, újraindítás után újratanulja; fájlba, ha kevés lenne
            for raw in reversed(items):
                it = core.normalize(raw, country)
                total = self._landed_huf(it)[1]
                old = self.last_total.get(it["id"])
                if total:
                    self.last_total[it["id"]] = total
                drop_from = None
                if it["id"] in seen_set:
                    # Már látott hirdetés: csak akkor érdekes, ha legalább 5%-kal olcsóbb lett.
                    if not (seeded and old and total and total <= old * 0.95):
                        continue
                    drop_from = old
                else:
                    seen.append(it["id"])
                    seen_set.add(it["id"])
                target = search.get("target_price_huf")
                if not seeded and not target:
                    self._add_hist(it, search, hist)
                    continue
                if seeded and not drop_from:
                    self.stats["new_items"] += 1
                rec = self._score(it, search, hist, min_hist, warn_words, add_hist=not drop_from)
                if rec is None:
                    continue
                # Csak a megadott országok (alapból HU, PL, RO) eladóitól jöhet hirdetés.
                cc = self.state.sellers.get(it["seller"])
                if cc not in allowed:
                    self.stats["foreign_skipped"] = self.stats.get("foreign_skipped", 0) + 1
                    continue
                rec["seller_country"] = cc
                if drop_from:
                    rec["drop_from"] = round(drop_from)
                    rec["warnings"].insert(0, f"Árcsökkenés: {core.fmt_num(drop_from)} Ft → {core.fmt_num(rec['total_huf'])} Ft")
                if seeded:
                    self.feed.appendleft(rec)
                    self.seq += 1
                # Akció: célár alatt (ha van célár), különben a piaci (medián) ár alatt a küszöb szerint.
                if target:
                    is_deal = rec["total_huf"] <= target
                else:
                    is_deal = rec["ratio"] is not None and rec["ratio"] <= thr
                if not is_deal or it["seller"] in blocked or rec["cond_rank"] < min_cond:
                    continue
                # árcsökkenésnél az új ár számít új jelzésnek, ugyanarra az árra nem szólunk kétszer
                if not self.state.mark_alerted(f"{it['id']}@{rec['total_huf']}" if drop_from else it["id"]):
                    continue
                best = self._best_total(search["name"])
                rec["best"] = best is None or rec["total_huf"] < best
                rec["first_round"] = not seeded
                self.deals = [d for d in self.deals if d["id"] != it["id"]]
                self.deals.insert(0, rec)
                del self.deals[MAX_DEALS:]
                self.seq += 1
                # Az első kör (már fent lévő hirdetések) csak a listába kerül, értesítés az újakról jön.
                if seeded and (mode == "all" or rec["best"]):
                    notify.append(rec)
        if notify:
            self._save_deals()
            app_cfg = self.cfg.get("app", {})
            for rec in notify:
                if app_cfg.get("windows_notify", True) and not self.focused:
                    title, body = notification_text(rec)
                    windows_toast(title, body)

    def _best_total(self, search_name, window_sec=24 * 3600):
        """A keresés eddigi legolcsóbb akciója (teljes ár, Ft) az elmúlt 24 órából."""
        cutoff = time.time() - window_sec
        totals = [d["total_huf"] for d in self.deals
                  if d["search"] == search_name and d["found"] >= cutoff]
        return min(totals) if totals else None

    def _landed_huf(self, it):
        rate = self.rates.get(it["cur"])
        if not rate or it["price"] is None:
            return None, None
        price = it["price"] * rate
        total = (it["total"] or it["price"]) * rate
        return price, total

    def _add_hist(self, it, search, hist):
        price, total = self._landed_huf(it)
        if price is None or not core.text_ok(it["title"], search, self.cfg):
            return
        if price < search.get("min_price_huf", 0):
            return
        hist.append(total)

    def _score(self, it, search, hist, min_hist, warn_words, add_hist=True):
        price, total = self._landed_huf(it)
        if price is None or not core.text_ok(it["title"], search, self.cfg):
            return None
        if price < search.get("min_price_huf", 0):
            return None
        med = sorted(hist)[len(hist) // 2] if len(hist) >= min_hist else None
        if add_hist:
            hist.append(total)
        mx = search.get("max_price_huf")
        if mx and price > mx:
            return None
        ratio = total / med if med else None
        target = search.get("target_price_huf")
        rating, rating_label = rating_for(ratio, bool(target and total <= target))
        title_l = it["title"].lower()
        warnings = []
        if ratio is not None and ratio < 0.3:
            warnings.append("Gyanúsan olcsó, nézd meg a leírást")
        hit = next((w for w in warn_words if w in title_l), None)
        if hit:
            warnings.append(f"A címben szerepel: „{hit}”")
        cur = it["cur"]
        price_label = f"{core.fmt_num(it['price'])} {'Ft' if cur == 'HUF' else cur}"
        return {
            "id": it["id"], "title": it["title"], "url": it["url"], "photo": it["photo"],
            "brand": it["brand"], "status": status_hu(it["status"]), "seller": it["seller"],
            "country": it["country"], "search": search["name"],
            "price": it["price"], "cur": cur, "price_label": price_label,
            "price_huf": round(price), "total_huf": round(total),
            "median_huf": round(med) if med else None,
            "ratio": round(ratio, 3) if ratio is not None else None,
            "rating": rating, "rating_label": rating_label,
            "warnings": warnings, "found": time.time(),
            "cond_rank": condition_rank(it["status"]), "best": False,
            "target_huf": search.get("target_price_huf"),
        }

    # -- UI-nak
    def snapshot(self, seq=None):
        """A listákat csak akkor küldi, ha változtak (seq), így a másodpercenkénti
        frissítés nem utaztat át minden alkalommal 400 akciót a felületre."""
        with self.lock:
            snap = {
                "running": self.running,
                "status": self.status,
                "stats": self.stats,
                "seq": self.seq,
                "now": time.time(),
                "rates": {k: round(v, 2) for k, v in self.rates.items() if k != "HUF"},
                "rates_info": self.rates_info,
            }
            if seq != self.seq:
                # másolat: a fordító/figyelő szálak közben új mezőket írhatnak a rekordokba
                snap["deals"] = [dict(d) for d in self.deals]
                snap["feed"] = [dict(d) for d in self.feed]
                snap["watch"] = [dict(d) for d in self.watch]
            return snap


# ---------------------------------------------------------------- JS API
class Api:
    def __init__(self, engine):
        self._e = engine

    def snapshot(self, seq=None):
        return self._e.snapshot(seq)

    def refresh_rates(self):
        self._e.refresh_rates()
        return self._e.snapshot(self._e.seq)

    def price_history(self, name):
        return self._e.price_history(name)

    def watch_add(self, item_id):
        e = self._e
        with e.lock:
            d = next((x for x in list(e.deals) + list(e.feed) if x["id"] == item_id), None)
            if not d or any(w["id"] == item_id for w in e.watch):
                return False
            e.watch.insert(0, {**d, "saved_at": time.time()})
            e.seq += 1
        e._save_watch()
        return True

    def watch_remove(self, item_id):
        with self._e.lock:
            self._e.watch = [w for w in self._e.watch if w["id"] != item_id]
            self._e.seq += 1
        self._e._save_watch()
        return True

    def watch_check(self):
        """Azonnali ellenőrzés a felület gombjáról (háttérben)."""
        threading.Thread(target=self._e.check_watch, args=(threading.Event(),), daemon=True).start()
        return True

    def start(self):
        self._e.start()
        return True

    def stop(self):
        self._e.stop()
        return True

    def get_config(self):
        return self._e.cfg

    def save_config(self, cfg):
        was = self._e.running
        self._e.save_config(cfg)
        if was:
            self._e.stop()
            time.sleep(0.3)
            self._e.start()
        return True

    def open_url(self, url):
        if isinstance(url, str) and url.startswith("https://www.vinted."):
            webbrowser.open(url)
        return True

    def hide_deal(self, item_id):
        with self._e.lock:
            self._e.deals = [d for d in self._e.deals if d["id"] != item_id]
            self._e.seq += 1
        self._e._save_deals()
        return True

    def block_seller(self, seller_id):
        if not seller_id:
            return False
        cfg = dict(self._e.cfg)
        blocked = list(map(str, cfg.get("blocked_sellers", [])))
        if seller_id not in blocked:
            blocked.append(seller_id)
        cfg["blocked_sellers"] = blocked
        self._e.save_config(cfg)
        with self._e.lock:
            self._e.deals = [d for d in self._e.deals if d.get("seller") != seller_id]
            self._e.seq += 1
        self._e._save_deals()
        return True

    def clear_deals(self):
        with self._e.lock:
            self._e.deals = []
            self._e.seq += 1
        self._e._save_deals()
        return True

    def set_focus(self, focused):
        self._e.focused = bool(focused)
        return True

    def test_notification(self):
        windows_toast("Próba értesítés", "Így fog kinézni, ha akciót talál a figyelő.")
        return True


# ---------------------------------------------------------------- Belépés
def selftest():
    """CI-ben fut: ellenőrzi, hogy a csomagolt app minden része megvan."""
    assert os.path.exists(ui_path()), "hiányzik az app_ui.html"
    html = ('<script>self.__next_f.push([1,' + json.dumps(
        '{"items":[{"id":1,"productItem":{"id":1,"title":"RTX 3060","url":"/items/1-x",'
        '"price":{"amount":"50000.00","currencyCode":"HUF"},"user":{"id":7}}}]}') + '])</script>')
    items = core.extract_items(html)
    assert len(items) == 1 and core.normalize(items[0], "hu")["seller"] == "7"
    import webview  # noqa: F401  (a csomagban benne kell lennie)
    if os.name == "nt":
        import clr  # noqa: F401  (a pywebview Windows-ablaka erre épül)
    print("selftest OK")


def _unblock_bundle():
    """A letöltött ZIP-ből kicsomagolt fájlokat a Windows „internetről jött”-nek jelöli
    (Zone.Identifier), és a .NET ezért nem tölti be a Python.Runtime.dll-t: az ablak el sem indul
    („Failed to resolve Python.Runtime.Loader.Initialize”). Indításkor levesszük a jelölést."""
    if os.name != "nt" or not getattr(sys, "frozen", False):
        return
    root = os.path.dirname(os.path.abspath(sys.executable))
    for d, _, files in os.walk(root):
        for f in files:
            try:
                os.remove(os.path.join(d, f) + ":Zone.Identifier")
            except OSError:
                pass


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
    _unblock_bundle()
    import webview
    if not os.path.exists(core.CONFIG_PATH):
        webview.create_window(APP_NAME, html=(
            "<body style='font:15px Segoe UI;padding:32px'>"
            f"<h2>Nincs config.json</h2><p>Tedd a config.json fájlt ebbe a mappába:<br><b>{core.BASE}</b>"
            "</p><p>Utána indítsd újra az appot.</p></body>"), width=560, height=300)
        webview.start()
        return
    engine = Engine()
    api = Api(engine)
    webview.create_window(APP_NAME, url=ui_path(), js_api=api,
                          width=1320, height=860, min_size=(960, 620),
                          background_color="#F4F5F7")
    engine.start()
    try:
        webview.start(debug="--debug" in sys.argv)
    finally:
        engine.stop()
        engine.state.save()


if __name__ == "__main__":
    main()
