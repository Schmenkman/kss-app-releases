# AP-Finder – Hardwareliste

Für die Suche nach Ruckus-APs (R350/R500/R510/R550/R610/R650, T300/T310/T350)
mit der SSID `@BayernWLAN`. Preise sind Richtwerte aus der Recherche (Stand
November 2026) – Verfügbarkeit und Preis bitte im Shop prüfen.

## Einkaufsliste

| # | Teil | Empfehlung | Link | ca. Preis |
|---|---|---|---|---|
| 1 | **Funkmodul** | Seeed **XIAO ESP32-C5** (2,4 + 5 GHz, USB-C, U.FL-Antennenbuchse). Die Variante „Pre-Soldered“ hat aufgelötete Stiftleisten – nicht nötig, schadet aber nicht | [OpenELAB (DE)](https://openelab.io/de/products/seeed-studio-xiao-esp32-c5) · [RobotShop EU](https://eu.robotshop.com/products/seeed-studio-xiao-esp32-c5-pre-soldered) | 9 € |
| 2 | **Richtantenne** | **ALFA APA-M25** Dual-Band-Panel, 8 dBi (2,4 GHz) / 10 dBi (5 GHz), RP-SMA-Stecker ([Datenblatt](https://www.alfa.com.tw/products/apa-m25)) | [Rokland (Versand nach DE)](https://store.rokland.com/en-de/products/alfa-apa-m25-2-4-5-ghz-dual-band-directional-10-dbi-panel-antenna) | 17 € + Versand |
| 3 | **Pigtail** | IPEX/U.FL-Stecker → **SMA-Stecker**, 15 cm | [WiMo 40350-SM-15](https://www.wimo.com/de/40350-sm-15) | 8 € |
| 4 | **Adapter** | **SMA-Buchse ↔ RP-SMA-Buchse**, 90°, bis 10 GHz (verbindet Pigtail und Antennen) | [Berrybase](https://www.berrybase.de/adapter-sma-buchse-zu-rp-sma-buchse-900-10-ghz) | 5 € |
| 5 | **Dämpfungsglied** | **20 dB**, SMA-Stecker/-Buchse, DC–6 GHz (für die letzten Meter unter dem AP) | [WiMo SMA-Dämpfungsglieder 3–20 dB](https://www.wimo.com/de/in-line-attenuator-sma-1watt) | 15–25 € |
| 6 | **Rundstrahler** | Delock **88393**, 2,4 + 5 GHz, 4–5 dBi, RP-SMA-Stecker, Kippgelenk ([Datenblatt](https://delock.de/produkt/88393/pdf.html?sprache=de)) | [Preisvergleich Geizhals](https://geizhals.de/520015) | 16–22 € |
| 7 | **USB-Kabel** | kurzes **USB-C ↔ USB-C**, 0,3 m (USB 2.0 genügt), z. B. Delock | [Bechtle (Delock 0,3 m)](https://www.bechtle.com/de-en/shop/delock-usb-c-cable-0-3m--4411925--p) – jedes kurze C-C-Kabel geht | 5–26 € |
| 8 | **Handyhalter** | Ulanzi **ST-02S** (Klemme 6,6–9,4 cm, zwei 1/4"-Gewinde) | [Ulanzi](https://ulanzi.com/products/st-02s-phone-tripod-mount-ulanzi) · [MediaMarkt AT](https://www.mediamarkt.at/de/product/_ulanzi-u-st-02s-handyhalterung-schwarz-144057395.html) | 15–28 € |
| 9 | Kleinkram | doppelseitiges Klettband (z. B. 3M Dual Lock), Kabelbinder, Schrumpfschlauch oder kleines Gehäuse für den XIAO | Baumarkt/Elektronikhandel | 5–10 € |

**Summe:** etwa 100–150 € inkl. Versand.

> Das Handy muss **USB-OTG** können (fast alle aktuellen Android-Geräte).
> Es versorgt den ESP32-C5 über das Kabel mit Strom – ein Akku ist nicht nötig.

## So wird verbunden

```
ohne Dämpfung (Suche aus der Entfernung):
 [XIAO ESP32-C5]─U.FL─(3) Pigtail─SMA-Stecker ─▶ (4) Adapter ◀─ RP-SMA-Stecker─(2) Panel bzw. (6) Rundstrahler

mit Dämpfung (Nahbereich, Pegel über ca. −30 dBm):
 [XIAO]─U.FL─(3) Pigtail─SMA-Stecker ─▶ (5) Dämpfungsglied, UMGEDREHT
        (Buchsen-Seite an den Pigtail, Stecker-Seite in den Adapter) ─▶ (4) Adapter ◀─ Antenne
```

* Ein Dämpfungsglied ist passiv und wirkt in beide Richtungen, deshalb darf
  es umgedreht eingesetzt werden. In der App unter Einstellungen
  „Dämpfungsglied (dB)“ auf 20 setzen und danach wieder auf 0.
* **Den U.FL-Stecker nur einmal aufstecken.** U.FL hält nur wenige
  Dutzend Steckzyklen aus. Antennen werden an den SMA-/RP-SMA-Schraubverbindungen
  gewechselt.
* **SMA ≠ RP-SMA:** Beide sehen ähnlich aus, der Innenleiter ist vertauscht.
  Die Kette oben ist so gewählt, dass nur der eine Adapter (4) nötig ist.
  Achtung: Berrybase führt einen ähnlichen 90°-Adapter mit nur 3 GHz. Der
  taugt nicht für 5 GHz, deshalb die 10-GHz-Variante nehmen.
* Am **XIAO ESP32-C5** ist die USB-C-Buchse direkt am Chip
  (USB-Serial/JTAG) – genau die richtige Schnittstelle für die App.

## Aufbau

1. Panelantenne hochkant hinter das Handy, also mit der flachen Seite nach
   vorne. Den Handyhalter (8) mit Klettband auf die Rückseite des Panels
   setzen.
2. Den XIAO mit Klettband oder Kabelbinder neben den Halter setzen.
   Pigtail und Adapter so verlegen, dass nichts am U.FL-Stecker zieht.
3. In der App unter Einstellungen „Hauptkeule zeigt aus der Rückseite“
   wählen. Das ist die Standardeinstellung.
4. Tipp: Die schmale 16°-Keule des Panels (5 GHz) liegt in seiner langen
   Achse. Hält man es quer, wird die Peilung in der Waagerechten schärfer.

## Alternativen

* **Funkmodul:** DFRobot FireBeetle 2 ESP32-C5-U (Modul WROOM-1U mit
  U.FL). Waveshare ESP32-C5-WiFi6-Kit gibt es auch mit externer
  Dual-Band-Antenne. Wichtig ist immer eine U.FL-Buchse und die native
  USB-Buchse am Handy (siehe [firmware/README.md](firmware/README.md)).
* **Dämpfungsglied:** Mini-Circuits VAT-20+/VAT-20A+ (DC–6 GHz) über
  Distributoren wie [DigiKey](https://www.digikey.at/en/products/detail/mini-circuits/VAT-20A/19186392).
  Günstige 20-dB-SMA-Dämpfer gibt es auch auf eBay. Wichtig sind
  **mindestens 6 GHz** Frequenzbereich.
* **Pigtail mit RP-SMA:** [WiMo 40350-SFR150](https://www.wimo.com/de/40350-sfr150)
  (U.FL → RP-SMA-Buchse) passt ohne Adapter direkt an die ALFA-Antenne.
  Für das Dämpfungsglied braucht es dann aber zwei Adapter.

## Was die Ruckus-APs ausmacht

* **Funkmodule:** Alle genannten Modelle senden auf 2,4 und 5 GHz. Das Panel
  deckt beide Bänder ab.
* **Sektor-Modelle:** **T310s/T310n** und **T350se** haben
  Sektorantennen (120° bzw. 30°) und strahlen vor allem nach vorne. Hinter
  dem Gerät ist der Pegel deutlich schwächer. **T300, T310c und T350c**
  strahlen rundum.
* **BeamFlex:** Die Innen-APs (R-Serie) richten Daten an Clients gezielt aus.
  Die App misst deshalb standardmäßig nur **Beacons** und **Probe-Antworten**,
  nicht den Datenverkehr.
