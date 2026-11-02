// Signalverarbeitung: Glättung (Kalman), Entfernungsschätzung, Hilfsfunktionen.
// Reine Logik ohne DOM.

/** Eindimensionaler Kalman-Filter für RSSI-Werte (dBm). */
export class Kalman {
  constructor(q = 0.8, r = 9) {
    this.q = q;   // Prozessrauschen pro Schritt (dB²) – wie schnell sich der Pegel ändern darf
    this.r = r;   // Messrauschen (dB²) eines Einzelwerts
    this.reset();
  }
  reset() {
    this.x = null;
    this.p = 0;
  }
  /** z = Messwert, n = Anzahl gemittelter Einzelwerte (verringert das Messrauschen) */
  update(z, n = 1) {
    if (z === null || z === undefined || !Number.isFinite(z)) return this.x;
    const r = this.r / Math.max(1, n);
    if (this.x === null) {
      this.x = z;
      this.p = r;
      return this.x;
    }
    this.p += this.q;
    const k = this.p / (this.p + r);
    this.x += k * (z - this.x);
    this.p *= 1 - k;
    return this.x;
  }
}

export function chanToMHz(ch) {
  if (ch >= 1 && ch <= 13) return 2407 + 5 * ch;
  if (ch === 14) return 2484;
  if (ch >= 32 && ch <= 177) return 5000 + 5 * ch;
  return 0;
}

export function bandOf(ch) {
  if (ch >= 1 && ch <= 14) return 2;
  if (ch >= 32 && ch <= 177) return 5;
  return 0;
}

export function bandLabel(ch) {
  const b = bandOf(ch);
  return b === 2 ? "2,4 GHz" : b === 5 ? "5 GHz" : "?";
}

/** Freiraumdämpfung bei 1 m (dB) */
export function fspl1m(mhz) {
  return 20 * Math.log10(mhz) - 27.55;
}

/** typische Sendeleistung (EIRP), wenn der AP keine meldet */
export const DEFAULT_TXP = { 2: 17, 5: 20 };

/** Erwarteter Pegel in 1 m Abstand mit 0-dBi-Empfangsantenne. */
export function rssiAt1m(ch, txp = null, override = null) {
  if (override !== null && override !== undefined && Number.isFinite(override)) return override;
  const tx = txp ?? DEFAULT_TXP[bandOf(ch)] ?? 17;
  return tx - fspl1m(chanToMHz(ch) || 2437);
}

/**
 * Entfernung aus dem Pegel (Log-Distance-Modell). Liefert einen Bereich,
 * weil der Pfadverlust-Exponent in Gebäuden stark schwankt.
 *   rssi       gemessener (geglätteter) Pegel in dBm
 *   antGain    Gewinn der Empfangsantenne in Hauptrichtung (dBi)
 *   attenDb    eingesetztes Dämpfungsglied (dB), wird zurückgerechnet
 *   nLo/nHi    Exponent-Bereich (2 = Freiraum, 3–3,5 = verbaut)
 */
export function estimateDistance({ rssi, ch, txp = null, antGain = 0, attenDb = 0, ref1m = null, nLo = 2.0, nHi = 3.0 }) {
  if (rssi === null || rssi === undefined || !Number.isFinite(rssi)) return null;
  const eff = rssi + attenDb - antGain;
  const r1 = rssiAt1m(ch, txp, ref1m);
  const loss = r1 - eff;
  if (loss <= 0) return { typ: 1, min: 0.3, max: 1, loss, r1 };
  const d = (n) => Math.pow(10, loss / (10 * n));
  const nTyp = (nLo + nHi) / 2;
  return { typ: d(nTyp), min: d(nHi), max: d(nLo), loss, r1 };
}

/**
 * Horizontale Entfernung aus dem Elevationswinkel zum AP und der Höhe
 * zwischen Antenne und Decke.
 */
export function horizontalFromElevation(elevDeg, ceilingM, handM) {
  const dh = ceilingM - handM;
  if (!(dh > 0) || !(elevDeg > 3)) return null;
  const e = Math.min(elevDeg, 89.5) * (Math.PI / 180);
  return dh / Math.tan(e);
}

export function fmtMeters(m) {
  if (m === null || m === undefined || !Number.isFinite(m)) return "–";
  if (m < 1) return "< 1 m";
  if (m < 10) return m.toFixed(1).replace(".", ",") + " m";
  return Math.round(m) + " m";
}

export function fmtRange(est) {
  if (!est) return "–";
  if (est.max <= 1) return "< 1 m";
  const lo = est.min < 10 ? est.min.toFixed(1).replace(".", ",") : Math.round(est.min);
  const hi = est.max < 10 ? est.max.toFixed(1).replace(".", ",") : Math.round(est.max);
  return `${lo}–${hi} m`;
}

/** Pegel auf 0…1 abbilden (für Balken/Ton). */
export function level01(rssi, lo = -90, hi = -30) {
  if (rssi === null || rssi === undefined || !Number.isFinite(rssi)) return 0;
  return Math.min(1, Math.max(0, (rssi - lo) / (hi - lo)));
}

/** Ab diesem Pegel (dBm, vor Dämpfungsglied) droht der Empfänger zu übersteuern. */
export const OVERLOAD_DBM = -30;

export function rssiColor(rssi) {
  if (rssi === null || rssi === undefined) return "var(--muted)";
  if (rssi >= -50) return "var(--good)";
  if (rssi >= -67) return "var(--ok)";
  if (rssi >= -80) return "var(--warn)";
  return "var(--bad)";
}
