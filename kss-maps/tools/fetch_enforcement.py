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
import shutil
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


# Autobahn-/Schnellstrassen-Abschnitte mit Wechselverkehrszeichen (LED-Anzeigen) oder Tempolimit
# nach Uhrzeit: dort zeigt die App "Anzeige beachten" statt "frei" bzw. das zur Uhrzeit gueltige Limit.
# Der seltene Schluessel zuerst (schnell ueber den Index), Strassenart wird hier im Skript geprueft.
def tempo_query(bb: str, timeout: int) -> str:
    return (
        '[out:json][timeout:{t}];'
        '('
        'way["maxspeed:variable"]{bb};'
        'way["maxspeed"="signals"]{bb};'
        'way["maxspeed:conditional"]["highway"~"^(motorway|trunk)$"]{bb};'
        ');out tags geom;'
    ).format(t=timeout, bb=bb)


FAST_ROADS = ("motorway", "motorway_link", "trunk", "trunk_link")

# Bayern in 4 Teilen (Rueckfallebene bei ueberlasteten Servern)
_S, _W, _N, _E = 47.20, 8.90, 50.60, 13.90
_MS, _MW = (_S + _N) / 2, (_W + _E) / 2
TILES = [(_S, _W, _MS, _MW), (_S, _MW, _MS, _E), (_MS, _W, _N, _MW), (_MS, _MW, _N, _E)]

# Letzter guter Stand (GitHub-Cache der CI): sind alle Overpass-Server ueberlastet, kommt der
# Stand des letzten erfolgreichen Builds in die APK statt gar keiner.
CACHE = os.environ.get("KSSMAPS_SNAPSHOT_CACHE", "")


def remember(out_dir: str, name: str) -> None:
    if not CACHE:
        return
    os.makedirs(CACHE, exist_ok=True)
    shutil.copyfile(os.path.join(out_dir, name), os.path.join(CACHE, name))


def restore(out_dir: str, name: str, what: str) -> bool:
    src = os.path.join(CACHE, name) if CACHE else ""
    if not src or not os.path.isfile(src):
        return False
    os.makedirs(out_dir, exist_ok=True)
    shutil.copyfile(src, os.path.join(out_dir, name))
    age = (time.time() - os.path.getmtime(src)) / 86400
    print("%s: Stand des letzten erfolgreichen Builds aus dem Zwischenspeicher (%.1f Tage alt)" % (what, age))
    return True


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
            remember(out_dir, "tankstellen.json")
            print("Tankstellen: %d, %d kB, %s, %.0f s" % (n, len(body) // 1024, ep, time.time() - t0))
            return
        except Exception as e:
            print("Tankstellen: Fehler bei %s: %s" % (ep, e))
    if restore(out_dir, "tankstellen.json", "Tankstellen"):
        return
    print("Keine Tankstellenliste - die App fragt OpenStreetMap bei Bedarf direkt.")


def tempo_ways(elements, into: dict) -> None:
    for e in elements:
        g = e.get("geometry") or []
        if e.get("type") != "way" or len(g) < 2:
            continue
        t = e.get("tags", {})
        var = "maxspeed:variable" in t or t.get("maxspeed") == "signals"
        hw = t.get("highway", "")
        if not ((var and hw in FAST_ROADS) or (t.get("maxspeed:conditional") and hw in ("motorway", "trunk"))):
            continue
        flat = []
        for pt in g:
            flat += [round(pt["lat"], 5), round(pt["lon"], 5)]
        into[e.get("id")] = {
            "ms": t.get("maxspeed", ""),
            "var": 1 if var else 0,
            "cond": t.get("maxspeed:conditional", ""),
            "ow": 0 if t.get("oneway") == "no" else 1,
            "g": flat,
        }


def write_tempo(out_dir: str, ways: list, how: str, t0: float) -> None:
    body = json.dumps({"ways": ways}, separators=(",", ":")).encode()
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "tempo.json"), "wb") as f:
        f.write(body)
    remember(out_dir, "tempo.json")
    var = sum(1 for w in ways if w["var"])
    print("Tempo: %d Abschnitte (%d mit Wechselverkehrszeichen), %d kB, %s, %.0f s"
          % (len(ways), var, len(body) // 1024, how, time.time() - t0))


def fetch_tempo(out_dir: str) -> None:
    """Kompakt fuer die App: {"ways":[{"ms":"none","var":1,"cond":"...","ow":1,"g":[lat,lon,...]}]}."""
    t0 = time.time()
    deadline = t0 + 9 * 60
    # 1. ganz Bayern auf einmal
    for ep in ENDPOINTS:
        try:
            d = json.loads(fetch(ep, tempo_query(BB, 120), 140))
            if "error" in d.get("remark", "").lower():
                raise ValueError(d["remark"])
            ways = {}
            tempo_ways(d.get("elements", []), ways)
            if len(ways) < 20:
                raise ValueError("unplausibel wenige Abschnitte: %d" % len(ways))
            write_tempo(out_dir, list(ways.values()), ep, t0)
            return
        except Exception as e:
            print("Tempo: Fehler bei %s: %s" % (ep, e))
        if time.time() > deadline - 300:
            break
    # 2. in 4 Teilen
    ways = {}
    complete = True
    for t in TILES:
        bb = "(%.2f,%.2f,%.2f,%.2f)" % t
        ok = False
        for ep in ENDPOINTS:
            if time.time() > deadline:
                break
            try:
                d = json.loads(fetch(ep, tempo_query(bb, 80), 100))
                if "error" in d.get("remark", "").lower():
                    raise ValueError(d["remark"])
                tempo_ways(d.get("elements", []), ways)
                ok = True
                break
            except Exception as e:
                print("Tempo: Teil %s: Fehler bei %s: %s" % (bb, ep, e))
        if not ok:
            complete = False
            break
    if complete and len(ways) >= 20:
        write_tempo(out_dir, list(ways.values()), "4 Teile", t0)
        return
    # 3. letzter guter Stand
    if restore(out_dir, "tempo.json", "Tempo"):
        return
    print("Keine Wechselverkehrszeichen-Daten - die App zeigt dort das Limit der Route.")


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
    remember(out_dir, "bayern.json")
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
                remember(out_dir, "bayern.json")
                print("Startbestand: %d Objekte (%d Blitzer), %d kB, %s, %.0f s"
                      % (len(els), cams, len(body) // 1024, ep, time.time() - t0))
                return True
            except Exception as e:  # naechsten Server probieren
                print("Fehler bei %s: %s" % (ep, e))
        time.sleep(5)
    if fetch_enforcement_tiles(out_dir):
        return True
    if restore(out_dir, "bayern.json", "Blitzer"):
        return True
    print("Kein Startbestand - die App laedt die Daten beim ersten Start selbst.")
    return False


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/enforcement"
    what = sys.argv[2] if len(sys.argv) > 2 else "alle"   # blitzer | tankstellen | tempo | alle
    if what in ("blitzer", "alle"):
        fetch_enforcement(out_dir)
    if what in ("tankstellen", "alle"):
        fetch_fuel(out_dir)
    if what in ("tempo", "alle"):
        fetch_tempo(out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
