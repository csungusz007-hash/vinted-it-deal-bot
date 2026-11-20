# Vinted IT-figyelő (HU / PL / RO)

Windows-os asztali app, ami a vinted.hu, vinted.pl és vinted.ro oldalon figyeli az új IT-hirdetéseket (videókártya, RAM, SSD, telefon, fülhallgató), és szól, ha valami jóval a szokásos ár alatt jelenik meg.

## Telepítés
1. Nyisd meg a repó **Releases → Vinted IT-figyelő (Windows app)** kiadását, és töltsd le a `VintedFigyelo-windows.zip`-et.
2. Csomagold ki egy mappába. Benne lesz a `VintedFigyelo.exe`, a `config.json` és ez a leírás.
3. Indítsd el a `VintedFigyelo.exe`-t. Ha a Windows kék ablakkal figyelmeztet, kattints a **További információ → Futtatás mindenképp** gombra (a program nincs digitálisan aláírva).

Python nem kell hozzá. A felületet a Windowsba épített Edge WebView2 rajzolja ki, ez Windows 10 és 11 alatt alapból megvan.

## Mit látsz az appban
- **Akciók**: kártyák fotóval, árral (külföldi árnál forintban is), árcímkével (Kiváló ár, Jó ár, Korrekt ár) és egy sávval, ami megmutatja, hol van a teljes ár a keresés mediánjához képest. Kattintásra megnyílik a hirdetés a böngészőben.
- **Összes új hirdetés**: minden friss hirdetés a keresésekből, akkor is, ha nem akció. Ebből látod, hogy a figyelő dolgozik.
- **Országok**: mindhárom országnál ott van, mikor frissült utoljára, és ha baj van, mi a hiba. A figyelő sosem áll le csendben.
- **Keresések**: egy kapcsolóval ki-be kapcsolhatók, a `+` gombbal újat adhatsz hozzá, a szerkesztőben pedig országonkénti kulcsszót, ársávot és saját akció-küszöböt állíthatsz be.
- A hirdetés `⋮` menüjében elrejtheted a hirdetést, vagy letilthatod az eladót.

## Hogyan dönti el, mi az akció
- Minden keresésnél gyűjti az árakat, és azok mediánjához hasonlít. Az első kör csak mintát gyűjt, akkor még nem jelez.
- Az összehasonlítás a **Vinted-díjjal együtt** fizetendő teljes áron megy, forintra átváltva (az árfolyamot induláskor az EKB-tól kéri le).
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

## Python-os futtatás (fejlesztéshez)
```
pip install pywebview
python app.py
```
A régi, konzolos változat továbbra is működik: `python vinted_bot.py`.

## Fontos
- A Vinted 2026 szeptemberében megszüntette a korábbi keresési API-t. Az app ezért a keresési oldalt olvassa, ugyanazt, amit a böngésző mutat. Ha a Vinted ezt is átalakítja, az országoknál hibaüzenet jelenik meg, és frissíteni kell az appot.
- A Vinted felhasználási feltételei tiltják az automatikus lekérdezést. Az app csak olvas, nem vásárol és nem üzen. Ésszerű tempóban, saját használatra futtasd. A kör közötti szünetet ne vedd túl rövidre.
