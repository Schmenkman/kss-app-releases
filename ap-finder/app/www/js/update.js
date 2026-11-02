// Update-Prüfung wie bei den anderen KSS-Apps: version.json im Release-Repo.
import { $, esc } from "./util.js";

const UPDATE_URL = "https://raw.githubusercontent.com/Schmenkman/kss-app-releases/main/apfinder-version.json";

let localVer = null;
export async function version() {
  if (localVer) return localVer;
  try {
    const r = await fetch("version.json", { cache: "no-store" });
    localVer = (await r.json()).version;
  } catch {
    localVer = "?";
  }
  return localVer;
}

export function cmpVer(a, b) {
  const pa = String(a).split(".").map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d > 0 ? 1 : -1;
  }
  return 0;
}

export async function checkUpdate(manual) {
  const mine = await version();
  let info;
  try {
    const res = await fetch(UPDATE_URL + "?t=" + Date.now(), { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    info = await res.json();
  } catch {
    if (manual) alert("Update-Prüfung nicht möglich (Internet? Noch kein Release veröffentlicht?).");
    return;
  }
  if (info && info.version && cmpVer(info.version, mine) > 0) {
    $("#updVer").textContent = `Neue Version ${info.version} verfügbar (du hast ${mine}).`;
    const notes = Array.isArray(info.notes) ? info.notes : [];
    $("#updNotes").innerHTML = notes.length
      ? "<b>Neu in dieser Version:</b><ul>" + notes.map((n) => `<li>${esc(n)}</li>`).join("") + "</ul>"
      : "";
    if (info.apkUrl) $("#updInstall").onclick = () => window.open(info.apkUrl, "_blank");
    $("#updModal").hidden = false;
  } else if (manual) {
    alert("Du hast bereits die neueste Version (" + mine + ").");
  }
}
