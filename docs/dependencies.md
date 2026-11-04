# Android dependency map

This is the local map of what this repository consumes and exports. It has no dependency on any private
repository: no submodules, no private package registry, no prebuilt binary of our own.

## What this repository consumes and exports

| Boundary | Authoritative input | Consumer |
| --- | --- | --- |
| Libraries | `gradle/libs.versions.toml` (Google Maven, Maven Central, Gradle Plugin Portal only) | `:core`, `:app-shared`, `:app` |
| Native libraries | JNA (`libjnidispatch.so`, for the no-replace rename in `core/notes/CaseRename.kt`) and AndroidX graphics-path, both from Maven Central | The release APK; `scripts/check-release-apk.py` lists the exact set |
| Plus code and catalog | This repo's `:core`, `:app-shared`, `gradle/libs.versions.toml` | `MaxDac/MarkNotes` (MarkNotes Plus, private) includes them through its `libre/` submodule and owns its root settings and everything sync |
| Shared fixtures | Not consumed here; the parity tests skip without a checkout (`-Pmarknotes.specDir=<path>`) | MarkNotes Plus runs them against `:core` |
| Android release | `app/build.gradle.kts`, fastlane changelog, `.fdroid.yml` | Signed GitHub APK and F-Droid rebuild; never change signing identity |

Cloud sync (the shared sync library, the sync engine and the cloud providers) is not in this repository. The screens
reach it only through `SyncHooks`, which has a no-op default in the libre app. Public APIs of `:core` and
`:app-shared` can break Plus: coordinate its submodule update explicitly. There is no automatic downstream PR
creation.

## Building

JDK 17+ (CI uses 21), the Gradle wrapper (**9.8.2**) and the Android SDK with platform 37. The build is plain
Gradle: no Rust, NDK or submodule is needed, which is also what F-Droid's build server runs.

```sh
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug
./gradlew :app:assembleRelease --no-build-cache
```

## Updating a dependency

Change `gradle/libs.versions.toml`, run the checks above and `scripts/fdroid-lint.sh`. A new native library in the
APK needs a deliberate change of `scripts/check-release-apk.py` and its test. Keep the Maven repositories to the
free ones F-Droid trusts. Publication, merging, tagging, signing and deploys each require explicit authorization.
