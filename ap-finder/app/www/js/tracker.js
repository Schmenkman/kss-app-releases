// Verarbeitet die 100-ms-Messfenster des Messkopfs: Glättung, Spitze,
// Verlauf, Messrate, Messpunkte.
import { Emitter } from "./protocol.js";
import { Kalman } from "./signal.js";

const HISTORY_MS = 30000;
const STALE_MS = 2500;

export class Tracker extends Emitter {
  constructor(device, getSettings) {
    super();
    this.getSettings = getSettings;
    this.kalman = new Kalman(0.8, 9);
    this.marks = [];
    this.reset();
    device.on("rssi", (m) => this._onRssi(m));
  }

  reset() {
    this.kalman.reset();
    this.value = null;      // geglätteter Pegel (dBm)
    this.raw = null;        // Mittelwert des letzten Fensters
    this.rawMax = null;     // stärkster Einzelwert des letzten Fensters
    this.peak = null;       // höchster geglätteter Wert seit Reset
    this.rate = 0;          // Messwerte pro Sekunde
    this.lastSample = 0;
    this.history = [];
    this.probeTx = 0;
    this.probeErr = 0;
    this.beaconAvg = null;  // Beacon-Pegel (geglättet, nur Anzeige)
    this.probeAvg = null;   // Pegel der Probe-Antworten (geglättet, nur Anzeige)
    this.emit("update", this);
  }

  resetPeak() {
    this.peak = this.value;
    this.emit("update", this);
  }

  get stale() {
    return !this.lastSample || Date.now() - this.lastSample > STALE_MS;
  }

  _onRssi(m) {
    const s = this.getSettings();
    // Beacons immer; Probe-Antworten (Standard: ja) und Datenframes (Standard: nein) optional
    const parts = [];
    if (m.nb > 0 && m.rb !== null && m.rb !== undefined) parts.push([m.rb, m.nb]);
    if (s.useProbeResp !== false && m.np > 0 && m.rp !== null && m.rp !== undefined) parts.push([m.rp, m.np]);
    if (s.countData && m.nd > 0 && m.rd !== null && m.rd !== undefined) parts.push([m.rd, m.nd]);
    const n = parts.reduce((a, [, k]) => a + k, 0);
    const raw = n ? parts.reduce((a, [v, k]) => a + v * k, 0) / n : null;

    const ema = (old, v) => (v === null || v === undefined ? old : old === null ? v : 0.8 * old + 0.2 * v);
    this.beaconAvg = ema(this.beaconAvg, m.nb > 0 ? m.rb : null);
    this.probeAvg = ema(this.probeAvg, m.np > 0 ? m.rp : null);

    const now = Date.now();
    this.rate = 0.8 * this.rate + 0.2 * (n * 10);
    this.probeTx = m.tx ?? 0;
    this.probeErr = m.te ?? 0;
    if (raw !== null) {
      this.raw = raw;
      this.rawMax = Math.max(m.xb ?? -999, m.xp ?? -999, m.xd ?? -999);
      this.value = this.kalman.update(raw, n);
      this.lastSample = now;
      if (this.peak === null || this.value > this.peak) this.peak = this.value;
    }
    this.history.push({ t: now, v: this.stale ? null : this.value, raw });
    while (this.history.length && now - this.history[0].t > HISTORY_MS) this.history.shift();
    this.emit("update", this);
  }

  /** höchster geglätteter Wert der letzten ms Millisekunden (Richtantenne: ≈ auf den AP gerichtet) */
  recentPeak(ms = 15000) {
    const now = Date.now();
    let best = null;
    for (const p of this.history) {
      if (p.v !== null && now - p.t <= ms && (best === null || p.v > best)) best = p.v;
    }
    return best;
  }

  addMark(label) {
    if (this.value === null || this.stale) return null;
    const mark = { label: label || `P${this.marks.length + 1}`, value: this.value, t: Date.now() };
    this.marks.push(mark);
    this.emit("marks", this.marks);
    return mark;
  }

  clearMarks() {
    this.marks = [];
    this.emit("marks", this.marks);
  }
}
