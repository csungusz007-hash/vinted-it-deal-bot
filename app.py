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
MAX_DEALS = 400
MAX_FEED = 150

DEFAULT_WARN_WORDS = ["doboz", "box", "pudełko", "cutie", "cutia", "fotó", "foto",
                      "zdjęcie", "photo", "imagine", "replika", "replica", "copy"]


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
        self.state = core.State(core.STATE_PATH)
        self.deals = self._load_deals()
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
        cfg.setdefault("warn_words", DEFAULT_WARN_WORDS)
        app_cfg = cfg.setdefault("app", {})
        app_cfg.setdefault("windows_notify", True)
        app_cfg.setdefault("sound", True)
        app_cfg.setdefault("notify_mode", "best")   # "best": csak a keresés legolcsóbb akciójáról
        app_cfg.setdefault("min_condition", 3)      # 3 = "Jó" vagy jobb
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
                return json.load(f)[:MAX_DEALS]
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
        threading.Thread(target=self._refresh_rates, daemon=True).start()
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

    def _refresh_rates(self):
        try:
            r = core.load_rates(self.cfg)
            with self.lock:
                self.rates.update(r)
        except Exception:
            pass

    # -- figyelés
    def _worker(self, country, stop):
        client = None
        while not stop.is_set():
            try:
                if client is None:
                    self.status[country].update(state="starting", msg="Kapcsolódás…")
                    client = core.VintedClient(country)
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
                    items = client.search(q)
                    empty += 0 if items else 1
                    self._process(country, search, q, items)
                    self.stats["checked"] += 1
                    st["items"] = st.get("items", 0) + len(items)
                    if first_round:
                        st.update(state="ok", progress=f"{n}/{len(jobs)}",
                                  msg=f"Első kör: {n}/{len(jobs)} keresés, {st['items']} hirdetés beolvasva")
                    stop.wait(gap + random.uniform(0, 0.7))
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
            min_cond = int(self.cfg.get("app", {}).get("min_condition", 3))
            mode = self.cfg.get("app", {}).get("notify_mode", "best")
            for raw in reversed(items):
                it = core.normalize(raw, country)
                if it["id"] in seen_set:
                    continue
                seen.append(it["id"])
                seen_set.add(it["id"])
                target = search.get("target_price_huf")
                if not seeded and not target:
                    self._add_hist(it, search, hist)
                    continue
                if seeded:
                    self.stats["new_items"] += 1
                rec = self._score(it, search, hist, min_hist, warn_words)
                if rec is None:
                    continue
                if seeded:
                    self.feed.appendleft(rec)
                # Akció: célár alatt (ha van célár), különben a piaci (medián) ár alatt a küszöb szerint.
                if target:
                    is_deal = rec["total_huf"] <= target
                else:
                    is_deal = rec["ratio"] is not None and rec["ratio"] <= thr
                if not is_deal or it["seller"] in blocked or rec["cond_rank"] < min_cond:
                    continue
                if not self.state.mark_alerted(it["id"]):
                    continue
                best = self._best_total(search["name"])
                rec["best"] = best is None or rec["total_huf"] < best
                rec["first_round"] = not seeded
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

    def _score(self, it, search, hist, min_hist, warn_words):
        price, total = self._landed_huf(it)
        if price is None or not core.text_ok(it["title"], search, self.cfg):
            return None
        if price < search.get("min_price_huf", 0):
            return None
        med = sorted(hist)[len(hist) // 2] if len(hist) >= min_hist else None
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
            "brand": it["brand"], "status": it["status"], "seller": it["seller"],
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
    def snapshot(self):
        with self.lock:
            counts = {}
            for d in self.deals:
                counts[d["search"]] = counts.get(d["search"], 0) + 1
            return {
                "running": self.running,
                "status": self.status,
                "deals": self.deals,
                "feed": list(self.feed),
                "counts": counts,
                "stats": self.stats,
                "seq": self.seq,
                "now": time.time(),
            }


# ---------------------------------------------------------------- JS API
class Api:
    def __init__(self, engine):
        self._e = engine

    def snapshot(self):
        return self._e.snapshot()

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
        self._e._save_deals()
        return True

    def clear_deals(self):
        with self._e.lock:
            self._e.deals = []
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
    print("selftest OK")


def main():
    if "--selftest" in sys.argv:
        selftest()
        return
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
