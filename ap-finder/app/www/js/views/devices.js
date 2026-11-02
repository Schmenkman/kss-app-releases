// Ansicht „Geräte“: Scan beider Bänder, physische APs mit ihren Funkmodulen,
// Suche nach SSID/Name/MAC, Filter auf die BayernWLAN-SSID.
import { $, $$, esc, throttle } from "../util.js";
import { deviceScore, groupDevices, groupRadios, looksLikeMac, macContains, normalizeMac, preferredRadio } from "../mac.js";
import { bandLabel, bandOf, level01, rssiColor } from "../signal.js";
import { rememberMac, set, settings } from "../store.js";

export default {
  id: "devices",
  sweep: null,

  mount(el, app) {
    this.el = el;
    this.app = app;
    el.innerHTML = `
      <div class="card">
        <input id="devQuery" type="text" inputmode="search" autocomplete="off" spellcheck="false"
               placeholder="MAC vom Typenschild, AP-Name oder BSSID" />
        <div class="row between" style="margin-top:10px">
          <div class="seg" id="devBands">
            <button type="button" data-b="25">Alle</button>
            <button type="button" data-b="2">2,4 GHz</button>
            <button type="button" data-b="5">5 GHz</button>
          </div>
          <button id="devScan" class="btn btn-sm btn-primary" type="button">Scan starten</button>
        </div>
        <label class="check small" style="margin-top:6px"><input type="checkbox" id="devSsidOnly" />
          <span>nur SSID <b id="devSsidName"></b></span></label>
        <div class="small" id="devInfo" style="margin-top:4px"></div>
        <div class="row" id="devRecent" style="margin-top:6px"></div>
      </div>
      <div id="devList"></div>`;

    const q = $("#devQuery", el);
    q.addEventListener("input", () => this.render());
    q.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        const mac = normalizeMac(q.value);
        if (mac) {
          rememberMac(mac);
          this.renderRecent();
        }
        q.blur();
      }
    });
    $$("#devBands button", el).forEach((b) =>
      b.addEventListener("click", async () => {
        set("bands", b.dataset.b);
        this.renderBands();
        if (app.device.mode === "scan") await app.device.startScan(settings().bands);
        this.render();
      })
    );
    $("#devSsidOnly", el).addEventListener("change", (e) => {
      // Haken aus = alle SSIDs; Haken an = zuletzt benutzte SSID (Standard @BayernWLAN)
      const s = settings();
      if (e.target.checked) set("ssidFilter", s.lastSsidFilter || "@BayernWLAN");
      else {
        if (s.ssidFilter) set("lastSsidFilter", s.ssidFilter);
        set("ssidFilter", "");
      }
      this.render();
    });
    $("#devScan", el).addEventListener("click", async () => {
      if (!app.device.connected) return app.openConnect();
      if (app.device.mode === "scan") await app.device.stop();
      else await app.device.startScan(settings().bands);
      this.render();
    });
    el.addEventListener("click", (e) => {
      const radio = e.target.closest("[data-key]");
      if (radio) {
        const g = this.radios.find((x) => x.key === radio.dataset.key);
        if (g) app.selectTarget(g);
        return;
      }
      const dev = e.target.closest("[data-dev]");
      if (dev) {
        const d = this.devices.find((x) => x.key === dev.dataset.dev);
        if (d) app.selectTarget(preferredRadio(d));
        return;
      }
      const act = e.target.closest("[data-act]");
      if (act?.dataset.act === "connect") app.openConnect();
      if (act?.dataset.act === "sim") app.connect("sim");
      const mac = e.target.closest("[data-mac]");
      if (mac) {
        q.value = mac.dataset.mac;
        this.render();
      }
    });

    this.devices = [];
    this.radios = [];
    const rerender = throttle(() => { if (!el.hidden) this.render(); }, 700);
    app.device.on("ap", rerender);
    app.device.on("state", () => this.render());
    app.device.on("sweep", (m) => {
      this.sweep = m;
      rerender();
    });
    this.renderBands();
    this.renderRecent();
  },

  async show() {
    const d = this.app.device;
    if (d.connected && d.mode !== "scan") await d.startScan(settings().bands);
    this.render();
  },

  hide() {},

  renderBands() {
    $$("#devBands button", this.el).forEach((b) => b.classList.toggle("on", b.dataset.b === settings().bands));
  },

  renderRecent() {
    const r = settings().recentMacs || [];
    $("#devRecent", this.el).innerHTML = r.length
      ? '<span class="small">Zuletzt:</span> ' + r.map((m) => `<button type="button" class="chip" data-mac="${esc(m)}">${esc(m)}</button>`).join(" ")
      : "";
  },

  render() {
    const { app, el } = this;
    const d = app.device;
    const s = settings();
    const scanBtn = $("#devScan", el);
    scanBtn.textContent = d.mode === "scan" ? "Scan stoppen" : "Scan starten";
    scanBtn.classList.toggle("btn-primary", d.mode !== "scan");
    $("#devSsidOnly", el).checked = !!s.ssidFilter;
    $("#devSsidName", el).textContent = s.ssidFilter || s.lastSsidFilter || "@BayernWLAN";

    const list = $("#devList", el);
    if (!d.connected) {
      $("#devInfo", el).textContent = "";
      list.innerHTML = `<div class="card empty">Kein Messkopf verbunden.<br>ESP32-C5 per USB-C anstecken.
        <div class="row gap" style="justify-content:center">
          <button type="button" class="btn btn-primary" data-act="connect">Verbinden</button>
          <button type="button" class="btn" data-act="sim">Simulator</button>
        </div></div>`;
      return;
    }

    const all = d.apList();
    const aps = s.ssidFilter ? all.filter((a) => a.ssid === s.ssidFilter) : all;
    const radiosAll = groupRadios(aps);
    app.state.groups = radiosAll;
    const radios = radiosAll.filter((g) => s.bands.includes(String(bandOf(g.ch))));
    let devices = groupDevices(radios);

    const q = $("#devQuery", el).value.trim();
    let macMode = false;
    if (q && looksLikeMac(q)) {
      macMode = true;
      const full = normalizeMac(q);
      if (full) {
        devices = devices
          .map((dv) => ({ ...dv, score: deviceScore(dv, full) }))
          .filter((dv) => dv.score > 0)
          .sort((a, b) => b.score - a.score || b.rssi - a.rssi);
      } else {
        devices = devices.filter((dv) => macContains(dv.base, q) || dv.bssids.some((b) => macContains(b, q)));
      }
    } else if (q) {
      const ql = q.toLowerCase();
      devices = devices.filter((dv) =>
        (dv.name + " " + dv.base + " " + dv.bssids.join(" ") + " " + dv.radios.flatMap((r) => r.ssids).join(" ")).toLowerCase().includes(ql));
    }
    this.devices = devices;
    this.radios = radiosAll;

    const sw = this.sweep ? ` · Durchlauf ${this.sweep.no} (${(this.sweep.ms / 1000).toFixed(1).replace(".", ",")} s)` : "";
    const hiddenOther = s.ssidFilter ? all.length - aps.length : 0;
    $("#devInfo", el).textContent = `${groupDevices(radiosAll).length} Geräte · ${radiosAll.length} Funkmodule${sw}` +
      (hiddenOther ? ` · ${hiddenOther} fremde BSSIDs ausgeblendet` : "") +
      (macMode && normalizeMac(q) ? " · sortiert nach MAC-Ähnlichkeit" : "");

    if (!devices.length) {
      list.innerHTML = q
        ? `<div class="card empty">Keine Treffer für „${esc(q)}“.<br><span class="small">Tipp: Bei Ruckus endet die Typenschild-MAC auf 0 und die Funk-MACs liegen im selben 16er-Block (…x0 bis …xF). Ist der AP gerade nicht in Reichweite oder auf einem anderen Band, erscheint er nicht.</span></div>`
        : `<div class="card empty">${d.mode === "scan" ? "Suche läuft … ein Durchlauf über alle Kanäle dauert ca. 4 s." : "Scan starten, um APs zu finden."}${s.ssidFilter ? `<br><span class="small">Es werden nur APs mit der SSID „${esc(s.ssidFilter)}“ angezeigt.</span>` : ""}</div>`;
      return;
    }

    list.innerHTML = devices.map((dv) => {
      const color = rssiColor(dv.rssi);
      const title = dv.name || `AP ${dv.base}`;
      const ssids = [...new Set(dv.radios.flatMap((r) => r.ssids))];
      const rows = dv.radios.map((r) => {
        const c = rssiColor(r.rssi);
        return `<button type="button" class="radio-row" data-key="${esc(r.key)}">
          <span class="grow">${bandLabel(r.ch)} · Kanal ${r.ch}${r.ftm ? ' <span class="chip ok">FTM</span>' : ""}<br>
            <span class="mono small">${esc(r.bssids[0])}${r.bssids.length > 1 ? ` +${r.bssids.length - 1}` : ""}</span></span>
          <span class="rs" style="color:${c}">${r.rssi}</span>
          <span class="chip acc">Orten ›</span>
        </button>`;
      }).join("");
      return `<div class="card ap-dev" data-dev="${esc(dv.key)}">
        <div class="row between">
          <div class="t1 grow">${esc(title)}</div>
          ${dv.score ? `<span class="score">Treffer ${dv.score} %</span>` : ""}
        </div>
        <div class="t2">Typenschild-MAC (vermutet): <span class="mono">${esc(dv.base)}</span></div>
        <div class="t3">${esc(ssids.join(" · "))}${dv.radios.some((r) => r.txp !== null) ? ` · Sendeleistung ${Math.max(...dv.radios.map((r) => r.txp ?? -99))} dBm` : ""}</div>
        <div class="bar"><i style="width:${Math.round(level01(dv.rssi, -95, -30) * 100)}%;background:${color}"></i></div>
        <div class="radios">${rows}</div>
      </div>`;
    }).join("");
  },
};
