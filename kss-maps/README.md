# KSS-Maps

Navigations-App für Android **und Android Auto**, ausgelegt auf Bayern. Kotlin, ohne
Google-Dienste, Karte mit MapLibre. Paket `de.sascha.kssmaps`.

## Was die App kann

| Wunsch | Umsetzung |
|---|---|
| Karte wie Google Maps, Bayern reicht | Vektorkarte (OpenFreeMap/OSM), Tag/Nacht automatisch nach Sonnenstand |
| Satellitenbilder | **Luftbild-Knopf**: in Bayern die amtlichen Luftbilder (DOP 40 cm, Open Data der Bayerischen Vermessungsverwaltung), außerhalb Esri-Satellit; Straßen und Namen bleiben darüber sichtbar |
| Blitzer, Abstandsmessungen, Baustellen, Stau, „sonstige Sachen“ – nur auf der Route | Feste Blitzer, Rotlicht- und Abschnittskontrollen, **Abstandsmessungen** und Mindestabstand-Strecken (OpenStreetMap, ganz Bayern – ein aktueller Bestand ist schon in der App, danach wöchentlich aktualisiert und zusätzlich je Route abgefragt), Stau/Baustellen/Sperrungen/Gefahren (Autobahn GmbH, mit TomTom-Schlüssel auf **allen** Straßen). Es wird nur angezeigt und gewarnt, was **auf der gefahrenen Strecke** liegt (Abgleich auf ~25–45 m, Gegenfahrbahn und Parallelstraßen werden aussortiert). Ohne Ziel: Blitzer voraus in Fahrtrichtung |
| Mobile Blitzer / Abstandsmessungen | **Messstellen der bayerischen Polizei**: die amtlichen Messstellenlisten des Innenministeriums (Blitzmarathon 2023/2024/2026) – Orte, an denen die Polizei Geschwindigkeit misst, auch außerhalb der Aktionstage; Warnung als „Mobile Messstelle“ (eigener Schalter). Dazu der rote Kamera-Knopf (Handy) bzw. „Mehr“ (Android Auto): gesehenen Blitzer oder Abstandsmessung merken – 4 Stunden oder dauerhaft. Live-Meldungen anderer Fahrer (Blitzer.de, Waze) gibt es nur in deren eigenen Apps, ohne Schnittstelle für Fremd-Apps |
| Warnen | Sprachwarnung + farbiges Banner („Blitzer in 600 m“, „Stau in 3 km, etwa 12 Minuten Verzögerung“), in Android Auto zusätzlich als Hinweis-Karte |
| Tempolimit anzeigen | Tempolimit-Schild aus OSM-Daten entlang der Route (ohne Route: per Kartenabgleich der letzten GPS-Punkte) |
| Aktuelle Geschwindigkeit | groß unten links, rot (und optional ein Ton) bei Überschreitung |
| Hausnummer am Ziel deutlich markieren | Das Gebäude der Zieladresse wird **magenta eingefärbt** (auch in 3D), die Hausnummer steht groß darauf – auch in Android Auto |
| Alles in Android Auto | Kartenfläche mit Route, Stau-Färbung, Blitzern/Abstandsmessungen, Zielgebäude, Tempo + Tempolimit, Warnbanner; Abbiegehinweise, Ankunftszeit, Suche (Sprache/Tastatur im Stand), letzte Ziele, Routenwahl, Tanken, „Mehr“ (Melden, Stau umfahren, Verkehr prüfen, Ton) |
| Stau in Routenberechnung und Alternativen | Bis zu 3 Routen, jede mit Fahrzeit **inkl. Stau**, Anzahl Staus/Baustellen/Blitzer. Ohne TomTom: Stauzeiten der Autobahn GmbH werden eingerechnet, plus automatische „Stau-optimierte“ Variante. Mit TomTom: Live-Verkehr direkt im Routing |
| Route ohne Stau wählen | Route mit Etikett „Ohne Stau“ und Knopf **„Stau umfahren“** (sperrt alle Staus/Sperrungen der Route und rechnet neu). Unterwegs prüft die App alle 3 Minuten und bietet schnellere Routen an |
| Gewisse Abschnitte nicht fahren (nicht pauschal) | **Lange auf eine Straße drücken → „Abschnitt meiden“.** Gesperrt wird nur dieses Stück (bei Autobahnen beide Fahrbahnen), dargestellt als rote Strichlinie. Liste mit Ein/Aus-Schalter und Löschen über den ⛔-Knopf. Autobahnen/Maut/Fähren komplett meiden gibt es zusätzlich in den Einstellungen |
| Letzte 20 Adressen | Verlauf in der Suche (Handy) und „Letzte Ziele“ (Android Auto); lange drücken = entfernen |
| Tankstellen mit Preisen, UTA | ⛽-Knopf: Diesel/Super E5/E10, Filter **„nur UTA“**. Reihenfolge: **zuerst die auf dem Weg zum Ziel liegenden, dann nach Preis**; danach Tankstellen mit Umweg. Ohne Tankerkönig-Schlüssel erscheinen die Tankstellen trotzdem sofort (Liste aller Tankstellen Bayerns aus OpenStreetMap ist in der App, mit UTA-Kennzeichnung) – nur ohne Preise |
| Erst zur Tankstelle, dann automatisch weiter | Tankstelle antippen → wird Zwischenstopp, danach geht es ohne neue Eingabe weiter zum Ziel |
| Sprachnavigation, Ton abschaltbar | Deutsche Ansagen in 3 Stufen (Vorankündigung, nah, „Jetzt …“), Lautsprecher-Knopf (Handy und Auto) |
| Funklöcher | Beim Start der Navigation wird die Karte entlang der Route automatisch offline gespeichert (bis 250 km), dazu ein großer Kartenspeicher (600 MB) für oft gefahrene Gegenden |

