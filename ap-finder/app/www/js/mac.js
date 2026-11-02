// MAC-Adressen normalisieren, mit BSSIDs vergleichen und Funkmodule gruppieren.
// Reine Logik ohne DOM – wird auch in den Node-Tests geprüft.

export function hexOnly(s) {
  return String(s ?? "").toLowerCase().replace(/[^0-9a-f]/g, "");
}

/** Vollständige MAC in beliebiger Schreibweise → "aa:bb:cc:dd:ee:ff" oder null. */
export function normalizeMac(s) {
  const raw = String(s ?? "").trim().toLowerCase();
  if (!raw || !/^[0-9a-f:\-.\s]+$/.test(raw)) return null;
  const h = raw.replace(/[^0-9a-f]/g, "");
  if (h.length !== 12) return null;
  return h.match(/../g).join(":");
}

/** Sieht die Eingabe nach (Teil-)MAC aus? (mind. 4 Hex-Zeichen, nur Hex/Trenner) */
export function looksLikeMac(s) {
  const raw = String(s ?? "").trim().toLowerCase();
  return /^[0-9a-f:\-.\s]+$/.test(raw) && hexOnly(raw).length >= 4;
}

export function macBytes(mac) {
  const h = hexOnly(mac);
  if (h.length !== 12) return null;
  return h.match(/../g).map((x) => parseInt(x, 16));
}

/** Hersteller-Präfix (OUI) "aa:bb:cc" */
export function oui(mac) {
  const n = normalizeMac(mac);
  return n ? n.slice(0, 8) : "";
}

export function isLocallyAdministered(mac) {
  const b = macBytes(mac);
  return !!b && (b[0] & 0x02) !== 0;
}

const nic = (b) => b[3] * 65536 + b[4] * 256 + b[5];

/**
 * Ähnlichkeit (0…100) zwischen der MAC vom Typenschild und einer BSSID.
 *  100  exakt gleich
 *   90  nur das erste Byte anders (lokal administrierte Varianten, z. B. Meraki/UniFi)
 * 85…65 gleiches Präfix (Byte 2+3, erstes Byte egal) und Abstand der hinteren
 *       drei Bytes ≤ 256 – je näher, desto höher (fortlaufende BSSIDs, Cisco/Aruba/LANCOM)
 *    0  kein Zusammenhang erkennbar
 */
export function macScore(target, bssid) {
  const t = macBytes(target);
  const b = macBytes(bssid);
  if (!t || !b) return 0;
  if (t.every((v, i) => v === b[i])) return 100;
  if (t.slice(1).every((v, i) => v === b[i + 1])) return 90;
  if (t[1] === b[1] && t[2] === b[2]) {
    const d = Math.abs(nic(t) - nic(b));
    if (d <= 256) {
      const base = t[0] === b[0] ? 85 : 80;
      return Math.round(base - (15 * d) / 256);
    }
  }
  return 0;
}

/** Teilsuche: passt die Hex-Folge irgendwo in die BSSID? */
export function macContains(bssid, query) {
  const q = hexOnly(query);
  return q.length >= 4 && hexOnly(bssid).includes(q);
}

/** Gehören zwei BSSIDs vermutlich zum selben Funkmodul? (Kanal separat prüfen) */
export function sameRadio(a, b) {
  const x = macBytes(a);
  const y = macBytes(b);
  if (!x || !y) return false;
  if (x.slice(1).every((v, i) => v === y[i + 1])) return true; // LAA-Varianten
  return x[1] === y[1] && x[2] === y[2] && Math.abs(nic(x) - nic(y)) <= 16;
}

/**
 * Fasst BSSIDs zu Funkmodulen zusammen (gleicher Kanal und ähnliche MAC oder
 * gleicher AP-Name). aps: [{bssid, ch, name, ssid, rssi, txp, ftm, …}]
 * Rückgabe: [{key, ch, name, bssids[], ssids[], rssi, txp, ftm, aps[]}], stärkste zuerst.
 */
export function groupRadios(aps) {
  const list = aps.filter((a) => a && a.bssid);
  const parent = list.map((_, i) => i);
  const find = (i) => (parent[i] === i ? i : (parent[i] = find(parent[i])));
  const union = (i, j) => { parent[find(i)] = find(j); };

  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const a = list[i];
      const b = list[j];
      if (a.ch !== b.ch) continue;
      const sameName = a.name && b.name && a.name === b.name;
      if (sameName || sameRadio(a.bssid, b.bssid)) union(i, j);
    }
  }

  const groups = new Map();
  list.forEach((a, i) => {
    const r = find(i);
    if (!groups.has(r)) groups.set(r, []);
    groups.get(r).push(a);
  });

  const out = [];
  for (const members of groups.values()) {
    members.sort((x, y) => (y.rssi ?? -999) - (x.rssi ?? -999) || x.bssid.localeCompare(y.bssid));
    const bssids = members.map((m) => m.bssid);
    const ssids = [...new Set(members.map((m) => (m.hidden || !m.ssid ? "(versteckt)" : m.ssid)))];
    const named = members.find((m) => m.name);
    const txps = members.map((m) => m.txp).filter((v) => v !== null && v !== undefined);
    out.push({
      key: [...bssids].sort()[0] + "@" + members[0].ch,
      ch: members[0].ch,
      name: named ? named.name : "",
      bssids,
      ssids,
      rssi: Math.max(...members.map((m) => m.rssi ?? -999)),
      txp: txps.length ? Math.max(...txps) : null,
      ftm: members.some((m) => m.ftm),
      lastSeen: Math.max(...members.map((m) => m.lastSeen ?? 0)),
      aps: members,
    });
  }
  out.sort((a, b) => b.rssi - a.rssi);
  return out;
}

/** Bestes MAC-Ähnlichkeitsmaß einer Gruppe zu einer gesuchten MAC. */
export function groupScore(group, target) {
  return Math.max(0, ...group.bssids.map((b) => macScore(target, b)));
}

/** Partner-Funkmodule (anderes Band) desselben Geräts finden. */
export function findPartners(group, groups) {
  return groups.filter((g) => {
    if (g === group || g.key === group.key) return false;
    if (bandOfChannel(g.ch) === bandOfChannel(group.ch)) return false;
    if (group.name && g.name) return group.name === g.name;
    return g.bssids.some((b) => group.bssids.some((a) => macScore(a, b) >= 65));
  });
}

export function bandOfChannel(ch) {
  if (ch >= 1 && ch <= 14) return 2;
  if (ch >= 32) return 5;
  return 0;
}
