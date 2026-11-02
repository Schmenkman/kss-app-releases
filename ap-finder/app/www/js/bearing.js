// Peilung: Lage des Handys → Antennenrichtung, Pegel über Winkel sammeln,
// Richtung des Maximums schätzen. Reine Logik ohne DOM.

const D2R = Math.PI / 180;
const R2D = 180 / Math.PI;

export const norm360 = (x) => ((x % 360) + 360) % 360;

/** Winkeldifferenz a − b im Bereich (−180, 180] */
export function angDiff(a, b) {
  let d = norm360(a - b);
  if (d > 180) d -= 360;
  return d;
}

/**
 * Richtung der Antennen-Hauptkeule aus den DeviceOrientation-Winkeln
 * (W3C: R = Rz(alpha)·Rx(beta)·Ry(gamma), Welt: x = Ost, y = Nord, z = oben).
 *   axis "back": Keule zeigt aus der Rückseite (Kamera-Richtung)
 *   axis "top":  Keule zeigt aus der Oberkante
 * Rückgabe: { az: Azimut 0…360 (im Uhrzeigersinn), el: Elevation −90…90 }
 * Bei relativer Orientierung (Gyro) ist az relativ zu einer beliebigen,
 * aber festen Referenz – für eine Rundum-Peilung genügt das.
 */
export function orientationToDir(alpha, beta, gamma, axis = "back") {
  const a = (alpha || 0) * D2R;
  const b = (beta || 0) * D2R;
  const g = (gamma || 0) * D2R;
  const cX = Math.cos(b), sX = Math.sin(b);
  const cY = Math.cos(g), sY = Math.sin(g);
  const cZ = Math.cos(a), sZ = Math.sin(a);
  let v;
  if (axis === "top") {
    v = [-sZ * cX, cZ * cX, sX];
  } else {
    v = [-(cZ * sY + sZ * sX * cY), -(sZ * sY - cZ * sX * cY), -cX * cY];
  }
  const horiz = Math.hypot(v[0], v[1]);
  return {
    az: norm360(Math.atan2(v[0], v[1]) * R2D),
    el: Math.atan2(v[2], horiz) * R2D,
  };
}

/** Kreisförmiger Mittelwert von Winkeln (Grad) */
export function circularMean(angles, weights = null) {
  let sx = 0, sy = 0;
  angles.forEach((a, i) => {
    const w = weights ? weights[i] : 1;
    sx += w * Math.sin(a * D2R);
    sy += w * Math.cos(a * D2R);
  });
  if (sx === 0 && sy === 0) return null;
  return norm360(Math.atan2(sx, sy) * R2D);
}

/** Sammelt Pegel über dem Winkel in Klassen (Bins). */
export class SweepRecorder {
  /** range 360 = Rundum (Azimut), 90 = Elevation 0…90° */
  constructor(binDeg = 5, range = 360) {
    this.binDeg = binDeg;
    this.range = range;
    this.circular = range === 360;
    this.n = Math.round(range / binDeg);
    this.reset();
  }
  reset() {
    this.sum = new Float64Array(this.n);
    this.cnt = new Uint32Array(this.n);
    this.samples = 0;
    this.lastAngle = undefined;
    this.turned = 0; // aufsummierte Drehung (Grad) – zeigt, ob einmal rundum gedreht wurde
  }
  binOf(angle) {
    if (this.circular) return Math.floor(norm360(angle) / this.binDeg) % this.n;
    if (angle < 0 || angle > this.range) return -1;
    return Math.min(this.n - 1, Math.floor(angle / this.binDeg));
  }
  add(angle, rssi) {
    if (rssi === null || rssi === undefined || !Number.isFinite(rssi) || !Number.isFinite(angle)) return;
    const i = this.binOf(angle);
    if (i < 0) return;
    if (this.circular && this.lastAngle !== undefined) {
      this.turned += Math.abs(angDiff(angle, this.lastAngle));
    }
    this.lastAngle = angle;
    this.sum[i] += rssi;
    this.cnt[i]++;
    this.samples++;
  }
  coverage() {
    let c = 0;
    for (let i = 0; i < this.n; i++) if (this.cnt[i]) c++;
    return c / this.n;
  }
  values() {
    return Array.from({ length: this.n }, (_, i) => (this.cnt[i] ? this.sum[i] / this.cnt[i] : null));
  }
}

