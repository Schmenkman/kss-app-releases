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

# Ziel wie aus einer anderen App (geo:-Link) -> Suche, Routen, Vorschau
adb shell "am start -a android.intent.action.VIEW -d 'geo:0,0?q=Marienplatz%207%2C%2085354%20Freising' -n $PKG/.MainActivity" >/dev/null
wait_log "ROUTES " 1 150
sleep 6
shot 02_vorschau
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
  python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from 0 --to 9000 --speed 25 --shot-every 50 --prefix 05_fahrt
  car_shot fahrt auto_02_fahrt
fi
# Sprung kurz vors Ziel (prueft nebenbei die Neuberechnung) und langsam ankommen
python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from-end 1200 --to-end 0 --speed 9 --shot-every 35 --prefix 06_ankunft
sleep 25
shot 07_ziel_markiert
car_shot ziel auto_03_ziel

# Tankstellen-Panel (ohne Tankerkoenig-Schluessel: Tankstellen aus OpenStreetMap ohne Preise)
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
adb shell input keyevent KEYCODE_BACK
sleep 2

# Einstellungen: Schluessel-Felder mit "Einfuegen" (Zwischenablage leer) und "Pruefen" (leer)
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
act --ez test_close_panels true
sleep 2

kill $LOGPID 2>/dev/null
sleep 1
grep -E "AndroidRuntime|FATAL EXCEPTION|SAY|ROUTES|ROUTE_INFO|TEST_|KSSMaps" "$OUT/logcat.txt" | grep -v -E "ROUTE_CHUNK|PREVIEW_CHUNK" > "$OUT/ereignisse.txt"
grep -E " [EW]/" "$OUT/logcat.txt" | grep -i -E "kssmaps|maplibre|mbgl|AndroidRuntime" | grep -v -E "ROUTE_CHUNK|PREVIEW_CHUNK" | tail -300 > "$OUT/warnungen.txt"
grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/absturz.txt" || echo "kein Absturz" > "$OUT/absturz.txt"
echo "===== Absturz?"; head -60 "$OUT/absturz.txt"
echo "===== Ereignisse"; head -200 "$OUT/ereignisse.txt"
exit 0
