// Einstellungen (lokal im Gerät gespeichert).
const KEY = "apfinder.settings.v1";

export const DEFAULTS = {
  antenna: "dir",      // dir = Richtantenne (Panel), omni = Rundstrahler
  axis: "back",        // Hauptkeule zeigt aus: back = Rückseite, top = Oberkante
  gain24: 8,           // Antennengewinn Richtantenne 2,4 GHz (dBi)
  gain5: 10,           // Antennengewinn Richtantenne 5 GHz (dBi)
  gainOmni: 2,         // Rundstrahler (dBi)
  attenDb: 0,          // eingesetztes Dämpfungsglied (dB)
  ceilingM: 3.0,       // Höhe der (abgehängten) Decke
  handM: 1.3,          // Höhe der Antenne beim Messen
  nLo: 2.0,            // Pfadverlust-Exponent: Freiraum
  nHi: 3.0,            // … verbaut
  probeHz: 10,         // aktive Probe Requests pro Sekunde (0 = nur passiv)
  countData: false,    // Datenframes des APs mitzählen
  sound: true,
  fineTone: false,     // Ton relativ zur Spitze statt absolut
  bands: "25",
  recentMacs: [],
};

let cache = null;

export function settings() {
  if (cache) return cache;
  let stored = {};
  try {
    stored = JSON.parse(localStorage.getItem(KEY) || "{}") || {};
  } catch { stored = {}; }
  cache = { ...DEFAULTS, ...stored };
  return cache;
}

export function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(settings()));
  } catch { /* privater Modus o. ä. */ }
}

export function set(key, value) {
  settings()[key] = value;
  save();
}

export function rememberMac(mac) {
  const s = settings();
  s.recentMacs = [mac, ...s.recentMacs.filter((m) => m !== mac)].slice(0, 8);
  save();
}

/** Antennengewinn für einen Kanal gemäß Einstellungen */
export function antennaGain(ch) {
  const s = settings();
  if (s.antenna !== "dir") return s.gainOmni;
  return ch >= 1 && ch <= 14 ? s.gain24 : s.gain5;
}
