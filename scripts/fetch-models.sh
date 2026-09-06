#!/usr/bin/env bash
# Downloads the TalTech streaming Zipformer into ./models/.
#
# The app downloads this itself on first run; this script is for development:
# it puts the files on your machine so you can sideload them (see --push) and
# skip the in-app download on every reinstall. models/ is gitignored.
#
# Usage:
#   ./scripts/fetch-models.sh            # download to ./models/
#   ./scripts/fetch-models.sh --push     # download, then push to a connected phone
set -euo pipefail

REPO="TalTechNLP/streaming-zipformer-large.et-en"
BASE="https://huggingface.co/$REPO/resolve/main"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/models/streaming-zipformer-large.et-en"
APP_ID="ee.kristjanr.dictation"

PUSH=0
[ "${1:-}" = "--push" ] && PUSH=1

mkdir -p "$DEST"

# int8 first — that is what we want on a phone. Whether TalTech ships quantised
# weights for the large model is unconfirmed (RESEARCH.md section 7), so fall
# back to float32 rather than failing.
fetch_first() {
  local dest_dir="$1"; shift
  for name in "$@"; do
    if [ -f "$dest_dir/$name" ]; then
      echo "    have $name"
      return 0
    fi
  done
  for name in "$@"; do
    echo "--> $name"
    if curl -fSL --retry 3 -C - -o "$dest_dir/$name.part" "$BASE/$name"; then
      mv "$dest_dir/$name.part" "$dest_dir/$name"
      return 0
    fi
    rm -f "$dest_dir/$name.part"
    echo "    not available, trying next"
  done
  echo "!! none of: $*" >&2
  return 1
}

echo "==> $REPO"
fetch_first "$DEST" encoder.int8.onnx encoder.onnx
fetch_first "$DEST" decoder.int8.onnx decoder.onnx
fetch_first "$DEST" joiner.int8.onnx joiner.onnx
fetch_first "$DEST" tokens.txt

echo "==> downloaded into $DEST"
du -sh "$DEST"

if [ "$PUSH" = "1" ]; then
  # filesDir is app-private, so the copy goes through run-as. Debug builds only.
  echo "==> pushing to $APP_ID"
  adb shell "run-as $APP_ID mkdir -p files/models/streaming-zipformer-large.et-en"
  for f in "$DEST"/*; do
    name="$(basename "$f")"
    echo "--> $name"
    adb push "$f" "/data/local/tmp/$name" >/dev/null
    adb shell "run-as $APP_ID cp /data/local/tmp/$name files/models/streaming-zipformer-large.et-en/$name"
    adb shell "rm /data/local/tmp/$name"
  done
  echo "==> pushed"
fi
