// Ansicht „Einstellungen“
import { $, esc, toast } from "../util.js";
import { DEFAULTS, save, settings } from "../store.js";
import { checkUpdate, version } from "../update.js";

const NUM_FIELDS = [
  ["attenDb", "Dämpfungsglied (dB) – Wert des eingeschraubten Dämpfungsglieds, 0 = keins", 0, 60, 1],
  ["ceilingM", "Deckenhöhe (m) – Unterkante abgehängte Decke", 1.5, 12, 0.1],
  ["handM", "Antennenhöhe beim Messen (m)", 0.2, 3, 0.1],
  ["gain24", "Gewinn Richtantenne 2,4 GHz (dBi)", 0, 30, 0.5],
  ["gain5", "Gewinn Richtantenne 5 GHz (dBi)", 0, 30, 0.5],
  ["gainOmni", "Gewinn Rundstrahler (dBi)", -5, 10, 0.5],
  ["nLo", "Pfadverlust-Exponent min (Freiraum ≈ 2,0)", 1.5, 5, 0.1],
  ["nHi", "Pfadverlust-Exponent max (verbaut ≈ 3,0–3,5)", 1.5, 6, 0.1],
  ["probeHz", "Probe Requests pro Sekunde (0 = nur passiv)", 0, 50, 1],
];

export default {
  id: "settings",

  mount(el, app) {
    this.el = el;
    this.app = app;
    const s = settings();
    el.innerHTML = `
      <div class="card">
        <h3>Antenne</h3>
        <label class="field"><span>Angeschlossene Antenne</span>
          <select id="stAntenna">
            <option value="dir">Richtantenne (Panel/Yagi)</option>
            <option value="omni">Rundstrahler</option>
          </select></label>
        <label class="field"><span>Hauptkeule der Richtantenne zeigt aus der …</span>
          <select id="stAxis">
            <option value="back">Rückseite des Handys (Kamera-Richtung)</option>
            <option value="top">Oberkante des Handys</option>
          </select></label>
      </div>
      <div class="card">
        <h3>Messung</h3>
        ${NUM_FIELDS.map(([k, label, min, max, step]) => `
          <label class="field"><span>${esc(label)}</span>
            <input type="number" id="st_${k}" min="${min}" max="${max}" step="${step}" inputmode="decimal" /></label>`).join("")}
        <label class="check"><input type="checkbox" id="stData" /> Datenframes des APs mitzählen (nur Pegel, kein Inhalt)</label>
        <p class="hint">Standard: nur Beacons und Probe Responses. Datenframes können mit Beamforming gesendet werden und schwanken stärker.</p>
      </div>
      <div class="card">
        <h3>Verbindung</h3>
        <div class="small" id="stDev"></div>
        <div class="row gap">
          <button id="stConnect" class="btn grow" type="button">Verbinden …</button>
          <button id="stDisconnect" class="btn grow" type="button">Trennen</button>
        </div>
      </div>
      <div class="card">
        <h3>App</h3>
        <div class="small">Version <b id="stVer">…</b></div>
        <div class="row gap">
          <button id="stUpdate" class="btn grow" type="button">Nach Update suchen</button>
          <button id="stReset" class="btn btn-ghost grow" type="button">Standardwerte</button>
        </div>
      </div>`;

    this.fill();

    $("#stAntenna", el).addEventListener("change", (e) => { s.antenna = e.target.value; save(); });
    $("#stAxis", el).addEventListener("change", (e) => { s.axis = e.target.value; save(); });
    $("#stData", el).addEventListener("change", async (e) => {
      s.countData = e.target.checked;
      save();
      await app.device.setCountData(s.countData);
    });
    for (const [k, , min, max] of NUM_FIELDS) {
      $("#st_" + k, el).addEventListener("change", async (e) => {
        const v = parseFloat(String(e.target.value).replace(",", "."));
        if (!Number.isFinite(v) || v < min || v > max) {
          toast(`Bitte einen Wert zwischen ${min} und ${max} eingeben.`);
          e.target.value = s[k];
          return;
        }
        s[k] = v;
        save();
        if (k === "probeHz") {
          app.state.probeOn = v > 0;
          await app.retrack();
        }
      });
    }
    $("#stConnect", el).addEventListener("click", () => app.openConnect());
    $("#stDisconnect", el).addEventListener("click", () => app.device.disconnect());
    $("#stUpdate", el).addEventListener("click", () => checkUpdate(true));
    $("#stReset", el).addEventListener("click", async () => {
      const keep = s.recentMacs;
      Object.assign(s, DEFAULTS, { recentMacs: keep });
      save();
      this.fill();
      app.state.probeOn = s.probeHz > 0;
      await app.device.setCountData(s.countData);
      await app.retrack();
      toast("Standardwerte gesetzt.");
    });
    app.device.on("state", () => this.renderDev());
    app.device.on("hello", () => this.renderDev());
    version().then((v) => { $("#stVer", el).textContent = v; });
    this.renderDev();
  },

  fill() {
    const s = settings();
    const el = this.el;
    $("#stAntenna", el).value = s.antenna;
    $("#stAxis", el).value = s.axis;
    $("#stData", el).checked = s.countData;
    for (const [k] of NUM_FIELDS) $("#st_" + k, el).value = s[k];
  },

  show() {
    this.fill();
    this.renderDev();
  },

  hide() {},

  renderDev() {
    const d = this.app.device;
    const i = d.info;
    $("#stDev", this.el).textContent = d.connected
      ? `Verbunden über ${d.transport?.label || "?"}${i ? ` · Firmware ${i.fw} · ${i.chip} · MAC ${i.mac}` : ""}`
      : "Nicht verbunden.";
  },
};
