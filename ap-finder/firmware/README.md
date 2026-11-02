# AP-Finder – Firmware für ESP32-C5

Der ESP32-C5 arbeitet als Funk-Messkopf am Handy. Er scannt beide Bänder,
misst gezielt die BSSIDs eines APs mit 10–50 Werten/s und kann eine
FTM-Laufzeitmessung auslösen. Das Protokoll ist in
[`../docs/PROTOKOLL.md`](../docs/PROTOKOLL.md) beschrieben.

## Fertige Firmware flashen (ohne Entwicklungsumgebung)

1. Die GitHub-Actions-Pipeline baut bei jedem Push die Datei
   `ap-finder-esp32c5-v6.0.3.bin`. Sie liegt im Artefakt
   `firmware-esp32c5-v6.0.3` des Laufs „AP-Finder Build“.
2. Im Chrome am PC <https://espressif.github.io/esptool-js/> öffnen und auf
   „Connect“ klicken. Das Board am **nativen USB-Port** anstecken.
   Klappt das nicht: die BOOT-Taste beim Anstecken gedrückt halten.
3. Die Datei bei **Flash Address `0x0`** eintragen und „Program“ wählen.

Alternativ per Kommandozeile:
`esptool --chip esp32c5 write_flash 0x0 ap-finder-esp32c5-v6.0.3.bin`

## Selbst bauen

```bash
# ESP-IDF v6.0 (oder v5.5) installiert und exportiert
cd ap-finder/firmware
idf.py set-target esp32c5
idf.py build flash
```

Host-Unit-Tests für Parser und Protokoll (ohne ESP-IDF, mit gcc oder clang):

```bash
make -C ap-finder/firmware/test
```

## Hinweise

* **USB-Port:** Das JSON-Protokoll läuft über den nativen USB-Port
  (USB-Serial/JTAG). Konsole und Logs liegen auf UART0. Beim XIAO ESP32-C5
  gibt es nur einen USB-Port, und der ist richtig. Beim DevKitC die Buchse
  „USB“ nehmen, nicht „UART“.
* **Antenne:** Ein Board mit U.FL-Anschluss verwenden, z. B. XIAO ESP32-C5
  oder ein Board mit Modul ESP32-C5-WROOM-1**U**. Die Richtantenne kommt über
  ein Pigtail von U.FL auf RP-SMA dran.
* **Ein Band zur Zeit:** Der C5 hört nie auf 2,4 und 5 GHz gleichzeitig. Der
  Scan wechselt die Kanäle, die Messung (TRACK) bleibt fest auf einem Kanal.
* **Ländereinstellung:** fest „DE“ (2,4 GHz Kanäle 1–13, 5 GHz nach EU-Regeln).
* **Frame-Typen:** Auf 5 GHz meldet der C5-Callback teils falsche Typen. Die
  Firmware wertet deshalb den 802.11-Header selbst aus.
