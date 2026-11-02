// Lage des Handys → Richtung der Antennen-Hauptkeule (Azimut/Elevation).
// Nutzt "deviceorientation" (auf Android gyro-gestützt, unabhängig von
// Kompass-Störungen im Gebäude). Ohne Sensor (PC) wird simuliert.
import { circularMean, orientationToDir } from "./bearing.js";
import { Emitter } from "./protocol.js";

export class Orientation extends Emitter {
  constructor(getAxis) {
    super();
    this.getAxis = getAxis;
    this.hasSensor = false;
    this.last = null;
    this.history = [];
    this.sim = { az: 0, el: 0 };
    this.forceSim = false;
    this._onEvent = this._onEvent.bind(this);
  }

  start() {
    if (typeof window !== "undefined") window.addEventListener("deviceorientation", this._onEvent);
  }

  _onEvent(e) {
    if (e.alpha === null && e.beta === null && e.gamma === null) return;
    this.hasSensor = true;
    const d = orientationToDir(e.alpha, e.beta, e.gamma, this.getAxis());
    this.last = { ...d, t: performance.now() };
    this._remember(d.az, d.el, false);
    if (!this.usingSim) this.emit("change", this.current());
  }

  get usingSim() {
    return this.forceSim || !this.hasSensor;
  }

  current() {
    if (this.usingSim || !this.last) return { az: this.sim.az, el: this.sim.el, sim: true };
    return { az: this.last.az, el: this.last.el, sim: false };
  }

  _remember(az, el, sim) {
    const now = performance.now();
    this.history.push({ az, el, sim, t: now });
    while (this.history.length && now - this.history[0].t > 2000) this.history.shift();
  }

  /**
   * Lage gemittelt über das Zeitfenster, zu dem ein gerade eingetroffener
   * Pegelwert gehört: Der Messkopf mittelt 100 ms, dazu kommen USB- und
   * App-Laufzeit. Ohne diesen Ausgleich wäre die Peilung beim Drehen um
   * einige Grad in Drehrichtung verschoben.
   */
  averaged(fromMs = 140, toMs = 20) {
    const sim = this.usingSim;
    const now = performance.now();
    const h = this.history.filter((x) => x.sim === sim && now - x.t <= fromMs && now - x.t >= toMs);
    if (!h.length) return this.current();
    return {
      az: circularMean(h.map((x) => x.az)),
      el: h.reduce((s, x) => s + x.el, 0) / h.length,
      sim,
    };
  }

  setSim(az, el = this.sim.el) {
    this.sim.az = ((az % 360) + 360) % 360;
    this.sim.el = Math.max(-90, Math.min(90, el));
    if (this.usingSim) {
      this._remember(this.sim.az, this.sim.el, true);
      this.emit("change", this.current());
    }
  }
}
