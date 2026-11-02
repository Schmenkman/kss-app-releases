// Steuerung für den Simulator: Grundriss, Gehen, Drehen, Neigen.
import { $, cssVar, setupCanvas } from "./util.js";

export function createSimPanel(app) {
  let canvas = null;
  let showAps = false;

  function draw() {
    if (!canvas || !canvas.isConnected || canvas.offsetParent === null) return;
    const world = app.sim?.world;
    if (!world) return;
    const { ctx, w, h } = setupCanvas(canvas);
    const minX = -8, maxX = 30, minY = -8, maxY = 24;
    const sc = Math.min(w / (maxX - minX), h / (maxY - minY));
    const X = (x) => (x - minX) * sc + (w - (maxX - minX) * sc) / 2;
    const Y = (y) => h - ((y - minY) * sc + (h - (maxY - minY) * sc) / 2);
    ctx.clearRect(0, 0, w, h);
    ctx.strokeStyle = cssVar("--line");
    ctx.lineWidth = 2;
    ctx.strokeRect(X(0), Y(world.room.h), world.room.w * sc, world.room.h * sc);
    ctx.font = "11px system-ui";
    if (showAps) {
      for (const ap of world.aps) {
        ctx.fillStyle = cssVar("--warn");
        ctx.fillRect(X(ap.pos[0]) - 4, Y(ap.pos[1]) - 4, 8, 8);
        ctx.fillText(ap.id, X(ap.pos[0]) + 6, Y(ap.pos[1]) - 4);
      }
    }
    const p = app.simPose.pos;
    const o = app.orientation.current();
    const r = (o.az * Math.PI) / 180;
    ctx.strokeStyle = cssVar("--accent");
    ctx.beginPath();
    ctx.moveTo(X(p[0]), Y(p[1]));
    ctx.lineTo(X(p[0]) + 18 * Math.sin(r), Y(p[1]) - 18 * Math.cos(r));
    ctx.stroke();
    ctx.fillStyle = cssVar("--accent");
    ctx.beginPath();
    ctx.arc(X(p[0]), Y(p[1]), 5, 0, 2 * Math.PI);
    ctx.fill();
    ctx.fillStyle = cssVar("--muted");
    ctx.fillText(`x ${p[0].toFixed(1)} m · y ${p[1].toFixed(1)} m`, 6, 14);
  }

  function move(dx, dy) {
    const p = app.simPose.pos;
    p[0] = Math.round((p[0] + dx * 0.5) * 10) / 10;
    p[1] = Math.round((p[1] + dy * 0.5) * 10) / 10;
    draw();
  }

  function rotate(d) {
    const o = app.orientation;
    o.setSim(o.sim.az + d, o.sim.el);
    draw();
  }

  function tilt(d) {
    const o = app.orientation;
    o.setSim(o.sim.az, o.sim.el + d);
  }

  app.orientation.on("change", draw);
  window.addEventListener("keydown", (e) => {
    if (app.device.transport?.kind !== "sim" || e.target.closest?.("input, select, textarea")) return;
    const k = e.key;
    if (k === "ArrowUp") move(0, 1);
    else if (k === "ArrowDown") move(0, -1);
    else if (k === "ArrowLeft") move(-1, 0);
    else if (k === "ArrowRight") move(1, 0);
    else if (k === "q") rotate(-15);
    else if (k === "e") rotate(15);
    else return;
    e.preventDefault();
  });

  return {
    draw,
    move,
    mount(container) {
      if (!container) return;
      if (!app.device.connected || app.device.transport?.kind !== "sim") {
        container.innerHTML = "";
        canvas = null;
        return;
      }
      container.innerHTML = `
        <div class="card sim-card">
          <h3>Simulator</h3>
          <canvas class="map"></canvas>
          <div class="row gap" style="align-items:flex-start">
            <div class="sim-grid">
              <span></span><button type="button" class="btn" data-mv="0,1" aria-label="nach Norden">↑</button><span></span>
              <button type="button" class="btn" data-mv="-1,0" aria-label="nach Westen">←</button><span></span>
              <button type="button" class="btn" data-mv="1,0" aria-label="nach Osten">→</button>
              <span></span><button type="button" class="btn" data-mv="0,-1" aria-label="nach Süden">↓</button><span></span>
            </div>
            <div class="stack grow">
              <div class="row"><button type="button" class="btn btn-sm" data-rot="-15">⟲ 15°</button>
                <button type="button" class="btn btn-sm" data-rot="15">⟳ 15°</button></div>
              <div class="row"><button type="button" class="btn btn-sm" data-tilt="10">Neigen ↑</button>
                <button type="button" class="btn btn-sm" data-tilt="-10">Neigen ↓</button></div>
              <label class="check small"><input type="checkbox" class="sim-show" ${showAps ? "checked" : ""} /> APs auf Karte zeigen</label>
            </div>
          </div>
          <p class="hint">Pfeile = 0,5 m gehen (Karte: oben = Norden). Ohne Lagesensor stellt man hier die Antennenrichtung ein. Tastatur: Pfeiltasten, Q/E.</p>
        </div>`;
      canvas = $("canvas.map", container);
      container.onclick = (e) => {
        const mv = e.target.closest("[data-mv]");
        if (mv) {
          const [dx, dy] = mv.dataset.mv.split(",").map(Number);
          move(dx, dy);
        }
        const rot = e.target.closest("[data-rot]");
        if (rot) rotate(Number(rot.dataset.rot));
        const tl = e.target.closest("[data-tilt]");
        if (tl) tilt(Number(tl.dataset.tilt));
      };
      $(".sim-show", container).onchange = (e) => {
        showAps = e.target.checked;
        draw();
      };
      requestAnimationFrame(draw);
    },
  };
}
