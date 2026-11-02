# AP-Finder – Recherche & Konzept

Ziel: WLAN-Access-Points (z. B. BayernWLAN), die verdeckt montiert sind (über
abgehängten Decken, in Schränken, hinter Verkleidungen), mit dem Handy plus
Zusatzhardware **in Richtung und ungefährer Entfernung orten**. Den Ziel-AP
wählt man aus einer Liste oder sucht ihn über die MAC-Adresse vom Typenschild.

---

## Kurzfassung – was ist die beste Lösung?

**Empfehlung: ESP32-C5 als Dual-Band-Messempfänger (2,4 + 5 GHz) per USB-C am
Android-Handy, mit einer Dual-Band-Richtantenne (Panel/Patch) und einem
20-dB-Dämpfungsglied für den Nahbereich.** Die App kombiniert drei
Messgrößen:

| Messgröße | liefert | typische Güte (Schätzung, siehe 4.4) |
|---|---|---|
| Signalstärke (RSSI), 10–50 Messungen/s | „heiß/kalt“, Feinsuche mit Ton | Feinsuche meist auf 1 Deckenplatte |
| Richtantenne + Lagesensor des Handys | **Richtung** (Peilung, Polardiagramm) | ±15–30° in Gebäuden, ±10° bei Sicht |
| Elevation zur Decke + bekannte Raumhöhe | **horizontale Entfernung** im Nahbereich | ±0,5–1 m bis ca. 4 m Abstand |
| RSSI-Pfadverlust | **grobe Entfernung** als Bereich | Faktor ~2 („3–8 m“) |
| FTM/802.11mc-Laufzeit (falls der AP es kann) | **Entfernung** unabhängig von Wänden | ±1–2 m |

So arbeiten auch Profi-Messgeräte: Der NetAlly AirCheck G3 ortet APs mit
eingebauten Antennen oder einer externen Richtantenne und gibt einen Ton aus,
der beim Annähern schneller und höher wird ([NetAlly][netally-locate],
[NetAlly KB][netally-kb]). Der hier beschriebene Eigenbau kostet ca. 85–125 €
statt mehrerer tausend Euro – Einkaufsliste mit Links: **[HARDWARE.md](HARDWARE.md)**.

Das Handy allein reicht nicht. Android lässt Apps im Vordergrund nur **4 WLAN-Scans
pro 2 Minuten** machen ([Android Developers][android-scan]). iOS bietet gar keine
WLAN-Scan-Schnittstelle. Der ESP32-C5 hört dagegen *jeden* Beacon des Ziel-APs mit,
etwa 10 pro Sekunde und SSID. Zusätzlich fragt er den AP aktiv an (Probe Request),
was weitere Messwerte liefert.

---

## 1. Ausgangslage

* BayernWLAN ist ein Verbund vieler Standorte. Vodafone ist Technologiepartner, außerdem
  strahlen z. B. die LRZ-APs im Münchner Wissenschaftsnetz BayernWLAN aus
  ([mobiflip][mobiflip-bw], [LRZ][lrz-bw]).
* **Zu suchende Geräte (Auftrag):** Ruckus **R350, R500, R510, R550, R610,
  R650** (innen) sowie **T300, T310c/s, T350c/se** (außen), SSID immer
  **`@BayernWLAN`**. Die Lösung bleibt herstellerneutral, ist aber auf diese
  Geräte abgestimmt – siehe Kapitel 5a.
* Typische Montage: über Mineralfaser-Rasterdecken (62,5 × 62,5 cm). Diese
  Platten dämpfen Funk kaum, Metall-Kassettendecken, Kabeltrassen und
  Lüftungskanäle dagegen stark (Reflexionen!).
* Die gesuchten Geräte senden auf 2,4 GHz **und** 5 GHz. Jede SSID je
  Funkmodul ist eine eigene BSSID (Funk-MAC). Weil alle APs dieselbe SSID
  `@BayernWLAN` haben, unterscheidet man sie nur über BSSID, Kanal und Pegel
  (und ggf. den AP-Namen). Die MAC vom Typenschild ist deshalb der
  wichtigste Schlüssel.

