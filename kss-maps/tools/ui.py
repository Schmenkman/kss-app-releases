"""Kleine UI-Fernsteuerung fuer die Testfahrt (uiautomator ueber adb).

  python3 ui.py tap --text "Los"
  python3 ui.py tap --desc "Luftbild"
  python3 ui.py exists --text "Ziel erreicht"
Rueckgabe 0 = gefunden/getippt, 1 = nicht gefunden.
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def dump():
    for _ in range(4):
        r = subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True, text=True, timeout=90)
        if "dumped" in (r.stdout + r.stderr):
            break
        time.sleep(2)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, text=True, timeout=30).stdout
    start = xml.find("<?xml")
    if start < 0:
        start = xml.find("<hierarchy")
    return ET.fromstring(xml[start:]) if start >= 0 else None


def find(root, text=None, desc=None, contains=False):
    if root is None:
        return None
    for n in root.iter("node"):
        t = n.get("text") or ""
        d = n.get("content-desc") or ""
        if text is not None and (t == text or (contains and text in t)):
            return n
        if desc is not None and (d == desc or (contains and desc in d)):
            return n
    return None


def center(n):
    b = [int(v) for v in re.findall(r"-?\d+", n.get("bounds"))]
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["tap", "exists", "longpress"])
    ap.add_argument("--text")
    ap.add_argument("--desc")
    ap.add_argument("--contains", action="store_true")
    a = ap.parse_args()
    n = find(dump(), a.text, a.desc, a.contains)
    if n is None:
        print(f"nicht gefunden: text={a.text} desc={a.desc}")
        return 1
    x, y = center(n)
    if a.cmd == "tap":
        subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
        print(f"getippt: {a.text or a.desc} @ {x},{y}")
    elif a.cmd == "longpress":
        subprocess.run(["adb", "shell", "input", "swipe", str(x), str(y), str(x), str(y), "1500"])
    else:
        print(f"gefunden: {a.text or a.desc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
