// „Geigerzähler“-Ton: je stärker das Signal, desto schneller und höher.
export class Geiger {
  constructor() {
    this.ctx = null;
    this.on = false;
    this.level = 0;
    this.timer = null;
    this.volume = 0.6;
  }
  async enable() {
    try {
      if (!this.ctx) {
        const AC = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!AC) return false;
        this.ctx = new AC();
      }
      await this.ctx.resume();
    } catch {
      return false;
    }
    if (!this.on) {
      this.on = true;
      this._loop();
    }
    return true;
  }
  disable() {
    this.on = false;
    clearTimeout(this.timer);
  }
  /** 0 = kein Signal (Stille), 1 = maximal */
  setLevel(x) {
    this.level = Math.min(1, Math.max(0, Number.isFinite(x) ? x : 0));
  }
  _beep() {
    const c = this.ctx;
    const t = c.currentTime;
    const o = c.createOscillator();
    const g = c.createGain();
    o.type = "sine";
    o.frequency.value = 380 + 1300 * this.level;
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(0.35 * this.volume, t + 0.004);
    g.gain.exponentialRampToValueAtTime(0.0001, t + 0.06);
    o.connect(g).connect(c.destination);
    o.start(t);
    o.stop(t + 0.07);
  }
  _loop() {
    if (!this.on) return;
    if (this.level > 0.01 && this.ctx) this._beep();
    const interval = this.level > 0.01 ? 900 - 830 * this.level : 400;
    this.timer = setTimeout(() => this._loop(), interval);
  }
}