## 2. Physik in einer Minute

* **RSSI** (Empfangspegel) fällt im Freiraum mit 20 dB pro Zehnfacher Entfernung
  (6 dB je Verdopplung). Bei 1 m Abstand sind es ca. 40 dB (2,4 GHz) bzw. 47 dB
  (5,5 GHz) Freiraumdämpfung. In Gebäuden kommen Wände, Decken, Körper und
  **Mehrwegeausbreitung** dazu: der Pegel schwankt bei kleinen Bewegungen um
  ±5–10 dB (Fading). Deshalb: viele Messungen mitteln, beide Bänder nutzen,
  langsam bewegen.
* **Nahbereich:** Je näher man kommt, desto steiler wird der Pegelverlauf –
  das ist der Grund, warum die Feinsuche („heiß/kalt“) gut funktioniert,
  die Entfernungsschätzung aus dem absoluten Pegel aber schlecht.
* **Richtantennen** bündeln Empfang in eine Richtung. Die Peilung findet das
  Maximum beim Drehen. Indoor kann das Maximum auch eine Reflexion sein –
  deshalb aus 2–3 Positionen peilen (Kreuzpeilung).
* **5 GHz ist für die Feinsuche besser:** kürzere Wellenlänge → bei gleicher
  Antennengröße schmalere Keule (ALFA APA-M25: 10 dBi bei 5 GHz), stärkere
  Dämpfung durch Wände → Signal „aus dem Nachbarraum“ stört weniger.
  **2,4 GHz ist für die Grobsuche besser** (größere Reichweite).

## 3. Verfahren im Vergleich

| # | Verfahren | Richtung | Entfernung | Hardware/Aufwand | Fazit |
|---|---|---|---|---|---|
| 3.1 | Handy-WLAN-Scan (RSSI) | – | sehr grob | keine | zu langsam (Scan-Drosselung), nur Notlösung |
| 3.2 | ESP32-C5 + Rundstrahler (RSSI, schnell) | indirekt (Körperabschattung) | grob (Bereich) | ~15 € | **Basis** für Grob- und Feinsuche |
| 3.3 | ESP32-C5 + **Richtantenne** | **ja** | über Elevation/Kreuzpeilung | ~40 € | **Kern der Empfehlung** |
| 3.4 | FTM / 802.11mc (Laufzeit) | – | **±1–2 m** | ESP32-C5 kann es | nutzen, **wenn der AP es unterstützt** (App prüft das) |
| 3.5 | Laufzeit über ACK („Polite WiFi“) | – | ~1,3–2,3 m | Forschungsniveau | experimentell, nicht für v1 |
| 3.6 | Phasenkohärentes Antennen-Array (AoA) | **±wenige °** | über Kreuzpeilung | ESPARGOS: 8 Antennen, PC/Ethernet | sehr genau, aber nicht handytauglich, nur 2,4 GHz |
| 3.7 | Nicht-Funk-Hilfen | – | – | – | ergänzend sehr wertvoll |

### 3.1 Handy allein
Android: 4 Scans pro 2 Minuten im Vordergrund; die Drosselung lässt sich nur
in den Entwickleroptionen abschalten („WLAN-Scan-Drosselung“), und selbst dann
dauert ein Scan mehrere Sekunden ([Android Developers][android-scan]). Für
„heiß/kalt“ in Echtzeit unbrauchbar. Pluspunkt: Android kann **Wi-Fi RTT**
(802.11mc), falls der AP FTM-Responder ist – das prüft die App über den
ESP32-C5 mit.

### 3.2 ESP32-C5 als Messempfänger
* Erster ESP32 mit **Dual-Band-WLAN (2,4 + 5 GHz, Wi-Fi 6)**, USB-C direkt am
  Chip (USB-Serial/JTAG) → wird am Handy als serielle USB-Schnittstelle (CDC)
  erkannt und vom Handy mit Strom versorgt.
