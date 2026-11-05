#!/bin/bash
# Natuerliche Stimme fuer KSS-Maps bereitstellen – nur in der CI, im Ordner kss-maps aufrufen:
#   bash tools/fetch_voice.sh <abi> [<abi> ...]        (Release: arm64-v8a, Emulator: x86_64)
#
#  1. Native Bibliotheken von sherpa-onnx v1.12.15 (Apache-2.0; enthaelt espeak-ng) fuer die
#     ABIs nach app/src/main/jniLibs/<abi>/ (nicht im Repo, siehe .gitignore)
#  2. Sprachpaket stimme-de-thorsten-medium.zip an der Vorabversion "kssmaps-stimme": fehlt es,
#     wird es EINMALIG aus dem Piper-Modell "Thorsten" (CC0) gebaut und hochgeladen. Danach wird
#     es nie ersetzt – die App prueft das Paket gegen die beim Bauen eingetragene Pruefsumme.
#  3. Pruefsumme und Groesse -> GITHUB_ENV (KSSMAPS_VOICE_SHA256 / KSSMAPS_VOICE_SIZE)
set -euo pipefail
SHERPA_VER=1.12.15
# Pruefsummen der Original-Downloads (leer: nur ins Protokoll schreiben)
LIBS_SHA256=""
MODEL_SHA256=""
MODEL=vits-piper-de_DE-thorsten-medium
TAG=kssmaps-stimme
ZIP=stimme-de-thorsten-medium.zip
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

# ---- 2. Sprachpaket
NAMES=$(gh release view "$TAG" --repo "$R" --json assets --jq '.assets[].name' 2>/dev/null || true)
if grep -qx "$ZIP" <<< "$NAMES"; then
  for i in 1 2 3 4; do
    if gh release download "$TAG" --repo "$R" --pattern "$ZIP" --dir "$W"; then break; fi
    sleep $((i * 5))
  done
  [ -s "$W/$ZIP" ] || { echo "::error::Sprachpaket an $TAG nicht ladbar"; exit 1; }
  echo "Sprachpaket von der Vorabversion $TAG"
else
  echo "Sprachpaket fehlt an $TAG – wird einmalig gebaut"
  curl -fsSL --retry 3 -o "$W/model.tar.bz2" \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL}.tar.bz2"
  check "$W/model.tar.bz2" "$MODEL_SHA256"
  mkdir -p "$W/model"
  tar -xjf "$W/model.tar.bz2" -C "$W/model"
  M=$(dirname "$(find "$W/model" -type f -name tokens.txt | head -1)")
  [ -d "$M/espeak-ng-data" ] || { echo "::error::espeak-ng-data fehlt im Modell"; ls -la "$M"; exit 1; }
  ls -la "$M"
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
  # gleiche Eingabe -> gleiches ZIP (feste Zeiten, feste Reihenfolge, ohne Zusatzfelder)
  (cd "$P" && find . -exec touch -h -d '2025-01-01 00:00:00' {} + \
    && find . -type f | sed 's|^\./||' | LC_ALL=C sort | zip -X -9 -q "$W/$ZIP" -@)
  NOTES="Sprachpaket für die eingebaute natürliche Stimme von KSS-Maps. Die App lädt es beim ersten Start einmalig selbst – nicht installieren, das ist keine App-Version. Inhalt und Lizenzen: LIZENZEN.txt im Paket."
  if ! gh release view "$TAG" --repo "$R" >/dev/null 2>&1; then
    gh release create "$TAG" --repo "$R" --prerelease --latest=false --target "$GITHUB_SHA" \
        --title "KSS-Maps Sprachpaket (natürliche Stimme)" --notes "$NOTES" \
      || gh release create "$TAG" --repo "$R" --prerelease --latest=false \
        --target "$(gh api "repos/$R" --jq .default_branch)" \
        --title "KSS-Maps Sprachpaket (natürliche Stimme)" --notes "$NOTES"
  fi
  gh release upload "$TAG" "$W/$ZIP" --repo "$R"
fi

# ---- 3. fuer den Build
SHA=$(sha256sum "$W/$ZIP" | cut -d' ' -f1)
SIZE=$(stat -c %s "$W/$ZIP")
unzip -l "$W/$ZIP" | tail -4
echo "Sprachpaket: $SIZE Bytes, sha256 $SHA"
{
  echo "KSSMAPS_VOICE_SHA256=$SHA"
  echo "KSSMAPS_VOICE_SIZE=$SIZE"
} >> "${GITHUB_ENV:-/dev/null}"
