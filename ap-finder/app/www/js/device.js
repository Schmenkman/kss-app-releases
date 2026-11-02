// Verbindung zum Messkopf (ESP32-C5 oder Simulator) und AP-Datenbank.
import { Emitter, LineSplitter, cmd, parseLine } from "./protocol.js";

const AP_TTL_MS = 45000; // APs, die so lange nicht gehört wurden, fallen aus der Liste

export class Device extends Emitter {
  constructor() {
    super();
    this.transport = null;
    this.state = "disconnected"; // disconnected | connecting | connected
    this.info = null;
    this.mode = "idle";          // idle | scan | track
    this.aps = new Map();        // bssid → Datensatz
    this.target = null;
    this.lastRx = 0;
    this.splitter = new LineSplitter((l) => this._onLine(l));
  }

  get connected() {
    return this.state === "connected";
  }

  async connect(transport) {
    if (this.transport) await this.disconnect();
    this.transport = transport;
    this._setState("connecting");
    transport.onData = (text) => this.splitter.push(text);
    transport.onClose = () => {
      if (this.transport === transport) {
        this.transport = null;
        this.mode = "idle";
        this._setState("disconnected");
      }
    };
    try {
      await transport.connect();
    } catch (e) {
      this.transport = null;
      this._setState("disconnected");
      throw e;
    }
    this._setState("connected");
    await this.send(cmd.hello());
  }

  async disconnect() {
    const t = this.transport;
    this.transport = null;
    this.mode = "idle";
    if (t) {
      try {
        await t.send(cmd.stop());
      } catch { /* egal */ }
      try {
        await t.disconnect();
      } catch { /* egal */ }
    }
    this._setState("disconnected");
  }

  async send(line) {
    if (!this.transport) return;
    try {
      await this.transport.send(line);
    } catch (e) {
      this.emit("error", { msg: "Senden fehlgeschlagen: " + (e?.message || e) });
    }
  }

  async startScan(bands = "25") {
    this.mode = "scan";
    await this.send(cmd.scan(110, bands));
  }

  async stop() {
    this.mode = "idle";
    await this.send(cmd.stop());
  }

  /** target: { ch, bssids[], label, … } */
  async track(target, probeHz = 10) {
    this.target = target;
    this.mode = "track";
    await this.send(cmd.track({ ch: target.ch, probeHz, bssids: target.bssids }));
  }

  async setCountData(on) {
    await this.send(cmd.data(on));
  }

  async ftm(bssid, ch) {
    await this.send(cmd.ftm(ch, bssid, 32));
  }

  /** aktuelle AP-Liste (veraltete Einträge entfernt) */
  apList(now = Date.now()) {
    for (const [k, a] of this.aps) {
      if (now - a.lastSeen > AP_TTL_MS) this.aps.delete(k);
    }
    return [...this.aps.values()];
  }

  clearAps() {
    this.aps.clear();
    this.emit("aps", null);
  }

  _setState(s) {
    this.state = s;
    this.emit("state", s);
  }

  _onLine(line) {
    const m = parseLine(line);
    if (!m) return;
    this.lastRx = Date.now();
    switch (m.t) {
      case "hello":
        this.info = m;
        this.emit("hello", m);
        break;
      case "ap":
        this._upsertAp(m);
        break;
      case "sweep":
        this.emit("sweep", m);
        break;
      case "rssi":
        this.emit("rssi", m);
        break;
      case "ftm":
        this.emit("ftm", m);
        break;
      case "ack":
        this.emit("ack", m);
        break;
      case "err":
        this.emit("error", m);
        break;
      default:
        this.emit("misc", m);
    }
  }

  _upsertAp(m) {
    const now = Date.now();
    const prev = this.aps.get(m.b);
    const rec = {
      bssid: m.b,
      ssid: m.s || prev?.ssid || "",
      hidden: !!m.h && !(prev && prev.ssid),
      ch: m.ch,
      rssi: m.r,
      txp: m.txp ?? prev?.txp ?? null,
      ftm: !!m.ftm || !!prev?.ftm,
      enc: m.enc,
      std: m.std,
      name: m.nm || prev?.name || "",
      bi: m.bi,
      lastSeen: now,
      firstSeen: prev?.firstSeen ?? now,
      seen: (prev?.seen ?? 0) + 1,
    };
    this.aps.set(m.b, rec);
    this.emit("ap", rec);
  }
}
