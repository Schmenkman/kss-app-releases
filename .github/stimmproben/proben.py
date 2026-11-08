#!/usr/bin/env python3
"""Hoerproben deutscher Offline-Stimmen fuer KSS-Maps (laeuft nur in der CI).

Erzeugt mit sherpa-onnx fuer jede Stimme dieselben Navigationsansagen, misst die Rechenzeit und
laesst jede Probe von Whisper (Spracherkennung, Deutsch) zuruecklesen – so laesst sich ohne
Zuhoeren pruefen, ob Zahlen und Strassennamen richtig gesprochen werden.
Ausgabe: out/stimme_NN_*.mp3, out/alle_stimmen.mp3, out/uebersicht.txt
"""
import os
import subprocess
import sys
import time

import numpy as np
import sherpa_onnx
import soundfile as sf
from scipy.signal import resample_poly

M = "models"
OUT = "out"
os.makedirs(OUT, exist_ok=True)

ST = f"{M}/sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
# Piper (espeak) liest Ziffern selbst richtig; Supertonic nicht immer (teils englisch) – dort
# schreibt die App Zahlen als deutsche Woerter aus (genau so hier).
TEXT = ("Stimme {nr}. In 300 Metern rechts abbiegen auf die Leopoldstraße. Danach links halten. "
        "Achtung, Stau in 2 Komma 5 Kilometern, etwa 8 Minuten Verzögerung. Ankunft gegen 14 Uhr 35.")
TEXT_WORDS = ("Stimme {nr}. In dreihundert Metern rechts abbiegen auf die Leopoldstraße. Danach links halten. "
              "Achtung, Stau in zwei Komma fünf Kilometern, etwa acht Minuten Verzögerung. Ankunft gegen vierzehn Uhr fünfunddreißig.")
WORDS = {1: "eins", 2: "zwei", 3: "drei", 4: "vier", 5: "fünf", 6: "sechs", 7: "sieben", 8: "acht",
         9: "neun", 10: "zehn", 11: "elf", 12: "zwölf", 13: "dreizehn"}

# Nummer, Name, Art, Modell bzw. Sprecher, Text, Rechenschritte (Supertonic)
VOICES = [
    (1, "Supertonic Sprecherin 1", "st", 0, TEXT_WORDS, 8),
    (2, "Supertonic Sprecherin 2", "st", 1, TEXT_WORDS, 8),
    (3, "Supertonic Sprecherin 3", "st", 2, TEXT_WORDS, 8),
    (4, "Supertonic Sprecherin 4", "st", 3, TEXT_WORDS, 8),
    (5, "Supertonic Sprecherin 5", "st", 4, TEXT_WORDS, 8),
    (6, "Supertonic Sprecher 1", "st", 5, TEXT_WORDS, 8),
    (7, "Supertonic Sprecher 2", "st", 6, TEXT_WORDS, 8),
    (8, "Supertonic Sprecher 3", "st", 7, TEXT_WORDS, 8),
    (9, "Supertonic Sprecher 4", "st", 8, TEXT_WORDS, 8),
    (10, "Supertonic Sprecher 5", "st", 9, TEXT_WORDS, 8),
    (11, "Thorsten (bisher)", "piper", "vits-piper-de_DE-thorsten-medium", TEXT, 0),
    (12, "Piper Kerstin", "piper", "vits-piper-de_DE-kerstin-low", TEXT, 0),
]
# nur Messung (nicht in alle_stimmen): wie schnell mit weniger Rechenschritten?
SPEED_TESTS = [(1, "Sprecherin 1 mit 4 Schritten", 0, 4), (1, "Sprecherin 1 mit 5 Schritten", 0, 5)]

_tts = {}


def tts_for(kind, model):
    key = (kind, model if kind == "piper" else "st")
    if key in _tts:
        return _tts[key]
    if kind == "st":
        mc = sherpa_onnx.OfflineTtsModelConfig(
            supertonic=sherpa_onnx.OfflineTtsSupertonicModelConfig(
                duration_predictor=f"{ST}/duration_predictor.int8.onnx",
                text_encoder=f"{ST}/text_encoder.int8.onnx",
                vector_estimator=f"{ST}/vector_estimator.int8.onnx",
                vocoder=f"{ST}/vocoder.int8.onnx",
                tts_json=f"{ST}/tts.json",
                unicode_indexer=f"{ST}/unicode_indexer.bin",
                voice_style=f"{ST}/voice.bin",
            ),
            num_threads=2, provider="cpu",
        )
    else:
        d = f"{M}/{model}"
        onnx = [f for f in os.listdir(d) if f.endswith(".onnx")][0]
        mc = sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(model=f"{d}/{onnx}", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"),
            num_threads=2, provider="cpu",
        )
    cfg = sherpa_onnx.OfflineTtsConfig(model=mc, max_num_sentences=1)
    if not cfg.validate():
        raise ValueError(f"Konfiguration ungueltig: {key}")
    t = sherpa_onnx.OfflineTts(cfg)
    _tts[key] = t
    return t


