// Ansicht „Suchen“: Pegel groß, Ton, Verlauf, Entfernung, Messpunkte, FTM.
import { $, cssVar, esc, fmtDbm, setupCanvas, toast } from "../util.js";
import { OVERLOAD_DBM, bandLabel, estimateDistance, fmtMeters, fmtRange, level01, rssiColor } from "../signal.js";
import { antennaGain, set, settings } from "../store.js";
import { findPartners } from "../mac.js";

const FTM_STATUS = {
  1: "Der AP unterstützt kein FTM.",
  2: "Der AP hat die FTM-Anfrage abgelehnt.",
  3: "Keine Antwort vom AP.",
  4: "FTM fehlgeschlagen.",
  5: "Keine gültigen Messungen.",
  6: "Abgebrochen.",
};

export default {
  id: "search",

  mount(el, app) {
    this.el = el;
    this.app = app;
    el.innerHTML = `
      <div id="srNoTarget" class="card empty">Noch kein Gerät gewählt.<br><br>
        <button type="button" class="btn btn-primary" data-go="devices">Gerät auswählen</button></div>
      <div id="srMain" hidden>
        <div class="card">
          <div class="big" id="srVal">–<small>dBm</small></div>
          <div class="trend" id="srTrend"></div>
          <div class="bar"><i id="srBar"></i></div>
          <div class="row between" style="margin-top:10px">
            <div class="small">Spitze <b id="srPeak">–</b> · Abstand <b id="srDelta">–</b></div>
            <div class="small"><b id="srRate">0</b> Werte/s</div>
          </div>
          <div id="srWarn"></div>
          <div class="row gap">
            <button id="srSound" class="btn grow" type="button">🔇 Ton aus</button>
            <button id="srFine" class="btn grow" type="button">Feinmodus</button>
            <button id="srPeakReset" class="btn grow" type="button">Spitze ↺</button>
          </div>
        </div>

        <div class="card">
          <h3>Verlauf (30 s)</h3>
          <canvas class="spark" id="srSpark"></canvas>
        </div>

        <div class="card">
          <h3>Entfernung</h3>
          <dl class="kv">
            <dt>aus Pegel</dt><dd id="srDistR">–</dd>
            <dt>aus Elevation</dt><dd id="srDistE">–</dd>
            <dt>Laufzeit (FTM)</dt><dd id="srDistF">–</dd>
          </dl>
          <p class="hint" id="srDistRHint"></p>
          <div class="row gap"><button id="srFtm" class="btn grow" type="button">Laufzeit messen (FTM)</button></div>
          <p class="hint" id="srFtmHint"></p>
        </div>

        <div class="card">
          <h3>Messpunkte (z. B. Deckenplatten)</h3>
          <div class="row">
            <input id="srMarkLabel" class="grow" type="text" placeholder="Bezeichnung, z. B. Platte B4" />
            <button id="srMark" class="btn btn-primary" type="button">Merken</button>
          </div>
          <ul class="marks" id="srMarks"></ul>
          <button id="srMarksClear" class="btn btn-ghost btn-sm" type="button">Liste leeren</button>
        </div>

        <div class="card">
          <h3>Messkopf</h3>
          <label class="check"><input type="checkbox" id="srProbe" /> Aktiv anfragen (Probe Requests, mehr Messwerte)</label>
          <div id="srPartner" class="stack" style="margin-top:8px"></div>
          <div class="small" id="srInfo" style="margin-top:8px"></div>
        </div>
        <div id="srSim"></div>
      </div>`;

    el.addEventListener("click", (e) => {
      const go = e.target.closest("[data-go]");
      if (go) app.go(go.dataset.go);
      const partner = e.target.closest("[data-partner]");
      if (partner) {
        const g = app.state.groups.find((x) => x.key === partner.dataset.partner);
        if (g) app.selectTarget(g, { stay: true });
      }
    });
    $("#srSound", el).addEventListener("click", async () => {
      set("sound", !settings().sound);
      await this.applySound();
    });
    $("#srFine", el).addEventListener("click", () => {
      set("fineTone", !settings().fineTone);
      this.renderButtons();
    });
    $("#srPeakReset", el).addEventListener("click", () => app.tracker.resetPeak());
    $("#srMark", el).addEventListener("click", () => {
      const input = $("#srMarkLabel", el);
      const m = app.tracker.addMark(input.value.trim());
      if (!m) return toast("Kein aktueller Messwert.");
      input.value = "";
      toast(`${m.label}: ${fmtDbm(m.value, 1)} dBm gemerkt`);
    });
    $("#srMarksClear", el).addEventListener("click", () => app.tracker.clearMarks());
    $("#srProbe", el).addEventListener("change", async (e) => {
      const s = settings();
      if (e.target.checked && !s.probeHz) set("probeHz", 10);
      app.state.probeOn = e.target.checked;
      await app.retrack();
    });
    $("#srFtm", el).addEventListener("click", () => this.startFtm());

    app.tracker.on("update", () => { if (!el.hidden) this.renderLive(); });
    app.tracker.on("marks", () => this.renderMarks());
    app.device.on("ftm", (m) => this.onFtm(m));
    window.addEventListener("resize", () => { if (!el.hidden) this.drawSpark(); });
  },

  async show() {
    const t = this.app.state.target;
    $("#srNoTarget", this.el).hidden = !!t;
    $("#srMain", this.el).hidden = !t;
    if (!t) return;
    $("#srProbe", this.el).checked = this.app.state.probeOn;
    this.renderButtons();
    this.renderPartner();
    this.renderMarks();
    this.renderFtm();
    this.renderLive();
    this.app.mountSimPanel($("#srSim", this.el));
    await this.applySound();
  },

  hide() {
    this.app.audio.disable();
  },

  async applySound() {
    const a = this.app.audio;
    if (settings().sound && !this.el.hidden) {
      const ok = await a.enable();
      if (!ok) toast("Ton nicht verfügbar.");
    } else {
      a.disable();
    }
    this.renderButtons();
  },

  renderButtons() {
    const s = settings();
    const snd = $("#srSound", this.el);
    snd.textContent = s.sound ? "🔊 Ton an" : "🔇 Ton aus";
    snd.classList.toggle("on", s.sound);
    $("#srFine", this.el).classList.toggle("on", s.fineTone);
  },

  renderPartner() {
    const t = this.app.state.target;
    const box = $("#srPartner", this.el);
    const groups = this.app.state.groups || [];
    const self = groups.find((g) => g.key === t.key) || t.group;
    const partners = self ? findPartners(self, groups) : [];
    box.innerHTML = partners.slice(0, 2).map((p) =>
      `<button type="button" class="btn" data-partner="${esc(p.key)}">Zum ${bandLabel(p.ch)}-Funkmodul wechseln (Kanal ${p.ch}, ${p.rssi} dBm)</button>`
    ).join("");
  },

  renderLive() {
    const { app, el } = this;
    const t = app.state.target;
    if (!t) return;
    const tr = app.tracker;
    const s = settings();
    const v = tr.stale ? null : tr.value;

    const valEl = $("#srVal", el);
    valEl.innerHTML = `${v === null ? "–" : fmtDbm(v)}<small>dBm</small>`;
    valEl.style.color = rssiColor(v);
    $("#srBar", el).style.width = Math.round(level01(v, -95, -25) * 100) + "%";
    $("#srBar", el).style.background = rssiColor(v);
    $("#srPeak", el).textContent = tr.peak === null ? "–" : fmtDbm(tr.peak) + " dBm";
    $("#srDelta", el).textContent = v === null || tr.peak === null ? "–" : fmtDbm(v - tr.peak, 1) + " dB";
    $("#srRate", el).textContent = Math.round(tr.rate);

    // Trend: aktueller Wert gegen vor ~1,5 s
    const h = tr.history;
    let trend = "";
    if (v !== null && h.length > 16) {
      const old = h[h.length - 16].v;
      if (old !== null) {
        const d = v - old;
        trend = d > 1.5 ? '<span style="color:var(--good)">▲ wärmer</span>'
          : d < -1.5 ? '<span style="color:var(--bad)">▼ kälter</span>'
          : '<span class="small">■ gleich</span>';
      }
    }
    if (tr.stale && app.device.connected) trend = '<span class="small">kein Signal vom AP</span>';
    $("#srTrend", el).innerHTML = trend;

    // Ton
    let lvl = 0;
    if (v !== null) {
      lvl = s.fineTone && tr.peak !== null ? level01(v, tr.peak - 15, tr.peak) : level01(v, -90, -30);
      if (lvl < 0.02) lvl = 0.02;
    }
    app.audio.setLevel(lvl);

    // Übersteuerung (Pegel am Empfänger, also vor Rückrechnung des Dämpfungsglieds)
    const warn = [];
    if (tr.rawMax !== null && tr.rawMax >= OVERLOAD_DBM && !tr.stale) {
      warn.push("Sehr starkes Signal – der Empfänger kann übersteuern. Dämpfungsglied (20 dB) einsetzen und unter Einstellungen eintragen, oder auf den Rundstrahler wechseln.");
    }
    if (tr.probeErr > 0) warn.push("Probe Requests werden auf diesem Kanal nicht gesendet (z. B. DFS-Kanal) – nur passive Messung.");
    $("#srWarn", el).innerHTML = warn.map((w) => `<div class="warnbox">${esc(w)}</div>`).join("");

    // Entfernung: Mit Richtantenne gilt der Antennengewinn nur in Hauptrichtung –
    // deshalb das Maximum der letzten 15 s verwenden (beim Schwenken ≈ auf den AP gerichtet).
    const dir = s.antenna === "dir";
    const basis = v === null ? null : dir ? Math.max(v, tr.recentPeak(15000) ?? v) : v;
    const est = estimateDistance({
      rssi: basis, ch: t.ch, txp: t.txp, antGain: antennaGain(t.ch), attenDb: s.attenDb, nLo: s.nLo, nHi: s.nHi,
    });
    $("#srDistR", el).textContent = est ? `ca. ${fmtMeters(est.typ)} (${fmtRange(est)})` : "–";
    $("#srDistRHint", el).textContent = dir
      ? "Pegel-Entfernung aus dem Maximum der letzten 15 s – dafür die Antenne kurz in Richtung AP schwenken."
      : "";
    const ed = app.state.elevDist;
    $("#srDistE", el).textContent = ed && ed.key === t.key
      ? (ed.flat ? `> ${fmtMeters(ed.m)} (flacher Winkel)` : `${fmtMeters(ed.m)} horizontal (${Math.round(ed.el)}°)`)
      : "– (Peilen → Elevation)";

    const info = [`${bandLabel(t.ch)} · Kanal ${t.ch}`, `${t.bssids.length} BSSID${t.bssids.length > 1 ? "s" : ""}`];
    if (t.txp !== null) info.push(`Sendeleistung laut AP ${t.txp} dBm`);
    if (s.attenDb) info.push(`Dämpfungsglied ${s.attenDb} dB`);
    if (tr.probeTx) info.push(`${tr.probeTx * 10} Probes/s`);
    $("#srInfo", el).textContent = info.join(" · ");
    this.drawSpark();
  },

  drawSpark() {
    const c = $("#srSpark", this.el);
    if (!c || c.offsetParent === null) return;
    const { ctx, w, h } = setupCanvas(c);
    ctx.clearRect(0, 0, w, h);
    const hist = this.app.tracker.history;
    const vals = hist.map((x) => x.v).filter((x) => x !== null);
    if (vals.length < 2) return;
    let hi = Math.max(...vals) + 2;
    let lo = Math.min(...vals) - 2;
    if (hi - lo < 12) { const m = (hi + lo) / 2; hi = m + 6; lo = m - 6; }
    const t0 = Date.now() - 30000;
    const X = (t) => ((t - t0) / 30000) * w;
    const Y = (v) => h - ((v - lo) / (hi - lo)) * (h - 8) - 4;
    ctx.fillStyle = cssVar("--muted");
    ctx.font = "11px system-ui";
    ctx.fillText(`${Math.round(hi)} dBm`, 4, 12);
    ctx.fillText(`${Math.round(lo)}`, 4, h - 4);
    ctx.strokeStyle = cssVar("--line");
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (const p of hist) if (p.raw !== null) { ctx.moveTo(X(p.t), Y(p.raw) - 1); ctx.lineTo(X(p.t), Y(p.raw) + 1); }
    ctx.stroke();
    ctx.strokeStyle = cssVar("--accent");
    ctx.lineWidth = 2;
    ctx.beginPath();
    let pen = false;
    for (const p of hist) {
      if (p.v === null) { pen = false; continue; }
      if (!pen) ctx.moveTo(X(p.t), Y(p.v)); else ctx.lineTo(X(p.t), Y(p.v));
      pen = true;
    }
    ctx.stroke();
  },

  renderMarks() {
    const marks = [...this.app.tracker.marks].sort((a, b) => b.value - a.value);
    const best = marks[0];
    $("#srMarks", this.el).innerHTML = marks.length
      ? marks.map((m) => `<li class="${m === best ? "best" : ""}"><span>${m === best ? "★ " : ""}${esc(m.label)}</span><span>${fmtDbm(m.value, 1)} dBm${m === best ? "" : ` (${fmtDbm(m.value - best.value, 1)} dB)`}</span></li>`).join("")
      : '<li class="small">Unter jeder verdächtigen Deckenplatte kurz ruhig halten und „Merken“ tippen – der stärkste Punkt wird markiert.</li>';
  },

  startFtm() {
    const { app } = this;
    const t = app.state.target;
    if (!t || !app.device.connected) return;
    app.state.ftm = { pending: true, key: t.key, t: Date.now() };
    app.device.ftm(t.bssids[0], t.ch);
    this.renderFtm();
  },

  onFtm(m) {
    const st = this.app.state;
    const t = st.target;
    if (!t || !t.bssids.includes(m.b)) return;
    st.ftm = { pending: false, key: t.key, t: Date.now(), ok: m.ok, m: m.ok ? m.dist_cm / 100 : null,
      msg: m.ok ? "" : FTM_STATUS[m.status] || (m.err === "timeout" ? "Zeitüberschreitung – keine Antwort." : "FTM nicht möglich.") };
    this.renderFtm();
    if (!this.el.hidden) toast(m.ok ? `FTM: ${fmtMeters(m.dist_cm / 100)}` : st.ftm.msg);
  },

  renderFtm() {
    const t = this.app.state.target;
    const f = this.app.state.ftm;
    const btn = $("#srFtm", this.el);
    btn.disabled = !!(f && f.pending);
    btn.textContent = f && f.pending ? "Messe …" : "Laufzeit messen (FTM)";
    $("#srDistF", this.el).textContent = f && f.key === t.key && !f.pending ? (f.ok ? fmtMeters(f.m) : "nicht möglich") : "–";
    $("#srFtmHint", this.el).textContent = f && f.key === t.key && !f.pending && !f.ok
      ? f.msg
      : t.ftm ? "Der AP kündigt FTM an – Laufzeitmessung ±1–2 m." : "Der AP kündigt kein FTM an. Ein Versuch schadet nicht, klappt aber meist nicht.";
  },
};
