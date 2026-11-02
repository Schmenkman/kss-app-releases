// Simulator: verhält sich wie die ESP32-C5-Firmware (gleiches Protokoll),
// erzeugt die Pegel aber aus einem einfachen Raum-Modell. Damit lassen sich
// App und Bedienung ohne Hardware testen. Alle MAC-Adressen sind Beispiele.
import { bandOf, chanToMHz } from "./signal.js";

const D2R = Math.PI / 180;

/**
 * Beispielgebäude: Raum 20 × 12 m, Decke 3 m, Ruckus-APs über der abgehängten
 * Decke, SSID @BayernWLAN. Adressen nach dem Ruckus-Schema (Basis-MAC …x0,
 * BSSIDs im selben 16er-Block; bei AP B liegt die 5-GHz-BSSID in einer
 * Variante des 4. Bytes). Alle Adressen sind Beispiele.
 */
export const DEFAULT_WORLD = {
  room: { w: 20, h: 12, ceiling: 3.0 },
  aps: [
    {
      id: "A", name: "", model: "R650", label: "8c:0c:90:1f:a9:30", pos: [14.4, 7.8, 3.1], loss: 2, ftm: false,
      radios: [
        { ch: 6, txp: 17, bssids: ["8c:0c:90:1f:a9:38"] },
        { ch: 44, txp: 20, bssids: ["8c:0c:90:1f:a9:3c"] },
      ],
      ssids: ["@BayernWLAN"],
    },
    {
      id: "B", name: "", model: "R510", label: "c4:10:8a:18:22:80", pos: [3.2, 9.6, 3.1], loss: 2, ftm: false,
      radios: [
        { ch: 1, txp: 18, bssids: ["c4:10:8a:18:22:88"] },
        { ch: 100, txp: 23, bssids: ["c4:10:8a:58:22:8c"] },
      ],
      ssids: ["@BayernWLAN"],
    },
    {
      id: "C", name: "BY-Rathaus-OG1-Saal", model: "R550", label: "c4:10:8a:18:23:40", pos: [2.0, 22.0, 3.1], loss: 8, ftm: true,
      radios: [
        { ch: 11, txp: 17, bssids: ["c4:10:8a:18:23:48"] },
        { ch: 52, txp: 20, bssids: ["c4:10:8a:18:23:4c"] },
      ],
      ssids: ["@BayernWLAN"],
    },
    {
      id: "D", name: "", model: "Fremdgerät", label: "3c:d9:2b:01:02:00", pos: [27, -6, 1.2], loss: 14, ftm: false,
      radios: [
        { ch: 11, txp: 20, bssids: ["3c:d9:2b:01:02:01"] },
        { ch: 36, txp: 20, bssids: ["3c:d9:2b:01:02:11"] },
      ],
      ssids: ["FRITZ!Box 7590 XY"],
    },
  ],
};

const SCAN_CH = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
  36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 149, 153, 157, 161, 165];

/** reproduzierbarer Zufall */
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Richtdiagramm Panel-Antenne: Hauptkeule cosⁿ, Rückseite −20 dB */
export function panelGainDb(thetaDeg, band) {
  const peak = band === 5 ? 10 : 8;
  const hpbw = band === 5 ? 50 : 66;
  if (thetaDeg >= 90) return peak - 20;
  const n = Math.log(0.5) / Math.log(Math.cos((hpbw / 2) * D2R));
  const p = Math.pow(Math.cos(thetaDeg * D2R), n);
  return Math.max(peak + 10 * Math.log10(Math.max(p, 1e-9)), peak - 20);
}

export class Simulator {
  /**
   * pose():    { pos: [x, y, z], az, el }  (az im Uhrzeigersinn ab +y)
   * antenna(): { type: "dir" | "omni", attenDb }
   */
  constructor({ world = DEFAULT_WORLD, pose, antenna, seed = 42 } = {}) {
    this.world = world;
    this.pose = pose || (() => ({ pos: [4, 4, 1.3], az: 0, el: 0 }));
    this.antenna = antenna || (() => ({ type: "omni", attenDb: 0 }));
    this.rand = mulberry32(seed);
    this.onLine = null;
    this.timer = null;
    this.t = 0;
    this.mode = "idle";
    this.countData = false;
    this._resetTrack();
  }

  start() {
    this.stop();
    this.t = 0;
    this.timer = setInterval(() => this.advance(20), 20);
    setTimeout(() => this._out({ t: "hello", fw: "sim-0.1.0", chip: "simulator", idf: "-", mac: "02:00:00:00:00:01" }), 50);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.mode = "idle";
  }

  _out(o) {
    this.onLine?.(JSON.stringify(o));
  }

