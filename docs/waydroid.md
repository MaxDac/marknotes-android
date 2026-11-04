# Testing on WayDroid

WayDroid runs Android in a Linux container on a Wayland desktop (Hyprland, Sway, GNOME, KDE). It is a fast way to
run the debug APK and the instrumented tests without Android Studio's emulator. It is **x86_64**, which this app
supports (`abiFilters` are `arm64-v8a` and `x86_64`; the sync native library is built for both).

WayDroid is not a replacement for the CI emulator job (`Checks / Instrumented tests (emulator)`): CI stays the
authority. Use WayDroid for the "UI changes need an emulator run" rule in `AGENTS.md` and for quick iteration.

## One-time setup (needs root, so a human does it)

1. Kernel: WayDroid needs the `binder_linux` module and `ashmem`/`memfd`. Check `zgrep -i binder /proc/config.gz` or
   `sudo modprobe binder_linux devices=binder,hwbinder,vndbinder`. If it fails, install a kernel that has binder
   (for example `linux-zen`, or the `binder_linux-dkms` package on Arch) and reboot.
2. Install `waydroid` (Arch/Omarchy: AUR `waydroid`, plus `lxc`, `dnsmasq`, `nftables`), then
   `sudo waydroid init` (vanilla image, **no GApps**: this app must be tested without Google services) and
   `sudo systemctl enable --now waydroid-container`.
3. Install `android-tools` (`adb`) and an Android SDK with platform 37 (`ANDROID_HOME`, or `sdk.dir` in the ignored
   `local.properties`). The Gradle build fails without it ("SDK location not found").
4. Start the UI session from the desktop (not over a plain TTY): `waydroid session start` then `waydroid show-full-ui`.

## Connect adb

```sh
waydroid status                        # note the IP address
adb connect <waydroid-ip>:5555         # enable "USB debugging" in Settings > Developer options once
adb devices                            # must list the device as "device", not "unauthorized"
```

If it stays `unauthorized`, accept the prompt in the WayDroid window, or run
`adb -s <ip>:5555 shell` after `waydroid shell` restarts adbd.

## Run the app

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.maxdac.marknotes.libre/io.github.maxdac.marknotes.MainActivity
```

(`waydroid app install <apk>` also works.) The debug build uses the same application id as release, so uninstall a
release build first, and remember data is local: `adb shell pm clear io.github.maxdac.marknotes.libre` resets it.

## Run the instrumented tests

```sh
./gradlew :app:connectedDebugAndroidTest :app-shared:connectedDebugAndroidTest
```

These tests wipe app data; use a WayDroid you can reset. `scripts/test-release-apk.sh` also clears data and must
only ever run on a disposable device (see `docs/fdroid.md`).

## Offline-only checklist (libre edition, no sync provider)

Walk through this after any UI, `:app-shared` or `DriveConnector` change:

- First launch: the Getting-started guide opens; its "Sync only if you want to" page says the edition is
  offline-only and lists no providers.
- Browser: create, rename, move and delete notes and folders; no **Sync now** toolbar button.
- Settings: no **Drives**, **Add drive**, **Sync now** or **Sync when reconnected** entries. Appearance, chord
  sequences, images, privacy (**Delete all local data**), app lock, diagnostics and About are present.
- Editor: autosave, preview, `@` note links, an image from the gallery, a shared text/PDF import.
- Rotate or resize the window: no crash, no lost edit.
- `adb logcat -d | grep -i marknotes` shows no exception from `SyncHooks` or `AppContainer` on start.

For MarkNotes Plus (the `libre/` submodule checkout in MaxDac/MarkNotes) also check that the drives UI is back
and that Dropbox sign-in returns through the `db-<key>` redirect.