## Einrichtung auf dem Handy

1. APK aus dem Release laden: `https://github.com/Schmenkman/kss-app-releases/releases` →
   neuestes `kssmaps-v…` → `kss-maps.apk`, auf dem Handy öffnen und installieren.
2. Beim Start Standort erlauben. Für Android Auto bei gesperrtem Handy zusätzlich in den
   Einstellungen der App: Standort **„Immer zulassen“** und **Akku-Optimierung aus**
   (Knöpfe dafür in *Einstellungen → Hintergrund & Android Auto*).
3. **Kostenlose Schlüssel** in *Einstellungen* eintragen:
   - **TomTom** (developer.tomtom.com, ohne Kreditkarte; 2 500 Anfragen/Tag frei):
     Live-Stau auf allen Straßen, Verkehrsfluss auf der Karte. Ohne Schlüssel funktioniert
     alles, Stau kommt dann aber nur für Autobahnen (Autobahn GmbH).
   - **Tankerkönig** (onboarding.tankerkoenig.de): Spritpreise (Diesel, Super E5/E10). Ohne
     Schlüssel zeigt die App die Tankstellen auf dem Weg ohne Preise.
4. Optional: deutsche Stimme prüfen (Android → Einstellungen → Sprachausgabe).

### Android Auto

Android Auto zeigt Apps, die nicht aus dem Play Store kommen, erst nach einmaligem Freischalten:

1. Android-Auto-Einstellungen öffnen (am Handy: Einstellungen → Verbundene Geräte → Android Auto).
2. Ganz unten **10× auf „Version“** tippen → Entwicklermodus bestätigen.
3. Menü **⋮ → Entwicklereinstellungen → „Unbekannte Quellen“** aktivieren.
4. Handy neu mit dem Auto verbinden – „KSS-Maps“ erscheint im App-Starter.

## Hinweis Blitzer

In Deutschland darf der **Fahrer** während der Fahrt keine Blitzerwarner benutzen
(§ 23 Abs. 1c StVO, 75 € und 1 Punkt). Blitzer- und Abstandswarnung sind eingeschaltet;
beim ersten Start erscheint einmalig dieser Hinweis, abschalten geht in den Einstellungen.
Mobile Blitzer: KSS-Maps nutzt die amtlichen Messstellenlisten der bayerischen Polizei
(Innenministerium) und deine eigenen Meldungen. Live-Meldungen von Blitzer.de oder Waze sind nur
in deren Apps nutzbar (keine offene Schnittstelle).

## Einmalige Einrichtung des Signier-Schlüssels (für Updates)

Android installiert Updates nur, wenn sie mit demselben Schlüssel signiert sind. Der
Schlüssel liegt **nicht** im Repo (das ist öffentlich), sondern als GitHub-Secret – gleiches
Vorgehen wie bei der Fitness-App:

```
cd kss-maps
python tools/make_keystore.py
gh auth login
powershell -ExecutionPolicy Bypass -File tools\setup_github.ps1
```

Danach den Ordner `signing/` sichern (OneDrive o. Ä.). Solange keine Secrets hinterlegt
sind, signiert die CI mit einem **temporären Test-Schlüssel** – die App läuft, aber vor
dem Installieren einer neueren Testversion muss die alte deinstalliert werden.

## Datenquellen

| Was | Quelle | Schlüssel |
|---|---|---|
| Karte | OpenFreeMap (OpenStreetMap) | – |
| Luftbild Bayern | geoservices.bayern.de DOP40 (CC BY 4.0) | – |
| Satellit außerhalb Bayerns | Esri World Imagery | – |
| Routing ohne Schlüssel | Valhalla, öffentliche FOSSGIS-Instanz (OSM) | – |
| Routing + Live-Verkehr | TomTom Routing API, Traffic Flow Tiles | TomTom (optional) |
| Stau/Baustellen/Sperrungen Autobahn | verkehr.autobahn.de (Autobahn GmbH des Bundes) | – |
| Blitzer, Rotlicht, Abschnittskontrolle, Abstandsmessung, Mindestabstand | OpenStreetMap über Overpass-API (`highway=speed_camera`, `type=enforcement`, `mindistance`) | – |
| Messstellen der Polizei (mobile Kontrollen) | Bayerisches Staatsministerium des Innern, Messstellenlisten Blitzmarathon; verortet mit Nominatim (© OpenStreetMap, ODbL) – `tools/fetch_messstellen.py`, Workflow `kss-maps-messstellen.yml` | – |
| Tempolimits | OSM-Daten über Valhalla `trace_attributes` | – |
| Adresssuche | Photon (komoot), Nominatim | – |
| Zielgebäude | OSM-Gebäude über Overpass-API | – |
| Spritpreise | Tankerkönig / MTS-K (CC BY 4.0) | Tankerkönig |
| Tankstellen ohne Schlüssel | OpenStreetMap (`amenity=fuel`, mit `payment:uta`), beim Bauen in die App gelegt; außerhalb Bayerns per Overpass-API – ohne Preise | – |
| UTA-Akzeptanz | Markenliste (bearbeitbar) + OSM `payment:uta` + eigene Korrektur je Tankstelle (lange drücken) | – |

