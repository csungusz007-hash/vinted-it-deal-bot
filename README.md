# Vinted IT-figyelő (HU / PL / RO)

Windows-os asztali app, ami a vinted.hu, vinted.pl és vinted.ro oldalon figyeli az új IT-hirdetéseket (videókártya, RAM, SSD, telefon, fülhallgató), és szól, ha valami jóval a szokásos ár alatt jelenik meg.

## Telepítés
1. Nyisd meg a repó **Releases → Vinted IT-figyelő (Windows app)** kiadását, és töltsd le a `VintedFigyelo-windows.zip`-et.
2. Csomagold ki egy mappába. Benne lesz a `VintedFigyelo.exe`, egy `_internal` mappa (az app futtatókörnyezete, ne töröld és ne vidd el az exe mellől), a `config.json` és ez a leírás.
3. Indítsd el a `VintedFigyelo.exe`-t. Ha a Windows kék ablakkal figyelmeztet, kattints a **További információ → Futtatás mindenképp** gombra (a program nincs digitálisan aláírva).

Python nem kell hozzá. A felületet a Windowsba épített Edge WebView2 rajzolja ki, ez Windows 10 és 11 alatt alapból megvan.

### Ha nem indul: „Failed to resolve Python.Runtime.Loader.Initialize”
A Windows a letöltött ZIP-ből kicsomagolt fájlokat letiltja. Az új verzió ezt indításkor magától feloldja. Ha mégis előjön: jobb klikk a letöltött ZIP-en → **Tulajdonságok** → pipáld be a **Feloldás** jelölőnégyzetet → OK, és csomagold ki újra.

## Android (telefon)
1. Telefonon nyisd meg a repó **Releases → Vinted IT-figyelő (Android)** kiadását, és töltsd le a `VintedFigyelo.apk`-t.
2. Nyisd meg a letöltött fájlt. Ha a telefon kéri, engedélyezd a böngészőnek az „ismeretlen forrásból” való telepítést.
3. Első indításkor engedélyezd az értesítéseket, és azt is, hogy az akkumulátor-optimalizálás ne állítsa le az appot. Enélkül az Android a háttérben leállíthatja a figyelést.

A telefonos app ugyanazt tudja, mint a Windows-os: a figyelés a háttérben fut (az értesítési sávban „Figyelés fut” jelzi), új akciónál értesítést kapsz, koppintásra megnyílik a hirdetés. A vissza gomb csak háttérbe teszi az appot, a figyelést a „Figyelés szüneteltetése” gomb állítja le.

## Akció-csomagok (célárak)
Az alapbeállításban 30 kész keresés van, mindegyiknek célára: ha egy hirdetés a Vinted-díjjal együtt ennél olcsóbb és legalább „Jó” állapotú, akció. Például:

| Csomag | Célár |
|---|---|
| RTX 3060 / 3070 / 3080 | 60 000 / 80 000 / 115 000 Ft |
| RTX 4060 / 4060 Ti / 4070 | 60 000 / 75 000 / 100 000 Ft |
| RX 6700 XT | 85 000 Ft |
| DDR4 16GB / DDR4 32GB / DDR5 32GB | 25 000 / 40 000 / 80 000 Ft |
| SSD 1TB / 2TB NVMe | 18 000 / 32 000 Ft |
| iPhone 13 / 14 / 15 / 15 Pro | 55 000 / 72 000 / 100 000 / 125 000 Ft |
| Galaxy S23 / S24 | 58 000 / 78 000 Ft |
| Fülhallgatók (bármilyen vezeték nélküli) | 10 000 Ft |
| AirPods Pro 2 / AirPods 3–4 | 32 000 / 20 000 Ft |
| Sony WH-1000XM5 / XM4 | 42 000 / 30 000 Ft |
| Logitech G502, Ryzen 5 5600 / 7600, 27" 144Hz monitor | 9 000 / 32 000 / 45 000 / 35 000 Ft |
| Bármilyen iPhone, legalább „Nagyon jó” állapotban | 30 000 Ft |
| 16GB RAM (DDR4 vagy DDR5, 1×16 vagy 2×8) | 30 000 Ft |
| Samsung Galaxy Watch 4-től felfelé (5, 6, 7, 8, FE, Ultra), legalább „Nagyon jó” | 25 000 Ft |

A célárak 2026 novemberi magyar használt árakból indulnak (kb. 25–40%-kal a szokásos ár alatt). Az RTX 4070 100 000 Ft-os célára ennél szigorúbb (a tipikus használt ár 185–215 ezer Ft), ott ritkán lesz találat. A keresésekben `min_condition` is megadható (4 = legalább „Nagyon jó”, 5 = új); ez felülírja az általános állapot-beállítást. Minden célár átírható a keresés szerkesztőjében, és saját keresést is felvehetsz.

Az első kör a már fent lévő hirdetések közül is kigyűjti a célár alattiakat a listába (értesítés nélkül). Utána csak az új hirdetésekről jön értesítés, keresésenként a legolcsóbbról.

