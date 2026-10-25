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
trap 'rm -f "$work/smoke.apk" "$work/smoke.apk.idsig" "$work/result.txt"; rmdir "$work"' EXIT
java -jar "$build_tools/lib/apksigner.jar" sign --ks "$HOME/.android/debug.keystore" \
  --ks-pass pass:android --key-pass pass:android --out "$work/smoke.apk" "$apk"
adb install -r "$work/smoke.apk"
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm clear io.github.maxdac.marknotes.libre
adb shell am instrument -w \
  io.github.maxdac.marknotes.libre.test/io.github.maxdac.marknotes.ReleaseSmokeInstrumentation \
  | tr -d '\r' | tee "$work/result.txt"
# am instrument may exit zero even when instrumentation fails.
grep -qx 'RELEASE_SMOKE_OK' "$work/result.txt"
