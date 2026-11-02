# AP-Finder – Hardwareliste

Für die Suche nach Ruckus-APs (R350/R500/R510/R550/R610/R650, T300/T310/T350)
mit der SSID `@BayernWLAN`. **Alle Teile kommen von Shops in Deutschland.**
Die Preise sind Richtwerte aus der Recherche (Stand November 2026) –
Verfügbarkeit und Preis bitte im Shop prüfen.

## Einkaufsliste

| # | Teil | Empfehlung | Shop (Deutschland) | ca. Preis |
|---|---|---|---|---|
| 1 | **Funkmodul** | Seeed **XIAO ESP32-C5** (2,4 + 5 GHz, USB-C, U.FL-Antennenbuchse, 8 MB Flash) | [Berrybase](https://www.berrybase.de/en/seeed-studio-xiao-esp32-c5-wifi-6-dual-band-ble5-zigbee-matter-thread-mqtt-risc-v-240mhz) | 8,90 € |
| 2 | **Richtantenne** | **ALFA APA-M25** Dual-Band-Panel, 8 dBi (2,4 GHz) / 10 dBi (5 GHz), RP-SMA-Stecker ([Datenblatt](https://www.alfa.com.tw/products/apa-m25)) | [WLAN-Profi-Shop](https://www.wlan-profi-shop.de/Alfa-Network-APA-M25-Dual-Band-WLAN-antenna-8-10dBi-24-5GHz) (laut Shop 1–3 Werktage) · [VARIA-Store Chemnitz](https://www.varia-store.com/product/alfa-network-apa-m25-antenne/) (laut Shop 11–13 Werktage) | 15–27 € |
| 3 | **Pigtail** | IPEX/U.FL-Stecker → **SMA-Stecker**, 15 cm | [WiMo 40350-SM-15](https://www.wimo.com/de/40350-sm-15) | 8,10 € |
| 4 | **Adapter** | **SMA-Buchse ↔ RP-SMA-Buchse**, 90°, bis 10 GHz (verbindet Pigtail und Antennen) | [Berrybase](https://www.berrybase.de/adapter-sma-buchse-zu-rp-sma-buchse-900-10-ghz) | siehe Shop |
| 5 | **Dämpfungsglied** | **20 dB**, SMA-Stecker/-Buchse, DC–6 GHz (für die letzten Meter unter dem AP) | [WiMo SMA-Dämpfungsglieder 3–20 dB](https://www.wimo.com/de/in-line-attenuator-sma-1watt) | siehe Shop |
| 6 | **Rundstrahler** | Delock WLAN-Antenne 2,4 + 5 GHz, 3–5 dBi, RP-SMA, mit Gelenk | [Berrybase](https://www.berrybase.de/wlan-802.11-ac-a-h-b-g-n-rp-sma-antenne-3-~-5-dbi-omnidirektional-gelenk) | siehe Shop |
| 7 | **USB-Kabel** | **USB-C ↔ USB-C**, 0,5 m, Goobay 66316 – oder ein beliebiges kurzes C-auf-C-Kabel bei Berrybase mitbestellen | [Welectron](https://welectron.com/Goobay-66316-USB-C-Cable-USB-31-05m) | siehe Shop |
| 8 | **Handyhalter** | **Kaiser 6015** (Kaiser Fototechnik), Klemme 55–90 mm, zwei 1/4"-Stativgewinde | [Foto Erhardt](https://www.foto-erhardt.de/stative/weitere-hersteller/kaiser-smartphone-halter-6015-mit-2-stativgewinden-1-4-zoll.html) · [Calumet](https://www.calumet.de/product/kaiser-smartphone-halter-in-schwarz) | 9,89 € |
| 9 | Kleinkram | doppelseitiges Klettband (z. B. 3M Dual Lock), Kabelbinder, Schrumpfschlauch oder kleines Gehäuse für den XIAO | Baumarkt/Elektronikhandel | 5–10 € |

**Summe:** grob 85–125 € plus Versand von 4–5 Shops. Belegt sind die Preise für XIAO, ALFA, Pigtail und Halter (zusammen gut 40–55 €). Die übrigen Positionen bitte im Shop nachsehen.

**Weniger Shops (Berrybase + WiMo):** Statt der ALFA-Antenne die
**Delock 88903** nehmen, eine Dual-Band-Richtantenne mit 6 dBi (2,4 GHz) /
8 dBi (5 GHz), RP-SMA und Gelenk, ebenfalls
[bei Berrybase](https://www.berrybase.de/wlan-802.11-ac-a-h-b-g-n-antenne-rp-sma-quick-6-~-8-dbi-direktional-gelenk).
Dazu ein USB-C-Kabel von Berrybase. Dann kommt alles außer Handyhalter und
Kleinkram von zwei Shops. Die Delock-Antenne ist kleiner und bündelt weniger
scharf als das ALFA-Panel. Die Peilung wird damit etwas ungenauer, die
Feinsuche funktioniert genauso.

> Das Handy muss **USB-OTG** können (fast alle aktuellen Android-Geräte).
> Es versorgt den ESP32-C5 über das Kabel mit Strom – ein Akku ist nicht nötig.

Hinweise zu den Shops:
* Berrybase hatte den XIAO ESP32-C5 bei der Recherche mit
  widersprüchlichem Lagerstatus („ausverkauft“ bzw. „100+ verfügbar“) gelistet.
* Den Firmensitz des WLAN-Profi-Shops konnte ich nicht prüfen, bitte ins
  Impressum schauen. VARIA-Store sitzt in Chemnitz, führt das Panel aber
  teils als Auslaufmodell.
* WiMo (Herxheim), Welectron (Forst/Karlsruhe) und Foto Erhardt (Münster) sind
  deutsche Händler. Welectron hatte beim Abruf zwei verschiedene Adressen im
  Impressum.

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

* **Funkmodul:** DFRobot FireBeetle 2 ESP32-C5-U (Modul WROOM-1U mit U.FL)
  oder Waveshare ESP32-C5-WiFi6-Kit mit externer Dual-Band-Antenne, falls ein
  deutscher Händler es führt. Wichtig sind eine U.FL-Buchse und die native
  USB-Buchse für das Handy (siehe [firmware/README.md](firmware/README.md)).
* **Pigtail mit RP-SMA:** [WiMo 40350-SFR150](https://www.wimo.com/de/40350-sfr150)
  (U.FL → RP-SMA-Buchse) passt ohne Adapter direkt an die ALFA-Antenne.
  Für das Dämpfungsglied braucht es dann aber zwei Adapter.
* **Dämpfungsglied:** Wichtig sind **mindestens 6 GHz** Frequenzbereich,
  SMA und 20 dB. Ein 10-dB-Dämpfungsglied zusätzlich hilft, wenn 20 dB zu
  viel sind.

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