* Der C5 kann **nicht gleichzeitig** auf beiden Bändern arbeiten, sondern
  wechselt ([Espressif Workshop][esp-c5-workshop]). Für die Ortung ist das egal:
  Man misst immer ein Funkmodul des APs auf einem Kanal.
* Im Promiscuous-Modus empfängt er alle Beacons/Probe Responses des Ziel-APs
  mit RSSI. Zusätzlich sendet die Firmware gezielte Probe Requests an die
  BSSID → der AP antwortet → mehr Messwerte pro Sekunde.
* Bekannte Eigenheit: Auf 5 GHz meldet der C5-Callback teils falsche
  Frame-Typen; die Firmware wertet den Frame-Control-Header deshalb selbst aus
  ([esp32.com][esp-promisc-bug]).

### 3.3 Richtantenne (Peilung)
* Empfohlen: **Dual-Band-Panel ALFA APA-M25** – 8 dBi (2,4 GHz) / 10 dBi (5 GHz),
  Öffnungswinkel 66° horizontal / 16° vertikal, RP-SMA-Stecker, 167 × 66 × 18 mm,
  72 g ([ALFA][alfa-m25]). Tipp: Die schmale 16°-Keule liegt in der langen
  Achse – hält man das Panel **quer** (lange Seite waagerecht), wird die
  Peilung in der Horizontalen deutlich schärfer (Polarisation dreht dabei mit;
  indoor meist unkritisch – einfach beide Haltungen probieren).
* Die App misst beim Drehen den Pegel über dem Richtungswinkel aus den
  Lagesensoren des Handys (Gyro-gestützt, unabhängig von Kompass-Störungen in
  Stahlbetonbauten) und zeichnet ein **Polardiagramm**. Die Richtung wird als
  geglättetes Maximum mit Güte-Angabe geschätzt; ein Pfeil zeigt danach
  laufend in die geschätzte Richtung.
* **Elevation:** Zeigt man mit der Antenne zur Decke, liefert der Neigungswinkel
  beim Maximum mit der Deckenhöhe die horizontale Entfernung:
  `Abstand = (Deckenhöhe − Handhöhe) / tan(Elevation)`.

### 3.4 FTM (Fine Timing Measurement, IEEE 802.11mc)
* Laufzeitmessung zwischen ESP32-C5 (Initiator) und AP (Responder); ESP-IDF
  unterstützt den C5 als FTM-Initiator im Station-Modus, auch **ohne Anmeldung
  am WLAN** ([ESP-IDF FTM-Beispiel][idf-ftm], [ESP-IDF Doku][idf-c5-ftm]).
* **Haken:** Der AP muss FTM-Responder sein. Die App zeigt pro AP an, ob er
  das ankündigt (Extended Capabilities, Bit 70). Espressif weist selbst darauf
  hin, dass Mehrwege, Antennenlage und fehlende Kalibrierung die Genauigkeit
  verschlechtern ([ESP-IDF Doku][idf-c5-ftm]).

### 3.5 Laufzeit über ACKs („Polite WiFi“ / Wi-Peep)
Jedes WLAN-Gerät bestätigt Frames an seine MAC mit einem ACK – auch von
Fremden. Aus der Zeit bis zum ACK lässt sich die Entfernung schätzen. Das
Forschungsprojekt Wi-Peep (Univ. Waterloo) erreichte mit ESP32/ESP8266 etwa
1 m bzw. **1,26–2,30 m** Fehler, allerdings erst nach Mittelung über
zehntausende Messungen und mit Korrektur der gerätespezifischen ACK-
Antwortzeit (8–13 µs) ([Waterloo][wipeep-uw], [Kaspersky][wipeep-kasp],
[Hackster][wipeep-hackster]). Die öffentliche ESP-IDF-API liefert nur
Zeitstempel in Mikrosekunden (1 µs ≙ 300 m). Für den Alltagseinsatz ist das
**nicht ausgereift → Ausbaustufe/Experiment**.

