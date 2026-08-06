# Tomorrowland Watcher

Überwacht die offiziellen Tomorrowland-Seiten und schickt dir eine Push-Nachricht
aufs Handy, sobald sich etwas zur **Vorregistrierung** oder zum **Ticketverkauf**
für 2027 tut. Läuft auf einem Raspberry Pi als systemd-Timer.

Das Tool **beobachtet und benachrichtigt nur**. Es loggt sich nirgends ein, stellt
sich in keine Warteschlange und kauft nichts. Automatisierter Ticketkauf verstößt
gegen die Tomorrowland-AGB und führt zur Sperrung des Accounts – genau des
Accounts, den du für die Vorregistrierung brauchst.

## Warum das reicht

Der Verkauf läuft über eine **Pflicht-Vorregistrierung** mit anschließend
**zufällig zugeloster** Warteschlangenposition. Wer auf die Millisekunde klickt,
hat keinen Vorteil. Der einzige Punkt, an dem du wirklich etwas verlieren kannst,
ist das **Verpassen des Registrierungsfensters** – und genau das verhindert dieses
Tool.

## Was du bekommst

| Ereignis | Priorität | Wann |
|---|---|---|
| Zeile mit „pre-registration"/„ticket sale" + 2027 oder Datum gefunden | 🔴 urgent | sofort, einmalig pro Fund |
| Sonstige Änderung auf einer Seite | 🟡 default | mit Auszug der neuen Zeilen |
| Countdown zu eingetragenem Termin | 🔴 urgent / 🟠 high | 7 Tage / 24 h / 2 h / 15 min vorher |
| Seite über mehrere Läufe nicht erreichbar | 🟠 high | nach 3 Fehlversuchen |
| Lebenszeichen | ⚪ min | alle 24 h |

## Installation auf dem Pi

```bash
git clone <dieses-repo> ~/kss-app-releases
cp -r ~/kss-app-releases/tomorrowland-watcher ~/tomorrowland-watcher
cd ~/tomorrowland-watcher
./install.sh
```

`install.sh` legt ein venv an, installiert `requests`, erzeugt `config.json` aus
der Vorlage und aktiviert den systemd-Timer (alle 30 Minuten, mit Zufallsversatz).

## Push aufs Handy einrichten

### Variante A: ntfy (empfohlen, kein Account nötig)

1. App **ntfy** installieren (iOS App Store / Google Play / F-Droid).
2. Ein Topic ausdenken, das niemand erraten kann – z. B. `tml-watch-sascha-9f3k2x`.
   Wer das Topic kennt, sieht deine Nachrichten, also nicht `tomorrowland` nehmen.
3. In der App auf „+" und das Topic abonnieren.
4. In `config.json` unter `notify.ntfy.topic` dasselbe Topic eintragen.

### Variante B: Telegram

1. Bei `@BotFather` einen Bot anlegen → Token.
2. Bot anschreiben, dann Chat-ID über `@userinfobot` holen.
3. In `config.json` unter `notify.telegram` eintragen und `enabled` auf `true`.

Beides gleichzeitig geht auch.

## Prüfen, ob alles läuft

```bash
cd ~/tomorrowland-watcher
./.venv/bin/python watcher.py --check-sources   # sind alle URLs erreichbar?
./.venv/bin/python watcher.py --test-notify     # kommt eine Push an?
./.venv/bin/python watcher.py --once -v         # ein Durchlauf mit Log
systemctl --user list-timers tomorrowland-watcher.timer
journalctl --user -u tomorrowland-watcher.service -n 50
```

**Wichtig:** `--check-sources` beim ersten Mal ausführen. Tomorrowland baut die
Seitenstruktur zwischen den Editionen regelmäßig um. Was `PRÜFEN` oder `FEHLER`
meldet, in `config.json` korrigieren oder auf `"enabled": false` setzen.

## Termine eintragen

Sobald der Vorregistrierungstermin bekannt ist:

```bash
./.venv/bin/python watcher.py --add-date "Vorregistrierung öffnet" 2026-12-08T15:00
./.venv/bin/python watcher.py --add-date "Vorregistrierung schließt" 2027-01-29T20:00
./.venv/bin/python watcher.py --add-date "Global Journey Verkauf" 2027-01-14T17:00
```

Danach kommen automatisch Countdown-Pushes 7 Tage, 24 Stunden, 2 Stunden und
15 Minuten vorher.

## Erwarteter Zeitplan 2027

Abgeleitet aus der 2026er Edition – **unbestätigt**, deshalb der Watcher:

| Phase | 2026 (real) | Erwartung 2027 |
|---|---|---|
| Vorregistrierung öffnet | Mo, 08.12.2025, 15:00 CET | Anfang Dezember 2026 |
| Vorregistrierung schließt | Fr, 30.01.2026, 20:00 CET | Ende Januar 2027 |
| Global Journey Verkauf | Januar 2026 | Januar 2027 |
| Worldwide Pre-Sale / Sale | Januar/Februar 2026 | Januar/Februar 2027 |

## Checkliste für den Verkaufstag (4 Tickets)

- [ ] **Alle 4 Personen registrieren sich einzeln** – vier Lose statt einem ist der
      größte legale Hebel. Wer drankommt, kauft für die Gruppe.
- [ ] Tomorrowland-Account je Person angelegt und **Identität verifiziert**
      (häufigster Stolperstein im Dezember)
- [ ] Ausweisdaten aller 4 Personen griffbereit – Tickets sind personalisiert und
      werden am Eingang geprüft
- [ ] Kreditkarte im Account hinterlegt, Limit ausreichend, **3-D-Secure getestet**
- [ ] Global Journey Packages als Alternative eingeplant (eigener, früherer
      Verkauf, deutlich weniger überlaufen)
- [ ] Offizielle Resale-Plattform nach dem Verkauf im Blick behalten

## Konfiguration

Alle Optionen stehen kommentiert in `config.example.json`. Die wichtigsten:

- `poll_interval_minutes` – Standard 30. Bitte nicht deutlich niedriger setzen;
  ein Pi, der im Sekundentakt anfragt, fällt auf und bringt nichts.
- `sources` – die überwachten Seiten, einzeln abschaltbar.
- `watch_terms.year` – auf `"2027"` gesetzt, für spätere Jahre anpassen.
- `heartbeat_hours` – auf `0` setzen, wenn dich das tägliche Lebenszeichen nervt.

## Tests

```bash
python3 test_watcher.py
```

Läuft ohne Netz und deckt Textextraktion, Datumserkennung, Signalerkennung,
Deduplizierung und Diff-Ausgabe ab.

## Dateien

```
watcher.py                     Hauptskript
config.example.json            Konfigurationsvorlage
test_watcher.py                Offline-Tests
install.sh                     Setup für den Pi
systemd/*.service, *.timer     systemd-User-Units
state.json, snapshots/         Laufzeitdaten (nicht im Git)
```
