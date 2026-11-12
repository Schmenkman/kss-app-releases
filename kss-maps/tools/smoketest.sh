#!/bin/bash
# Automatische Testfahrt im Android-Emulator (laeuft in der CI, siehe .github/workflows/kss-maps.yml).
# Ergebnis: Screenshots + Protokoll (Ansagen, Routen, Abstuerze) in ./smoke
set -u
PKG=de.sascha.kssmaps
APK=kss-maps/app/build/outputs/apk/debug/app-debug.apk
T=kss-maps/tools
OUT=smoke
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "Screenshot $1"; }
geo() { adb emu geo fix "$1" "$2" >/dev/null; }   # Laenge, Breite
act() { adb shell "am start -n $PKG/.MainActivity $*" >/dev/null; }
# Wartet, bis MUSTER mindestens N-mal im Protokoll steht (hoechstens S Sekunden)
wait_log() {
  local pat="$1" n="$2" max="$3" i=0 c
  while [ "$i" -lt "$max" ]; do
    c=$(grep -c -E "$pat" "$OUT/logcat.txt" 2>/dev/null); c=${c:-0}
    if [ "$c" -ge "$n" ]; then echo "OK: '$pat' x$c nach ${i}s"; return 0; fi
    sleep 2; i=$((i + 2))
  done
  echo "ZEITUEBERSCHREITUNG: '$pat' (${max}s)"; return 1
}
# Bildschirm drehen: quer (1) bzw. hochkant (0)
rotate() {
  adb shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
  adb shell cmd window user-rotation lock "$1" >/dev/null 2>&1 || adb shell settings put system user_rotation "$1"
  sleep 4
}
# Laeuft die App noch? Sonst Grund festhalten (Systemprotokoll) und neu starten, damit der Rest
# der Testfahrt weiterlaeuft.
ensure_alive() {
  if [ -z "$(adb shell pidof $PKG 2>/dev/null | tr -d '\r')" ]; then
    echo "APP BEENDET vor: $1 ($(date +%H:%M:%S))" | tee -a "$OUT/app_beendet.txt"
    adb shell dumpsys activity exit-info $PKG 2>/dev/null | head -80 >> "$OUT/app_beendet.txt"
    act
    sleep 20
    # Android-Auto-Flaeche gehoert zum alten Prozess: neu starten, sonst fehlen die Auto-Bilder
    act --ez test_car_start true
    sleep 10
  fi
}
# Anzahl der berechneten Routenvorschauen bisher
routes_n() { local c; c=$(grep -c "ROUTES " "$OUT/logcat.txt" 2>/dev/null); echo "${c:-0}"; }
# Android-Auto-Karte (unsichtbare Flaeche wie im Auto) als Bild sichern
car_shot() {
  act --es test_car_capture "$1"; sleep 3
  adb exec-out run-as $PKG cat "files/car_$1.png" > "$OUT/$2.png" 2>/dev/null
  if [ -s "$OUT/$2.png" ]; then echo "Auto-Bild $2"; else rm -f "$OUT/$2.png"; echo "Auto-Bild $2 fehlt"; fi
}

adb install -r -g "$APK" || { echo "Installation fehlgeschlagen"; exit 1; }

# Update-Pruefung aus; die Erststart-Hinweise werden unten wie von Hand weggeklickt
cat > /tmp/navi_prefs.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="cameras" value="true" />
    <long name="last_update_check" value="4102444800000" />
</map>
XML
adb push /tmp/navi_prefs.xml /data/local/tmp/navi_prefs.xml >/dev/null
adb shell "run-as $PKG mkdir -p /data/data/$PKG/shared_prefs"
adb shell "cat /data/local/tmp/navi_prefs.xml | run-as $PKG sh -c 'cat > /data/data/$PKG/shared_prefs/kss_maps.xml'"

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

