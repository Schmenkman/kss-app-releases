// Transportwege zum Messkopf. Gemeinsame Schnittstelle:
//   connect(), disconnect(), send(line), onData(text), onClose(), kind, label

export const ESPRESSIF_VID = 0x303a;

function usbPlugin() {
  const C = globalThis.Capacitor;
  if (!C || !C.Plugins) return null;
  return C.Plugins.UsbSerial || null;
}

/** Android-App: natives Capacitor-Plugin „UsbSerial“ (USB-Host, CDC-ACM). */
export class CapacitorUsbTransport {
  constructor() {
    this.kind = "usb";
    this.label = "USB";
    this.subs = [];
    this.onData = null;
    this.onClose = null;
  }
  static available() {
    return !!usbPlugin();
  }
  /** Ist ein passendes Gerät angesteckt? */
  static async present() {
    const p = usbPlugin();
    if (!p) return false;
    try {
      const r = await p.list();
      return (r?.devices || []).some((d) => d.vendorId === ESPRESSIF_VID);
    } catch {
      return false;
    }
  }
  async connect() {
    const p = usbPlugin();
    if (!p) throw new Error("USB-Plugin nicht verfügbar (nur in der Android-App).");
    this.subs.push(
      await p.addListener("data", (ev) => {
        for (const l of ev?.lines || []) this.onData?.(l + "\n");
      })
    );
    this.subs.push(
      await p.addListener("state", (ev) => {
        if (ev?.state === "disconnected") this.onClose?.();
      })
    );
    const info = await p.connect({ vendorId: ESPRESSIF_VID });
    this.label = "USB" + (info?.name ? " · " + info.name : "");
  }
  async disconnect() {
    const p = usbPlugin();
    try {
      await p?.disconnect();
    } finally {
      for (const s of this.subs) {
        try {
          await s.remove();
        } catch { /* egal */ }
      }
      this.subs = [];
    }
  }
  async send(line) {
    await usbPlugin().write({ data: line + "\n" });
  }
}

/** Chrome am PC (und Android-Chrome, sofern unterstützt): Web Serial API. */
export class WebSerialTransport {
  constructor() {
    this.kind = "webserial";
    this.label = "Web Serial";
    this.port = null;
    this.reader = null;
    this.onData = null;
    this.onClose = null;
  }
  static available() {
    return typeof navigator !== "undefined" && !!navigator.serial;
  }
  async connect() {
    this.port = await navigator.serial.requestPort({ filters: [{ usbVendorId: ESPRESSIF_VID }] });
    await this.port.open({ baudRate: 115200 });
    // DTR an, RTS aus: beim ESP32-USB-Serial/JTAG löst RTS ohne DTR einen Reset aus.
    try {
      await this.port.setSignals({ dataTerminalReady: true, requestToSend: false });
    } catch { /* nicht jedes Gerät unterstützt das */ }
    this._readLoop();
  }
  async _readLoop() {
    const dec = new TextDecoder();
    try {
      while (this.port?.readable) {
        this.reader = this.port.readable.getReader();
        try {
          for (;;) {
            const { value, done } = await this.reader.read();
            if (done) break;
            if (value) this.onData?.(dec.decode(value, { stream: true }));
          }
        } finally {
          this.reader.releaseLock();
          this.reader = null;
        }
      }
    } catch { /* Gerät abgezogen */ }
    this.onClose?.();
  }
  async disconnect() {
    try {
      await this.reader?.cancel();
    } catch { /* egal */ }
    try {
      await this.port?.close();
    } catch { /* egal */ }
    this.port = null;
  }
  async send(line) {
    const w = this.port.writable.getWriter();
    try {
      await w.write(new TextEncoder().encode(line + "\n"));
    } finally {
      w.releaseLock();
    }
  }
}

/** Simulator: spricht dasselbe Protokoll wie die Firmware (siehe sim.js). */
export class SimTransport {
  constructor(sim) {
    this.kind = "sim";
    this.label = "Simulator";
    this.sim = sim;
    this.onData = null;
    this.onClose = null;
  }
  static available() {
    return true;
  }
  async connect() {
    this.sim.onLine = (l) => this.onData?.(l + "\n");
    this.sim.start();
  }
  async disconnect() {
    this.sim.stop();
    this.onClose?.();
  }
  async send(line) {
    this.sim.command(line);
  }
}