## Mit látsz az appban
- **Akciók**: kártyák fotóval, árral (külföldi árnál forintban is), árcímkével (Kiváló ár, Jó ár, Korrekt ár) és egy sávval, ami megmutatja, hol van a teljes ár a keresés mediánjához képest. Kattintásra megnyílik a hirdetés a böngészőben.
- **Összes új hirdetés**: minden friss hirdetés a keresésekből, akkor is, ha nem akció. Ebből látod, hogy a figyelő dolgozik.
- **Országok**: mindhárom országnál ott van, mikor frissült utoljára, és ha baj van, mi a hiba. A figyelő sosem áll le csendben.
- **Keresések**: egy kapcsolóval ki-be kapcsolhatók, a `+` gombbal újat adhatsz hozzá, a szerkesztőben pedig országonkénti kulcsszót, ársávot és saját akció-küszöböt állíthatsz be.
- A hirdetés `⋮` menüjében elrejtheted a hirdetést, letilthatod az eladót, vagy elmentheted.
- **Mentett**: az elmentett hirdetéseket 10 percenként ellenőrzi. Jelzi, ha eladták, törölték, lefoglalták, vagy változott az ára, és árcsökkenésnél vagy eladásnál Windows-értesítést küld.
- **Ártörténet**: a bal oldali listában egy keresés nevére kattintva grafikon mutatja a piaci ár (medián) óránkénti alakulását és a talált akciókat.
- **Magyar címek**: a lengyel és román címeket magyarra fordítja, az eredeti cím az egérmutató alatt látszik. Az állapotfeliratok („Bardzo dobry”, „Foarte bun”…) is magyarul jelennek meg. A fordítás a Beállításokban kikapcsolható.
- **Árcsökkenés**: ha egy már látott hirdetés legalább 5%-kal olcsóbb lesz, és így akcióvá válik, külön jelzést kapsz róla.

## Hogyan dönti el, mi az akció
- Minden keresésnél gyűjti az árakat, és azok mediánjához hasonlít. Az első kör csak mintát gyűjt, akkor még nem jelez.
- Az összehasonlítás a **Vinted-díjjal együtt** fizetendő teljes áron megy, forintra átváltva. Az árfolyamot az EKB-tól kéri le induláskor és utána óránként. A Beállítások → Árfolyamok résznél látod az aktuális PLN/RON/EUR árfolyamot, és kézzel is frissítheted. Ha nincs internet, a `config.json`-ban mentett értékkel számol.
- Csak a **piaci ár alatti** hirdetés számít akciónak: alapból legalább 35%-kal a medián alatt (Beállítások → Ár a piaci árhoz képest), és legalább „Jó” állapotú (Beállítások → Minimális állapot).
- Figyelmeztetést tesz a kártyára, ha a hirdetés gyanúsan olcsó (a medián 30%-a alatt), vagy a címben „doboz”, „fotó”, „replika” és hasonló szó szerepel. Ez a leggyakoribb átverés a Vinteden: töredékáron hirdetett telefon, amiből csak a doboz jön.

## Értesítések
Termékenként (keresésenként) csak a **legjobb ajánlatról** jön értesítés: akkor, ha az új akció olcsóbb az elmúlt 24 óra összes akciójánál abban a keresésben. Például: „iPhone 15 · 25 000 Ft · Jó · −89%”. Ha mindenről szeretnél értesítést, a Beállításokban átállíthatod.

Ha az app nincs előtérben, Windows-értesítést küld. Ha előtérben van, csak egy rövid hangjelzést ad, és az új kártya kiemelve jelenik meg. Mindkettő kikapcsolható a Beállításokban.

## Fájlok az .exe mellett
| Fájl | Mire való |
|---|---|
| `config.json` | Keresések és beállítások. Az app írja, de kézzel is szerkesztheted. |
| `state.json` | A már látott hirdetések és a gyűjtött árak. Ha törlöd, újra mintát gyűjt. |
| `akciok.json` | Az elmentett akciók. |
| `figyelolista.json` | A Mentett fülön követett hirdetések. |
| `arnaplo.json` | A piaci árak óránkénti naplója az ártörténet-grafikonhoz. |

## Python-os futtatás (fejlesztéshez)
```
pip install pywebview
python app.py
```
A régi, konzolos változat továbbra is működik: `python vinted_bot.py`.

A motor gyors önellenőrzése (hálózat nélkül): `python test_engine.py`.

## Fontos
- A Vinted 2026 szeptemberében megszüntette a korábbi keresési API-t. Az app ezért a keresési oldalt olvassa, ugyanazt, amit a böngésző mutat. Ha a Vinted ezt is átalakítja, az országoknál hibaüzenet jelenik meg, és frissíteni kell az appot.
- A Vinted felhasználási feltételei tiltják az automatikus lekérdezést. Az app csak olvas, nem vásárol és nem üzen. Ésszerű tempóban, saját használatra futtasd. A kör közötti szünetet ne vedd túl rövidre.
