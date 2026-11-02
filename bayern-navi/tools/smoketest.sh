#!/bin/bash
# Automatische Testfahrt im Android-Emulator (laeuft in der CI, siehe .github/workflows/bayern-navi.yml).
# Ergebnis: Screenshots + Protokoll (Ansagen, Routen, Abstuerze) in ./smoke
set -u
PKG=de.sascha.bayernnavi
APK=bayern-navi/app/build/outputs/apk/debug/app-debug.apk
T=bayern-navi/tools
OUT=smoke
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "Screenshot $1"; }
geo() { adb emu geo fix "$1" "$2" >/dev/null; }   # Laenge, Breite
act() { adb shell "am start -n $PKG/.MainActivity $*" >/dev/null; }

adb install -r -g "$APK" || { echo "Installation fehlgeschlagen"; exit 1; }

# Erststart-Dialoge ueberspringen (Blitzer-Frage beantwortet, Schluessel-Hinweis gezeigt)
cat > /tmp/navi_prefs.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="cameras_asked" value="true" />
    <boolean name="cameras" value="true" />
    <boolean name="setup_shown" value="true" />
    <long name="last_update_check" value="4102444800000" />
</map>
XML
adb push /tmp/navi_prefs.xml /data/local/tmp/navi_prefs.xml >/dev/null
adb shell "run-as $PKG mkdir -p /data/data/$PKG/shared_prefs"
adb shell "cat /data/local/tmp/navi_prefs.xml | run-as $PKG sh -c 'cat > /data/data/$PKG/shared_prefs/bayern_navi.xml'"

adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGPID=$!

# Start: Muenchen-Schwabing
geo 11.5850 48.1640
act
sleep 30
geo 11.5851 48.1641
sleep 5
shot 01_start

# Ziel wie aus einer anderen App (geo:-Link) -> Suche, Routen, Vorschau
adb shell "am start -a android.intent.action.VIEW -d 'geo:0,0?q=Marienplatz%207%2C%2085354%20Freising' -n $PKG/.MainActivity" >/dev/null
sleep 40
shot 02_vorschau
act --ez test_toggle_satellite true
sleep 15
shot 03_luftbild
act --ez test_toggle_satellite true
sleep 4

# "Los" tippen (Fallback: Test-Intent)
python3 $T/ui.py tap --text "Los" || act --ez test_start_navigation true
sleep 10
shot 04_navigation_start

# Fahrt: erste 9 km mit 90 km/h
python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from 0 --to 9000 --speed 25 --shot-every 50 --prefix 05_fahrt
# Sprung kurz vors Ziel (prueft nebenbei die Neuberechnung) und langsam ankommen
python3 $T/drive.py --log "$OUT/logcat.txt" --out "$OUT" --from-end 1200 --to-end 0 --speed 9 --shot-every 35 --prefix 06_ankunft
sleep 25
shot 07_ziel_markiert

# Tankstellen-Panel (ohne Tankerkoenig-Schluessel: Hinweis erwartet)
act --ez test_open_fuel true
sleep 10
shot 08_tanken
act --ez test_close_panels true
sleep 2

# Suche oeffnen und tippen
python3 $T/ui.py tap --text "Wohin?" || python3 $T/ui.py tap --text "Marienplatz 7" --contains
sleep 3
adb shell input text "Hauptbahnhof%sMuenchen"
sleep 8
shot 09_suche
adb shell input keyevent KEYCODE_BACK
sleep 2

kill $LOGPID 2>/dev/null
sleep 1
grep -E "AndroidRuntime|FATAL EXCEPTION|SAY|ROUTES|ROUTE_INFO|BayernNavi" "$OUT/logcat.txt" | grep -v ROUTE_CHUNK > "$OUT/ereignisse.txt"
grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/absturz.txt" || echo "kein Absturz" > "$OUT/absturz.txt"
echo "===== Absturz?"; head -60 "$OUT/absturz.txt"
echo "===== Ereignisse"; head -200 "$OUT/ereignisse.txt"
exit 0
