// Ansicht „Geräte“: Scan beider Bänder, Funkmodule, Suche nach SSID/Name/MAC.
import { $, $$, esc, throttle } from "../util.js";
import { groupRadios, groupScore, looksLikeMac, macContains, normalizeMac } from "../mac.js";
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
               placeholder="SSID, AP-Name oder MAC vom Typenschild" />
        <div class="row between" style="margin-top:10px">
          <div class="seg" id="devBands">
            <button type="button" data-b="25">Alle</button>
            <button type="button" data-b="2">2,4 GHz</button>
            <button type="button" data-b="5">5 GHz</button>
          </div>
          <button id="devScan" class="btn btn-sm btn-primary" type="button">Scan starten</button>
        </div>
        <div class="small" id="devInfo" style="margin-top:8px"></div>
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
    $("#devScan", el).addEventListener("click", async () => {
      if (!app.device.connected) return app.openConnect();
      if (app.device.mode === "scan") await app.device.stop();
      else await app.device.startScan(settings().bands);
      this.render();
    });
    el.addEventListener("click", (e) => {
      const card = e.target.closest("[data-key]");
      if (card) {
        const g = this.groups.find((x) => x.key === card.dataset.key);
        if (g) app.selectTarget(g);
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

    this.groups = [];
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
    const scanBtn = $("#devScan", el);
    scanBtn.textContent = d.mode === "scan" ? "Scan stoppen" : "Scan starten";
    scanBtn.classList.toggle("btn-primary", d.mode !== "scan");

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

    const aps = d.apList();
    const all = groupRadios(aps);
    app.state.groups = all;
    const bands = settings().bands;
    let groups = all.filter((g) => bands.includes(String(bandOf(g.ch))));

    const q = $("#devQuery", el).value.trim();
    let macMode = false;
    if (q && looksLikeMac(q)) {
      macMode = true;
      const full = normalizeMac(q);
      if (full) {
        groups = groups
          .map((g) => ({ ...g, score: groupScore(g, full) }))
          .filter((g) => g.score > 0)
          .sort((a, b) => b.score - a.score || b.rssi - a.rssi);
      } else {
        groups = groups.filter((g) => g.bssids.some((b) => macContains(b, q)));
      }
    } else if (q) {
      const ql = q.toLowerCase();
      groups = groups.filter((g) => (g.name + " " + g.ssids.join(" ") + " " + g.bssids.join(" ")).toLowerCase().includes(ql));
    }
    this.groups = groups;

    const sw = this.sweep ? ` · Durchlauf ${this.sweep.no} (${(this.sweep.ms / 1000).toFixed(1).replace(".", ",")} s)` : "";
    $("#devInfo", el).textContent = `${aps.length} BSSIDs · ${all.length} Funkmodule${sw}` +
      (macMode && normalizeMac(q) ? " · sortiert nach MAC-Ähnlichkeit" : "");

    if (!groups.length) {
      list.innerHTML = q
        ? `<div class="card empty">Keine Treffer für „${esc(q)}“.<br><span class="small">Tipp: Die MAC vom Typenschild ist meist die Ethernet-MAC – gefunkt wird mit abgeleiteten BSSIDs. Die App zeigt ähnliche Adressen mit Trefferquote.</span></div>`
        : `<div class="card empty">${d.mode === "scan" ? "Suche läuft … ein Durchlauf über alle Kanäle dauert ca. 4 s." : "Scan starten, um APs zu finden."}</div>`;
      return;
    }

    list.innerHTML = groups.map((g) => {
      const title = g.name || g.ssids[0] || g.bssids[0];
      const sub = g.name ? g.ssids.join(" · ") : g.ssids.slice(1).join(" · ");
      const color = rssiColor(g.rssi);
      return `<div class="card ap" data-key="${esc(g.key)}">
        <div class="grow">
          <div class="t1">${esc(title)}</div>
          <div class="t2">${bandLabel(g.ch)} · Kanal ${g.ch} · ${g.bssids.length} BSSID${g.bssids.length > 1 ? "s" : ""}
            ${g.ftm ? ' <span class="chip ok">FTM</span>' : ""}
            ${g.txp !== null ? ` · ${g.txp} dBm` : ""}
            ${g.score ? ` · <span class="score">Treffer ${g.score} %</span>` : ""}</div>
          ${sub ? `<div class="t3">${esc(sub)}</div>` : ""}
          <div class="t3 mono">${esc(g.bssids[0])}${g.bssids.length > 1 ? ` +${g.bssids.length - 1}` : ""}</div>
          <div class="bar"><i style="width:${Math.round(level01(g.rssi, -95, -30) * 100)}%;background:${color}"></i></div>
        </div>
        <div class="rs" style="color:${color}">${g.rssi}</div>
      </div>`;
    }).join("");
  },
};
