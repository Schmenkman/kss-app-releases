// AP-Finder – Einstieg: Verbindung, Navigation, gemeinsamer Zustand.
import { $, $$, esc, toast } from "./util.js";
import { Device } from "./device.js";
import { CapacitorUsbTransport, SimTransport, WebSerialTransport } from "./transport.js";
import { DEFAULT_WORLD, Simulator } from "./sim.js";
import { Orientation } from "./orientation.js";
import { Tracker } from "./tracker.js";
import { Geiger } from "./audio.js";
import { settings } from "./store.js";
import { checkUpdate } from "./update.js";
import { bandLabel } from "./signal.js";
import { createSimPanel } from "./simpanel.js";
import devicesView from "./views/devices.js";
import searchView from "./views/search.js";
import peilenView from "./views/peilen.js";
import settingsView from "./views/settings.js";

const VIEWS = [devicesView, searchView, peilenView, settingsView];

const app = {
  device: new Device(),
  audio: new Geiger(),
  sim: null,
  simPose: { pos: [4, 4, 1.3] },
  state: { target: null, groups: [], elevDist: null, ftm: null, probeOn: settings().probeHz > 0 },
  views: {},
  current: null,
};
app.orientation = new Orientation(() => settings().axis);
app.tracker = new Tracker(app.device, settings);
const simPanel = createSimPanel(app);
window.apFinder = app; // für Tests/Fehlersuche

app.go = (tab) => {
  if (location.hash !== "#/" + tab) location.hash = "#/" + tab;
  else route();
};

app.openConnect = () => {
  $("#connUsb").hidden = !CapacitorUsbTransport.available();
  $("#connSerial").hidden = !WebSerialTransport.available();
  $("#connectModal").hidden = false;
};

app.connect = async (kind) => {
  $("#connectModal").hidden = true;
  let t;
  if (kind === "usb") t = new CapacitorUsbTransport();
  else if (kind === "serial") t = new WebSerialTransport();
  else {
    if (!app.sim) {
      app.sim = new Simulator({
        world: DEFAULT_WORLD,
        pose: () => {
          const o = app.orientation.current();
          return { pos: app.simPose.pos, az: o.az, el: o.el };
        },
        antenna: () => ({ type: settings().antenna, attenDb: settings().attenDb }),
      });
    }
    t = new SimTransport(app.sim);
  }
  try {
    await app.device.connect(t);
  } catch (e) {
    toast("Verbindung fehlgeschlagen: " + (e?.message || e), 4500);
    return;
  }
  toast("Verbunden: " + t.label);
  if (app.state.target && app.current !== "devices") await app.retrack();
  route();
};

app.selectTarget = async (group, opts = {}) => {
  app.state.target = {
    key: group.key,
    ch: group.ch,
    name: group.name,
    label: group.name || group.ssids[0] || group.bssids[0],
    bssids: group.bssids.slice(0, 8),
    ssids: group.ssids,
    txp: group.txp,
    ftm: group.ftm,
    group,
  };
  app.state.ftm = null;
  app.tracker.reset();
  app.tracker.clearMarks();
  renderTargetBar();
  await app.retrack();
  if (opts.stay) app.views[app.current]?.show?.();
  else app.go("search");
};

app.retrack = async () => {
  const t = app.state.target;
  if (!t || !app.device.connected) return;
  const s = settings();
  await app.device.setCountData(s.countData);
  await app.device.track(t, app.state.probeOn ? s.probeHz : 0);
};

app.mountSimPanel = (container) => simPanel.mount(container);

function route() {
  const tab = location.hash.replace(/^#\//, "") || "devices";
  const id = VIEWS.some((v) => v.id === tab) ? tab : "devices";
  if (app.current && app.current !== id) app.views[app.current].hide?.();
  app.current = id;
  $$("section[data-view]").forEach((s) => { s.hidden = s.dataset.view !== id; });
  $$(".tabbar a").forEach((a) => a.classList.toggle("active", a.dataset.tab === id));
  const d = app.device;
  if (d.connected && (id === "search" || id === "bearing") && app.state.target && d.mode !== "track") app.retrack();
  app.views[id].show?.();
}

function modeLabel(m) {
  return m === "scan" ? "scannt" : m === "track" ? "misst Ziel" : "bereit";
}

function renderHeader() {
  const d = app.device;
  $("#hdrDot").className = "dot" + (d.connected ? (d.transport?.kind === "sim" ? " sim" : " on") : "");
  $("#hdrSub").textContent = d.connected
    ? `${d.transport?.label || ""}${d.info ? " · FW " + d.info.fw : ""} · ${modeLabel(d.mode)}`
    : d.state === "connecting" ? "verbinde …" : "nicht verbunden";
  $("#btnConnect").textContent = d.connected ? "Trennen" : "Verbinden";
}

function renderTargetBar() {
  const t = app.state.target;
  const bar = $("#targetBar");
  bar.hidden = !t;
  if (t) {
    bar.innerHTML = `Ziel: <b>${esc(t.label)}</b> · ${bandLabel(t.ch)} · Kanal ${t.ch} · ${t.bssids.length} BSSID${t.bssids.length > 1 ? "s" : ""}`;
  }
}

async function autoConnectUsb() {
  if (!CapacitorUsbTransport.available()) return;
  const plugin = globalThis.Capacitor.Plugins.UsbSerial;
  plugin.addListener("state", (ev) => {
    if (ev?.state === "attached" && !app.device.connected) app.connect("usb");
  });
  if (await CapacitorUsbTransport.present()) await app.connect("usb");
}

document.addEventListener("DOMContentLoaded", () => {
  for (const v of VIEWS) {
    app.views[v.id] = v;
    v.mount($(`section[data-view="${v.id}"]`), app);
  }
  app.orientation.start();

  $("#btnConnect").addEventListener("click", () => (app.device.connected ? app.device.disconnect() : app.openConnect()));
  $("#connUsb").addEventListener("click", () => app.connect("usb"));
  $("#connSerial").addEventListener("click", () => app.connect("serial"));
  $("#connSim").addEventListener("click", () => app.connect("sim"));
  $("#connCancel").addEventListener("click", () => { $("#connectModal").hidden = true; });
  $("#updLater").addEventListener("click", () => { $("#updModal").hidden = true; });

  const d = app.device;
  d.on("state", (s) => {
    renderHeader();
    if (s === "disconnected") toast("Messkopf getrennt.");
  });
  d.on("hello", renderHeader);
  d.on("ack", renderHeader);
  d.on("error", (m) => toast("Messkopf: " + (m?.msg || "Fehler"), 4000));

  window.addEventListener("hashchange", route);
  renderHeader();
  renderTargetBar();
  route();
  autoConnectUsb();
  checkUpdate(false);
});
