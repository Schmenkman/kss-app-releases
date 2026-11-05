# KSS-Maps

Navigations-App für Android **und Android Auto**, ausgelegt auf Bayern. Kotlin, ohne
Google-Dienste, Karte mit MapLibre. Paket `de.sascha.kssmaps`.

## Was die App kann

| Wunsch | Umsetzung |
|---|---|
| Karte wie Google Maps, Bayern reicht | Vektorkarte (OpenFreeMap/OSM), Tag/Nacht automatisch nach Sonnenstand |
| Satellitenbilder | **Luftbild-Knopf**: in Bayern die amtlichen Luftbilder (DOP 40 cm, Open Data der Bayerischen Vermessungsverwaltung), außerhalb Esri-Satellit; Straßen und Namen bleiben darüber sichtbar |
| Baustellen, Stau, Sperrungen, Gefahren – nur auf der Route | Stau/Baustellen/Sperrungen/Gefahren (Autobahn GmbH, mit TomTom-Schlüssel auf **allen** Straßen). Es wird nur angezeigt und gewarnt, was **auf der gefahrenen Strecke** liegt (Gegenfahrbahn und Parallelstraßen werden aussortiert, auch nach Fahrtrichtung). Baustellen nur, solange sie gelten: Beginn/Ende aus der Meldung, Tagesbaustellen nur in ihrem Zeitfenster (z. B. 9–15 Uhr), TomTom-Baustellen ohne jede Auswirkung entfallen. Verkehr wird unterwegs alle 2 Minuten neu geprüft, Autobahn-Meldungen alle 90 Sekunden |
| Blitzer / Abstandsmessungen | Schalter, **standardmäßig aus** – Blitzer.de Pro läuft parallel. Eingeschaltet (Einstellungen oder Android Auto → „Mehr“): feste Blitzer, Rotlicht-/Abschnittskontrollen, Abstandsmessungen aus OpenStreetMap und eigene Meldungen (roter Kamera-Knopf), nur auf der Route |
| Warnen | Sprachwarnung + farbiges Banner: **„Baustelle in 800 m“**, in der Baustelle **„Baustelle noch 1,2 km“** (genauso Stau mit Verzögerung, Sperrung, Gefahr). In Android Auto als Banner auf der Karte – ohne Hinweiskarte mit Zeitbalken, die das Antippen blockiert |
| Tempolimit anzeigen | Tempolimit-Schild aus OSM-Daten entlang der Route (ohne Route: per Kartenabgleich der letzten GPS-Punkte); Lücken füllt TomTom (z. B. 100 auf Landstraßen ohne Schild). Auf Autobahnen mit **LED-Anzeigen** (Wechselverkehrszeichen) zeigt die App ein schwarzes Schild **„Anzeige beachten“** statt „frei“ – das gerade angezeigte LED-Tempo gibt keine freie Datenquelle her. Limits nach Uhrzeit (z. B. nachts 120) gelten zur passenden Zeit |
| Aktuelle Geschwindigkeit | groß unten links, rot (und optional ein Ton) bei Überschreitung |
| Hausnummer am Ziel deutlich markieren | Das Gebäude der Zieladresse wird **magenta eingefärbt** (auch in 3D), die Hausnummer steht groß darauf – auch in Android Auto |
| Alles in Android Auto | Kartenfläche mit Route, Abbiegepfeilen, Stau-Färbung, Baustellen, Zielgebäude, Tempo + Tempolimit, Warnbanner; Abbiegehinweise, Ankunftszeit, Suche (Sprache/Tastatur im Stand), Routenwahl. **Immer sichtbare Knöpfe** rechts untereinander: ☎ Kunde anrufen, 📅 Termine, Übersicht der Route ab hier mit Alternativen (eine davon staufrei), Tag/Nacht/Automatisch, schnelle Stau-Korrektur (neue Route ohne Stau, höchstens etwas länger), Tanken, Einstellungen, Zentrieren; unten neben dem Tempo − / + und Beenden. Tag/Nacht folgt der App-Einstellung (Automatisch = Sonnenstand), nicht dem Nachtmodus des Autos |
| Stau in Routenberechnung und Alternativen | Bis zu 3 Routen, jede mit Fahrzeit **inkl. Stau**, Anzahl Staus/Baustellen. Ohne TomTom: Stauzeiten der Autobahn GmbH werden eingerechnet, plus automatische „Stau-optimierte“ Variante. Mit TomTom: Live-Verkehr direkt im Routing |
| Route ohne Stau wählen | Route mit Etikett „Ohne Stau“ und Knopf **„Stau umfahren“** (sperrt alle Staus/Sperrungen der Route und rechnet neu). Unterwegs prüft die App alle 3 Minuten und bietet schnellere Routen an |
| Gewisse Abschnitte nicht fahren (nicht pauschal) | **Lange auf eine Straße drücken → „Abschnitt meiden“.** Gesperrt wird nur dieses Stück (bei Autobahnen beide Fahrbahnen), dargestellt als rote Strichlinie. Liste mit Ein/Aus-Schalter und Löschen über den ⛔-Knopf. Autobahnen/Maut/Fähren komplett meiden gibt es zusätzlich in den Einstellungen |
| Letzte 20 Adressen | Verlauf in der Suche (Handy) und „Letzte Ziele“ (Android Auto); lange drücken = entfernen |
| Startpunkt ändern (Start → Ziel) | In der Suche „Wohin?“ oben **„Von … ändern ›“** antippen (oder in der Routenvorschau die Zeile „Von“) → andere Startadresse (Suche, Verlauf oder „Mein Standort“), danach geht es mit dem Ziel weiter. ⇅ tauscht Start und Ziel (Rückweg). Mit eigenem Start zeigt die App Zeit, Strecke, Stau und die **Wegbeschreibung**; mit dem ⌖-Knopf geht es wieder ab dem Standort los |
| Spracheingabe | Mikrofon in der Suchleiste und in der Suche (Google-Spracherkennung, Deutsch). „Navigiere zum Marienplatz 7 in Freising“ öffnet direkt die Route; nur eine Adresse sprechen zeigt die Trefferliste. In Android Auto über „Hey Google, navigiere zu …“ |
| Querformat | Wie Google Maps in Android Auto: Abbiege-Hinweis links oben, Ankunft und Knöpfe links unten, die Karte rechts bleibt frei; Tempo unten links auf der Karte |
| Beim Abbiegen näher heran | Kurz vor einer Abzweigung, Ausfahrt, Gabelung, einem Kreisverkehr oder dem Ziel zoomt die Karte eine Stufe heran und schaut etwas steiler von oben – so sieht man bei mehreren Straßen kurz hintereinander, welche es ist. Etwa 9 Sekunden vorher (in der Stadt ab ca. 120 m, auf der Autobahn ab ca. 300 m), kurz danach geht es sanft zurück; zwei Abzweigungen dicht hintereinander bleiben nah. Auch in Android Auto |
| Abbiegepfeile auf der Karte | Vor der nächsten Abzweigung, Ausfahrt oder dem Kreisverkehr liegt ein weißer Pfeil direkt auf der Route (wie bei Google Maps) – so sieht man, welche Straße es ist |
| Tunnel | Ohne GPS-Signal fährt der Pfeil mit dem zuletzt gemessenen Tempo entlang der Route weiter (Ansagen laufen weiter), bis das GPS wieder da ist; ungenaue Netzortung wird beim Navigieren ignoriert |
| Flüssiger Positionspfeil | GPS kommt einmal pro Sekunde – dazwischen wird die Position für jedes Bild vorausberechnet (entlang der Route, mit dem gefahrenen Tempo) und weich korrigiert; Pfeil und Karte bewegen sich gleichmäßig statt ruckweise |
| Tankstellen mit Preisen, UTA | ⛽-Knopf: Diesel/Super E5/E10, Filter **„nur UTA“**. Reihenfolge: **zuerst die auf dem Weg zum Ziel liegenden, dann nach Preis**; danach Tankstellen mit Umweg. Ohne Tankerkönig-Schlüssel erscheinen die Tankstellen trotzdem sofort (Liste aller Tankstellen Bayerns aus OpenStreetMap ist in der App, mit UTA-Kennzeichnung) – nur ohne Preise |
| Erst zur Tankstelle, dann automatisch weiter | Tankstelle antippen → wird Zwischenstopp, danach geht es ohne neue Eingabe weiter zum Ziel |
| Sprachnavigation, Ton abschaltbar | Deutsche Ansagen in 3 Stufen (Vorankündigung, nah, „Jetzt …“), Lautsprecher-Knopf (Handy und Auto) |
| Termine des Tages | 📅-Knopf **„Termine“**: die Termine von heute aus **COMP** nach Uhrzeit (Kunde, Adresse, wen anrufen); antippen = Route zur Adresse. Jeder mit seinem eigenen COMP-Zugang; sieht ein Zugang mehrere Techniker (Chef), oben einmal auswählen – die Wahl bleibt gespeichert. In Android Auto als Liste über die Leiste |
| Kunde anrufen | Während der Fahrt zu einem Termin erscheint der grüne ☎-Knopf **„Kunde“**: Leiste rollt seitlich heraus (die Karte rückt nach links, Pfeil und Fahrweg bleiben frei) mit den Ansprechpartnern aus dem **Terminzusatz** – wer „vorher anzurufen“ ist, steht oben als „ZUERST ANRUFEN“ –, allen Nummern und dem vollständigen Terminzusatz. Nummer antippen → **„Anrufen?“ Ja/Nein**, Ja wählt direkt – in Android Auto ohne Wählfeld, die Navigation bleibt die große Ansicht. Steht im Terminzusatz „30 min vorher anrufen“, fragt die App 30 Minuten vor der Ankunft von selbst **„Herr … anrufen?“** (ohne Zeitangabe: 15 Minuten vorher). Eigene Nummern (Büro, Firmenhandy) in den Einstellungen werden nie als Kunde angezeigt |
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
5. Neue Version installieren: solange die CI mit dem vorläufigen Signier-Schlüssel baut, vorher
   die alte KSS-Maps deinstallieren (für Android Auto wieder über KingInstaller installieren).