### 3.6 Phasenkohärentes Antennen-Array (Angle of Arrival)
ESPARGOS (Uni Stuttgart): 8 ESP32-S2 mit gemeinsamem Takt und
Phasenreferenz, MUSIC-Spektralschätzung, Echtzeit-Visualisierung von
WLAN-Quellen ([arXiv][espargos-paper], [RTL-SDR.com][espargos-rtl]).
Genauestes Verfahren für die *Richtung*, aber: nur 2,4 GHz („ESPARGOS One“),
Anschluss per Ethernet an einen PC, Hardware nicht offen
([GitHub ESPARGOS][espargos-gh]). Mit einzelnen ESP32-C5 (1 Antenne pro Chip,
keine gemeinsame Phase) lässt sich das nicht nachbauen. → Für diesen
Einsatzzweck **nicht empfohlen**.

### 3.7 Nicht-Funk-Hilfen (oft der schnellste Weg!)
* **AP-Name im Beacon:** Viele Hersteller senden den Gerätenamen mit – Cisco
  *und Meraki* (Aironet-IE „AP name v2“), Aruba, Ruckus, Huawei, Mist,
  Ubiquiti, Extreme, Fortinet, Arista, Alcatel-Lucent u. a. Die Firmware liest
  diese Felder aus (Formate nach dem Wireshark-Dissector
  [packet-ieee80211.c][wireshark]). Steht dort z. B. „Rathaus-OG2-Flur“, ist
  die halbe Suche erledigt.
* **LLDP/CDP am Switch:** Port und Gerätename des APs → Kabelweg.
* **Kabelsuchgerät (Tongenerator + Suchsonde):** am Patchfeld einspeisen und
  dem Kabel durch die Decke folgen (PoE-taugliches Gerät verwenden).
* **LED blinken lassen** über das Management (wenn sichtbar).

## 4. Empfohlene Lösung

### 4.1 Einkaufsliste

Komplette Liste mit Links, Kabelkette und Aufbau: **[HARDWARE.md](HARDWARE.md)**.

Kurzfassung (alles von Shops in Deutschland): Seeed XIAO ESP32-C5 und
Adapter SMA-Buchse ↔ RP-SMA-Buchse (10 GHz) bei Berrybase, ALFA APA-M25
(Dual-Band-Panel) bei einem deutschen Händler, Pigtail U.FL → SMA-Stecker
und 20-dB-Dämpfungsglied (SMA, bis 6 GHz) bei WiMo, Dual-Band-Rundstrahler
(Delock) bei Berrybase, kurzes USB-C-Kabel, Handyhalter Kaiser 6015.
Zusammen etwa 85–125 € plus Versand.

### 4.2 Aufbau
```
 [Handy] ──USB-C── [XIAO ESP32-C5] ──U.FL→RP-SMA── [(20 dB)] ── [Panel-Antenne]
   ▲ App: Liste, Suche, Peilung              Antennen-Hauptkeule zeigt aus der
   │ Lage-Sensoren                           Handy-Rückseite (Kamera-Richtung)
```
Das Panel hinter das Handy montieren, Hauptkeule = Blickrichtung der Kamera
(einstellbar in der App: „Rückseite“ oder „Oberkante“). Bei Boards mit zwei
USB-Buchsen (z. B. DevKitC) die Buchse am **nativen USB** (USB-Serial/JTAG)
verwenden, nicht den USB-UART-Port.

### 4.3 Vorgehen vor Ort
1. **AP auswählen:** Scan → Liste (SSID, BSSID, Band, Kanal, Pegel, FTM,
   AP-Name). Alternativ MAC vom Typenschild eingeben → die App zeigt passende
   BSSIDs mit Trefferquote (siehe Kapitel 5). BSSIDs desselben Funkmoduls
   werden gruppiert und gemeinsam gemessen (mehr Messwerte).
2. **Grobsuche (Etage/Raum):** 2,4-GHz-Funkmodul wählen, Ton an, dem
   steigenden Pegel folgen. Alternativ mit der Richtantenne in die vier
   Raumecken zielen und in den stärksten Bereich gehen.
3. **Peilen:** 5-GHz-Funkmodul, an 2–3 Positionen eine langsame
   360°-Drehung (≈ 10 s). Die App zeigt Polardiagramm, Richtungspfeil und Güte.
