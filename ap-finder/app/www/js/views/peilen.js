// Ansicht „Peilen“: Rundum-Peilung (Richtung) und Elevation (Entfernung zur Decke).
import { $, $$, cssVar, fmtDbm, fmtDeg, setupCanvas, toast } from "../util.js";
import { SweepRecorder, angDiff, estimatePeak } from "../bearing.js";
import { fmtMeters, horizontalFromElevation } from "../signal.js";
import { settings } from "../store.js";

const BIN = 5;
/** Unterhalb dieser Elevation ist die Entfernung aus dem Winkel zu ungenau */
const FLAT_DEG = 15;

export default {
  id: "bearing",

  mount(el, app) {
    this.el = el;
    this.app = app;
    this.mode = "az";
    this.recAz = new SweepRecorder(BIN, 360);
    this.recEl = new SweepRecorder(BIN, 90);
    this.recording = false;
    this.estAz = null;
    this.estEl = null;
    this.simTimer = null;

    el.innerHTML = `
      <div id="pbNoTarget" class="card empty">Noch kein Gerät gewählt.<br><br>
        <button type="button" class="btn btn-primary" data-go="devices">Gerät auswählen</button></div>
      <div id="pbMain" hidden>
        <div class="card">
          <div class="row between">
            <div class="seg" id="pbMode">
              <button type="button" data-m="az" class="on">Richtung</button>
              <button type="button" data-m="el">Elevation</button>
            </div>
            <div class="row">
              <button id="pbStart" class="btn btn-sm btn-primary" type="button">Start</button>
              <button id="pbReset" class="btn btn-sm" type="button">Neu</button>
            </div>
          </div>
          <p class="hint" id="pbHint"></p>
          <div class="progress"><i id="pbProg"></i></div>
        </div>
        <div class="card">
          <canvas class="polar" id="pbPolar"></canvas>
          <canvas class="elev" id="pbElev" hidden></canvas>
          <div class="arrow-wrap">
            <div class="arrow" id="pbArrow">⬆</div>
            <div><div class="result" id="pbResult">–</div><div class="small" id="pbQuality"></div></div>
          </div>
        </div>
        <div class="card">
          <dl class="kv">
            <dt>Antennenrichtung</dt><dd id="pbCur">–</dd>
            <dt>Neigung</dt><dd id="pbTilt">–</dd>
            <dt>Pegel (Fenster)</dt><dd id="pbRssi">–</dd>
            <dt>Messwerte</dt><dd id="pbN">0</dd>
          </dl>
          <p class="hint" id="pbSensor"></p>
        </div>
        <div id="pbSim"></div>
      </div>`;

    el.addEventListener("click", (e) => {
      const go = e.target.closest("[data-go]");
      if (go) app.go(go.dataset.go);
    });
    $$("#pbMode button", el).forEach((b) => b.addEventListener("click", () => {
      this.mode = b.dataset.m;
      this.stop();
      this.renderAll();
    }));
    $("#pbStart", el).addEventListener("click", () => (this.recording ? this.stop() : this.start()));
    $("#pbReset", el).addEventListener("click", () => {
      this.stop();
      if (this.mode === "az") { this.recAz.reset(); this.estAz = null; }
      else { this.recEl.reset(); this.estEl = null; }
      this.renderAll();
    });

    app.tracker.on("update", (tr) => this.onSample(tr));
    app.orientation.on("change", () => { if (!el.hidden) this.renderLive(); });
    window.addEventListener("resize", () => { if (!el.hidden) this.draw(); });
  },

  show() {
    const t = this.app.state.target;
    $("#pbNoTarget", this.el).hidden = !!t;
    $("#pbMain", this.el).hidden = !t;
    if (!t) return;
    if (this.key !== t.key) {
      this.key = t.key;
      this.recAz.reset();
      this.recEl.reset();
      this.estAz = this.estEl = null;
    }
    this.app.mountSimPanel($("#pbSim", this.el));
    this.renderAll();
  },

  hide() {
    this.stop();
  },

  start() {
    const rec = this.mode === "az" ? this.recAz : this.recEl;
    if (rec.samples === 0 || rec.coverage() > 0.9) rec.reset();
    this.recording = true;
    const o = this.app.orientation;
    if (o.usingSim) this.startSimMotion();
    this.renderAll();
  },

  stop() {
    this.recording = false;
    clearInterval(this.simTimer);
    this.simTimer = null;
    if (this.el) this.renderAll();
  },

  /** Ohne Lagesensor (PC/Simulator): Drehung bzw. Schwenk automatisch simulieren */
  startSimMotion() {
    const o = this.app.orientation;
    clearInterval(this.simTimer);
    if (this.mode === "az") {
      let done = 0;
      o.setSim(o.sim.az, 0);
      this.simTimer = setInterval(() => {
        done += 3;
        o.setSim(o.sim.az + 3, 0);
        if (done >= 372) this.stop();
      }, 60);
    } else {
      const az = this.estAz ? this.estAz.angle : o.sim.az;
      let el = 0;
      o.setSim(az, 0);
      this.simTimer = setInterval(() => {
        el += 1.5;
        o.setSim(az, el);
        if (el >= 90) this.stop();
      }, 60);
    }
  },

  onSample(tr) {
    if (!this.recording || tr.raw === null || tr.stale) return;
    const pose = this.app.orientation.averaged(120);
    if (this.mode === "az") {
      if (pose.el < -35 || pose.el > 45) return; // Antenne grob waagerecht halten
      this.recAz.add(pose.az, tr.raw);
      this.estAz = estimatePeak(this.recAz.values(), { binDeg: BIN, circular: true, sigmaBins: 2 });
      if (this.recAz.coverage() >= 0.97 || this.recAz.turned >= 380) this.finish();
    } else {
      this.recEl.add(Math.max(0, pose.el), tr.raw);
      this.estEl = estimatePeak(this.recEl.values(), { binDeg: BIN, circular: false, sigmaBins: 1.5 });
      if (this.recEl.coverage() >= 0.9 && pose.el >= 85) this.finish();
    }
    if (!this.el.hidden) this.renderAll();
  },

  finish() {
    this.stop();
    if (this.mode === "el" && this.estEl) {
      const s = settings();
      const flat = this.estEl.angle < FLAT_DEG;
      const m = horizontalFromElevation(flat ? FLAT_DEG : this.estEl.angle, s.ceilingM, s.handM);
      if (m !== null) {
        this.app.state.elevDist = { key: this.app.state.target.key, m, el: this.estEl.angle, flat, t: Date.now() };
      }
    }
    toast(this.mode === "az" ? "Rundum-Peilung fertig" : "Elevation gemessen");
  },

  renderAll() {
    if (!this.el || this.el.hidden || !this.app.state.target) return;
    const az = this.mode === "az";
    $$("#pbMode button", this.el).forEach((b) => b.classList.toggle("on", b.dataset.m === this.mode));
    $("#pbPolar", this.el).hidden = !az;
    $("#pbElev", this.el).hidden = az;
    const startBtn = $("#pbStart", this.el);
    startBtn.textContent = this.recording ? "Stopp" : "Start";
    const rec = az ? this.recAz : this.recEl;
    $("#pbProg", this.el).style.width = Math.round(rec.coverage() * 100) + "%";
    $("#pbN", this.el).textContent = rec.samples;
    const sim = this.app.orientation.usingSim;
    $("#pbHint", this.el).textContent = az
      ? (this.recording
        ? (sim ? "Simulierte Drehung läuft …" : "Langsam einmal im Kreis drehen (ca. 10 s), Antenne waagerecht.")
        : "Start tippen und sich mit der Antenne langsam einmal um die eigene Achse drehen. Danach zeigt der Pfeil zum AP.")
      : (this.recording
        ? (sim ? "Simulierter Schwenk läuft …" : "Antenne langsam von waagerecht bis senkrecht zur Decke schwenken.")
        : "Erst Richtung peilen, dann in diese Richtung zeigen, Start tippen und die Antenne langsam zur Decke schwenken.");
    $("#pbSensor", this.el).textContent = sim
      ? "Kein Lagesensor erkannt – Bewegung wird simuliert (Simulator-Steuerung unten)."
      : "Lagesensor aktiv (gyro-gestützt).";
    this.renderLive();
    this.draw();
  },

  renderLive() {
    if (!this.el || this.el.hidden || !this.app.state.target) return;
    const o = this.app.orientation.current();
    const tr = this.app.tracker;
    $("#pbCur", this.el).textContent = fmtDeg(o.az);
    $("#pbTilt", this.el).textContent = fmtDeg(o.el);
    $("#pbRssi", this.el).textContent = tr.raw === null || tr.stale ? "–" : fmtDbm(tr.raw, 1) + " dBm";

    const arrow = $("#pbArrow", this.el);
    const res = $("#pbResult", this.el);
    const q = $("#pbQuality", this.el);
    if (this.mode === "az") {
      const est = this.estAz;
      if (!est || this.recAz.coverage() < 0.5) {
        arrow.style.transform = "rotate(0deg)";
        arrow.style.opacity = 0.25;
        res.textContent = this.recAz.samples ? "weiterdrehen …" : "–";
        q.textContent = "";
      } else {
        const d = angDiff(est.angle, o.az);
        arrow.style.transform = `rotate(${d}deg)`;
        arrow.style.opacity = 1;
        res.textContent = Math.abs(d) < 10 ? "geradeaus" : `${Math.round(Math.abs(d))}° ${d > 0 ? "rechts" : "links"}`;
        let txt = `Güte: ${est.quality} (${est.contrast.toFixed(0)} dB über Median)`;
        if (est.width) txt += ` · Keule ${Math.round(est.width)}°`;
        if (est.secondary) txt += ` · zweites Maximum bei ${Math.round(angDiff(est.secondary.angle, o.az))}° – Reflexion? Von anderer Stelle nochmals peilen.`;
        q.textContent = txt;
      }
    } else {
      const est = this.estEl;
      arrow.style.opacity = est ? 1 : 0.25;
      arrow.style.transform = est ? `rotate(${90 - est.angle}deg)` : "rotate(0deg)";
      if (est) {
        const s = settings();
        const m = horizontalFromElevation(est.angle, s.ceilingM, s.handM);
        res.textContent = `Elevation ${Math.round(est.angle)}°`;
        const de = (x) => String(x).replace(".", ",");
        if (est.angle < FLAT_DEG) {
          const minM = horizontalFromElevation(FLAT_DEG, s.ceilingM, s.handM);
          q.textContent = `Flacher Winkel → AP vermutlich weiter als ${fmtMeters(minM)} entfernt (Elevation hier unsicher). Näher herangehen und wiederholen.`;
        } else {
          const warn = est.secondary ? " · zwei Maxima – Reflexion? Wiederholen." : "";
          q.textContent = m !== null
            ? `→ horizontal ca. ${fmtMeters(m)} (Decke ${de(s.ceilingM)} m, Antenne ${de(s.handM)} m)${warn}`
            : warn.slice(3);
        }
      } else {
        res.textContent = this.recEl.samples ? "weiter schwenken …" : "–";
        q.textContent = "";
      }
    }
    this.drawLiveMarker();
  },

  draw() {
    if (this.mode === "az") this.drawPolar();
    else this.drawElev();
  },

  drawLiveMarker() {
    // Polardiagramm ist „Blickrichtung oben“ → bei Drehung neu zeichnen
    if (this.mode === "az") this.drawPolar();
  },

  drawPolar() {
    const c = $("#pbPolar", this.el);
    if (!c || c.offsetParent === null) return;
    const { ctx, w, h } = setupCanvas(c);
    const cx = w / 2, cy = h / 2;
    const R = Math.min(w, h) / 2 - 22;
    const cur = this.app.orientation.current().az;
    const line = cssVar("--line"), muted = cssVar("--muted"), acc = cssVar("--accent"), good = cssVar("--good");
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = line;
    ctx.lineWidth = 1;
    for (let k = 1; k <= 3; k++) { ctx.beginPath(); ctx.arc(cx, cy, (R * k) / 3, 0, 2 * Math.PI); ctx.stroke(); }
    for (let a = 0; a < 360; a += 45) {
      const r = ((a - 90) * Math.PI) / 180;
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + R * Math.cos(r), cy + R * Math.sin(r)); ctx.stroke();
    }
    ctx.fillStyle = muted;
    ctx.font = "12px system-ui";
    ctx.textAlign = "center";
    ctx.fillText("Antenne", cx, 13);

    const vals = this.recAz.values();
    const valid = vals.filter((v) => v !== null);
    if (!valid.length) {
      ctx.fillText("Noch keine Messwerte", cx, cy + 4);
      return;
    }
    const vmax = Math.max(...valid);
    const vmin = vmax - 30;
    const rad = (v) => R * Math.max(0.02, (v - vmin) / (vmax - vmin));
    const scr = (deg) => ((deg - cur - 90) * Math.PI) / 180; // Blickrichtung oben

    ctx.fillStyle = acc + "55";
    vals.forEach((v, i) => {
      if (v === null) return;
      ctx.beginPath();
      ctx.moveTo(cx, cy);
      ctx.arc(cx, cy, rad(v), scr(i * BIN), scr((i + 1) * BIN));
      ctx.closePath();
      ctx.fill();
    });
    const est = this.estAz;
    if (est?.smoothed) {
      ctx.strokeStyle = acc;
      ctx.lineWidth = 2;
      ctx.beginPath();
      let first = true;
      est.smoothed.forEach((v, i) => {
        if (v === null) { first = true; return; }
        const a = scr((i + 0.5) * BIN);
        const x = cx + rad(v) * Math.cos(a), y = cy + rad(v) * Math.sin(a);
        if (first) ctx.moveTo(x, y); else ctx.lineTo(x, y);
        first = false;
      });
      ctx.stroke();
    }
    if (est && this.recAz.coverage() >= 0.5) {
      const a = scr(est.angle);
      ctx.strokeStyle = good;
      ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(cx, cy); ctx.lineTo(cx + R * Math.cos(a), cy + R * Math.sin(a)); ctx.stroke();
    }
    ctx.fillStyle = muted;
    ctx.fillText(`Ringe: je 10 dB · max ${Math.round(vmax)} dBm`, cx, h - 4);
    // Markierung Blickrichtung
    ctx.fillStyle = acc;
    ctx.beginPath(); ctx.moveTo(cx, 18); ctx.lineTo(cx - 7, 30); ctx.lineTo(cx + 7, 30); ctx.closePath(); ctx.fill();
  },

  drawElev() {
    const c = $("#pbElev", this.el);
    if (!c || c.offsetParent === null) return;
    const { ctx, w, h } = setupCanvas(c);
    const ox = 24, oy = h - 18;
    const R = Math.min(w - 40, h - 30);
    const line = cssVar("--line"), muted = cssVar("--muted"), acc = cssVar("--accent"), good = cssVar("--good");
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = line;
    for (let k = 1; k <= 3; k++) { ctx.beginPath(); ctx.arc(ox, oy, (R * k) / 3, -Math.PI / 2, 0); ctx.stroke(); }
    for (let a = 0; a <= 90; a += 30) {
      const r = (-a * Math.PI) / 180;
      ctx.beginPath(); ctx.moveTo(ox, oy); ctx.lineTo(ox + R * Math.cos(r), oy + R * Math.sin(r)); ctx.stroke();
    }
    ctx.fillStyle = muted;
    ctx.font = "12px system-ui";
    ctx.fillText("waagerecht", ox + R - 64, oy + 14);
    ctx.fillText("Decke", ox + 4, oy - R + 10);
    const vals = this.recEl.values();
    const valid = vals.filter((v) => v !== null);
    if (!valid.length) return;
    const vmax = Math.max(...valid), vmin = vmax - 25;
    const rad = (v) => R * Math.max(0.02, (v - vmin) / (vmax - vmin));
    ctx.fillStyle = acc + "55";
    vals.forEach((v, i) => {
      if (v === null) return;
      ctx.beginPath();
      ctx.moveTo(ox, oy);
      ctx.arc(ox, oy, rad(v), (-(i + 1) * BIN * Math.PI) / 180, (-i * BIN * Math.PI) / 180);
      ctx.closePath();
      ctx.fill();
    });
    const est = this.estEl;
    if (est) {
      const a = (-est.angle * Math.PI) / 180;
      ctx.strokeStyle = good;
      ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(ox, oy); ctx.lineTo(ox + R * Math.cos(a), oy + R * Math.sin(a)); ctx.stroke();
    }
    const el = this.app.orientation.current().el;
    const a = (-Math.max(0, Math.min(90, el)) * Math.PI) / 180;
    ctx.strokeStyle = acc;
    ctx.lineWidth = 2;
    ctx.setLineDash([5, 4]);
    ctx.beginPath(); ctx.moveTo(ox, oy); ctx.lineTo(ox + R * Math.cos(a), oy + R * Math.sin(a)); ctx.stroke();
    ctx.setLineDash([]);
  },
};
