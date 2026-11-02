"""Simulierte Autofahrt fuer die Testfahrt im Emulator.

Liest die aktive Route aus dem Log der App (ROUTE_CHUNK-Zeilen, nur Test-Builds), faehrt sie
mit 1 GPS-Punkt pro Sekunde ab (adb emu geo fix) und macht zwischendurch Screenshots.

  python3 drive.py --log smoke/logcat.txt --out smoke --from 0 --to 9000 --speed 25 --shot-every 60 --prefix 05_fahrt
  python3 drive.py ... --from-end 900 --to-end 0 --speed 9 ...
"""
import argparse
import math
import re
import subprocess
import sys
import time


def decode(s, precision=6):
    factor = 10 ** precision
    pts, index, lat, lon = [], 0, 0, 0
    while index < len(s):
        for which in (0, 1):
            result, shift = 0, 0
            while True:
                b = ord(s[index]) - 63
                index += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            d = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += d
            else:
                lon += d
        pts.append((lat / factor, lon / factor))
    return pts


def read_route(path):
    text = open(path, errors="ignore").read()
    sets, cur, total = [], {}, 0
    for i, n, c in re.findall(r"ROUTE_CHUNK (\d+)/(\d+) (\S+)", text):
        i, n = int(i), int(n)
        if i == 1:
            cur, total = {}, n
        cur[i] = c
        if total and len(cur) == total:
            sets.append("".join(cur[k] for k in range(1, total + 1)))
    return decode(sets[-1]) if sets else None


def dist(a, b):
    r = 6371008.8
    f1, f2 = math.radians(a[0]), math.radians(b[0])
    df, dl = f2 - f1, math.radians(b[1] - a[1])
    h = math.sin(df / 2) ** 2 + math.cos(f1) * math.cos(f2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1, math.sqrt(h)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--prefix", default="fahrt")
    ap.add_argument("--from", dest="start", type=float)
    ap.add_argument("--to", dest="end", type=float)
    ap.add_argument("--from-end", type=float)
    ap.add_argument("--to-end", type=float)
    ap.add_argument("--speed", type=float, default=20.0)
    ap.add_argument("--shot-every", type=float, default=60.0)
    a = ap.parse_args()

    pts = None
    for _ in range(30):
        pts = read_route(a.log)
        if pts:
            break
        time.sleep(2)
    if not pts:
        print("Keine Route im Log gefunden")
        return 1
    cum = [0.0]
    for i in range(1, len(pts)):
        cum.append(cum[-1] + dist(pts[i - 1], pts[i]))
    total = cum[-1]
    start = a.start if a.start is not None else max(0.0, total - (a.from_end or 0))
    end = a.end if a.end is not None else max(0.0, total - (a.to_end or 0))
    end = min(end, total)
    print(f"Route {total:.0f} m, fahre {start:.0f} -> {end:.0f} m mit {a.speed} m/s")

    def point_at(d):
        lo, hi = 0, len(cum) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if cum[mid] <= d:
                lo = mid
            else:
                hi = mid
        seg = cum[hi] - cum[lo]
        t = 0 if seg <= 0 else (d - cum[lo]) / seg
        return (pts[lo][0] + (pts[hi][0] - pts[lo][0]) * t, pts[lo][1] + (pts[hi][1] - pts[lo][1]) * t)

    t0 = time.time()
    last_shot = t0
    k, d, n_shot = 0, start, 0
    while d <= end + 0.01:
        lat, lon = point_at(d)
        subprocess.run(["adb", "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"], capture_output=True)
        now = time.time()
        if now - last_shot >= a.shot_every:
            last_shot = now
            n_shot += 1
            fn = f"{a.out}/{a.prefix}_{n_shot:02d}.png"
            subprocess.Popen(f"adb exec-out screencap -p > {fn}", shell=True)
        k += 1
        d = start + k * a.speed
        sleep = t0 + k - time.time()
        if sleep > 0:
            time.sleep(sleep)
    # am Ende noch ein paar Sekunden stehen bleiben
    lat, lon = point_at(end)
    for _ in range(8):
        subprocess.run(["adb", "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}"], capture_output=True)
        time.sleep(1)
    subprocess.run(f"adb exec-out screencap -p > {a.out}/{a.prefix}_ende.png", shell=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
