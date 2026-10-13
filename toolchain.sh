#!/usr/bin/env bash
# Downloads the build toolchain (Android build-tools 34, platform 34, Kotlin 2.1.20)
# into ./sdk — roughly 1 GB, once. Requires JDK 17+ on PATH.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"
SDK="$ROOT/sdk"
DL="${TMPDIR:-/tmp}/opencode-phone-dl"
mkdir -p "$SRC_DIR" 2>/dev/null || SRC_DIR=""
DL_DIR="$DL"
mkdir -p "$DL_DIR" "$SDK"

BT="$SDK/build-tools/34.0.0"
KOTLINC="$SDK/kotlinc/bin/kotlinc"
AJ="$SDK/platforms/android-34/android.jar"

have() { [ -n "$(command -v "$1" 2>/dev/null)" ]; }

if [ -f "$AJ" ] && [ -x "$BT/aapt2" ] && [ -x "$KOTLINC" ]; then
  echo "Toolchain already present."
  exit 0
fi

dl() { # dl <url> <out>
  echo "↓ $2"
  curl -fL --retry 3 -o "$DL_DIR/$2" "$1"
}

if ! have curl; then
  echo "curl is required" >&2; exit 1
fi

# 1) android.jar (platform 34)
if [ ! -f "$AJ" ]; then
  echo "-- installing android platform 34"
  dl https://dl.google.com/android/repository/cmdline-tools/cmdline-tools/latest.zip 2>/dev/null || dl https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip cmdtools.zip
  unzip -q -o "$DL_DIR/cmdtools.zip" -d "$SDK/.tmp-cmd"
  mv "$SDK/.tmp-cmd/cmdline-tools" "$SDK/staging"
  mkdir -p "$SDK/cmdline-tools/latest"
  mv "$SDK/staging/"* "$SDK/cmdline-tools/latest/" 2>/dev/null || true
  RMG_FLAG=""
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "platforms;android-34" > "$DL_DIR/sdkmanager.log" 2>&1 || { tail -5 "$DL_DIR/sdkmanager.log"; exit 1; }
  rm -rf "$SDK/.tmp-cmd" "$SDK/staging"
fi

# 2) build tools 34
if [ ! -x "$BT/aapt2" ]; then
  echo "-- installing build-tools 34"
  dl https://dl.google.com/android/repository/build-tools_r34-linux.zip buildtools.zip
  unzip -q -o "$DL_DIR/buildtools.zip" -d "$SDK/.tmp-bt"
  mkdir -p "$BT"
  mv "$SDK/.tmp-bt/"* "$BT/"
  rm -rf "$SDK/.tmp-bt"
fi

# 3) kotlin compiler
if [ ! -x "$KOTLINC" ]; then
  echo "-- installing kotlinc 2.1.20"
  dl https://github.com/JetBrains/kotlin/releases/download/v2.1.20/kotlin-compiler-2.1.20.zip kotlinc.zip
  unzip -q -o "$DL_DIR/kotlinc.zip" -d "$SDK"
fi

# 4) zip/jar/keytool sanity
have keytool || { echo "keytool missing — install JDK 17+"; exit 1; }
have zip || { echo "zip missing"; exit 1; }

echo "TOOLCHAIN READY -> $SDK"
echo "Next:  ./build.sh"
