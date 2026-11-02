---
name: waydroid-test
description: >-
  Run and manually test the MarkNotes Android app (and its instrumented tests) on WayDroid: check the setup, connect
  adb, install the debug APK, walk the offline-only checklist and read logcat. Use when asked to test the app, run
  it on a device or emulator, WayDroid, or after a UI, :app-shared or DriveConnector change.
---

# Testing on WayDroid

Follow `docs/waydroid.md`; do not duplicate it here.

1. Verify the prerequisites without changing the system: `command -v waydroid adb`, `waydroid status`,
   `adb devices`, and that an Android SDK exists (`$ANDROID_HOME` or `sdk.dir` in `local.properties`). Binder
   kernel support and installing packages need root: **stop and tell the user the exact commands** from
   `docs/waydroid.md` rather than running `sudo` or installing packages yourself.
2. Build with `./gradlew -q --console=plain :app:assembleDebug`, install with `adb install -r`, start the activity
   and capture `adb logcat -d`. Never run `scripts/test-release-apk.sh` or the instrumented tests on a device that
   holds data the user cares about: they clear app data.
3. Walk the offline-only checklist in `docs/waydroid.md` and report what you saw. If you cannot reach a device,
   say the app was not run; never describe a UI change as verified from unit tests alone.
4. Keep WayDroid results out of the repo; CI's emulator job remains the required check.