4. **Nahbereich:** Antenne zur Decke richten → Elevation + Raumhöhe ergeben die
   Entfernung. Dann Platte für Platte darunter messen („Messpunkt merken“).
   Pegel über ca. −30 dBm → Empfänger übersteuert: **20-dB-Dämpfungsglied**
   einschrauben (Offset in der App eintragen) oder auf den Rundstrahler
   wechseln. Die App meldet das.
5. **FTM:** Unterstützt der AP FTM, liefert der Knopf „FTM“ zusätzlich eine
   Laufzeit-Entfernung.

### 4.4 Erwartbare Genauigkeit
Die Werte sind **Schätzungen aus Physik und Praxiserfahrung, keine
Messergebnisse mit dieser Hardware** – sie müssen vor Ort validiert werden:
* Raum/Bereich finden: zuverlässig.
* Peilung: ±10° bei Sichtverbindung, ±15–30° in verwinkelten Gebäuden;
  Fehlpeilungen durch Reflexionen per Kreuzpeilung erkennen.
* Entfernung aus RSSI: nur als Bereich (Faktor ~2), abhängig von Sendeleistung
  und Wänden. Sendet der AP seine Sendeleistung (TPC-Report bzw. Cisco DTPC),
  rechnet die App damit.
* Entfernung aus Elevation: ±0,5–1 m bis ca. 4 m horizontal.
* Feinsuche: in der Regel auf eine Deckenplatte genau; schwieriger bei
  Metalldecken oder wenn der AP direkt unter sich ein Antennen-Minimum hat
  (dann liegt das Maximum ringförmig um den AP – die Mitte des Rings ist es).
* FTM: ±1–2 m, wenn verfügbar.

## 5. MAC-Adresse vom Typenschild vs. BSSID

Auf dem Typenschild steht meist die **Ethernet-MAC**. Gefunkt wird mit
**BSSIDs**, die der Hersteller daraus ableitet – je SSID und Funkmodul eine:
* fortlaufend: Basis-MAC + 1…n bzw. untere 4 Bit pro SSID (Cisco, Aruba, LANCOM …)
* „lokal administriert“: erstes Byte verändert (Bit 0x02 gesetzt und weitere
  Varianten), Rest gleich (Meraki, UniFi …)
* eigener Adressblock je Funkmodul (2,4 und 5 GHz unterscheiden sich).

Die App normalisiert jede Schreibweise (`AA:BB:…`, `AA-BB-…`, `aabb.ccdd.eeff`,
`AABBCCDDEEFF`) und bewertet BSSIDs nach Ähnlichkeit:
* exakt gleich: 100 %
* nur das erste Byte anders (lokal administrierte Varianten): 90 %
* gleiches Präfix (Byte 2 und 3, erstes Byte egal) und Abstand der hinteren
  drei Bytes ≤ 256: 85–65 % (je näher, desto höher)

Geräte desselben Herstellers mit fortlaufenden MACs bekommen dadurch ebenfalls
Treffer. Der Kandidat mit der höchsten Quote ist der wahrscheinlichste; im
Zweifel hilft der AP-Name aus dem Beacon. Später kann eine Zuordnungstabelle
(„diese MAC gehört zu diesen BSSIDs“) das je Hersteller verschärfen.

## 5a. Ruckus-APs bei BayernWLAN

* **BSSID-Schema:** Laut Ruckus-Support entsteht die BSSID aus der MAC des APs,
  indem die **letzte Hex-Ziffer** ersetzt wird (z. B. AP `8c:0c:90:1f:a9:30` →
  BSSIDs `8c:0c:90:1f:a9:3X`) ([Ruckus KBA 4312][ruckus-kba]). Die Basis-MAC
  endet immer auf **0**, die erste 2,4-GHz-BSSID ist Basis **+ 8**
  ([Ruckus-Community][ruckus-bssid]). Eine offizielle Formel für alle Modelle
  und Firmware-Stände gibt es nicht. Bei vielen WLANs wurden zusätzlich
  Varianten im **4. Byte** beobachtet (Schritte von 0x40).
  **Die App wertet das so aus:** Gleicher 16er-Block in der letzten Stelle, das
  4. Byte ohne seine obersten zwei Bits verglichen. Das ergibt 98 %, also
  „sicher dasselbe Gerät“. Beide Funkmodule (2,4 und 5 GHz) erscheinen als
  **ein Gerät** mit der vermuteten Typenschild-MAC (`…:x0`).