  _gauss() {
    const u = 1 - this.rand();
    const v = this.rand();
    return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
  }

  /** ortsfeste Mehrwege-Welligkeit (±3 dB), damit „heiß/kalt“ realistisch ruckelt */
  _multipath(pos, ch) {
    const l = ch <= 14 ? 0.9 : 0.45;
    return 1.8 * Math.sin((2 * Math.PI * pos[0]) / l + ch) * Math.sin((2 * Math.PI * pos[1]) / (l * 1.3) + ch * 0.7) +
      1.2 * Math.sin((2 * Math.PI * (pos[0] + pos[1])) / (l * 2.1));
  }

  /** Pegel eines Frames vom Funkmodul radio des APs ap am aktuellen Standort */
  rssiFor(ap, radio, noise = true) {
    const p = this.pose();
    const ant = this.antenna();
    const d = [ap.pos[0] - p.pos[0], ap.pos[1] - p.pos[1], ap.pos[2] - p.pos[2]];
    const dist = Math.max(0.3, Math.hypot(...d));
    const band = bandOf(radio.ch);
    let gain = 2;
    if (ant.type === "dir") {
      const beam = [Math.sin(p.az * D2R) * Math.cos(p.el * D2R), Math.cos(p.az * D2R) * Math.cos(p.el * D2R), Math.sin(p.el * D2R)];
      const cos = (beam[0] * d[0] + beam[1] * d[1] + beam[2] * d[2]) / dist;
      gain = panelGainDb(Math.acos(Math.max(-1, Math.min(1, cos))) / D2R, band);
    }
    const fspl = 20 * Math.log10(dist) + 20 * Math.log10(chanToMHz(radio.ch)) - 27.55;
    let r = radio.txp + gain - fspl - ap.loss + this._multipath(p.pos, radio.ch) - (ant.attenDb || 0);
    if (noise) r += 2.2 * this._gauss();
    if (r > -25) r = -25 + (r + 25) * 0.3; // Übersteuerung: Empfänger komprimiert
    return Math.round(r);
  }

  _radiosOn(ch) {
    const out = [];
    for (const ap of this.world.aps) for (const r of ap.radios) if (r.ch === ch) out.push({ ap, radio: r });
    return out;
  }

  command(line) {
    const tok = String(line).trim().split(/\s+/);
    const c = (tok[0] || "").toUpperCase();
    if (c === "HELLO") {
      this._out({ t: "hello", fw: "sim-0.1.0", chip: "simulator", idf: "-", mac: "02:00:00:00:00:01" });
    } else if (c === "PING") {
      this._out({ t: "pong", ms: Math.round(this.t), mode: 0 });
    } else if (c === "STOP") {
      this.mode = "idle";
      this._out({ t: "ack", cmd: "STOP" });
    } else if (c === "SCAN") {
      const dwell = parseInt(tok[1] || "110", 10);
      const bands = tok[2] || "25";
      this.scan = {
        chans: SCAN_CH.filter((ch) => (ch <= 14 ? bands.includes("2") : bands.includes("5"))),
        idx: 0, dwell, next: this.t + dwell, sweepStart: this.t, no: 0, bss: 0,
      };
      this.mode = "scan";
      this._out({ t: "ack", cmd: "SCAN", dwell, nch: this.scan.chans.length });
    } else if (c === "TRACK") {
      const ch = parseInt(tok[1], 10);
      const probe = parseInt(tok[2], 10) || 0;
      const bssids = (tok[3] || "").toLowerCase().split(",").filter(Boolean);
      this._resetTrack();
      Object.assign(this.track, { ch, probe, bssids, next: this.t + 100, nextProbe: this.t });
      this.mode = "track";
      this._out({ t: "ack", cmd: "TRACK", ch, n: bssids.length, probe });
    } else if (c === "FTM") {
      const ch = parseInt(tok[1], 10);
      const b = (tok[2] || "").toLowerCase();
      setTimeout(() => this._ftm(ch, b), 350);
    } else if (c === "DATA") {
      this.countData = tok[1] === "1";
      this._out({ t: "ack", cmd: "DATA", on: this.countData ? 1 : 0 });
    } else {
      this._out({ t: "err", msg: "unbekannter Befehl" });
    }
  }

  _resetTrack() {
    this.track = { ch: 0, probe: 0, bssids: [], next: 0, nextProbe: 0, b: [], p: [], d: [], tx: 0, beaconPhase: 0 };
  }

