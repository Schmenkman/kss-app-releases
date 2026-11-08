#!/usr/bin/env python3
"""Hoerproben der Testfahrt mit Whisper (sherpa-onnx) zuruecklesen – nur in der CI.

Zeigt ohne Zuhoeren, ob jede eingebaute Stimme verstaendlich und deutsch spricht (Zahlen,
Strassennamen). Aufruf: erkenne_stimme.py <whisper-small-ordner> <wav> [<wav> ...]
"""
import sys
import wave

import numpy as np
import sherpa_onnx


def read_wav(path):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1:
            raise ValueError("nur 16 Bit mono")
        rate = w.getframerate()
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32) / 32768.0
    return pcm, rate


def pieces(s, rate, longest=25.0, shortest=12.0):
    """Whisper hoert hoechstens 30 s am Stueck: an der leisesten Stelle zwischen 12 und 25 s teilen."""
    frame = max(1, int(rate * 0.05))
    out = []
    while len(s) > longest * rate:
        lo, hi = int(shortest * rate) // frame, int(longest * rate) // frame
        energy = [float(np.mean(s[i * frame:(i + 1) * frame] ** 2)) for i in range(lo, hi)]
        cut = (lo + int(np.argmin(energy))) * frame + frame // 2
        out.append(s[:cut])
        s = s[cut:]
    out.append(s)
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    d = sys.argv[1]
    rec = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{d}/small-encoder.int8.onnx", decoder=f"{d}/small-decoder.int8.onnx",
        tokens=f"{d}/small-tokens.txt", language="de", task="transcribe", num_threads=4,
    )
    for path in sys.argv[2:]:
        try:
            s, rate = read_wav(path)
        except Exception as e:  # weiter mit der naechsten Probe
            print(f"{path}: nicht lesbar ({e})")
            continue
        text = []
        for p in pieces(s, rate):
            st = rec.create_stream()
            st.accept_waveform(rate, p)
            rec.decode_stream(st)
            text.append(st.result.text.strip())
        print(f"{path}: {len(s) / rate:.1f} s, {rate} Hz\n  gehoert: {' '.join(text)}\n", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
