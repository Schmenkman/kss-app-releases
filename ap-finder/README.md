# AP-Finder

Verdeckt montierte WLAN-Access-Points (z. B. BayernWLAN über abgehängten
Decken) mit dem Android-Handy und einem **ESP32-C5 mit Richtantenne** finden:
**Richtung** (Peilung), **ungefähre Entfernung** (Pegel, Elevation zur Decke,
FTM-Laufzeit) und **Feinsuche mit Ton**. Den Ziel-AP wählt man aus der Liste
oder sucht ihn über die **MAC vom Typenschild**.

* Recherche, Verfahrensvergleich, Vorgehen vor Ort, Ruckus-Besonderheiten:
  **[KONZEPT.md](KONZEPT.md)**
* Einkaufsliste mit Links und Verkabelung: **[HARDWARE.md](HARDWARE.md)**
* Firmware: [firmware/](firmware/) · Protokoll: [docs/PROTOKOLL.md](docs/PROTOKOLL.md)
* App (Capacitor, Vanilla-JS wie die anderen KSS-Apps): [app/](app/)

## Schnellstart

1. **Hardware** laut [HARDWARE.md](HARDWARE.md) besorgen: XIAO ESP32-C5,
   Dual-Band-Panelantenne, Pigtail, Adapter, 20-dB-Dämpfungsglied,
   Rundstrahler, USB-C-Kabel, Handyhalter.
2. **Firmware flashen:** siehe [firmware/README.md](firmware/README.md).
   Die fertige `.bin` baut die GitHub-Actions-Pipeline.
3. **App installieren:** Die APK (`ap-finder-debug.apk`) liegt im Artefakt
   `ap-finder-apk` des Laufs „AP-Finder Build“.
4. Messkopf per USB-C anstecken. Android fragt, ob AP-Finder geöffnet werden
   soll: „Immer“ wählen, dann ist auch der USB-Zugriff erlaubt.
5. **Geräte:** Der Scan läuft automatisch. Standardmäßig erscheinen nur APs
   mit der SSID `@BayernWLAN`. Jede Karte ist ein physischer AP mit der
   vermuteten Typenschild-MAC (Ruckus: `…x0`) und seinen Funkmodulen für
   2,4 und 5 GHz. Typenschild-MAC eingeben → „Treffer 98 %“ → „Orten“.
   **Suchen:** Pegel, Ton, Entfernung, Messpunkte. **Peilen:** Rundum drehen,
   der Pfeil zeigt danach zum AP. Mit „Elevation“ ergibt sich der Abstand
   über die Deckenhöhe.

## Ohne Hardware ausprobieren

```bash
cd ap-finder/app
npm install
npm run serve          # → http://localhost:8080 → „Verbinden“ → „Simulator“
npm test               # Logik-Tests (MAC-Suche, Peilung, Entfernung, Simulator)
node test/ui.e2e.mjs   # UI-Durchlauf mit Playwright (optional)
```

Im Chrome am PC funktioniert mit angestecktem ESP32-C5 auch „Web Serial“.
Damit lässt sich die Firmware ohne APK testen.

## APK selbst bauen

```bash
cd ap-finder/app
npm ci
npx cap sync android
cd android && ./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Für Updates über die App (wie bei den anderen KSS-Apps) prüft AP-Finder
`apfinder-version.json` im Release-Repo. Diese Datei gibt es noch nicht – sie
wird erst mit einem Release angelegt.