* **Verbindliche Zuordnung:** Am AP liefert `get wlanlist` (CLI) die BSSIDs je
  WLAN, im SmartZone-Controller steht sie unter den WLANs des APs.
* **AP-Name im Beacon:** Ab **SmartZone 6.0** kann der AP-Name pro WLAN in
  Beacons und Probe Responses mitgesendet werden (Hersteller-Element
  `00:13:92`, Typ 3). Das ist aber **standardmäßig aus** (AP-CLI:
  `set hostname_ad <wlan> 1`) ([WH Network][ruckus-apname]). Wenn der
  Betreiber das für `@BayernWLAN` einschaltet, zeigt die App die Namen
  direkt an. Die Firmware liest das Feld bereits aus.
* **FTM (802.11mc):** Dass diese Ruckus-Modelle FTM als Responder anbieten,
  ließ sich nicht belegen. Vermutlich geht die Laufzeitmessung also nicht.
  Die App zeigt pro AP an, ob er FTM ankündigt.
* **BeamFlex:** Die R-Serie richtet Daten an Clients gezielt aus. Datenframes
  sind für die Ortung unbrauchbar, deshalb misst die App standardmäßig nur
  Beacons und Probe-Antworten. Die Firmware meldet beide getrennt, damit
  sich ein Unterschied im Feld erkennen lässt.
* **Außen-APs:** T310s/T310n und T350se haben Sektorantennen (120° bzw. 30°)
  ([CommScope T310][t310-ds], [Ruckus T350se][t350se]). Vor dem Gerät ist der
  Pegel deutlich höher als dahinter. T300, T310c und T350c strahlen rundum.

## 6. Architektur

```
┌──────────────────────── Android-App (Capacitor) ────────────────────────┐
│ www/ (Vanilla JS):  Liste · MAC-Suche · Suchen(Ton) · Peilen · Einst.   │
│   transport.js ──┬─ Capacitor-Plugin „UsbSerial“ (Java, Android-USB-Host)│
│                  ├─ Web Serial (Chrome am PC, zum Testen)               │
│                  └─ Simulator (ohne Hardware, Demo/Test)                │
└─────────────────────────────── USB-CDC ─────────────────────────────────┘
┌──────────────── ESP32-C5-Firmware (ESP-IDF) ────────────────┐
│ Promiscuous-RX → IE-Parser (SSID, Kanal, FTM-Bit, TPC,      │
│ AP-Name) → Kanal-Hopping-Scan | Tracking (100-ms-Mittel)     │
│ Probe Requests (aktiv) | FTM-Initiator                       │
└──────────────────────────────────────────────────────────────┘
```
Protokoll: Textbefehle (Handy → ESP) und JSON-Zeilen (ESP → Handy), siehe
[`docs/PROTOKOLL.md`](docs/PROTOKOLL.md).

## 7. Rechtliches & Datenschutz
* Nur an Infrastruktur verwenden, für die ein Auftrag/eine Berechtigung
  besteht.
* Ausgewertet werden standardmäßig nur **Beacons und Probe Responses**
  (an alle bzw. an uns adressiert). Datenframes des Ziel-APs werden höchstens
  für den Pegel gezählt (nur Kopf, kein Inhalt) – in der App abschaltbar.
* Client-MACs (Endgeräte) werden weder angezeigt noch gespeichert.
* Gesendet werden nur normgerechte Probe Requests bzw. FTM-Requests – wie bei
  jedem Handy, das WLANs sucht.