/**
 * Schätzt die Richtung des Maximums aus Bin-Werten (dBm, null = keine Daten).
 * Verfahren: Gauß-Glättung, Maximum suchen, dann Mitte zwischen den beiden
 * −3-dB-Flanken (genauer als das reine Maximum bei breiten Keulen).
 * Rückgabe: { angle, peak, contrast, width, quality, secondary, smoothed } oder null.
 */
export function estimatePeak(values, { binDeg = 5, circular = true, sigmaBins = 2 } = {}) {
  const n = values.length;
  if (!n) return null;
  const wrap = (i) => ((i % n) + n) % n;
  const at = (i) => (circular ? values[wrap(i)] : i >= 0 && i < n ? values[i] : null);

  const R = Math.max(1, Math.ceil(3 * sigmaBins));
  const sm = new Array(n).fill(null);
  for (let i = 0; i < n; i++) {
    let s = 0, w = 0;
    for (let d = -R; d <= R; d++) {
      const v = at(i + d);
      if (v === null || v === undefined) continue;
      const wt = Math.exp(-0.5 * (d / sigmaBins) ** 2);
      s += v * wt;
      w += wt;
    }
    sm[i] = w >= 0.5 ? s / w : null;
  }

  let pi = -1;
  for (let i = 0; i < n; i++) {
    if (sm[i] !== null && (pi < 0 || sm[i] > sm[pi])) pi = i;
  }
  if (pi < 0) return null;
  const peak = sm[pi];
  const smAt = (i) => (circular ? sm[wrap(i)] : i >= 0 && i < n ? sm[i] : null);

  const edge = (dir) => {
    let prev = peak;
    for (let s = 1; s < n; s++) {
      const v = smAt(pi + dir * s);
      if (v === null || v === undefined) return { pos: pi + dir * (s - 1), found: false };
      if (v <= peak - 3) {
        const frac = prev - v > 1e-9 ? (prev - (peak - 3)) / (prev - v) : 0;
        return { pos: pi + dir * (s - 1 + frac), found: true };
      }
      prev = v;
    }
    return { pos: pi, found: false };
  };
  const L = edge(-1);
  const Rr = edge(+1);
  let center = pi;
  let width = null;
  if (L.found && Rr.found) {
    center = (L.pos + Rr.pos) / 2;
    width = (Rr.pos - L.pos) * binDeg;
  }
  let angle = (center + 0.5) * binDeg;
  if (circular) angle = norm360(angle);
  else angle = Math.max(0, Math.min(n * binDeg, angle));

  const vals = sm.filter((v) => v !== null).sort((a, b) => a - b);
  const median = vals[Math.floor(vals.length / 2)];
  const contrast = peak - median;

  let secondary = null;
  for (let i = 0; i < n; i++) {
    const v = sm[i];
    if (v === null) continue;
    const l = smAt(i - 1), r = smAt(i + 1);
    const isMax = (l === null || l === undefined || v >= l) && (r === null || r === undefined || v >= r);
    if (!isMax) continue;
    const a = (i + 0.5) * binDeg;
    const sep = circular ? Math.abs(angDiff(a, angle)) : Math.abs(a - angle);
    if (sep >= 60 && v >= peak - 3 && (!secondary || v > secondary.value)) {
      secondary = { angle: circular ? norm360(a) : a, value: v };
    }
  }

  let quality = contrast >= 8 ? "gut" : contrast >= 4 ? "mittel" : "schwach";
  if (secondary) quality = "zweideutig";
  return { angle, peak, contrast, width, quality, secondary, smoothed: sm };
}
