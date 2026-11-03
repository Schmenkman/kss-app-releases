#!/usr/bin/env python3
"""Messstellen der bayerischen Polizei fuer Geschwindigkeitskontrollen (mobile Blitzer).

Quelle: Messstellenlisten des Bayerischen Staatsministeriums des Innern zu den Blitzmarathons
(amtliche Veroeffentlichung zur allgemeinen Kenntnisnahme). Dort misst die Polizei auch sonst
regelmaessig – deshalb warnt KSS-Maps an diesen Stellen vor einer moeglichen mobilen Messung.

Die PDFs enthalten Adressen (Strasse, PLZ, Ort), keine Koordinaten. Die Koordinaten kommen
EINMALIG ueber Nominatim (OpenStreetMap, ODbL) – mit 1 Anfrage pro Sekunde, einem Thread und
einem im Repo gespeicherten Zwischenspeicher, wie es die Nominatim-Nutzungsregeln fuer
einmalige Aufgaben verlangen. Spaetere Laeufe fragen nur neue Adressen ab.

Aufruf (im CI): python3 tools/fetch_messstellen.py
Ergebnis:       app/src/main/assets/messstellen/bayern.json, data/geocode_cache.json
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # kss-maps/
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "messstellen", "bayern.json")
CACHE = os.path.join(ROOT, "data", "geocode_cache.json")
WORK = os.environ.get("RUNNER_TEMP", "/tmp")

UA = "KSS-Maps-Build/1.0 (private Navigations-App; einmalige Geokodierung der amtlichen Messstellenliste)"

# Amtliche Listen (neueste zuerst). Fehlende/umgezogene Dateien werden uebersprungen.
SOURCES = [
    (2026, "https://www.stmi.bayern.de/media/05_innere_sicherheit/Verkehrssicherheit/blitzmarathon/messstellenliste-blitzmarathon-2026-04-08.pdf"),
    (2024, "https://www.stmi.bayern.de/assets/stmi/med/aktuell/messstellenliste_blitzmarathon_2024.pdf"),
    (2024, "https://www.g7.bayern.de/assets/stmi/med/aktuell/messstellenliste_blitzmarathon_2024.pdf"),
] + [
    (2023, "https://www.innenministerium.bayern.de/assets/stmi/sus/verkehrssicherheit/blitzmarathon_2023_messstellen_%s.pdf" % rb)
    for rb in ("oberbayern", "niederbayern", "oberpfalz", "oberfranken", "mittelfranken", "unterfranken", "schwaben")
]

# Bayerische Postleitzahlen
PLZ_RE = re.compile(r"^(.*?)\s+((?:63|8[0-9]|9[0-7])\d{3})\s+(\S.*?)\s*$")
ROAD_REF = re.compile(r"^(A|B|St|ST|Kr|KR|[A-ZÄÖÜ]{1,3})\s?\d{1,4}\b")
HEADER_WORDS = ("standort", "plz", "messstelle", "straße", "strasse")


def download(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        if not data.startswith(b"%PDF"):
            print("  kein PDF:", url)
            return None
        return data
    except Exception as e:
        print("  nicht erreichbar:", url, "-", e)
        return None


def pdf_text(data: bytes, name: str) -> str:
    path = os.path.join(WORK, name)
    with open(path, "wb") as f:
        f.write(data)
    out = subprocess.run(["pdftotext", "-layout", path, "-"], capture_output=True, text=True)
    return out.stdout


def parse(text: str):
    rows = []
    for line in text.splitlines():
        s = line.strip()
        if not s or any(w in s.lower() for w in HEADER_WORDS) and not re.search(r"\d{5}", s):
            continue
        m = PLZ_RE.match(s)
        if not m:
            continue
        left, plz, place = m.group(1).strip(), m.group(2), m.group(3).strip()
        # Ort: nur bis zu einer weiteren Spalte (2+ Leerzeichen)
        place = re.split(r"\s{2,}", place)[0].strip()
        fields = [f.strip() for f in re.split(r"\s{2,}", left) if f.strip()]
        if not fields:
            continue
        street = fields[-1]
        # "ja"/"nein"/"x" als Spalte "Variabel" ueberspringen
        if street.lower() in ("ja", "nein", "x", "-") and len(fields) > 1:
            street = fields[-2]
        hint = " ".join(fields[:-1])[:80]
        if len(street) < 3 or len(place) < 2:
            continue
        rows.append({"street": street, "plz": plz, "place": place, "hint": hint})
    return rows


def nominatim(params: dict):
    q = urllib.parse.urlencode(dict(params, format="jsonv2", limit=1, countrycodes="de"))
    req = urllib.request.Request("https://nominatim.openstreetmap.org/search?" + q, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        res = json.loads(r.read())
    if res:
        return [round(float(res[0]["lat"]), 6), round(float(res[0]["lon"]), 6)]
    return None


def main() -> int:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = json.load(f)

    entries = {}  # key -> entry
    seen_years = set()
    for year, url in SOURCES:
        if year in seen_years and year != 2023:
            continue
        data = download(url)
        if not data:
            continue
        rows = parse(pdf_text(data, "ms_%d_%d.pdf" % (year, abs(hash(url)) % 10000)))
        print("%d: %d Messstellen aus %s" % (year, len(rows), url.rsplit("/", 1)[-1]))
        for r in rows[:3]:
            print("   Beispiel:", r)
        if rows:
            seen_years.add(year)
        for r in rows:
            key = "%s|%s|%s" % (r["street"].lower(), r["plz"], r["place"].lower())
            e = entries.setdefault(key, dict(r, years=[]))
            if year not in e["years"]:
                e["years"].append(year)

    if not entries:
        print("Keine Messstellen gelesen – nichts geaendert.")
        return 0

    requests = 0
    last = 0.0
    out = []
    for key, e in entries.items():
        if key not in cache and requests < 4000:
            attempts = [
                {"street": e["street"], "postalcode": e["plz"], "city": e["place"]},
                {"q": "%s, %s %s" % (e["street"], e["plz"], e["place"])},
            ]
            if ROAD_REF.match(e["street"]):
                attempts.append({"q": "%s %s" % (e["street"], e["place"])})
            pos = None
            for params in attempts:
                wait = 1.1 - (time.time() - last)
                if wait > 0:
                    time.sleep(wait)
                last = time.time()
                requests += 1
                try:
                    pos = nominatim(params)
                except Exception as ex:
                    print("  Nominatim-Fehler:", ex)
                    time.sleep(5)
                    pos = None
                if pos:
                    break
            cache[key] = pos
            if requests % 200 == 0:
                print("  %d Anfragen ..." % requests)
                with open(CACHE, "w", encoding="utf-8") as f:
                    json.dump(cache, f, ensure_ascii=False, indent=0, sort_keys=True)
        pos = cache.get(key)
        if pos:
            out.append({"lat": pos[0], "lon": pos[1], "street": e["street"], "plz": e["plz"],
                        "place": e["place"], "hint": e["hint"], "years": sorted(e["years"])})

    with open(CACHE, "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False, indent=0, sort_keys=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"source": "Bayerisches Staatsministerium des Innern – Messstellenlisten Blitzmarathon; "
                             "Koordinaten: © OpenStreetMap-Mitwirkende (Nominatim, ODbL)",
                   "messstellen": out}, f, ensure_ascii=False, indent=0)
    print("Messstellen: %d gelesen, %d mit Koordinaten, %d neue Nominatim-Anfragen" % (len(entries), len(out), requests))
    return 0


if __name__ == "__main__":
    sys.exit(main())
