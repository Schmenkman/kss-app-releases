// Protokoll zum ESP32-C5: Textbefehle hin, JSON-Zeilen zurück (docs/PROTOKOLL.md).

/** Zerlegt einen Byte-/Text-Strom in Zeilen. */
export class LineSplitter {
  constructor(onLine) {
    this.onLine = onLine;
    this.buf = "";
  }
  push(chunk) {
    this.buf += chunk;
    let i;
    while ((i = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, i).replace(/\r$/, "");
      this.buf = this.buf.slice(i + 1);
      if (line) this.onLine(line);
    }
    if (this.buf.length > 8192) this.buf = ""; // Müll ohne Zeilenende verwerfen
  }
}

/** JSON-Zeile vom ESP auswerten; Bootloader-Ausgaben u. ä. werden ignoriert. */
export function parseLine(line) {
  const s = String(line).trim();
  if (!s.startsWith("{")) return null;
  try {
    const o = JSON.parse(s);
    return o && typeof o.t === "string" ? o : null;
  } catch {
    return null;
  }
}

export function ssidToHex(ssid) {
  const bytes = new TextEncoder().encode(String(ssid ?? "")).slice(0, 32);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export const cmd = {
  hello: () => "HELLO",
  ping: () => "PING",
  stop: () => "STOP",
  scan: (dwellMs = 110, bands = "25") => `SCAN ${dwellMs} ${bands}`,
  track: ({ ch, probeHz = 0, bssids, ssid = "" }) =>
    `TRACK ${ch} ${Math.max(0, Math.min(50, Math.round(probeHz)))} ${bssids.slice(0, 8).join(",")}` +
    (ssid ? " " + ssidToHex(ssid) : ""),
  ftm: (ch, bssid, frames = 32) => `FTM ${ch} ${bssid} ${frames}`,
  data: (on) => `DATA ${on ? 1 : 0}`,
};

/** Einfacher Event-Emitter (funktioniert im Browser und in Node). */
export class Emitter {
  constructor() {
    this._h = new Map();
  }
  on(ev, fn) {
    if (!this._h.has(ev)) this._h.set(ev, new Set());
    this._h.get(ev).add(fn);
    return () => this.off(ev, fn);
  }
  off(ev, fn) {
    this._h.get(ev)?.delete(fn);
  }
  emit(ev, data) {
    for (const fn of [...(this._h.get(ev) ?? [])]) {
      try {
        fn(data);
      } catch (e) {
        console.error(e);
      }
    }
  }
}
