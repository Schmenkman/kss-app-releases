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


def fetch(ep: str, query: str = QUERY, timeout: int = 150) -> bytes:
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(ep, data=data, headers={
        "User-Agent": "KSS-Maps-Build/1.0 (private Navigations-App; einmal pro Build)",
        "Content-Type": "application/x-www-form-urlencoded",
    })
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def fetch_fuel(out_dir: str) -> None:
    for ep in ENDPOINTS:
        try:
            t0 = time.time()
            body = fetch(ep, FUEL_QUERY, 120)
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


def tile_query(bb: str) -> str:
    return (
        '[out:json][timeout:120];'
        'node["highway"="speed_camera"]{bb};out body;'
        'relation["type"="enforcement"]{bb};out body geom;'
        '(way["mindistance"]{bb};way["mindistance:hgv"]{bb};);out tags geom;'
    ).format(bb=bb)


def fetch_enforcement_tiles(out_dir: str) -> bool:
    """Rueckfallebene bei ueberlasteten Servern: Bayern in 4 kleineren Teilen laden und zusammenfuegen."""
    s, w, n, e = 47.20, 8.90, 50.60, 13.90
    ms, mw = (s + n) / 2, (w + e) / 2
    tiles = [(s, w, ms, mw), (s, mw, ms, e), (ms, w, n, mw), (ms, mw, n, e)]
    elements, seen = [], set()
    for t in tiles:
        bb = "(%.2f,%.2f,%.2f,%.2f)" % t
        ok = False
        for attempt in range(2):
            for ep in ENDPOINTS:
                try:
                    d = json.loads(fetch(ep, tile_query(bb), 100))
                    if "error" in d.get("remark", "").lower():
                        raise ValueError(d["remark"])
                    for el in d.get("elements", []):
                        key = (el.get("type"), el.get("id"))
                        if key not in seen:
                            seen.add(key)
                            elements.append(el)
                    ok = True
                    break
                except Exception as ex:
                    print("Teil %s: Fehler bei %s: %s" % (bb, ep, ex))
            if ok:
                break
            time.sleep(10)
        if not ok:
            print("Teil %s fehlt - kein Startbestand." % bb)
            return False
    cams = sum(1 for el in elements if el.get("type") == "node" and el.get("tags", {}).get("highway") == "speed_camera")
    if cams < 200:
        print("unplausibel wenige Blitzer: %d" % cams)
        return False
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "bayern.json"), "w", encoding="utf-8") as f:
        json.dump({"version": 0.6, "elements": elements}, f, ensure_ascii=False)
    print("Startbestand (4 Teile): %d Objekte (%d Blitzer)" % (len(elements), cams))
    return True


def fetch_enforcement(out_dir: str) -> bool:
    for attempt in range(1):
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
        time.sleep(5)
    if fetch_enforcement_tiles(out_dir):
        return True
    print("Kein Startbestand - die App laedt die Daten beim ersten Start selbst.")
    return False


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/enforcement"
    what = sys.argv[2] if len(sys.argv) > 2 else "alle"   # blitzer | tankstellen | alle
    if what in ("blitzer", "alle"):
        fetch_enforcement(out_dir)
    if what in ("tankstellen", "alle"):
        fetch_fuel(out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
