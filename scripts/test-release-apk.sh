#!/usr/bin/env bash
# Run the platform-only smoke runner against the actual optimized APK on a disposable emulator.
set -euo pipefail

apk="${1:?Usage: test-release-apk.sh <unsigned-release.apk>}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Android SDK is required}}"
build_tools="$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
if [ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]; then
  echo "Release smoke tests clear app data and must run on a disposable emulator." >&2
  exit 1
fi

work="$(mktemp -d)"
trap 'rm -f "$work/smoke.apk" "$work/smoke.apk.idsig" "$work/tests.apk" "$work/tests.apk.idsig" "$work/smoke.keystore" "$work/result.txt"; rmdir "$work"' EXIT
# Cached debug APKs do not guarantee that this runner has the keystore that signed them.
keytool -genkeypair -keystore "$work/smoke.keystore" -storepass android -keypass android \
  -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA -keysize 2048 -validity 2 -storetype PKCS12 -noprompt
for pair in "$apk:smoke.apk" "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk:tests.apk"; do
  input="${pair%:*}"
  output="${pair##*:}"
  java -jar "$build_tools/lib/apksigner.jar" sign --ks "$work/smoke.keystore" \
    --ks-pass pass:android --key-pass pass:android --out "$work/$output" "$input"
done
for package in io.github.maxdac.marknotes.libre.test io.github.maxdac.marknotes.libre; do
  installed="$(adb shell pm list packages "$package" | tr -d '\r')"
  if grep -qx "package:$package" <<< "$installed"; then
    adb uninstall "$package"
  fi
done
adb install -r "$work/smoke.apk"
adb install -r "$work/tests.apk"
adb shell am instrument -w \
  io.github.maxdac.marknotes.libre.test/io.github.maxdac.marknotes.ReleaseSmokeInstrumentation \
  | tr -d '\r' | tee "$work/result.txt"
# am instrument may exit zero even when instrumentation fails.
grep -qx 'RELEASE_SMOKE_OK' "$work/result.txt"
adb uninstall io.github.maxdac.marknotes.libre.test
adb uninstall io.github.maxdac.marknotes.libre