## 8. Ausbaustufen
1. **v0.1 (dieser Stand):** Scan beider Bänder, MAC-Suche, AP-Name, Suchmodus
   mit Ton, Peilmodus (Polardiagramm, Pfeil, Elevation), Entfernung
   (RSSI-Bereich, Elevation, FTM), Simulator, Web-Serial zum Testen am PC.
2. **v0.2:** Messpunkte/Peilungen auf Grundriss-Skizze → automatische
   Kreuzpeilung.
3. **v0.3:** AR-Ansicht (ARCore): Positionen + Messwerte → 3D-Schätzung →
   Marker an der Decke im Kamerabild.
4. **Experimente:** ACK-Laufzeit; 2–4 ESP32-C5 mit je einer Richtantenne
   (Amplitudenvergleich → Richtung ohne Drehen); BLE statt USB für iPhone.

---

### Quellen
* [netally-locate]: NetAlly AirCheck G3 – Locating Wi-Fi Devices: <https://www.netally.com/user-guides/aircheckg3/Content/B_Testing-Applications/Wi-Fi_Analysis/Locating_Wi-Fi_Devices.htm>
* [netally-kb]: NetAlly KB – Locate APs and Wi-Fi Clients: <https://www.netally.com/knowledgebase/locate-aps-and-wi-fi-clients-3/>
* [android-scan]: Android Developers – Wi-Fi scanning overview: <https://developer.android.com/develop/connectivity/wifi/wifi-scan>
* [mobiflip-bw]: mobiflip – Vodafone/BayernWLAN: <https://www.mobiflip.de/vodafone-neue-gigabit-mobilfunkstationen-und-meilenstein-fuer-bayernwlan/amp/>
* [lrz-bw]: LRZ – BayernWLAN: <https://collab.dvb.bayern/lrzpublic/bayernwlan-10745885.html>
* [esp-c5-workshop]: Espressif – ESP-IDF with ESP32-C5 Workshop: <https://developer.espressif.com/workshops/esp-idf-with-esp32-c5/>
* [esp-promisc-bug]: esp32.com – ESP32-C5 promiscuous callback reports wrong frame type: <https://esp32.com/viewtopic.php?p=154671>
* [alfa-m25]: ALFA APA-M25 Datenblatt: <https://www.alfa.com.tw/products/apa-m25>
* [idf-ftm]: ESP-IDF FTM-Beispiel (unterstützte Chips inkl. ESP32-C5): <https://github.com/espressif/esp-idf/blob/release/v6.0/examples/wifi/ftm/README.md>
* [idf-c5-ftm]: ESP-IDF v6.0 (ESP32-C5) – Wi-Fi Driver, FTM: <https://docs.espressif.com/projects/esp-idf/en/v6.0/esp32c5/api-guides/wifi-driver/security-and-roaming.html>
* [wipeep-uw]: University of Waterloo – Wi-Peep: <https://uwaterloo.ca/computer-science/news/can-your-wifi-devices-be-used-spy-on-you>
* [wipeep-kasp]: Kaspersky – Wi-Peep: <https://www.kaspersky.com/blog/wi-peep-wireless-localization/46611>
* [wipeep-hackster]: Hackster – Wi-Peep: <https://www.hackster.io/news/wi-spy-98d985364820>
* [espargos-paper]: Euchner et al., ESPARGOS (arXiv 2502.09405): <https://arxiv.org/pdf/2502.09405>
* [espargos-rtl]: RTL-SDR.com – ESPARGOS: <https://www.rtl-sdr.com/espargos-an-esp32-phased-array-for-seeing-wifi/>
* [espargos-gh]: GitHub – ESPARGOS: <https://github.com/orgs/ESPARGOS/>
* [wireshark]: Wireshark 802.11-Dissector: <https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-ieee80211.c>
* [xiao-cnx]: CNX Software – XIAO ESP32-C5: <https://www.cnx-software.com/2026/01/16/xiao-esp32-c5-a-tiny-usb-c-iot-board-with-dual-band-wifi-6-bluetooth-5-0-le-zigbee-and-thread-connectivity/>
* [xiao-scargill]: Scargill – XIAO ESP32-C5 first look: <https://tech.scargill.net/seeed-studio-xiao-esp32-c5-first-look/>
* [c5-boards]: ESP32-C5 Boards 2026: <https://esp32.co.uk/esp32-c5-versions-best-dev-boards-2026/>
* [ruckus-kba]: Ruckus KBA 4312 – BSSID aus AP-MAC: <https://support.ruckuswireless.com/articles/000004312>
* [ruckus-bssid]: Ruckus-Community – AP-MAC und BSSID: <https://community.ruckuswireless.com/discussion/comment/42578#Comment_42578>
* [ruckus-apname]: WH Network – AP-Namen in Beacons (Ruckus SmartZone 6.0): <https://whnetwork.wordpress.com/2022/05/24/troubleshooting-with-ap-names-advertised-in-802-11-beacon-frames/>
* [t310-ds]: CommScope – Ruckus T310 Datenblatt: <https://de.commscope.com/globalassets/digizuite/761778-p360-t310-external.pdf>
* [t350se]: Ruckus – T350se: <https://support.ruckuswireless.com/products/192-ruckus-t350se>

