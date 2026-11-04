# AGENTS.md

Canonical instructions for coding agents (Claude Code, GitHub Copilot, others) working on **MarkNotes for
Android, libre build** (`io.github.maxdac.marknotes.libre`): offline only (no sync provider), every dependency free software,
published on F-Droid and GitHub Releases. `CLAUDE.md` and `.github/copilot-instructions.md` point here; keep agent
guidance in this file.

MarkNotes is one of several apps that share the same notes folder. The on-disk format must stay identical across
them (see [Cross-app parity](#cross-app-parity)). This repository contains no sync code: cloud sync lives in
MarkNotes Plus (private), which builds on this repository.

## Sibling repositories

| Repo | What | Stack |
| --- | --- | --- |
| [MaxDac/marknotes-android](https://github.com/MaxDac/marknotes-android) | This repo: Android app, libre build (offline only), on F-Droid | Kotlin, Gradle, Jetpack Compose |
| [MaxDac/MarkNotes](https://github.com/MaxDac/MarkNotes) | MarkNotes Plus (private; called "Pro" in code): this repo as the `libre/` submodule plus cloud sync (Dropbox, OneDrive, Proton Drive), GitHub releases only | Kotlin, Gradle |
| [MaxDac/marknotes-electron](https://github.com/MaxDac/marknotes-electron) | Desktop app (Windows, macOS, Linux) | Electron, TypeScript, CodeMirror |
| [MaxDac/marknotes-web](https://github.com/MaxDac/marknotes-web) | Web app | Phoenix (Elixir), TypeScript assets |

The shared sync library and the shared spec (note format, parity matrix and golden fixtures) are private
repositories. This repository does not depend on either: it has no submodules, and F-Droid and the public CI can
build it without access to them. The fixtures are run against `:core` by MarkNotes Plus, which has them.
Read the public sibling code with `gh` without cloning, for example
`gh api repos/MaxDac/marknotes-electron/contents/src -H "Accept: application/vnd.github.raw"` or
`gh search code --repo MaxDac/marknotes-web <term>`.

### This repo inside MarkNotes Plus

MaxDac/MarkNotes includes this repo as a submodule and builds `:core` and `:app-shared` from it, adding its own
`:app` and the sync modules. The extension point is `SyncHooks` (`app-shared/.../sync/SyncHooks.kt`): the screens
only use that interface, the libre app uses `NoSyncHooks` (no sync UI), and Plus returns its own implementation from
`MarkNotesApp.createSyncHooks`. So:

- keep `:core` and `:app-shared` free of sync code, of cloud providers and of non-free code, and never add a
  dependency on a private repository;
- changing a public API of `:core` or `:app-shared` (`SyncHooks`, `SyncHost`, `MarkNotesApp`, `AppContainer`,
  `MarkNotesDatabase`, ...) breaks MarkNotes Plus until it bumps the submodule and adapts: say so in the PR and
  follow up there;
- dependencies both apps use go in `gradle/libs.versions.toml` here; MarkNotes Plus reads this catalog as `libs`;
- the Room schema (`data/SyncDatabase.kt`) keeps the sync tables, so the database of users upgrading from versions
  that synced, and Plus, keep working.

## Build and test

Requirements: JDK 17+ (CI uses 21; Android Studio's JBR works) and the Android SDK with platform 37. On Windows use
`.\gradlew.bat` and set `JAVA_HOME`/`ANDROID_HOME` if they aren't set.
The wrapper is Gradle 9.8.2. Read [docs/dependencies.md](docs/dependencies.md) before changing dependencies or
release plumbing.

These mirror the required CI checks in `.github/workflows/checks.yml` (do not rename the jobs: the `main`
ruleset requires them by name):

| CI check | Command |
| --- | --- |
| Checks / Lint (ktlint, detekt, Android Lint) | `./gradlew ktlintCheck detekt lintDebug --continue` (`ktlintFormat` auto-fixes style) |
| Checks / Unit tests | `./gradlew testDebugUnitTest :core:test --continue` |
| Checks / Instrumented tests (emulator) | `./gradlew :app:connectedDebugAndroidTest :app-shared:connectedDebugAndroidTest` (needs a running emulator/device) |
| Checks / Build debug APK | `./gradlew assembleDebug` |
| Checks / F-Droid build | `scripts/fdroid-lint.sh` (needs fdroidserver), then the from-source build in the README, "Building from source, like F-Droid" (Linux), then `fdroid scanner --exit-code <apk>` |
| Checks / Reproducible build | The same from-source build twice at different paths; the unsigned APKs must be identical |
| Checks / F-Droid server build (fdroid build) | `scripts/fdroid-server-build.sh <out>` (Docker, full clone): the real `fdroid build` in F-Droid's buildserver image; its APK must equal the F-Droid build's |

PRs also run **Dependency review** (fails on high-severity vulnerabilities, after submitting the resolved
Gradle graph), **actionlint** and **CodeQL**. Required checks and review-thread resolution are enforced by the
`main` ruleset (the single source of truth; there is no classic branch protection). Fast inner loop for
`:core` changes: `./gradlew :core:test`.

## Architecture

| Module | Contents |
| --- | --- |
| `:core` | Pure Kotlin/JVM, no Android APIs. `notes/` (`LocalNoteStore`, `FrontMatter` note IDs, `NoteIndex` and note links, `NotePaths`, `Attachments`, `ChordSequence`), `Hashing`. All format logic lives here and is unit tested. |
| `:app-shared` | Android library with the app: Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room (the database schema, including the tables of earlier versions that synced), manual DI (`AppContainer`), the `SyncHooks` extension point (no sync ships here), encrypted token storage. |
| `:app` | The libre application: `LibreApp` (no sync, offline only), manifest, instrumented tests. |

Put new format, link or attachment logic in `:core` so it can be tested on the JVM and, where Plus has the
fixtures, against them.

## Conventions

- Kotlin style is enforced by ktlint (`android_studio` style, 120 columns, see `.editorconfig`) and detekt
  (`config/detekt/detekt.yml`). Run `ktlintFormat` before committing.
- Dependencies go in `gradle/libs.versions.toml`.
- Sync is not part of this repository. UI changes need an emulator run (the CI job, or WayDroid locally: see
  [docs/waydroid.md](docs/waydroid.md) and `.claude/skills/waydroid-test/SKILL.md`).
- Never commit secrets, keystores or `local.properties`. This app ships no cloud provider and no provider keys; those
  live in MarkNotes Plus.
- Fill in the PR template, including **Parity impact**.
- Nothing private in this public repository: no tokens, no content of the private repositories, no links that only
  work with access to them.

## F-Droid

F-Droid builds the app from source with the recipe in [`.fdroid.yml`](.fdroid.yml) (fdroiddata's copy,
`metadata/io.github.maxdac.marknotes.libre.yml`, takes precedence; see [docs/fdroid.md](docs/fdroid.md)). Rules:

- **Free software only**: no Google Play Services, Firebase, proprietary SDKs or analytics, and every Maven
  repository must be a free one F-Droid trusts (Google, Maven Central, Gradle Plugin Portal). The Checks / F-Droid
  build job runs `fdroid scanner` on the APK. This app has no anti-features; a cloud provider (a non-free network service) belongs in MarkNotes Pro, never here.
- **No prebuilt binaries, no native code of our own**: the build is plain Gradle (no Rust, no NDK, no submodules).
  The only native libraries in the APK are JNA's and AndroidX graphics-path's, from Maven Central
  (`scripts/check-release-apk.py` lists them).
- **Reproducible**: F-Droid ships our signed APK only if its rebuild matches. Keep `vcsInfo` and `dependenciesInfo`
  off, and don't add build inputs that depend on time, paths or the
  machine. The Checks / Reproducible build job catches regressions.
- **Inclusion MR**: while [fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034)
  is open, use the `fdroid-inclusion-mr` skill (`.claude/skills/fdroid-inclusion-mr/SKILL.md`, read by Claude Code
  and Copilot) to handle reviewer comments and to move the MR to each new release. Issue #21 removes it after the
  merge.
- **Version literals**: F-Droid reads `versionCode`/`versionName` from `app/build.gradle.kts`, so they stay literals
  (set by the Prepare release workflow). Store metadata is in `fastlane/metadata/android/en-US/` (one changelog per
  versionCode).

## Release and secrets

Releases are reproducible signed APKs: run **Prepare release** (`version`, notes), merge its PR, then run
**Release** on `main`. It builds from source exactly like F-Droid, signs with `RELEASE_KEYSTORE_BASE64`/
`RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD`, checks the signer against
`AllowedAPKSigningKeys` and the `RELEASE_CERT_SHA256` variable, verifies F-Droid's build against it with
`apksigcopier compare`, and publishes `marknotes-libre-X.Y.Z.apk` plus `SHA256SUMS` (same checksum file name as the
desktop and web releases). The signing key must never change. Roll forward only: `versionCode` can't go down.
Details in the README, "Releasing".

## Cross-app parity

These areas are shared with the desktop and web apps and must behave identically:

- note format: files and folders, front-matter and note IDs (alphabet, lazy assignment, duplicates);
- note links (`marknotes:<id>`, escaping, `@` picker matching, broken links);
- attachments (`attachments/<sha256>.<ext>`, accepted types, size limits, cleanup);
- chord sequences (`type: chords` detection, body grammar, canonical serialisation, validation, display names).

The contract (`note-format.md`, the `features.md` matrix and the golden fixtures) is in a private spec repository,
and the sync rules are in a private sync repository. Rules:

1. Changes to any of these land in the spec first, then are mirrored here **and** in marknotes-electron and
   marknotes-web (and in the sync repository / MarkNotes Plus for sync).
2. The fixtures are not available to this repository's CI: the parity tests (`CoreParity` in
   `core/src/test/.../parity/`, which maps each fixture file to the real `:core` function; `note-links/mention`
   runs in `:app-shared`'s `MentionParityTest`) **skip** without them. MarkNotes Plus has the fixtures and runs them
   against this repository's `:core`, so a parity regression is caught there. To run them yourself with a spec
   checkout: `./gradlew :core:parityTest -Pmarknotes.specDir=<path to the checkout>` (fails on an unmapped fixture
   file; `-Pmarknotes.parity.required=true` makes `:app-shared` fail instead of skip too). The `sync/`, `manifest/`
   and `merge/` fixtures are run by Plus's sync module, not here. A case this app can't satisfy yet gets a
   `knownDivergence.android` reason in the spec repo, never a silently weakened test here.
3. Features that aren't shared (for example Android-only UI) don't need spec changes, but update the
   `features.md` matrix in the spec when a user-visible feature is added, removed or changed.