  _ftm(ch, bssid) {
    for (const ap of this.world.aps) {
      for (const r of ap.radios) {
        if (r.ch === ch && r.bssids.includes(bssid)) {
          if (!ap.ftm) {
            this._out({ t: "ftm", b: bssid, ok: false, status: 1, dist_cm: 0, rtt_ns: 0, rtt_raw: 0, n: 0 });
            return;
          }
          const p = this.pose();
          const dist = Math.hypot(ap.pos[0] - p.pos[0], ap.pos[1] - p.pos[1], ap.pos[2] - p.pos[2]);
          const meas = Math.max(0, dist + 0.6 * this._gauss() + 0.4);
          this._out({ t: "ftm", b: bssid, ok: true, status: 0, dist_cm: Math.round(meas * 100),
            rtt_ns: Math.round((meas * 2) / 0.2998), rtt_raw: Math.round((meas * 2) / 0.2998) + 3, n: 24 });
          return;
        }
      }
    }
    this._out({ t: "ftm", b: bssid, ok: false, err: "timeout" });
  }

  /** Simulationszeit um dt Millisekunden weiterschalten */
  advance(dt) {
    const end = this.t + dt;
    while (this.t < end) {
      const step = Math.min(10, end - this.t);
      this.t += step;
      if (this.mode === "scan") this._scanStep();
      else if (this.mode === "track") this._trackStep(step);
    }
  }

  _scanStep() {
    const s = this.scan;
    if (this.t < s.next) return;
    const ch = s.chans[s.idx];
    for (const { ap, radio } of this._radiosOn(ch)) {
      radio.bssids.forEach((b, i) => {
        const r = this.rssiFor(ap, radio);
        if (r < -94) return;
        this._out({ t: "ap", b, s: ap.ssids[i] || ap.ssids[0], h: 0, ch, r, n: 1, txp: null,
          ftm: ap.ftm ? 1 : 0, enc: i === 0 ? "open" : "rsn", std: ch > 14 ? "ax" : "n", nm: ap.name, bi: 100 });
        s.bss++;
      });
    }
    s.idx++;
    if (s.idx >= s.chans.length) {
      s.idx = 0;
      s.no++;
      this._out({ t: "sweep", no: s.no, ms: Math.round(this.t - s.sweepStart), bss: s.bss, drop: 0 });
      s.sweepStart = this.t;
      s.bss = 0;
    }
    s.next = this.t + s.dwell;
  }

  _trackStep(step) {
    const tr = this.track;
    const targets = [];
    for (const { ap, radio } of this._radiosOn(tr.ch)) {
      for (const b of radio.bssids) if (tr.bssids.includes(b)) targets.push({ ap, radio, b });
    }
    // Beacons: jede BSSID alle 102,4 ms
    tr.beaconPhase += step;
    while (tr.beaconPhase >= 102.4 / Math.max(1, targets.length) && targets.length) {
      tr.beaconPhase -= 102.4 / targets.length;
      const tg = targets[Math.floor(this.rand() * targets.length)];
      const r = this.rssiFor(tg.ap, tg.radio);
      if (r >= -94) tr.b.push(r);
    }
    // Probe Requests → Probe Responses
    if (tr.probe > 0 && this.t >= tr.nextProbe) {
      tr.nextProbe = this.t + 1000 / tr.probe;
      tr.tx++;
      if (targets.length) {
        const tg = targets[tr.tx % targets.length];
        const r = this.rssiFor(tg.ap, tg.radio);
        if (r >= -92) tr.p.push(r);
      }
    }
    if (this.countData && targets.length && this.rand() < 0.15) {
      const tg = targets[0];
      const r = this.rssiFor(tg.ap, tg.radio) + 3 * this._gauss();
      if (r >= -94) tr.d.push(Math.round(r));
    }
    if (this.t >= tr.next) {
      const agg = (a) => (a.length ? (a.reduce((x, y) => x + y, 0) / a.length) : null);
      this._out({
        t: "rssi", ms: Math.round(this.t), ch: tr.ch,
        nb: tr.b.length, rb: tr.b.length ? Math.round(agg(tr.b) * 10) / 10 : null,
        xb: tr.b.length ? Math.max(...tr.b) : null, mb: tr.b.length ? Math.min(...tr.b) : null,
        np: tr.p.length, rp: tr.p.length ? Math.round(agg(tr.p) * 10) / 10 : null, xp: tr.p.length ? Math.max(...tr.p) : null,
        nd: tr.d.length, rd: tr.d.length ? Math.round(agg(tr.d) * 10) / 10 : null, xd: tr.d.length ? Math.max(...tr.d) : null,
        nf: -95, tx: tr.tx, te: 0,
      });
      tr.b = [];
      tr.p = [];
      tr.d = [];
      tr.tx = 0;
      tr.next += 100;
    }
  }
}
