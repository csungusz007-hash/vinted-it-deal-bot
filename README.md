# Vinted IT-akció figyelő (HU / PL / RO)

## Telepítés
1. Telepítsd a Python 3.8 vagy újabb verzióját: https://www.python.org/downloads/ (Windowson pipáld be az „Add Python to PATH” opciót).
2. Tedd egy mappába ezt a három fájlt: `vinted_bot.py`, `config.json`, `start.bat`.
3. Indítás: Windowson dupla katt a `start.bat`-ra, máshol `python3 vinted_bot.py`. Leállítás: Ctrl+C.

Más csomagot nem kell telepíteni.

## Mit csinál
- A három ország egymással párhuzamosan fut, mindegyik a legújabb hirdetéseket kéri le a megadott keresésekre.
- Első indításkor betölti a meglévő hirdetéseket alapár-mintának (ilyenkor még nem jelez). Ezután csak az új hirdetéseket nézi.
- Minden új hirdetés árát átváltja forintra (indításkor friss ECB-árfolyammal), és összeveti az adott keresés eddigi árainak **mediánjával**.
- Akkor jelez, ha a hirdetés:
  - a `min_price_huf` és `max_price_huf` közötti sávban van,
  - **és** ára legfeljebb a medián `deal_threshold`-szorosa (0.65 = legalább 35%-kal olcsóbb),
  - **és** nincs a címében tiltott szó (hibás, törött, na części, pentru piese…).
- Kiírja: ország, ár (eredeti pénznemben és Ft-ban), kedvezmény %, link, feltöltés ideje másodpercre, és hogy hány másodperce jelent meg.
- Minden találat bekerül a `talalatok.csv` fájlba (Excelben megnyitható).

## Beállítások (config.json)
| Kulcs | Jelentés |
|---|---|
| `poll_interval_sec` | Szünet két kör között országonként (mp) |
| `request_gap_sec` | Szünet két kérés között (mp). **Ne vedd 1 alá**, különben a Vinted letilt (429/403). |
| `deal_threshold` | 0.65 = 35%-kal a medián alatt. Ha `1.0`-ra állítod, minden `max_price_huf` alatti hirdetést jelez. |
| `searches` | A keresések. `queries` országonként (`hu`/`pl`/`ro`) vagy `all`; `must_contain` = legalább egy ilyen szó legyen a címben; saját `deal_threshold` is megadható. |
| `exclude_words` | Ha a cím ezek közül bármelyiket tartalmazza, a hirdetést kihagyja |

Tipp: minél pontosabb a keresés (pl. „rtx 3060” a „videókártya” helyett), annál jobb a medián, és annál kevesebb a téves jelzés.

## Értesítés telefonra
**Telegram:** a @BotFather botnál a `/newbot` paranccsal kérj tokent. Írj egy üzenetet a saját botodnak, a chat ID-dat pedig a @userinfobot bottól kérdezd meg. Mindkettőt írd be a `notify` részbe.
**Discord:** Csatorna beállításai → Integrációk → Webhook → másold be az URL-t a `discord_webhook` mezőbe.

## Fontos
- A bot a Vinted nem hivatalos, a weboldal által is használt API-ját hívja. Ha a Vinted ezt megváltoztatja, a botot igazítani kell.
- Az automatikus lekérdezést a Vinted felhasználási feltételei nem engedik. Ésszerű tempóban, saját használatra futtasd.
- A sebesség: 12 keresésnél országonként kb. 20–30 mp alatt ér körbe egy kör. A jelzés a feltöltéshez képest jellemzően 5–40 mp-cel később érkezik. Kevesebb kereséssel gyorsabb.
- A `state.json` tárolja, mit látott már a bot. Ha törlöd, a bot újra betölti az alapár-mintát.