### Android Auto

Aktuelle Android-Auto-Versionen (17.x) zeigen Apps, die mit den Android-Auto-Vorlagen gebaut sind,
**nur noch, wenn Android sie als „aus dem Play Store installiert“ führt** – der Schalter
„Unbekannte Quellen“ allein reicht dafür nicht mehr
([UnknownAuto](https://github.com/GitMag/UnknownAuto/), [Fermata #846](https://github.com/AndreyPavlenko/Fermata/issues/846)).
Zwei Wege:

**A) Installation mit KingInstaller oder UnknownAuto (kostenlos, Open Source)** – diese Apps
installieren die APK so, dass Android den Play Store als Installer einträgt:
1. KSS-Maps ggf. deinstallieren, die APK herunterladen (nicht öffnen).
2. [KingInstaller](https://github.com/fcaronte/KingInstaller) installieren → APK wählen →
   **Install** (ohne Schalter). Im „App Diagnostic Checker“ muss *Installed by: Play Store*
   stehen. Klappt das nicht: [Shizuku](https://github.com/RikkaApps/Shizuku) einrichten
   (Entwickleroptionen → Kabelloses Debugging → mit Shizuku koppeln → Start) und in
   KingInstaller den Schalter **Shizuku Trick** nutzen. Alternativ [UnknownAuto](https://github.com/GitMag/UnknownAuto/)
   (braucht Shizuku). Xiaomi/POCO/Redmi: nur mit Root.
3. Android-Auto-Einstellungen: 10× auf „Version“ → ⋮ → Entwicklereinstellungen →
   **„Unbekannte Quellen“** an; dann **„Launcher anpassen“** → KSS-Maps anhaken.
4. Android Auto beenden (App-Info → Beenden erzwingen), Handy neu mit dem Auto verbinden.

**B) Google Play (interner Test)** – offizieller Weg mit automatischen Updates: eigenes
Play-Console-Konto (einmalig 25 $, Identitätsprüfung), App als *interner Test* hochladen,
eigenes Google-Konto als Tester eintragen und KSS-Maps aus dem Play Store installieren.

## Termine aus COMP

Einstellungen → **„Termine & Kunden (COMP)“**: COMP-Benutzer und -Passwort eintragen →
**„Anmelden & testen“** zeigt, ob es klappt und wie viele Termine heute anstehen. Danach
erscheinen die Termine unter dem 📅-Knopf (Handy und Android Auto).

- **Nur lesend:** KSS-Maps holt die Techniker der Schicht und die Termine des Tages – genau wie
  die KSS-Dispo-App. Es öffnet keine Auftragsbearbeitung (die würde den Auftrag in COMP sperren)
  und ändert nichts.
- **Zugangsdaten** liegen verschlüsselt (Android Keystore) nur auf dem Handy, nie im Code, im
  Repo oder im Protokoll. „Abmelden“ löscht sie. Termine und Kundendaten bleiben nur im Speicher
  der App (nichts auf der Speicherkarte, nichts im Log).
- **Chef-Zugang** (sieht mehrere Techniker): unter „Termine“ oben den Techniker wählen – wird
  gespeichert. Ein Zugang, der nur einen Techniker sieht, zeigt keine Auswahl.
- Die **Telefonnummern der Techniker** aus COMP und die eigenen Nummern aus den Einstellungen
  werden nie als Kunden-Nummer angezeigt.

## Blitzer

Blitzer-, Abstands- und Überwachungswarnungen sind in KSS-Maps **standardmäßig aus** – dafür läuft
Blitzer.de Pro parallel. Einschalten: Einstellungen → „Blitzerwarnungen in KSS-Maps“ oder in
Android Auto unter „Mehr“. (Hinweis: Dem Fahrer ist die Nutzung von Blitzerwarnern während der
Fahrt verboten, § 23 Abs. 1c StVO.)

## Eingebaute API-Schlüssel (TomTom, Tankerkönig)

Damit nach einer Neuinstallation nichts mehr eingetragen werden muss, baut die CI die Schlüssel
aus zwei GitHub-Secrets in die APK ein: `KSSMAPS_TOMTOM_KEY` und `KSSMAPS_TANKERKOENIG_KEY`
(Repo → Settings → Secrets and variables → Actions → New repository secret, oder
`tools\setup_github.ps1`). Sie stehen **nie** im Repo; in der APK sind sie nur leicht
verschleiert – wer die öffentliche APK zerlegt, könnte sie auslesen. Bei Missbrauch: neuen
Schlüssel erzeugen, Secret ersetzen, neu bauen. Ein in den Einstellungen eingetragener eigener
Schlüssel hat Vorrang. Die Testfahrt baut ohne Schlüssel (ihre Protokolle sind öffentlich).

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
| Blitzer, Rotlicht, Abschnittskontrolle, Abstandsmessung (wenn eingeschaltet) | OpenStreetMap über Overpass-API (`highway=speed_camera`, `type=enforcement`, `mindistance`) | – |
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
             Valhalla/TomTom/Autobahn/Tankerkoenig/Geocoding/Overpass-Parser, Fuel (Sortierung, UTA),
             Comp (COMP-Seiten/-Antworten), ContactParser (Ansprechpartner aus dem Terminzusatz)
  data/      Http, Prefs (Einstellungen, Verlauf, gesperrte Abschnitte), Services (alle Netzaufrufe),
             CompClient + CompAppointmentSource (Termine aus COMP, nur lesend), SecretStore (Keystore)
  nav/       NavEngine (EIN Zustand für Handy und Auto), Voice (TTS über Navigations-Audiokanal)
  ui/        MapController (Karte für Handy UND Auto), Icons (alle Symbole gezeichnet), Panels
  car/       Android Auto: NaviCarAppService, CarMapRenderer (VirtualDisplay + MapLibre), Screens
  MainActivity, NavService (Vordergrunddienst während der Navigation), NaviApp
app/src/test/     Tests der Kernlogik (Parser, Abgleich, Ansagen, Sortierung, Gebäude …)
app/src/carTest/  Tests aller Android-Auto-Vorlagen (Robolectric, `-PcarTests`)
tools/smoketest.sh  Testfahrt im Emulator (CI, Commit-Nachricht mit „[testfahrt]“): Route,
                  Luftbild, gesperrter Abschnitt, Fahrt mit Ansagen, Ankunft, Tanken, Suche –
                  plus Bilder der Android-Auto-Karte (auf unsichtbarer Fläche gezeichnet)
tools/fetch_enforcement.py  Blitzer-Startbestand, Tankstellenliste und LED-Tempo-Strecken für die APK (CI)
```

## Automatische Prüfungen

- **Logik-Tests** (JVM): Routen-Parser, Blitzer-/Stau-Abgleich, Ansagetexte, Tankstellen-Sortierung,
  Suche, Gebäude-Erkennung – bei jedem Build.
- **Android-Auto-Tests** (Robolectric): jede Vorlage (Karte/Navigation, Routenauswahl, Suche, letzte
  Ziele, Tanken, Termine, Kunde, „Anrufen?“, „Mehr“, Hinweise) in allen Zuständen – Android Auto wirft bei falsch gebauten
  Vorlagen Ausnahmen, die hier auffallen statt im Auto.
- **Testfahrt im Emulator** (Commit-Nachricht mit `[testfahrt]`): Erststart-Hinweise, Ziel per
  geo-Link, Routen mit Baustellen, Luftbild, gesperrter Abschnitt, Navigation München →
  Freising mit Ansagen, Ankunft mit markiertem Zielhaus,
  Tankstellen, Suche, Querformat, eigener Startpunkt mit Wegbeschreibung, Sprachbefehl,
  Termine mit Kunden-Leiste und „Anrufen?“-Abfrage (nur erfundene Beispieltermine, es wird nie
  gewählt) – plus
  Bilder der Android-Auto-Karte und eine Messung, wie gleichmäßig der Positionspfeil läuft
  (`pfeil.txt`). Ergebnisse hängen als `testfahrt_*` an der Vorabversion `kssmaps-testfahrt`
  (nicht installieren – nur Testergebnisse). Der Emulator nutzt die ANGLE-Grafik
  (`-gpu swangle_indirect`); mit der älteren SwiftShader-Emulation fehlen Kartensymbole und
  Beschriftungen – nur im Emulator, nicht auf echten Geräten.

## Build & Release

Jeder Push baut und testet; das veröffentlichte APK im Release wird **nur** bei `[release]` in der
Commit-Nachricht (oder manuellem Start) ersetzt – so landet nie ein ungetesteter Zwischenstand
beim Download-Link.

`.github/workflows/kss-maps.yml`: Tests → signierte APK → GitHub-Release
`kssmaps-v<version>` mit `kss-maps.apk`. `version.json` ist die einzige Versionsquelle
(`versionCode = major*10000 + minor*100 + patch`). Die App prüft beim Start
die GitHub-Releases (neuestes `kssmaps-v…` mit `kss-maps.apk`) und bietet neue Versionen zum Download an (Installation über KingInstaller für Android Auto).
Beim Release: Version in `version.json` hochzählen, `notes` pflegen, `apkUrl` anpassen, pushen.
