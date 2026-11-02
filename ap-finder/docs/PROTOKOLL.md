# Protokoll Handy ↔ ESP32-C5

Transport: USB-CDC (natives USB-Serial/JTAG des ESP32-C5, VID `0x303A`).
Die Baudrate spielt keine Rolle. Beim Öffnen **DTR = 1, RTS = 0** setzen,
denn RTS ohne DTR setzt den ESP32 zurück.

* **Handy → ESP:** eine Textzeile pro Befehl, abgeschlossen mit `\n`.
* **ESP → Handy:** eine JSON-Zeile pro Nachricht, abgeschlossen mit `\n`.
  Zeilen, die nicht mit `{` beginnen, sind zu ignorieren, z. B. die
  Bootmeldungen des ROM-Bootloaders.

## Befehle

| Befehl | Bedeutung |
|---|---|
| `HELLO` | Kennung abfragen → `hello` |
| `PING` | Lebenszeichen → `pong` |
| `STOP` | Messung beenden (Leerlauf) |
| `SCAN [dwell_ms] [bands]` | Kanal-Hopping-Scan. `dwell_ms` 40–1000 (Standard 110), `bands` = `2`, `5` oder `25` (Standard) |
| `TRACK <ch> <probe_hz> <bssid[,bssid…]> [ssid_hex]` | Fest auf Kanal `ch`, misst bis zu 8 BSSIDs. `probe_hz` 0–50: gerichtete Probe Requests pro Sekunde (0 = nur passiv). Ohne `ssid_hex` gehen die Probe Requests mit Wildcard-SSID raus |
| `FTM <ch> <bssid> [frames]` | Einmalige Laufzeitmessung (802.11mc), `frames` ∈ {0,16,24,32,64}, Standard 32 |
| `DATA <0\|1>` | Datenframes des APs mitzählen (nur Pegel, kein Inhalt) |

MAC-Adressen dürfen mit `:`, `-`, `.` oder ohne Trenner geschrieben werden.

## Nachrichten

```jsonc
{"t":"hello","fw":"0.1.0","chip":"esp32c5","idf":"v6.0.3","mac":"aa:bb:cc:dd:ee:ff"}
{"t":"ack","cmd":"SCAN","dwell":110,"nch":37}
{"t":"ap","b":"00:00:5e:00:53:20","s":"@BayernWLAN","h":0,"ch":6,"r":-61,"n":2,
 "txp":17,"ftm":1,"enc":"open","std":"ax","nm":"BY-Rathaus-OG1-Flur","bi":100}
{"t":"sweep","no":3,"ms":4170,"bss":41,"drop":0}
{"t":"ack","cmd":"TRACK","ch":6,"n":3,"probe":10}
{"t":"rssi","ms":123400,"ch":6,"nb":1,"rb":-55.0,"xb":-55,"mb":-55,
 "np":1,"rp":-53.0,"xp":-53,"nd":0,"rd":null,"xd":null,"nf":-96,"tx":1,"te":0}
{"t":"ftm","b":"00:00:5e:00:53:30","ok":true,"status":0,"dist_cm":432,"rtt_ns":29,"rtt_raw":31,"n":24}
{"t":"st","mode":"idle","ms":5000}
{"t":"err","msg":"TRACK: ungueltiger Kanal"}
```

| Feld | Bedeutung |
|---|---|
| `ap.b` / `s` / `h` | BSSID, SSID (UTF-8), versteckt (1/0) |
| `ap.ch` / `r` / `n` | Kanal, bester Pegel (dBm) in der Verweilzeit, Anzahl Frames |
| `ap.txp` | Sendeleistung laut TPC-Report bzw. Cisco-DTPC (dBm) oder `null` |
| `ap.ftm` | AP kündigt FTM-Responder an (Extended Capabilities, Bit 70) |
| `ap.enc` / `std` | `open`/`wep`/`rsn`; höchster Standard `n`/`ac`/`ax` |
| `ap.nm` | AP-Name aus Hersteller-Element (Cisco/Meraki, Aruba, Ruckus, Huawei, Mist, Ubiquiti, Extreme, Fortinet, Arista, Alcatel-Lucent, …) |
| `rssi.nb` / `rb` / `xb` / `mb` | Beacons im 100-ms-Fenster: Anzahl, Mittel, Max, Min |
| `rssi.np` / `rp` / `xp` | Probe Responses (Antworten auf unsere Probe Requests): Anzahl, Mittel, Max – getrennt, weil APs mit adaptiven Antennen (Ruckus BeamFlex) Unicast-Antworten anders abstrahlen könnten |
| `rssi.nd` / `rd` / `xd` | Datenframes/sonstige Mgmt-Frames des APs (nur mit `DATA 1`) |
| `rssi.nf` | Rauschboden (dBm) |
| `rssi.tx` / `te` | gesendete Probe Requests / Sendefehler im Fenster (z. B. DFS-Kanal) |
| `ftm.dist_cm` / `rtt_ns` | geschätzte Entfernung / Laufzeit; `status` 1 = AP kann kein FTM, 3 = keine Antwort |
