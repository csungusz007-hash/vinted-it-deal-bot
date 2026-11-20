#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Vinted IT-akció figyelő bot – vinted.hu / vinted.pl / vinted.ro

- Folyamatosan figyeli a config.json-ban megadott kereséseket (legújabb hirdetések).
- Minden új hirdetést forintra vált, és összeveti az adott keresés eddigi
  árainak mediánjával. Ha jóval olcsóbb (pl. 35%-kal a medián alatt) és
  a megadott ársávban van, azonnal kiírja: ár, Ft-ban, kedvezmény, link,
  feltöltés ideje másodpercre.
- Opcionális értesítés: Telegram és/vagy Discord.
- Csak a Python standard könyvtárát használja (Python 3.8+).

Indítás:  python vinted_bot.py
Leállítás: Ctrl+C
"""
import csv
import http.cookiejar
import json
import os
import random
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import deque
from datetime import datetime

if getattr(sys, "frozen", False):  # .exe-ként fut (PyInstaller)
    BASE = os.path.dirname(os.path.abspath(sys.executable))
else:
    BASE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE, "config.json")
STATE_PATH = os.path.join(BASE, "state.json")
LOG_PATH = os.path.join(BASE, "talalatok.csv")

DOMAINS = {"hu": "www.vinted.hu", "pl": "www.vinted.pl", "ro": "www.vinted.ro"}
LANG = {"hu": "hu-HU,hu;q=0.9,en;q=0.6",
        "pl": "pl-PL,pl;q=0.9,en;q=0.6",
        "ro": "ro-RO,ro;q=0.9,en;q=0.6"}
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

os.system("")  # ANSI színek bekapcsolása Windows konzolon
C = {"g": "\033[92m", "y": "\033[93m", "c": "\033[96m", "r": "\033[91m",
     "d": "\033[2m", "b": "\033[1m", "x": "\033[0m"}

_print_lock = threading.Lock()


def log(msg):
    with _print_lock:
        print(msg, flush=True)


def now_str():
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def fmt_num(v):
    return f"{v:,.0f}".replace(",", " ")


# ---------------------------------------------------------------- Vinted API
class VintedClient:
    """Egy országhoz tartozó munkamenet (sütik + nem hivatalos JSON API)."""

    SESSION_MAX_AGE = 30 * 60  # fél óránként új süti

    def __init__(self, country):
        self.country = country
        self.host = DOMAINS[country]
        self.cookie_time = 0
        self._new_session()

    def _headers(self, html=False):
        return {
            "User-Agent": UA,
            "Accept": ("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                       if html else "application/json, text/plain, */*"),
            "Accept-Language": LANG[self.country],
            "Referer": f"https://{self.host}/",
        }

    def _new_session(self):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        req = urllib.request.Request(f"https://{self.host}/", headers=self._headers(html=True))
        with self.opener.open(req, timeout=20) as r:
            r.read()
        self.cookie_time = time.time()

    def search(self, text, per_page=96):
        if time.time() - self.cookie_time > self.SESSION_MAX_AGE:
            self._new_session()
        params = {"page": 1, "per_page": per_page, "search_text": text,
                  "order": "newest_first"}
        url = f"https://{self.host}/api/v2/catalog/items?" + urllib.parse.urlencode(params)
        for attempt in range(4):
            try:
                req = urllib.request.Request(url, headers=self._headers())
                with self.opener.open(req, timeout=20) as r:
                    data = json.loads(r.read().decode("utf-8", "replace"))
                return data.get("items", []) or []
            except urllib.error.HTTPError as e:
                if e.code in (401, 403):
                    time.sleep(2 + attempt * 3)
                    self._new_session()
                    continue
                if e.code == 429:
                    wait = 30 * (attempt + 1)
                    log(f"{C['y']}[{self.country.upper()}] Túl sok kérés (429) – várok {wait} mp-et…{C['x']}")
                    time.sleep(wait)
                    continue
                raise
        return []


# ---------------------------------------------------------------- Adatfeldolgozás
def money(v, fallback_cur=None):
    """A Vinted az árat hol szövegként, hol {'amount','currency_code'} objektumként adja."""
    if v is None:
        return None, fallback_cur
    if isinstance(v, dict):
        try:
            return float(v.get("amount")), v.get("currency_code") or fallback_cur
        except (TypeError, ValueError):
            return None, fallback_cur
    try:
        return float(str(v).replace(",", ".")), fallback_cur
    except ValueError:
        return None, fallback_cur


def normalize(raw, country):
    price, cur = money(raw.get("price"), raw.get("currency"))
    total, _ = money(raw.get("total_item_price"), cur)
    photo = raw.get("photo") or {}
    ts = (photo.get("high_resolution") or {}).get("timestamp")
    url = raw.get("url") or f"/items/{raw.get('id')}"
    if url.startswith("/"):
        url = f"https://{DOMAINS[country]}{url}"
    return {
        "id": str(raw.get("id")),
        "title": raw.get("title") or "",
        "price": price,
        "cur": (cur or "").upper(),
        "total": total,
        "url": url,
        "brand": raw.get("brand_title") or "",
        "status": raw.get("status") or "",
        "ts": ts if isinstance(ts, (int, float)) else None,
        "country": country,
    }


def text_ok(title, search, cfg):
    t = title.lower()
    excl = [w.lower() for w in cfg.get("exclude_words", []) + search.get("exclude_words", [])]
    if any(w in t for w in excl):
        return False
    must = search.get("must_contain")
    if must and not any(w.lower() in t for w in must):
        return False
    return True


def evaluate(item, search, cfg, hist, rates):
    """Visszaad (huf, median, ratio)-t ha akció, különben None. A hist-et frissíti."""
    if item["price"] is None or item["price"] <= 0:
        return None
    rate = rates.get(item["cur"])
    if not rate:
        return None
    huf = item["price"] * rate
    if not text_ok(item["title"], search, cfg):
        return None
    if huf < search.get("min_price_huf", 0):
        return None

    med = statistics.median(hist) if len(hist) >= cfg.get("min_history", 15) else None
    hist.append(huf)  # a mediánba csak releváns (szűrőn átment) árak kerülnek

    mx = search.get("max_price_huf")
    if mx and huf > mx:
        return None
    thr = search.get("deal_threshold", cfg.get("deal_threshold", 0.65))
    ratio = huf / med if med else None
    if ratio is not None and ratio > thr:
        return None
    if ratio is None and not mx:
        return None  # még nincs mihez viszonyítani
    return huf, med, ratio


# ---------------------------------------------------------------- Állapot
class State:
    def __init__(self, path):
        self.path = path
        self.lock = threading.Lock()
        self.seen = {}
        self.hist = {}
        self.alerted = deque(maxlen=20000)
        self._alerted_set = set()
        if os.path.exists(path):
            try:
                with open(path, encoding="utf-8") as f:
                    d = json.load(f)
                self.seen = {k: deque(v, maxlen=3000) for k, v in d.get("seen", {}).items()}
                self.hist = {k: deque(v, maxlen=300) for k, v in d.get("hist", {}).items()}
                for i in d.get("alerted", []):
                    self.mark_alerted(i)
            except Exception as e:
                log(f"{C['y']}state.json nem olvasható ({e}), tiszta lappal indulok.{C['x']}")

    def seen_for(self, key):
        return self.seen.setdefault(key, deque(maxlen=3000))

    def hist_for(self, key):
        return self.hist.setdefault(key, deque(maxlen=300))

    def mark_alerted(self, uid):
        if uid in self._alerted_set:
            return False
        if len(self.alerted) == self.alerted.maxlen:
            self._alerted_set.discard(self.alerted[0])
        self.alerted.append(uid)
        self._alerted_set.add(uid)
        return True

    def save(self):
        with self.lock:
            d = {"seen": {k: list(v) for k, v in self.seen.items()},
                 "hist": {k: [round(x) for x in v] for k, v in self.hist.items()},
                 "alerted": list(self.alerted)}
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(d, f)
            os.replace(tmp, self.path)


# ---------------------------------------------------------------- Értesítés
def http_post(url, data, json_body=False):
    if json_body:
        body = json.dumps(data).encode()
        headers = {"Content-Type": "application/json", "User-Agent": "VintedDealBot/1.0"}
    else:
        body = urllib.parse.urlencode(data).encode()
        headers = {"Content-Type": "application/x-www-form-urlencoded", "User-Agent": "VintedDealBot/1.0"}
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        r.read()


def notify(cfg, text):
    n = cfg.get("notify", {})
    def run():
        try:
            if n.get("telegram_bot_token") and n.get("telegram_chat_id"):
                http_post(f"https://api.telegram.org/bot{n['telegram_bot_token']}/sendMessage",
                          {"chat_id": n["telegram_chat_id"], "text": text})
            if n.get("discord_webhook"):
                http_post(n["discord_webhook"], {"content": text[:1900]}, json_body=True)
        except Exception as e:
            log(f"{C['y']}Értesítés sikertelen: {e}{C['x']}")
    threading.Thread(target=run, daemon=True).start()


_csv_lock = threading.Lock()


def report(item, search, huf, med, ratio, cfg):
    found = now_str()
    if item["ts"]:
        up = datetime.fromtimestamp(item["ts"]).strftime("%Y-%m-%d %H:%M:%S")
        age = int(time.time() - item["ts"])
        age_s = f"{age} mp-e" if age < 120 else f"{age // 60} perce"
    else:
        up, age_s = "?", "?"
    cur = item["cur"]
    price_s = f"{fmt_num(item['price'])} {'Ft' if cur == 'HUF' else cur}"
    huf_s = "" if cur == "HUF" else f" (≈ {fmt_num(huf)} Ft)"
    if ratio is not None:
        deal_s = f"-{(1 - ratio) * 100:.0f}% a mediánhoz (medián ≈ {fmt_num(med)} Ft)"
    else:
        deal_s = "max. ár alatt (még nincs elég adat a mediánhoz)"
    cc = item["country"].upper()

    log(f"\n{C['g']}{C['b']}🔥 [{cc}] {search['name']}  →  {price_s}{huf_s}{C['x']}\n"
        f"   {C['b']}{item['title']}{C['x']}  {C['d']}{item['brand']} · {item['status']}{C['x']}\n"
        f"   {C['y']}{deal_s}{C['x']}\n"
        f"   {C['c']}{item['url']}{C['x']}\n"
        f"   {C['d']}feltöltve: {up} ({age_s}) · észlelve: {found}{C['x']}")

    with _csv_lock:
        new = not os.path.exists(LOG_PATH)
        with open(LOG_PATH, "a", newline="", encoding="utf-8-sig") as f:
            w = csv.writer(f, delimiter=";")
            if new:
                w.writerow(["eszlelve", "feltoltve", "orszag", "kereses", "cim", "ar",
                            "penznem", "ar_huf", "median_huf", "kedvezmeny_%", "link"])
            w.writerow([found, up, cc, search["name"], item["title"], item["price"], cur,
                        round(huf), round(med) if med else "",
                        round((1 - ratio) * 100) if ratio is not None else "", item["url"]])

    notify(cfg, f"🔥 [{cc}] {search['name']}: {price_s}{huf_s}\n{item['title']}\n"
                f"{deal_s}\nFeltöltve: {up} ({age_s})\n{item['url']}")


# ---------------------------------------------------------------- Árfolyam
def load_rates(cfg):
    rates = {k.upper(): float(v) for k, v in cfg.get("to_huf", {}).items()}
    rates["HUF"] = 1.0
    for url in ("https://api.frankfurter.dev/v1/latest?base=HUF&symbols=PLN,RON,EUR",
                "https://api.frankfurter.app/latest?from=HUF&to=PLN,RON,EUR"):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "VintedDealBot/1.0"})
            with urllib.request.urlopen(req, timeout=8) as r:
                d = json.loads(r.read().decode())
            for cur, per_huf in d["rates"].items():
                rates[cur] = 1 / float(per_huf)
            log(f"{C['d']}Friss ECB árfolyam: 1 PLN ≈ {rates['PLN']:.1f} Ft, "
                f"1 RON ≈ {rates['RON']:.1f} Ft{C['x']}")
            return rates
        except Exception:
            continue
    log(f"{C['y']}Nem sikerült friss árfolyamot lekérni, a config.json-ban lévőt használom.{C['x']}")
    return rates


# ---------------------------------------------------------------- Fő ciklus
def queries_for(search, country):
    q = search.get("queries", {})
    if isinstance(q, dict):
        q = q.get(country) or q.get("all") or []
    return [q] if isinstance(q, str) else list(q)


def country_worker(country, cfg, state, rates, stop):
    client = None
    gap = cfg.get("request_gap_sec", 1.5)
    while not stop.is_set():
        try:
            if client is None:
                client = VintedClient(country)
                log(f"{C['g']}[{country.upper()}] kapcsolódva: {DOMAINS[country]}{C['x']}")
            t0 = time.time()
            for search in cfg["searches"]:
                for q in queries_for(search, country):
                    if stop.is_set():
                        return
                    key = f"{country}|{search['name']}|{q}"
                    with state.lock:
                        seeded = key in state.seen
                    items = client.search(q)
                    with state.lock:
                        seen = state.seen_for(key)
                        seen_set = set(seen)
                        hist = state.hist_for(key)
                        hits = []
                        for raw in reversed(items):  # régebbitől az újabb felé
                            it = normalize(raw, country)
                            if it["id"] in seen_set:
                                continue
                            seen.append(it["id"])
                            seen_set.add(it["id"])
                            res = evaluate(it, search, cfg, hist, rates)
                            if res and (seeded or cfg.get("alert_on_start", False)):
                                if state.mark_alerted(f"{country}:{it['id']}"):
                                    hits.append((it, res))
                    for it, (huf, med, ratio) in hits:
                        report(it, search, huf, med, ratio, cfg)
                    if not seeded:
                        log(f"{C['d']}[{country.upper()}] '{q}': {len(items)} hirdetés betöltve "
                            f"(alapár-minta), mostantól csak az újakat figyelem.{C['x']}")
                    time.sleep(gap + random.uniform(0, 0.7))
            state.save()
            if cfg.get("verbose"):
                log(f"{C['d']}[{country.upper()}] kör kész ({time.time() - t0:.1f} mp) – {now_str()}{C['x']}")
            stop.wait(cfg.get("poll_interval_sec", 5))
        except Exception as e:
            log(f"{C['r']}[{country.upper()}] hiba: {e} – 20 mp múlva újrapróbálom.{C['x']}")
            client = None
            stop.wait(20)


def main():
    if not os.path.exists(CONFIG_PATH):
        print(f"Nincs config.json ebben a mappában: {BASE}")
        print("Tedd a config.json-t a program mellé, és indítsd újra.")
        if getattr(sys, "frozen", False):
            input("Nyomj Entert a bezáráshoz…")
        sys.exit(1)
    with open(CONFIG_PATH, encoding="utf-8") as f:
        cfg = json.load(f)

    countries = [c for c in cfg.get("countries", ["hu", "pl", "ro"]) if c in DOMAINS]
    rates = load_rates(cfg)
    state = State(STATE_PATH)
    stop = threading.Event()

    log(f"{C['b']}Vinted IT-akció figyelő – {', '.join(c.upper() for c in countries)} – "
        f"{len(cfg['searches'])} keresés. Leállítás: Ctrl+C{C['x']}")

    threads = [threading.Thread(target=country_worker, args=(c, cfg, state, rates, stop), daemon=True)
               for c in countries]
    for t in threads:
        t.start()
    try:
        while any(t.is_alive() for t in threads):
            time.sleep(0.5)
    except KeyboardInterrupt:
        log("\nLeállítás…")
        stop.set()
        for t in threads:
            t.join(timeout=5)
        state.save()


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"Váratlan hiba: {e}")
        if getattr(sys, "frozen", False):
            input("Nyomj Entert a bezáráshoz…")
        raise
