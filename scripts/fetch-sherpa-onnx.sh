#!/usr/bin/env bash
# Vendors sherpa-onnx into the app source tree.
#
# sherpa-onnx is not published to Maven Central, so the upstream Android demos
# copy two things into the project: the prebuilt JNI libraries and the Kotlin
# API sources that bind to them. We do the same, pinned to one release, rather
# than committing ~15 MB of .so files to git.
#
# Usage: ./scripts/fetch-sherpa-onnx.sh [version]
set -euo pipefail

VERSION="${1:-1.13.7}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs"
API_DIR="$ROOT/app/src/main/java/com/k2fsa/sherpa/onnx"
ABIS=("arm64-v8a")

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "==> sherpa-onnx v$VERSION"

echo "--> prebuilt JNI libraries"
tarball="sherpa-onnx-v$VERSION-android.tar.bz2"
curl -fSL --retry 3 -o "$work/$tarball" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$tarball"
tar xjf "$work/$tarball" -C "$work"

rm -rf "$JNI_DIR"
mkdir -p "$JNI_DIR"
for abi in "${ABIS[@]}"; do
  if [ ! -d "$work/jniLibs/$abi" ]; then
    echo "!! release has no $abi libraries" >&2
    exit 1
  fi
  mkdir -p "$JNI_DIR/$abi"
  # README.md files in the archive are documentation, not libraries.
  cp "$work/jniLibs/$abi"/*.so "$JNI_DIR/$abi/"
  echo "    $abi: $(ls "$JNI_DIR/$abi" | wc -l) libraries"
done

echo "--> Kotlin API sources"
git -c advice.detachedHead=false clone --quiet --depth 1 --branch "v$VERSION" --filter=blob:none --sparse \
  https://github.com/k2-fsa/sherpa-onnx.git "$work/src"
git -C "$work/src" sparse-checkout set sherpa-onnx/kotlin-api >/dev/null

rm -rf "$API_DIR"
mkdir -p "$API_DIR"
cp "$work/src/sherpa-onnx/kotlin-api"/*.kt "$API_DIR/"
echo "    $(ls "$API_DIR" | wc -l) source files"

cat > "$JNI_DIR/README.md" <<EOF
Prebuilt sherpa-onnx v$VERSION libraries, fetched by scripts/fetch-sherpa-onnx.sh.
Not committed to git. Apache 2.0, see https://github.com/k2-fsa/sherpa-onnx.
EOF

cat > "$API_DIR/README.md" <<EOF
Kotlin API sources vendored from sherpa-onnx v$VERSION by
scripts/fetch-sherpa-onnx.sh. Not committed to git, not edited here.
Apache 2.0, see https://github.com/k2-fsa/sherpa-onnx.
EOF

echo "==> done. Now: ./gradlew assembleDebug"
