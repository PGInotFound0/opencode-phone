#!/usr/bin/env bash
# Build the OpenCode Bridge APK without Gradle: aapt2 -> kotlinc -> d8 -> apksigner.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"

# Toolchain layout (bundled under ./sdk, or override via env):
#   $SDK/platforms/android-34/android.jar
#   $SDK/build-tools/34.0.0/{aapt2,d8,apksigner,zipalign}
#   $SDK/kotlinc/bin/kotlinc
SDK="${BRIDGE_SDK:-$ROOT/sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-34/android.jar"
KC="$SDK/kotlinc/bin/kotlinc"
APP="$ROOT/app"
OUT="${BRIDGE_BUILD:-$ROOT/build}"
APK="${BRIDGE_APK:-$ROOT/OpenCodeBridge.apk}"

if [ ! -f "$AJ" ] || [ ! -x "$BT/aapt2" ] || [ ! -x "$KC" ]; then
  echo "Toolchain not found under: $SDK" >&2
  echo "Expected: platforms/android-34, build-tools/34.0.0 (aapt2/d8/apksigner/zipalign), kotlinc" >&2
  echo "Set BRIDGE_SDK=/path/to/sdk or run ./toolchain.sh to download it." >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT"

echo "== 1/6 aapt2 compile resources"
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"

echo "== 2/6 aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest "$APP/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 \
  --java "$OUT/gen" \
  "$OUT/res.zip"

echo "== 3/6 kotlin compile"
"$KC" \
  $(find "$APP/src" -name '*.kt' | sort) \
  "$OUT/gen/com/opencode/bridge/R.java" \
  -classpath "$AJ" \
  -jvm-target 17 -Xjdk-release=17 \
  -d "$OUT/classes" 2>&1 | grep -vE "^\s*$|warning:" || true
test -d "$OUT/classes/com/opencode/bridge" || { echo "kotlinc produced no classes"; exit 1; }

echo "== 4/6 dex"
jar cf "$OUT/app-classes.jar" -C "$OUT/classes" .
"$BT/d8" --release --lib "$AJ" --min-api 26 --output "$OUT" \
  "$OUT/app-classes.jar" \
  "$SDK/kotlinc/lib/kotlin-stdlib.jar" \
  "$SDK/kotlinc/lib/annotations-13.0.jar"
test -f "$OUT/classes.dex" || { echo "d8 produced no dex"; exit 1; }

echo "== 5/6 package + align"
zip -j -q "$OUT/base.apk" "$OUT/classes.dex"
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== 6/6 sign"
KS="${BRIDGE_KEYSTORE:-$OUT/debug.keystore}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey \
    -keypass android -dname "CN=OpenCode Bridge Debug" -keyalg RSA -keysize 2048 \
    -validity 10000 > /dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey \
  --key-pass pass:android --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK" | head -3
echo "BUILT: $APK ($(du -h "$APK" | cut -f1))"