[netally-locate]: https://www.netally.com/user-guides/aircheckg3/Content/B_Testing-Applications/Wi-Fi_Analysis/Locating_Wi-Fi_Devices.htm
[netally-kb]: https://www.netally.com/knowledgebase/locate-aps-and-wi-fi-clients-3/
[android-scan]: https://developer.android.com/develop/connectivity/wifi/wifi-scan
[mobiflip-bw]: https://www.mobiflip.de/vodafone-neue-gigabit-mobilfunkstationen-und-meilenstein-fuer-bayernwlan/amp/
[lrz-bw]: https://collab.dvb.bayern/lrzpublic/bayernwlan-10745885.html
[esp-c5-workshop]: https://developer.espressif.com/workshops/esp-idf-with-esp32-c5/
[esp-promisc-bug]: https://esp32.com/viewtopic.php?p=154671
[alfa-m25]: https://www.alfa.com.tw/products/apa-m25
[idf-ftm]: https://github.com/espressif/esp-idf/blob/release/v6.0/examples/wifi/ftm/README.md
[idf-c5-ftm]: https://docs.espressif.com/projects/esp-idf/en/v6.0/esp32c5/api-guides/wifi-driver/security-and-roaming.html
[wipeep-uw]: https://uwaterloo.ca/computer-science/news/can-your-wifi-devices-be-used-spy-on-you
[wipeep-kasp]: https://www.kaspersky.com/blog/wi-peep-wireless-localization/46611
[wipeep-hackster]: https://www.hackster.io/news/wi-spy-98d985364820
[espargos-paper]: https://arxiv.org/pdf/2502.09405
[espargos-rtl]: https://www.rtl-sdr.com/espargos-an-esp32-phased-array-for-seeing-wifi/
[espargos-gh]: https://github.com/orgs/ESPARGOS/
[wireshark]: https://github.com/wireshark/wireshark/blob/master/epan/dissectors/packet-ieee80211.c
[xiao-cnx]: https://www.cnx-software.com/2026/01/16/xiao-esp32-c5-a-tiny-usb-c-iot-board-with-dual-band-wifi-6-bluetooth-5-0-le-zigbee-and-thread-connectivity/
[xiao-scargill]: https://tech.scargill.net/seeed-studio-xiao-esp32-c5-first-look/
[c5-boards]: https://esp32.co.uk/esp32-c5-versions-best-dev-boards-2026/
[ruckus-kba]: https://support.ruckuswireless.com/articles/000004312
[ruckus-bssid]: https://community.ruckuswireless.com/discussion/comment/42578#Comment_42578
[ruckus-apname]: https://whnetwork.wordpress.com/2022/05/24/troubleshooting-with-ap-names-advertised-in-802-11-beacon-frames/
[t310-ds]: https://de.commscope.com/globalassets/digizuite/761778-p360-t310-external.pdf
[t350se]: https://support.ruckuswireless.com/products/192-ruckus-t350se
