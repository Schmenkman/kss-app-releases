// UI-Durchlauf mit Simulator (Playwright, Chromium):  node test/ui.e2e.mjs
// Startet einen lokalen Server, bedient die App wie ein Nutzer und prüft die
// wichtigsten Ergebnisse. Bildschirmfotos landen in test-results/.
import { createRequire } from "node:module";
import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const appDir = fileURLToPath(new URL("..", import.meta.url));
const outDir = fileURLToPath(new URL("../test-results/", import.meta.url));
mkdirSync(outDir, { recursive: true });
const PORT = 8790;

const server = spawn(process.execPath, ["scripts/serve.mjs", String(PORT)], { cwd: appDir, stdio: "ignore" });
await new Promise((r) => setTimeout(r, 600));

const browser = await chromium.launch();
const errors = [];
try {
  const ctx = await browser.newContext({ viewport: { width: 412, height: 915 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => errors.push(e.message));
  page.on("console", (m) => { if (m.type() === "error" && !/Failed to load resource/.test(m.text())) errors.push(m.text()); });
  await page.goto(`http://localhost:${PORT}/`, { waitUntil: "networkidle" });

  // Verbinden mit Simulator, Scan läuft automatisch
  await page.click("#btnConnect");
  await page.click("#connSim");
  // Geräte (je AP eine Karte mit 2,4- und 5-GHz-Funkmodul); FRITZ!Box wird per SSID-Filter ausgeblendet
  await page.waitForFunction(() => document.querySelectorAll("#devList [data-dev] [data-key]").length >= 6, null, { timeout: 15000 });
  assert.equal(await page.locator("#devList [data-dev]").count(), 3);
  assert.match(await page.textContent("#devInfo"), /ausgeblendet/);
  await page.screenshot({ path: outDir + "1-geraete.png", fullPage: true });

  // MAC vom Typenschild (Ruckus-Basis-MAC) → richtiger AP oben, beide Funkmodule
  await page.fill("#devQuery", "8C-0C-90-1F-A9-30");
  await page.waitForTimeout(300);
  const first = await page.textContent("#devList [data-dev] .t1");
  assert.equal(first.trim(), "AP 8c:0c:90:1f:a9:30");
  assert.match(await page.textContent("#devList [data-dev] .score"), /Treffer 98 %/);
  assert.equal(await page.locator("#devList [data-dev]").first().locator("[data-key]").count(), 2);
  await page.screenshot({ path: outDir + "2-mac-suche.png", fullPage: true });

  // Ziel wählen → Suchen
  await page.click("#devList [data-key]");
  await page.waitForFunction(() => /\d/.test(document.querySelector("#srVal")?.textContent || ""), null, { timeout: 8000 });
  const far = await page.evaluate(() => window.apFinder.tracker.value);
  // zum AP gehen (x 14,4 / y 7,8)
  for (let i = 0; i < 16; i++) await page.keyboard.press("ArrowRight");
  for (let i = 0; i < 6; i++) await page.keyboard.press("ArrowUp");
  await page.waitForTimeout(3000);
  const close = await page.evaluate(() => window.apFinder.tracker.recentPeak(2500));
  assert.ok(close > far, `näher am AP stärker: ${close} > ${far}`);
  await page.click("#srMark");
  assert.equal(await page.locator("#srMarks li.best").count(), 1);

  // FTM: Ruckus-AP A kündigt kein FTM an → sauber „nicht möglich“
  await page.click("#srFtm");
  await page.waitForFunction(() => /nicht möglich/.test(document.querySelector("#srDistF")?.textContent || ""), null, { timeout: 5000 });
  await page.screenshot({ path: outDir + "3-suchen.png", fullPage: true });

  // Peilen: Rundum (simulierte Drehung)
  await page.evaluate(() => { window.apFinder.simPose.pos = [6, 3, 1.3]; });
  await page.click("a[data-tab=bearing]");
  await page.click("#pbStart");
  await page.waitForFunction(() => document.querySelector("#pbStart")?.textContent === "Start", null, { timeout: 20000 });
  const est = await page.evaluate(() => window.apFinder.views.bearing.estAz?.angle);
  const truth = (Math.atan2(14.4 - 6, 7.8 - 3) * 180) / Math.PI;
  const diff = Math.abs(((est - truth + 540) % 360) - 180);
  assert.ok(diff <= 20, `Peilung ${est?.toFixed(1)}° vs. ${truth.toFixed(1)}°`);
  await page.screenshot({ path: outDir + "4-peilen.png", fullPage: true });

  // Elevation → horizontale Entfernung (im Nahbereich, ca. 3,7 m vom AP)
  await page.evaluate(() => { window.apFinder.simPose.pos = [11, 6.3, 1.3]; });
  await page.click("#pbMode button[data-m=el]");
  await page.click("#pbStart");
  await page.waitForFunction(() => document.querySelector("#pbStart")?.textContent === "Start", null, { timeout: 20000 });
  const ed = await page.evaluate(() => window.apFinder.state.elevDist);
  const elevDist = ed?.m;
  const trueH = Math.hypot(14.4 - 11, 7.8 - 6.3);
  assert.ok(ed && !ed.flat, "Elevation sollte im Nahbereich nicht flach sein");
  assert.ok(Math.abs(elevDist - trueH) <= 1.2, `Elevation → ${elevDist} m (wahr ${trueH.toFixed(1)} m)`);
  await page.screenshot({ path: outDir + "5-elevation.png", fullPage: true });

  await page.click("a[data-tab=settings]");
  await page.screenshot({ path: outDir + "6-einstellungen.png", fullPage: true });

  assert.deepEqual(errors, [], "JavaScript-Fehler: " + errors.join(" | "));
  console.log(`OK – Peilung ${est.toFixed(1)}° (wahr ${truth.toFixed(1)}°), Elevation-Abstand ${elevDist.toFixed(1)} m (wahr ${trueH.toFixed(1)} m)`);
} finally {
  await browser.close();
  server.kill();
}
