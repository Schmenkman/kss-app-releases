#!/usr/bin/env python3
"""Startbestand fuer die App: feste Blitzer, Abstandsmessungen, Abschnittskontrollen und
Mindestabstand-Strecken in Bayern aus OpenStreetMap (ODbL, (c) OpenStreetMap-Mitwirkende).

Wird im CI vor dem Bauen ausgefuehrt und landet als Asset in der APK. So zeigt die App die
Verkehrsueberwachung sofort ab dem ersten Start an; danach erneuert sie die Daten woechentlich
selbst. Schlaegt der Download hier fehl, wird ohne Startbestand gebaut (kein Abbruch).

Aufruf: python3 tools/fetch_enforcement.py app/src/main/assets/enforcement
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
# Rechteck um Bayern (etwas grosszuegiger als die Landesgrenze)
BB = "(47.20,8.90,50.60,13.90)"
# Gleiche Bausteine wie Overpass.enforcementBBoxQuery() in der App
QUERY = (
    '[out:json][timeout:170];'
    'node["highway"="speed_camera"]{bb};out body;'
    'relation["type"="enforcement"]{bb};out body geom;'
    '(way["mindistance"]{bb};way["mindistance:hgv"]{bb};);out tags geom;'
).format(bb=BB)


# Alle Tankstellen (ohne Preise, mit UTA-Tag): Liste ohne Tankerkoenig-Schluessel und UTA-Abgleich
FUEL_QUERY = (
    '[out:json][timeout:170];'
    '(node["amenity"="fuel"]{bb};way["amenity"="fuel"]{bb};);out center tags;'
).format(bb=BB)


def fetch(ep: str, query: str = QUERY) -> bytes:
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(ep, data=data, headers={
        "User-Agent": "KSS-Maps-Build/1.0 (private Navigations-App; einmal pro Build)",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    with urllib.request.urlopen(req, timeout=190) as r:
        return r.read()


def fetch_fuel(out_dir: str) -> None:
    for ep in ENDPOINTS:
        try:
            t0 = time.time()
            body = fetch(ep, FUEL_QUERY)
            d = json.loads(body)
            if "error" in d.get("remark", "").lower():
                raise ValueError(d["remark"])
            n = len(d.get("elements", []))
            if n < 500:
                raise ValueError("unplausibel wenige Tankstellen: %d" % n)
            os.makedirs(out_dir, exist_ok=True)
            with open(os.path.join(out_dir, "tankstellen.json"), "wb") as f:
                f.write(body)
            print("Tankstellen: %d, %d kB, %s, %.0f s" % (n, len(body) // 1024, ep, time.time() - t0))
            return
        except Exception as e:
            print("Tankstellen: Fehler bei %s: %s" % (ep, e))
    print("Keine Tankstellenliste - die App fragt OpenStreetMap bei Bedarf direkt.")


def fetch_enforcement(out_dir: str) -> bool:
    for attempt in range(2):
        for ep in ENDPOINTS:
            try:
                t0 = time.time()
                body = fetch(ep)
                d = json.loads(body)
                remark = d.get("remark", "")
                if "error" in remark.lower():
                    raise ValueError(remark)
                els = d.get("elements", [])
                cams = sum(1 for e in els if e.get("type") == "node" and e.get("tags", {}).get("highway") == "speed_camera")
                if cams < 200:
                    raise ValueError("unplausibel wenige Blitzer: %d" % cams)
                os.makedirs(out_dir, exist_ok=True)
                with open(os.path.join(out_dir, "bayern.json"), "wb") as f:
                    f.write(body)
                print("Startbestand: %d Objekte (%d Blitzer), %d kB, %s, %.0f s"
                      % (len(els), cams, len(body) // 1024, ep, time.time() - t0))
                return True
            except Exception as e:  # naechsten Server probieren
                print("Fehler bei %s: %s" % (ep, e))
        time.sleep(15)
    print("Kein Startbestand - die App laedt die Daten beim ersten Start selbst.")
    return False


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/enforcement"
    fetch_enforcement(out_dir)   # wichtiger zuerst
    fetch_fuel(out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