UTA veröffentlicht keine offene Liste der Akzeptanzstellen; laut UTA und Vergleichsportalen
wird die Karte in Deutschland an praktisch allen Markentankstellen angenommen. Stationen
ohne erkennbare Marke stehen auf „UTA unbekannt“ und erscheinen bei „nur UTA“ nicht.

## Aufbau

```
app/src/main/java/de/sascha/kssmaps/
  core/      reine Logik ohne Android (auf der JVM getestet):
             Geo, Model, RouteTracker, HazardMatcher, Instructions (deutsche Ansagen),
             Valhalla/TomTom/Autobahn/Tankerkoenig/Geocoding/Overpass-Parser, Fuel (Sortierung, UTA)
  data/      Http, Prefs (Einstellungen, Verlauf, gesperrte Abschnitte), Services (alle Netzaufrufe)
  nav/       NavEngine (EIN Zustand für Handy und Auto), Voice (TTS über Navigations-Audiokanal)
  ui/        MapController (Karte für Handy UND Auto), Icons (alle Symbole gezeichnet), Panels
  car/       Android Auto: NaviCarAppService, CarMapRenderer (VirtualDisplay + MapLibre), Screens
  MainActivity, NavService (Vordergrunddienst während der Navigation), NaviApp
app/src/test/     Tests der Kernlogik (Parser, Abgleich, Ansagen, Sortierung, Gebäude …)
app/src/carTest/  Tests aller Android-Auto-Vorlagen (Robolectric, `-PcarTests`)
tools/smoketest.sh  Testfahrt im Emulator (CI, Commit-Nachricht mit „[testfahrt]“): Route,
                  Luftbild, gesperrter Abschnitt, Fahrt mit Ansagen, Ankunft, Tanken, Suche –
                  plus Bilder der Android-Auto-Karte (auf unsichtbarer Fläche gezeichnet)
tools/fetch_enforcement.py  Startbestand Blitzer/Abstandsmessungen und Tankstellenliste für die APK (CI)
```

## Automatische Prüfungen

- **Logik-Tests** (JVM): Routen-Parser, Blitzer-/Stau-Abgleich, Ansagetexte, Tankstellen-Sortierung,
  Suche, Gebäude-Erkennung – bei jedem Build.
- **Android-Auto-Tests** (Robolectric): jede Vorlage (Karte/Navigation, Routenauswahl, Suche, letzte
  Ziele, Tanken, „Mehr“, Hinweise) in allen Zuständen – Android Auto wirft bei falsch gebauten
  Vorlagen Ausnahmen, die hier auffallen statt im Auto.
- **Testfahrt im Emulator** (Commit-Nachricht mit `[testfahrt]`): Erststart-Hinweise, Ziel per
  geo-Link, Routen mit Blitzern/Baustellen, Luftbild, gesperrter Abschnitt, Navigation München →
  Freising mit Ansagen, Blitzerwarnung (Ansage + Banner), Ankunft mit markiertem Zielhaus,
  Tankstellen, Suche – plus Bilder der Android-Auto-Karte. Ergebnisse hängen als `testfahrt_*` am
  Release, der nächste normale Build entfernt sie. Der Emulator nutzt die ANGLE-Grafik
  (`-gpu swangle_indirect`); mit der älteren SwiftShader-Emulation fehlen Kartensymbole und
  Beschriftungen – nur im Emulator, nicht auf echten Geräten.

## Build & Release

Jeder Push baut und testet; das veröffentlichte APK im Release wird **nur** bei `[release]` in der
Commit-Nachricht (oder manuellem Start) ersetzt – so landet nie ein ungetesteter Zwischenstand
beim Download-Link.

`.github/workflows/kss-maps.yml`: Tests → signierte APK → GitHub-Release
`kssmaps-v<version>` mit `kss-maps.apk`. `version.json` ist die einzige Versionsquelle
(`versionCode = major*10000 + minor*100 + patch`). Die App prüft beim Start
`kss-maps/version.json` auf `main` und bietet neue Versionen zum Download an.
Beim Release: Version in `version.json` hochzählen, `notes` pflegen, `apkUrl` anpassen, pushen.
