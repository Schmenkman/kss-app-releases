#!/bin/bash
# Natuerliche Stimmen fuer KSS-Maps bereitstellen – nur in der CI, im Ordner kss-maps aufrufen:
#   bash tools/fetch_voice.sh <abi> [<abi> ...]        (Release: arm64-v8a, Emulator: x86_64)
#
#  1. Native Bibliotheken von sherpa-onnx (Apache-2.0; enthaelt espeak-ng) fuer die ABIs nach
#     app/src/main/jniLibs/<abi>/ (nicht im Repo, siehe .gitignore)
#  2. Sprachpakete an der Vorabversion "kssmaps-stimme" – fehlt eins, wird es EINMALIG aus dem
#     Original gebaut und hochgeladen, danach nie ersetzt (die App prueft gegen die beim Bauen
#     eingetragene Pruefsumme):
#       stimme-de-thorsten-medium.zip  Piper "Thorsten" (CC0)
#       stimme-supertonic-3.zip        Supertonic 3 (10 Stimmen, 5 weiblich, OpenRAIL-M)
#  3. Pruefsummen und Groessen -> GITHUB_ENV (KSSMAPS_VOICE_* bzw. KSSMAPS_VOICE_ST_*)
set -euo pipefail
SHERPA_VER=1.13.8
# Pruefsummen der Original-Downloads; ein anderer Inhalt unter derselben Adresse bricht den Schritt
# ab (leer: nur ins Protokoll schreiben – beim ersten Build ermitteln und eintragen)
LIBS_SHA256=""
MODEL_SHA256="50487d9c95fdf2191f31d2588569381063ba1591dcd4c7d4bdd30f12b2191714"
MODEL_ST_SHA256=""
MODEL=vits-piper-de_DE-thorsten-medium
MODEL_ST=sherpa-onnx-supertonic-3-tts-int8-2026-05-11
TAG=kssmaps-stimme
R="${GITHUB_REPOSITORY:?nur in der CI}"
W="${RUNNER_TEMP:-/tmp}/stimme"
[ $# -ge 1 ] || { echo "Aufruf: $0 <abi> [<abi> ...]"; exit 2; }
rm -rf "$W"
mkdir -p "$W"

check() { # Datei, erwartete Pruefsumme (leer = nur anzeigen)
  local got
  got=$(sha256sum "$1" | cut -d' ' -f1)
  if [ -n "$2" ] && [ "$got" != "$2" ]; then
    echo "::error::Pruefsumme falsch: $(basename "$1") $got"
    exit 1
  fi
  echo "sha256 $(basename "$1"): $got"
}

# ---- 1. native Bibliotheken
curl -fsSL --retry 3 -o "$W/libs.tar.bz2" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VER}/sherpa-onnx-v${SHERPA_VER}-android.tar.bz2"
check "$W/libs.tar.bz2" "$LIBS_SHA256"
mkdir -p "$W/libs"
tar -xjf "$W/libs.tar.bz2" -C "$W/libs"
for abi in "$@"; do
  src=$(find "$W/libs" -type f -path "*/$abi/libsherpa-onnx-jni.so" | head -1)
  if [ -z "$src" ]; then
    echo "::error::libsherpa-onnx-jni.so fuer $abi fehlt"
    find "$W/libs" -maxdepth 3 | head -40
    exit 1
  fi
  d=$(dirname "$src")
  dst="app/src/main/jniLibs/$abi"
  mkdir -p "$dst"
  cp "$d/libsherpa-onnx-jni.so" "$d/libonnxruntime.so" "$dst/"
  # weitere benoetigte Bibliotheken aus demselben Paket (z. B. libc++_shared.so)
  for need in $(readelf -d "$dst"/*.so 2>/dev/null | sed -n 's/.*Shared library: \[\(.*\)\].*/\1/p' | sort -u); do
    if [ -f "$d/$need" ] && [ ! -f "$dst/$need" ]; then
      cp "$d/$need" "$dst/"
      echo "+ $need"
    fi
  done
  echo "== $abi"
  ls -la "$dst"
  readelf -d "$dst/libsherpa-onnx-jni.so" | grep NEEDED || true
done

# ---- 2. Sprachpakete
NAMES=$(gh release view "$TAG" --repo "$R" --json assets --jq '.assets[].name' 2>/dev/null || true)
NOTES="Sprachpakete für die eingebauten natürlichen Stimmen von KSS-Maps. Die App lädt das gewählte Paket einmalig selbst – nicht installieren, das ist keine App-Version. Inhalt und Lizenzen: LIZENZEN.txt im jeweiligen Paket."

# gleiche Eingabe -> gleiches ZIP (feste Zeiten, feste Reihenfolge, ohne Zusatzfelder)
pack_zip() { # Ordner, ZIP
  (cd "$1" && find . -exec touch -h -d '2025-01-01 00:00:00' {} + \
    && find . -type f | sed 's|^\./||' | LC_ALL=C sort | zip -X -9 -q "$2" -@)
}

build_thorsten() { # ZIP
  curl -fsSL --retry 3 -o "$W/model.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
  check "$W/model.tar.bz2" "$MODEL_SHA256"
  mkdir -p "$W/model"
  tar -xjf "$W/model.tar.bz2" -C "$W/model"
  M=$(dirname "$(find "$W/model" -type f -name tokens.txt | head -1)")
  [ -d "$M/espeak-ng-data" ] || { echo "::error::espeak-ng-data fehlt im Modell"; ls -la "$M"; exit 1; }
  P="$W/paket"
  mkdir -p "$P"
  cp "$M"/*.onnx "$M/tokens.txt" "$P/"
  cp -r "$M/espeak-ng-data" "$P/"
  # Aussprache-Woerterbuecher: nur Deutsch (+ Englisch fuer englische Woerter)
  find "$P/espeak-ng-data" -maxdepth 1 -type f -name '*_dict' ! -name de_dict ! -name en_dict -delete
  for f in MODEL_CARD README.md LICENSE; do
    if [ -f "$M/$f" ]; then cp "$M/$f" "$P/"; fi
  done
  cat > "$P/LIZENZEN.txt" <<'TXT'
Sprachpaket der eingebauten Stimme von KSS-Maps

- Stimme "Thorsten" (Piper-Modell de_DE-thorsten-medium): Thorsten-Voice von Thorsten Mueller,
  CC0 1.0 (gemeinfrei) – https://github.com/thorstenMueller/Thorsten-Voice, https://github.com/rhasspy/piper
- Umgewandelt fuer sherpa-onnx (Apache-2.0): https://github.com/k2-fsa/sherpa-onnx
- espeak-ng-data (Aussprache-Regeln): espeak-ng, GPL-3.0 – https://github.com/espeak-ng/espeak-ng
TXT
  pack_zip "$P" "$1"
}

build_supertonic() { # ZIP
  curl -fsSL --retry 3 -o "$W/st.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL_ST}.tar.bz2"
  check "$W/st.tar.bz2" "$MODEL_ST_SHA256"
  mkdir -p "$W/st"
  tar -xjf "$W/st.tar.bz2" -C "$W/st"
  M=$(dirname "$(find "$W/st" -type f -name voice.bin | head -1)")
  P="$W/paket_st"
  mkdir -p "$P"
  for f in duration_predictor.int8.onnx text_encoder.int8.onnx vector_estimator.int8.onnx vocoder.int8.onnx \
           tts.json unicode_indexer.bin voice.bin; do
    [ -f "$M/$f" ] || { echo "::error::$f fehlt im Supertonic-Modell"; ls -la "$M"; exit 1; }
    cp "$M/$f" "$P/"
  done
  for f in LICENSE README.md; do
    if [ -f "$M/$f" ]; then cp "$M/$f" "$P/"; fi
  done
  cat > "$P/LIZENZEN.txt" <<'TXT'
Sprachpaket "Supertonic 3" fuer KSS-Maps (10 Stimmen: 5 Sprecherinnen, 5 Sprecher; 31 Sprachen)

- Modell: Supertonic 3 von Supertone Inc., OpenRAIL-M-Lizenz –
  https://huggingface.co/Supertone/supertonic-3 (Lizenz: https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE)
  Nutzung nur im Rahmen der dort genannten Einschraenkungen (keine schaedliche oder irrefuehrende Verwendung).
- Beispielcode/Umwandlung: MIT (Supertone Inc.), sherpa-onnx (Apache-2.0) – https://github.com/k2-fsa/sherpa-onnx
TXT
  pack_zip "$P" "$1"
}

ensure_pack() { # ZIP, Bau-Funktion, Variablen-Praefix
  local zip=$1 build=$2 envp=$3
  if grep -qx "$zip" <<< "$NAMES"; then
    for i in 1 2 3 4; do
      if gh release download "$TAG" --repo "$R" --pattern "$zip" --dir "$W"; then break; fi
      sleep $((i * 5))
    done
    [ -s "$W/$zip" ] || { echo "::error::$zip an $TAG nicht ladbar"; exit 1; }
    echo "$zip von der Vorabversion $TAG"
  else
    echo "$zip fehlt an $TAG – wird einmalig gebaut"
    "$build" "$W/$zip"
    if ! gh release view "$TAG" --repo "$R" >/dev/null 2>&1; then
      gh release create "$TAG" --repo "$R" --prerelease --latest=false --target "$GITHUB_SHA" \
          --title "KSS-Maps Sprachpakete (natürliche Stimmen)" --notes "$NOTES" \
        || gh release create "$TAG" --repo "$R" --prerelease --latest=false \
          --target "$(gh api "repos/$R" --jq .default_branch)" \
          --title "KSS-Maps Sprachpakete (natürliche Stimmen)" --notes "$NOTES"
    fi
    gh release upload "$TAG" "$W/$zip" --repo "$R"
  fi
  local sha size
  sha=$(sha256sum "$W/$zip" | cut -d' ' -f1)
  size=$(stat -c %s "$W/$zip")
  unzip -l "$W/$zip" | tail -3
  echo "$zip: $size Bytes, sha256 $sha"
  {
    echo "${envp}_SHA256=$sha"
    echo "${envp}_SIZE=$size"
  } >> "${GITHUB_ENV:-/dev/null}"
}

ensure_pack stimme-de-thorsten-medium.zip build_thorsten KSSMAPS_VOICE
ensure_pack stimme-supertonic-3.zip build_supertonic KSSMAPS_VOICE_ST