def synth(kind, model, text, steps=8):
    t = tts_for(kind, model)
    if kind == "st":
        g = sherpa_onnx.GenerationConfig()
        g.sid = model
        g.num_steps = steps
        g.speed = 1.0
        g.extra["lang"] = "de"
        return t.generate(text, g)
    return t.generate(text, sid=0, speed=1.0)


def asr():
    w = f"{M}/sherpa-onnx-whisper-small"
    if not os.path.isdir(w):
        return None
    return sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{w}/small-encoder.int8.onnx", decoder=f"{w}/small-decoder.int8.onnx",
        tokens=f"{w}/small-tokens.txt", language="de", task="transcribe", num_threads=4,
    )


def main():
    rec = asr()
    lines = []
    combined = []
    gap = np.zeros(int(24000 * 0.7), dtype=np.float32)
    for nr, name, kind, model, text, steps in VOICES:
        if kind == "st" and not os.path.isdir(ST):
            continue
        if kind == "piper" and not os.path.isdir(f"{M}/{model}"):
            lines.append(f"{nr:2d} {name}: Modell nicht verfuegbar")
            continue
        try:
            synth(kind, model, "Hallo.")  # aufwaermen
            t0 = time.time()
            a = synth(kind, model, text.format(nr=WORDS[nr] if kind == "st" else nr), steps or 8)
            dt = time.time() - t0
        except Exception as e:  # weiter mit der naechsten Stimme
            lines.append(f"{nr:2d} {name}: FEHLER {e}")
            continue
        s = np.asarray(a.samples, dtype=np.float32)
        sr = a.sample_rate
        dur = len(s) / sr
        peak = float(np.max(np.abs(s))) if len(s) else 0.0
        if peak > 0.01:
            s = s * (0.89 / peak)
        slug = name.lower().replace(" ", "_").replace("(", "").replace(")", "").replace(",", "")
        wav = f"{OUT}/stimme_{nr:02d}_{slug}.wav"
        sf.write(wav, s, sr)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-codec:a", "libmp3lame", "-b:a", "96k", wav[:-4] + ".mp3"], check=True)
        combined.append(resample_poly(s, 24000, sr).astype(np.float32))
        combined.append(gap)
        heard = ""
        if rec is not None:
            st = rec.create_stream()
            st.accept_waveform(sr, s)
            rec.decode_stream(st)
            heard = st.result.text.strip()
        lines.append(f"{nr:2d} {name}: {sr} Hz, {dur:.1f} s gesprochen, {dt:.2f} s gerechnet (Faktor {dt / dur:.2f})\n   gehoert: {heard}")
        print(lines[-1], flush=True)
        os.remove(wav)
    if os.path.isdir(ST):
        for nr, name, sid, steps in SPEED_TESTS:
            t0 = time.time()
            a = synth("st", sid, TEXT_WORDS.format(nr=WORDS[nr]), steps)
            dt = time.time() - t0
            dur = len(a.samples) / a.sample_rate
            lines.append(f"   Messung {name}: {dur:.1f} s gesprochen, {dt:.2f} s gerechnet (Faktor {dt / dur:.2f})")
            print(lines[-1], flush=True)
    if combined:
        sf.write(f"{OUT}/alle_stimmen.wav", np.concatenate(combined), 24000)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", f"{OUT}/alle_stimmen.wav", "-codec:a", "libmp3lame", "-b:a", "96k", f"{OUT}/alle_stimmen.mp3"], check=True)
        os.remove(f"{OUT}/alle_stimmen.wav")
    sizes = subprocess.run(["du", "-sh"] + sorted(os.path.join(M, d) for d in os.listdir(M)), capture_output=True, text=True).stdout
    with open(f"{OUT}/uebersicht.txt", "w", encoding="utf-8") as f:
        f.write("Text: " + TEXT.format(nr="N") + "\n\n")
        f.write("\n".join(lines) + "\n\nModellgroessen (ausgepackt):\n" + sizes)
    print(open(f"{OUT}/uebersicht.txt", encoding="utf-8").read())


if __name__ == "__main__":
    sys.exit(main())
