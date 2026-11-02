// Unit-Tests der App-Logik (ohne Browser):  npm test
import test from "node:test";
import assert from "node:assert/strict";

import {
  baseMac, deviceKey, deviceScore, findPartners, groupDevices, groupRadios, groupScore, looksLikeMac, macScore,
  normalizeMac, preferredRadio, sameRadio,
} from "../www/js/mac.js";
import { Kalman, chanToMHz, estimateDistance, horizontalFromElevation, rssiAt1m } from "../www/js/signal.js";
import { SweepRecorder, angDiff, estimatePeak, norm360, orientationToDir } from "../www/js/bearing.js";
import { LineSplitter, cmd, parseLine, ssidToHex } from "../www/js/protocol.js";
import { DEFAULT_WORLD, Simulator, panelGainDb } from "../www/js/sim.js";
import { Device } from "../www/js/device.js";
import { Tracker } from "../www/js/tracker.js";

const near = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg ?? ""} ${a} ≉ ${b} (±${tol})`);

/* ---------------- MAC ---------------- */
test("MAC normalisieren", () => {
  assert.equal(normalizeMac("AA-BB-CC-DD-EE-FF"), "aa:bb:cc:dd:ee:ff");
  assert.equal(normalizeMac("aabb.ccdd.eeff"), "aa:bb:cc:dd:ee:ff");
  assert.equal(normalizeMac(" AABBCCDDEEFF "), "aa:bb:cc:dd:ee:ff");
  assert.equal(normalizeMac("aa:bb:cc:dd:ee"), null);
  assert.equal(normalizeMac("zz:bb:cc:dd:ee:ff"), null);
  assert.ok(looksLikeMac("5e:00:53"));
  assert.ok(!looksLikeMac("eduroam"));
  assert.ok(!looksLikeMac("ab"));
});

test("MAC-Ähnlichkeit", () => {
  assert.equal(macScore("00:00:5e:00:53:10", "00:00:5e:00:53:10"), 100);
  assert.equal(macScore("a8:b1:c2:44:55:60", "aa:b1:c2:44:55:60"), 90);          // LAA-Variante
  assert.equal(macScore("00:00:5e:00:53:10", "00:00:5e:00:53:20"), 84);          // fortlaufend, Δ = 16
  assert.equal(macScore("a8:b1:c2:44:55:60", "aa:b1:c2:44:55:61"), 80);          // LAA + Δ = 1
  assert.equal(macScore("00:00:5e:00:53:10", "00:00:5e:00:55:10"), 0);           // Δ = 512 → zu weit
  assert.equal(macScore("00:00:5e:00:53:10", "11:22:33:44:55:66"), 0);
  assert.ok(macScore("00:00:5e:00:53:10", "00:00:5e:00:53:11") > macScore("00:00:5e:00:53:10", "00:00:5e:00:53:50"));
});

test("Ruckus-Schema: Basis-MAC …x0, BSSIDs im 16er-Block, Varianten im 4. Byte", () => {
  // Beispiel aus Ruckus KBA 4312: AP 8c:0c:90:1f:a9:30 → BSSIDs 8c:0c:90:1f:a9:3X
  assert.equal(macScore("8c:0c:90:1f:a9:30", "8c:0c:90:1f:a9:38"), 98);
  assert.equal(macScore("8C-0C-90-1F-A9-30", "8c:0c:90:1f:a9:3c"), 98);
  assert.equal(macScore("8c:0c:90:1f:a9:30", "8c:0c:90:1f:a9:48"), 84);   // nächster Block = anderes Gerät
  // beobachtete Varianten im 4. Byte (18/58/98/d8)
  for (const b4 of ["18", "58", "98", "d8"]) {
    assert.equal(macScore("c4:10:8a:18:22:80", `c4:10:8a:${b4}:22:8c`), 98, b4);
  }
  assert.equal(deviceKey("c4:10:8a:58:22:8c"), deviceKey("c4:10:8a:18:22:80"));
  assert.equal(baseMac("8c:0c:90:1f:a9:3c"), "8c:0c:90:1f:a9:30");
  assert.ok(sameRadio("c4:10:8a:18:22:88", "c4:10:8a:98:22:89"));
});

test("Geräte aus Funkmodulen (2,4 + 5 GHz)", () => {
  const radios = groupRadios([
    { bssid: "8c:0c:90:1f:a9:38", ch: 6, ssid: "@BayernWLAN", rssi: -60 },
    { bssid: "8c:0c:90:1f:a9:3c", ch: 44, ssid: "@BayernWLAN", rssi: -66 },
    { bssid: "c4:10:8a:18:22:88", ch: 1, ssid: "@BayernWLAN", rssi: -75 },
    { bssid: "c4:10:8a:58:22:8c", ch: 100, ssid: "@BayernWLAN", rssi: -82 },
    { bssid: "c4:10:8a:18:23:48", ch: 11, ssid: "@BayernWLAN", rssi: -70, name: "BY-Saal" },
  ]);
  const devs = groupDevices(radios);
  assert.equal(devs.length, 3);
  const a = devs.find((d) => d.base === "8c:0c:90:1f:a9:30");
  assert.deepEqual(a.radios.map((r) => r.ch), [6, 44]);
  assert.equal(deviceScore(a, "8c:0c:90:1f:a9:30"), 98);
  const b = devs.find((d) => d.base === "c4:10:8a:18:22:80");
  assert.equal(b.radios.length, 2);
  assert.equal(devs.find((d) => d.name === "BY-Saal").radios.length, 1);
  // 5 GHz bevorzugen, wenn gut empfangen; sonst das stärkste Funkmodul
  assert.equal(preferredRadio(a).ch, 44);
  assert.equal(preferredRadio(b).ch, 1);
  const partner = findPartners(a.radios[0], radios);
  assert.equal(partner[0].ch, 44);
});

test("Funkmodule gruppieren", () => {
  const aps = [
    { bssid: "00:00:5e:00:53:20", ch: 6, ssid: "@BayernWLAN", rssi: -60 },
    { bssid: "00:00:5e:00:53:21", ch: 6, ssid: "eduroam", rssi: -58 },
    { bssid: "00:00:5e:00:53:30", ch: 44, ssid: "@BayernWLAN", rssi: -65 },
    { bssid: "aa:b1:c2:44:55:60", ch: 1, ssid: "@BayernWLAN", rssi: -70 },
    { bssid: "ae:b1:c2:44:55:60", ch: 1, ssid: "Intern", rssi: -71 },
    { bssid: "3c:d9:2b:01:02:01", ch: 6, ssid: "FRITZ!Box", rssi: -80 },
  ];
  const g = groupRadios(aps);
  assert.equal(g.length, 4);
  const a6 = g.find((x) => x.ch === 6 && x.bssids.includes("00:00:5e:00:53:20"));
  assert.deepEqual(a6.bssids, ["00:00:5e:00:53:21", "00:00:5e:00:53:20"]); // stärkste zuerst
  assert.equal(a6.rssi, -58);
  const b1 = g.find((x) => x.ch === 1);
  assert.equal(b1.bssids.length, 2);
  assert.ok(sameRadio("aa:b1:c2:44:55:60", "ae:b1:c2:44:55:60"));
  assert.equal(groupScore(a6, "00:00:5e:00:53:10"), 84);
  const partners = findPartners(a6, g);
  assert.equal(partners.length, 1);
  assert.equal(partners[0].ch, 44);
});

test("Gruppierung über AP-Namen", () => {
  const g = groupRadios([
    { bssid: "00:11:22:33:44:01", ch: 36, name: "AP-1", rssi: -50 },
    { bssid: "02:99:88:77:66:55", ch: 36, name: "AP-1", rssi: -52 },
    { bssid: "00:11:22:33:45:99", ch: 36, name: "AP-2", rssi: -60 },
  ]);
  assert.equal(g.length, 2);
  assert.equal(g[0].name, "AP-1");
  assert.equal(g[0].bssids.length, 2);
});

/* ---------------- Signal ---------------- */
test("Kalman glättet Rauschen", () => {
  const k = new Kalman(0.8, 9);
  let rnd = 1;
  const noise = () => { rnd = (rnd * 16807) % 2147483647; return (rnd / 2147483647 - 0.5) * 12; };
  let last;
  for (let i = 0; i < 200; i++) last = k.update(-60 + noise(), 3);
  near(last, -60, 2.5, "Kalman");
  assert.equal(k.update(null), last);
});

test("Entfernung aus Pegel", () => {
  assert.equal(chanToMHz(6), 2437);
  assert.equal(chanToMHz(36), 5180);
  const r1 = rssiAt1m(6, 17);
  near(r1, 17 - 40.2, 0.1);
  // 20 dB unter dem 1-m-Wert: Freiraum (n=2) → 10 m, n=3 → 4,6 m
  const e = estimateDistance({ rssi: r1 - 20, ch: 6, txp: 17, nLo: 2, nHi: 3 });
  near(e.max, 10, 0.01);
  near(e.min, 4.64, 0.01);
  assert.ok(e.typ > e.min && e.typ < e.max);
  // Dämpfungsglied und Antennengewinn werden herausgerechnet
  const a = estimateDistance({ rssi: r1 - 40, ch: 6, txp: 17, attenDb: 20, antGain: 0 });
  near(a.max, 10, 0.01);
  const b = estimateDistance({ rssi: r1 - 12, ch: 6, txp: 17, antGain: 8 });
  near(b.max, 10, 0.01);
  assert.equal(estimateDistance({ rssi: r1 + 3, ch: 6, txp: 17 }).max, 1);
  assert.equal(estimateDistance({ rssi: null, ch: 6 }), null);
});

test("Entfernung aus Elevation", () => {
  near(horizontalFromElevation(45, 3, 1.3), 1.7, 1e-9);
  near(horizontalFromElevation(30, 3, 1.3), 1.7 / Math.tan(Math.PI / 6), 1e-9);
  assert.equal(horizontalFromElevation(2, 3, 1.3), null);
  assert.equal(horizontalFromElevation(45, 1, 1.3), null);
});

/* ---------------- Peilung ---------------- */
test("Lage → Antennenrichtung", () => {
  // flach, Oberkante nach Norden
  let d = orientationToDir(0, 0, 0, "top");
  near(d.az, 0, 1e-6); near(d.el, 0, 1e-6);
  // flach, um 90° gegen den Uhrzeigersinn gedreht → Oberkante zeigt nach Westen
  d = orientationToDir(90, 0, 0, "top");
  near(d.az, 270, 1e-6);
  // aufrecht, Rückseite nach Norden
  d = orientationToDir(0, 90, 0, "back");
  near(norm360(d.az + 0.0), 0, 1e-6); near(d.el, 0, 1e-6);
  // nach hinten gekippt (beta 120°): Rückseite zeigt 30° nach oben
  d = orientationToDir(0, 120, 0, "back");
  near(d.el, 30, 1e-6);
  // Oberkante senkrecht nach oben
  d = orientationToDir(0, 90, 0, "top");
  near(d.el, 90, 1e-6);
  near(angDiff(10, 350), 20, 1e-9);
  near(angDiff(350, 10), -20, 1e-9);
});

function synthPattern(trueDeg, { noise = 1.5, hpbw = 60, seed = 7 } = {}) {
  let s = seed;
  const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647; };
  const rec = new SweepRecorder(5, 360);
  for (let a = 0; a < 360; a += 2) {
    const th = Math.abs(angDiff(a, trueDeg));
    const n = Math.log(0.5) / Math.log(Math.cos((hpbw / 2) * Math.PI / 180));
    const g = th >= 90 ? -20 : Math.max(10 * Math.log10(Math.pow(Math.cos(th * Math.PI / 180), n) + 1e-9), -20);
    rec.add(a, -55 + g + (rnd() - 0.5) * 2 * noise);
  }
  return rec;
}

test("Peilung findet die Richtung", () => {
  for (const truth of [0, 37, 123, 250, 355]) {
    const rec = synthPattern(truth);
    assert.ok(rec.coverage() > 0.99);
    const est = estimatePeak(rec.values(), { binDeg: 5, circular: true, sigmaBins: 2 });
    near(angDiff(est.angle, truth), 0, 6, `Peilung ${truth}°:`);
    assert.ok(est.contrast > 8);
    assert.equal(est.quality, "gut");
    assert.equal(est.secondary, null);
  }
});

test("Peilung erkennt zwei gleich starke Maxima", () => {
  const rec = new SweepRecorder(5, 360);
  for (let a = 0; a < 360; a += 5) {
    const g1 = Math.max(-20, -0.01 * angDiff(a, 60) ** 2);
    const g2 = Math.max(-20, -0.01 * angDiff(a, 240) ** 2);
    rec.add(a, -55 + Math.max(g1, g2));
  }
  const est = estimatePeak(rec.values(), { binDeg: 5 });
  assert.equal(est.quality, "zweideutig");
  assert.ok(est.secondary);
});

test("Elevation nahe 0° mit breiter Keule (eine −3-dB-Flanke fehlt)", () => {
  const rec = new SweepRecorder(5, 90);
  // cos^n-Keule mit 66° Halbwertsbreite, Maximum bei 18°: untere Flanke läge unter 0°
  const n = Math.log(0.5) / Math.log(Math.cos((33 * Math.PI) / 180));
  for (let e = 0; e <= 90; e += 1) {
    const th = Math.abs(e - 18);
    rec.add(e, -50 + 10 * Math.log10(Math.pow(Math.cos((th * Math.PI) / 180), n)));
  }
  const est = estimatePeak(rec.values(), { binDeg: 5, circular: false, sigmaBins: 1.5 });
  near(est.angle, 18, 4, "Elevation am Rand");
});

test("Elevation (nicht zyklisch)", () => {
  const rec = new SweepRecorder(5, 90);
  for (let e = 0; e <= 90; e += 1) rec.add(e, -50 - 0.02 * (e - 40) ** 2);
  const est = estimatePeak(rec.values(), { binDeg: 5, circular: false, sigmaBins: 1.5 });
  near(est.angle, 40, 4, "Elevation");
});

/* ---------------- Protokoll ---------------- */
test("Zeilen zerlegen und JSON prüfen", () => {
  const lines = [];
  const sp = new LineSplitter((l) => lines.push(l));
  sp.push('ESP-ROM:esp32c5\r\n{"t":"hel');
  sp.push('lo","fw":"0.1.0"}\n{"t":"rssi","nb":3}\n{kaputt\n');
  assert.equal(lines.length, 4);
  assert.equal(parseLine(lines[0]), null);
  assert.equal(parseLine(lines[1]).fw, "0.1.0");
  assert.equal(parseLine(lines[2]).nb, 3);
  assert.equal(parseLine(lines[3]), null);
  assert.equal(parseLine('{"x":1}'), null);
});

test("Befehle", () => {
  assert.equal(cmd.scan(110, "25"), "SCAN 110 25");
  assert.equal(cmd.track({ ch: 36, probeHz: 10, bssids: ["aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"] }),
    "TRACK 36 10 aa:bb:cc:dd:ee:ff,11:22:33:44:55:66");
  assert.equal(cmd.track({ ch: 6, probeHz: 99, bssids: ["aa:bb:cc:dd:ee:ff"], ssid: "@BayernWLAN" }),
    "TRACK 6 50 aa:bb:cc:dd:ee:ff 4042617965726e574c414e");
  assert.equal(cmd.ftm(44, "aa:bb:cc:dd:ee:ff", 32), "FTM 44 aa:bb:cc:dd:ee:ff 32");
  assert.equal(ssidToHex("Ä"), "c384");
});

/* ---------------- Simulator + Device + Tracker ---------------- */
function rig(pose) {
  const sim = new Simulator({ world: DEFAULT_WORLD, pose: () => pose, antenna: () => ({ type: "dir", attenDb: 0 }), seed: 3 });
  const device = new Device();
  device.transport = { send: async (l) => sim.command(l) };
  device.state = "connected";
  sim.onLine = (l) => device.splitter.push(l + "\n");
  const tracker = new Tracker(device, () => ({ countData: false }));
  return { sim, device, tracker };
}

test("Simulator-Scan füllt die AP-Liste", async () => {
  const pose = { pos: [10, 7, 1.3], az: 80, el: 0 };
  const { sim, device } = rig(pose);
  await device.startScan("25");
  sim.advance(4500);
  const aps = device.apList();
  assert.equal(aps.length, 8, "alle 8 BSSIDs gefunden");
  const bayern = aps.filter((a) => a.ssid === "@BayernWLAN");
  assert.equal(aps.length - bayern.length, 2, "FRITZ!Box-BSSIDs ausgefiltert");
  const devs = groupDevices(groupRadios(bayern));
  assert.equal(devs.length, 3);
  const ranked = devs.map((d) => ({ d, s: deviceScore(d, "8C:0C:90:1F:A9:30") })).sort((a, b) => b.s - a.s);
  assert.equal(ranked[0].s, 98);
  assert.equal(ranked[0].d.base, "8c:0c:90:1f:a9:30");
  assert.equal(ranked[0].d.radios.length, 2);
  assert.equal(ranked[1].s, 0);
  // AP B: 5-GHz-BSSID mit Variante im 4. Byte → trotzdem ein Gerät
  const b = devs.map((d) => ({ d, s: deviceScore(d, "c4:10:8a:18:22:80") })).sort((x, y) => y.s - x.s);
  assert.equal(b[0].s, 98);
  assert.equal(b[0].d.radios.length, 2);
  assert.ok(b[1].s > 0 && b[1].s < 80, "Nachbar aus derselben Serie mit niedriger Quote");
});

test("Simulator: näher = stärker, Rundum-Peilung zeigt zum AP", async () => {
  const ap = DEFAULT_WORLD.aps[0];
  const pose = { pos: [4, 4, 1.3], az: 0, el: 0 };
  const { sim, device, tracker } = rig(pose);
  await device.track({ ch: 6, bssids: ap.radios[0].bssids }, 10);

  const level = () => {
    // Antenne auf den AP richten und 2 s messen
    pose.az = norm360(Math.atan2(ap.pos[0] - pose.pos[0], ap.pos[1] - pose.pos[1]) * 180 / Math.PI);
    tracker.reset();
    sim.advance(2000);
    return tracker.value;
  };
  const far = level();
  pose.pos = [12, 7, 1.3];
  const close = level();
  assert.ok(close > far + 3, `nah ${close} > fern ${far}`);
  assert.ok(tracker.rate > 15, "Messrate mit Probes " + tracker.rate);

  // Rundum drehen, 5° je 100 ms
  pose.pos = [6, 3, 1.3];
  const truth = norm360(Math.atan2(ap.pos[0] - 6, ap.pos[1] - 3) * 180 / Math.PI);
  const rec = new SweepRecorder(5, 360);
  tracker.reset();
  for (let a = 0; a < 365; a += 5) {
    pose.az = a;
    sim.advance(100);
    if (tracker.raw !== null) rec.add(a, tracker.raw);
  }
  const est = estimatePeak(rec.values(), { binDeg: 5 });
  near(angDiff(est.angle, truth), 0, 15, "Peilung im Simulator:");
});

test("Simulator: FTM nur bei FTM-fähigem AP", async () => {
  const pose = { pos: [10, 7, 1.3], az: 0, el: 0 };
  const { device } = rig(pose);
  const got = [];
  device.on("ftm", (m) => got.push(m));
  const c = DEFAULT_WORLD.aps[2];
  await device.ftm(c.radios[1].bssids[0], c.radios[1].ch);                 // AP C kündigt FTM an
  await device.ftm(DEFAULT_WORLD.aps[0].radios[1].bssids[0], 44);          // AP A nicht
  await new Promise((r) => setTimeout(r, 450));
  assert.equal(got.length, 2);
  const ok = got.find((m) => m.ok);
  const dist = Math.hypot(c.pos[0] - 10, c.pos[1] - 7, c.pos[2] - 1.3);
  near(ok.dist_cm / 100, dist, 2.5, "FTM");
  assert.equal(got.find((m) => !m.ok).status, 1);
});

test("Tracker wertet Beacons, Probe-Antworten und Daten getrennt", () => {
  const dev = new Device();
  let opts = { countData: false, useProbeResp: true };
  const tr = new Tracker(dev, () => opts);
  dev._onLine(JSON.stringify({ t: "rssi", nb: 2, rb: -60, xb: -59, mb: -61, np: 2, rp: -50, xp: -49, nd: 4, rd: -40, xd: -38, tx: 2, te: 0 }));
  near(tr.raw, -55, 1e-9, "Beacons + Probes");
  opts = { countData: false, useProbeResp: false };
  dev._onLine(JSON.stringify({ t: "rssi", nb: 2, rb: -60, xb: -59, mb: -61, np: 2, rp: -50, xp: -49, nd: 4, rd: -40, xd: -38, tx: 2, te: 0 }));
  near(tr.raw, -60, 1e-9, "nur Beacons");
  opts = { countData: true, useProbeResp: false };
  dev._onLine(JSON.stringify({ t: "rssi", nb: 2, rb: -60, xb: -59, mb: -61, np: 0, rp: null, xp: null, nd: 2, rd: -40, xd: -38, tx: 0, te: 0 }));
  near(tr.raw, -50, 1e-9, "Beacons + Daten");
  assert.ok(tr.beaconAvg < -59 && tr.probeAvg > -51);
});

test("Richtdiagramm Panel", () => {
  near(panelGainDb(0, 5), 10, 1e-9);
  near(panelGainDb(25, 5), 7, 0.05); // halbe Halbwertsbreite → −3 dB
  assert.equal(panelGainDb(120, 2), -12);
});