# Start: Muenchen-Schwabing
geo 11.5850 48.1640
act
sleep 25
# Erster Start: Hinweis Blitzer/Abstandsmessungen und Einrichtungs-Hinweis wie ein Nutzer wegklicken
shot 00_erster_start
python3 $T/ui.py tap --text "Verstanden" --icase || echo "kein Blitzer-Hinweis"
sleep 3
shot 00b_einrichtung
python3 $T/ui.py tap --text "Später" --icase || adb shell input keyevent KEYCODE_BACK
sleep 3
geo 11.5851 48.1641
sleep 5
shot 01_start
# "Couch": 12 s wandernde GPS-Punkte (bis ~9 m) mit Rausch-Tempo -> erwartet 0 km/h, ruhiger Pfeil
for off in $(python3 -c "
import random
random.seed(3)
print(' '.join('%.6f,%.6f,%.1f' % (11.5851 + random.gauss(0, 0.00004), 48.1641 + random.gauss(0, 0.00003), abs(random.gauss(0.8, 0.5))) for _ in range(12)))
"); do
  IFS=, read -r glon glat gkn <<< "$off"
  adb emu geo fix "$glon" "$glat" 500 12 "$gkn" >/dev/null
  sleep 1
done
shot 01b_stillstand

# Ziel wie aus einer anderen App (geo:-Link) -> Suche, Routen, Vorschau
adb shell "am start -a android.intent.action.VIEW -d 'geo:0,0?q=Marienplatz%207%2C%2085354%20Freising' -n $PKG/.MainActivity" >/dev/null
wait_log "ROUTES " 1 150
sleep 6
shot 02_vorschau
# Querformat: Routenleiste links, Karte rechts frei
rotate 1
sleep 3
shot 02q_vorschau_quer
rotate 0
# Android-Auto-Kartenflaeche starten (zeigt jetzt die Routenvorschau)
act --ez test_car_start true
sleep 15
car_shot vorschau auto_01_vorschau
act --ez test_toggle_satellite true
sleep 15
shot 03_luftbild
act --ez test_toggle_satellite true
sleep 4

# Einen Abschnitt der schnellsten Route (bei km 14, A 9) sperren -> neue Route muss ihn umfahren
AV=$(python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --tag PREVIEW_CHUNK --point-at 14000 | tail -1)
echo "Sperrpunkt: $AV"
act --es test_avoid "'$AV'"
wait_log "ROUTES " 2 150
sleep 6
shot 03b_abschnitt_gesperrt
# Wegbeschreibung (Liste aller Abbiegungen)
python3 $T/ui.py tap --desc "Wegbeschreibung" || act --ez test_open_steps true
sleep 4
shot 03c_wegbeschreibung
act --ez test_close_panels true
sleep 2

# Eingebaute Stimmen: das Paket der Standardstimme ("Sprecherin 1", Supertonic, einmalig ~125 MB)
# laedt die App seit dem Start im Hintergrund. Bereit? Dann Hoerprobe als WAV (Testfahrt-Anhang).
# Danach Wechsel zu Thorsten (zweites Paket – bis es da ist, spricht die Sprecherin weiter), zu
# einem Sprecher und zurueck: die Fahrt spricht wieder mit der Standardstimme.
probe() { # Name
  local n
  n=$(( $(grep -c "NEURAL_WAV" "$OUT/logcat.txt" 2>/dev/null || true) + 1 ))
  act --es test_voice_wav probe
  wait_log "NEURAL_WAV" "$n" 120 || true
  adb exec-out run-as $PKG cat files/stimme_probe.wav > "$OUT/stimme_probe_$1.wav" 2>/dev/null
  if [ -s "$OUT/stimme_probe_$1.wav" ]; then echo "Hoerprobe $1: $(stat -c %s "$OUT/stimme_probe_$1.wav") Bytes"; else rm -f "$OUT/stimme_probe_$1.wav"; echo "keine Hoerprobe $1"; fi
}
wait_log "NEURAL_VOICE (ready|nicht verfuegbar)" 1 420 || true
probe sprecherin1
act --es test_neural_voice thorsten
wait_log "NEURAL_VOICE ready stimme=thorsten" 1 300 && probe thorsten
act --es test_neural_voice st-m2
wait_log "NEURAL_VOICE ready stimme=st-m2" 1 120 && probe sprecher2
act --es test_neural_voice st-f1
wait_log "NEURAL_VOICE ready stimme=st-f1" 2 120 || true

# "Los" tippen (Fallback: Test-Intent)
python3 $T/ui.py tap --text "Los" || act --ez test_start_navigation true
sleep 10
shot 04_navigation_start

# Melden-Knopf: Dialog oeffnen (Screenshot) und Abstandsmessung melden
python3 $T/ui.py tap --desc "Blitzer melden" && sleep 3 && shot 04b_melden && python3 $T/ui.py tap --text "📏 Abstandsmessung hier (4 Stunden)" --contains
sleep 3

# Fahrt bis kurz vor den ersten Blitzer der Route (Position aus ROUTE_INFO) – prueft Warnung,
# Banner und Ansage; danach am Blitzer vorbei. Ohne Blitzer auf der Route: die ersten 9 km.
CAM=$(grep -o "SPEED_CAMERA@[0-9]*" "$OUT/logcat.txt" | head -1 | cut -d@ -f2)
echo "Erster Blitzer auf der Route bei: ${CAM:-keiner} m"
if [ -n "$CAM" ] && [ "$CAM" -gt 1500 ] && [ "$CAM" -lt 25000 ]; then
  STOP=$((CAM - 250))   # im Stand gilt der 300-m-Warnbereich -> Banner bleibt sichtbar
  python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from 0 --to $STOP --speed 30 --shot-every 60 --prefix 05_fahrt
  shot 05b_blitzer_voraus
  car_shot blitzer auto_02_fahrt
  python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from $STOP --to $((CAM + 250)) --speed 22 --shot-every 100 --prefix 05c_blitzer
else
  # mit "Tunnel" von 6000 bis 6750 m: 30 s keine GPS-Punkte, der Pfeil soll mit dem letzten Tempo weiterfahren
  python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from 0 --to 9000 --speed 25 --shot-every 50 --prefix 05_fahrt --gap-from 6000 --gap-to 6750
  ensure_alive auto_fahrt
  car_shot fahrt auto_02_fahrt
fi
# Eigene Knoepfe auf der Android-Auto-Karte: Antippen trifft den richtigen Knopf (Probe ohne Auto -> Protokoll)
for b in RECENTER OVERVIEW DAYNIGHT CALL ZOOM_IN END; do act --es test_car_click $b; sleep 1; done
# Zoom und Uebersicht wie mit den Knoepfen im Auto: die Karte bleibt beim Zoomen bei der Position,
# die Uebersicht zeigt die Strecke bis zum Ziel genordet von oben, danach zurueck zur Fahransicht
ensure_alive auto_zoom
act --es test_car_action ZOOM_OUT; sleep 1; act --es test_car_action ZOOM_OUT; sleep 4
car_shot zoom_raus auto_04_zoom_raus
act --es test_car_action ZOOM_IN; sleep 1; act --es test_car_action ZOOM_IN; sleep 1; act --es test_car_action ZOOM_IN; sleep 4
car_shot zoom_rein auto_05_zoom_rein
act --es test_car_action OVERVIEW; sleep 25; act --es test_car_action OVERVIEW; sleep 5
car_shot uebersicht auto_06_uebersicht
act --es test_car_action OVERVIEW_CLOSE; sleep 5
car_shot zurueck auto_07_zurueck
# Querformat beim Fahren (wie Google Maps in Android Auto): 1,5 km mit 15 m/s
ensure_alive quer_fahrt
rotate 1
python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from 9000 --to 10500 --speed 15 --shot-every 30 --prefix 05q_quer
rotate 0
# Sprung kurz vors Ziel (prueft nebenbei die Neuberechnung) und langsam ankommen
python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from-end 1200 --to-end 0 --speed 9 --shot-every 35 --prefix 06_ankunft
sleep 25
shot 07_ziel_markiert
car_shot ziel auto_03_ziel

# Tankstellen-Panel (ohne Tankerkoenig-Schluessel: Tankstellen aus OpenStreetMap ohne Preise)
ensure_alive tanken
act --ez test_open_fuel true
sleep 25
shot 08_tanken
act --ez test_close_panels true
sleep 2

# Suche oeffnen und tippen (vorher Ziel loeschen, damit die Leiste "Wohin?" zeigt)
act --ez test_clear true
sleep 3
python3 $T/ui.py tap --text "Wohin?"
sleep 3
adb shell input text "Hauptbahnhof%sMuenchen"
sleep 8
shot 09_suche
# Startpunkt direkt in der Suche: "Von … aendern" -> Startsuche -> "Mein Standort" -> zurueck zur Zielsuche
python3 $T/ui.py tap --desc "Startpunkt ändern" && sleep 3 && shot 09b_startpunkt_in_suche
python3 $T/ui.py tap --text "Mein Standort" && sleep 3 && shot 09c_wieder_zielsuche
adb shell input keyevent KEYCODE_BACK
sleep 2

# Sprachbefehl (wie von der Google-Spracherkennung erkannt): direkt zur Routenvorschau
ensure_alive sprachbefehl
act --ez test_close_panels true
sleep 2
N=$(routes_n)
act --es test_voice "'Navigiere zur Allianz Arena'"
wait_log "ROUTES " $((N + 1)) 150
sleep 5
shot 11_sprachbefehl
# Eigener Startpunkt (Muenchen Stachus) -> Planung von dort; statt "Los" die Wegbeschreibung
ensure_alive startpunkt
N=$(routes_n)
act --es test_origin "48.13906,11.56575"
wait_log "ROUTES " $((N + 1)) 150
sleep 6
shot 12_start_ziel
ensure_alive start_ziel_quer
rotate 1
sleep 3
shot 12q_start_ziel_quer
python3 $T/ui.py tap --text "Wegbeschreibung" || act --ez test_open_steps true
sleep 4
shot 12b_wegbeschreibung_quer
act --ez test_close_panels true
rotate 0
# Startpunkt-Suche ("Von wo?") mit "Mein Standort" oben
act --ez test_open_origin_search true
sleep 4
shot 12c_startpunkt_suche
adb shell input keyevent KEYCODE_BACK
sleep 1
act --ez test_close_panels true
act --ez test_clear true
sleep 2

# Termine des Tages (ERFUNDENE Beispieltermine, nie echte Kunden): Liste -> Termin anfahren ->
# Knopf "Kunde" -> seitliche Leiste (Karte rueckt nach links) -> Nummer -> "Anrufen?" -> NEIN
ensure_alive termine
act --ez test_demo_appointments true
sleep 2
python3 $T/ui.py tap --desc "Termine" || act --ez test_open_appointments true
sleep 4
shot 13_termine_heute
N=$(routes_n)
python3 $T/ui.py tap --desc "Termin 11:00" --contains || act --ei test_pick_appointment 1
wait_log "ROUTES " $((N + 1)) 150
sleep 6
shot 13b_termin_vorschau
python3 $T/ui.py tap --desc "Kunde" || act --ez test_open_customer true
sleep 3
shot 13c_kunde_vorschau
act --ez test_close_customer true
sleep 1
python3 $T/ui.py tap --text "Los" || act --ez test_start_navigation true
sleep 8
# "Herr Probe 30 min vorher anrufen" bei nur ~7 Min. Fahrt: die Erinnerung kommt gleich beim Losfahren
if python3 $T/ui.py exists --text "Anrufen?"; then
  shot 13c2_anruf_erinnerung
  python3 $T/ui.py tap --text "Nein" --icase || adb shell input keyevent KEYCODE_BACK
  sleep 2
else
  echo "keine Anruf-Erinnerung beim Losfahren"
fi
python3 $T/ui.py tap --desc "Kunde" || act --ez test_open_customer true
sleep 3
shot 13d_kunde_fahrt
# Nummer antippen -> Abfrage "Anrufen?" (Screenshot) -> "Nein" (in der Testfahrt nie waehlen)
if python3 $T/ui.py tap --desc "anrufen:" --contains; then
  sleep 2
  shot 13e_anrufen_frage
  python3 $T/ui.py tap --text "Nein" --icase || adb shell input keyevent KEYCODE_BACK
  sleep 2
fi
python3 $T/ui.py exists --text "Anrufen?" && { echo "Anruf-Abfrage noch offen"; adb shell input keyevent KEYCODE_BACK; }
ensure_alive kunde_quer
rotate 1
sleep 3
shot 13q_kunde_quer
rotate 0
sleep 2
act --ez test_close_customer true
act --ez test_clear true
sleep 2

# Einstellungen: Schluessel-Felder mit "Einfuegen" (Zwischenablage leer) und "Pruefen" (leer)
ensure_alive einstellungen
act --ez test_close_panels true
sleep 2
act --ez test_open_settings true
sleep 4
shot 10_einstellungen
python3 $T/ui.py tap --text "Einfügen" || echo "kein Einfügen-Knopf"
sleep 2
python3 $T/ui.py tap --text "Prüfen" || echo "kein Prüfen-Knopf"
sleep 3
shot 10b_schluessel_knoepfe
# weiter nach unten: COMP-Zugang (leer), dann Schalter "Blitzerwarnungen in KSS-Maps" (Standard: aus)
adb shell input swipe 540 1900 540 700 400
sleep 1
adb shell input swipe 540 1900 540 700 400
sleep 2
shot 10c_comp_zugang
adb shell input swipe 540 1900 540 700 400
sleep 1
adb shell input swipe 540 1900 540 900 400
sleep 2
shot 10d_warnungen
# weiter: "Stimme der Ansagen" (eingebaute natuerliche Stimme, Status)
adb shell input swipe 540 1900 540 1000 400
sleep 2
shot 10e_stimme
# Auswahl der eingebauten Stimme (nur ansehen, zurueck ohne Wahl)
if python3 $T/ui.py tap --text "– ändern" --contains; then
  sleep 3
  shot 10f_stimmwahl
  adb shell input keyevent KEYCODE_BACK
  sleep 2
fi
act --ez test_close_panels true
sleep 2

kill $LOGPID 2>/dev/null
sleep 1
grep -E "AndroidRuntime|FATAL EXCEPTION|SAY|ROUTES|ROUTE_INFO|TEST_|KSSMaps" "$OUT/logcat.txt" | grep -v -E "ROUTE_CHUNK|PREVIEW_CHUNK" > "$OUT/ereignisse.txt"
grep "PUCK_SMOOTH" "$OUT/logcat.txt" > "$OUT/pfeil.txt" || echo "keine Messung" > "$OUT/pfeil.txt"
grep -E "NEURAL_|TEST_VOICE_WAV|SAY|TTS_VOICE|AudioTrack" "$OUT/logcat.txt" > "$OUT/stimme.txt" || echo "keine Ansagen" > "$OUT/stimme.txt"
grep -E " [EW]/" "$OUT/logcat.txt" | grep -i -E "kssmaps|maplibre|mbgl|AndroidRuntime" | grep -v -E "ROUTE_CHUNK|PREVIEW_CHUNK" | tail -300 > "$OUT/warnungen.txt"
grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/absturz.txt" || echo "kein Absturz" > "$OUT/absturz.txt"
# Native Abstuerze (Kartenbibliothek), Speicher-Notabschaltung, Prozess-Ende, ANR – mit Umgebung
grep -n -E "F/libc|F/DEBUG|Fatal signal|Abort message|backtrace:|lowmemorykiller|lmkd|has died|ANR in|Watchdog|FATAL EXCEPTION IN SYSTEM" "$OUT/logcat.txt" | head -400 > "$OUT/absturz_details.txt" || true
[ -s "$OUT/absturz_details.txt" ] || echo "keine nativen Abstuerze / Prozess-Enden" > "$OUT/absturz_details.txt"
adb shell dumpsys activity exit-info $PKG 2>/dev/null | head -150 > "$OUT/exit_info.txt"
echo "===== Absturz?"; head -60 "$OUT/absturz.txt"
echo "===== Ereignisse"; head -200 "$OUT/ereignisse.txt"
exit 0
