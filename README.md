# MarkNotes

Simple Android markdown notes app with folders, offline-first, and optional two-way sync with Dropbox.

This is the libre build of MarkNotes, published on [F-Droid](https://f-droid.org/packages/io.github.maxdac.marknotes.libre/)
and on [GitHub Releases](https://github.com/MaxDac/marknotes-android/releases): free software only, built from source,
reproducible. [MarkNotes Plus](https://github.com/MaxDac/MarkNotes) is the same app (it includes this repo as a git
submodule) with OneDrive and Proton Drive sync added, released on GitHub only. The two install side by side
(`io.github.maxdac.marknotes.libre` and `io.github.maxdac.marknotes`).

## Features

- **Offline-first notes**: every note is a `.md` file in a folder tree inside the app's private storage
  (`filesDir/notes`). Folders map to real directories.
- **Getting started and Markdown help**: a skippable guide appears once for new and existing installations.
  Skip, Finish, Close or Android Back dismisses it; replay it from **Settings → Help**. The practice editor is
  memory-only, never creates notes or attachments, and never syncs; it survives rotation but is discarded when
  the app locks, the guide is dismissed or the app process ends. The offline Markdown reference shows source and
  rendered examples, the toolbar's limits, and Android-specific link/image support. Both screens are shared with MarkNotes Plus.
- **Folder system**: create, open, rename, delete and **move** notes and folders (item menu → "Move to…").
  A navigation drawer shows the whole folder tree so you can jump straight to any folder.
- **Editor**: raw markdown editing with a toggle to a rendered preview (Markwon: tables, strikethrough,
  task lists, syntax-highlighted fenced code blocks via Prism4j). A formatting toolbar above the keyboard
  inserts bold, italic, strikethrough, headings, bullet/numbered lists, checkboxes, quotes, inline code,
  code blocks and links. Pressing Enter on a list, checkbox or quote line continues it on the next line;
  Enter on an empty item ends the list. Changes are autosaved (debounced, and flushed when leaving the editor).
- **Note links with `@`**: every note has a stable 8-character ID stored in a small front-matter header at the
  top of the file (hidden in the editor and preview). Typing `@` at the start of a line or after a space opens a
  panel listing every note with its full folder path and ID (with a copy button); keep typing to filter by ID
  or by title/path, and tap a note to insert `[Title](marknotes:<id>)`. In the preview, tapping the link opens
  the note; links to missing notes are shown struck through and tapping them says "Note not found".
- **Images**: the toolbar's image button adds a photo from the gallery (photo picker) or the camera. Images are
  downscaled to at most 2048 px, stored once under `attachments/` (named by content hash), shown in the preview
  and synced. Settings → **Clean unused images** deletes images no note refers to.
- **Import from other apps**: pick MarkNotes in Android's share sheet to turn shared text, web pages (HTML),
  `.txt`/`.md`/`.html` files, Word documents (`.docx`), PDFs (text only) or images into a new note, optionally
  converted to Markdown. Before saving, choose the title and destination from the existing folders, including
  nested folders or the MarkNotes root. Conversion runs on the device.
- **Share a note**: tap **Share note** in the editor or preview to send its current Markdown source as text through
  Android's share sheet to a compatible app. Chord notes can be shared from their list or text view; save or close
  a chord-builder draft first. Recent edits are included, but hidden front-matter metadata is not. Images remain
  Markdown references, not attached files, and internal note links need MarkNotes to resolve. The app you choose
  receives the note text and may keep or upload it.
- **Chord sequences** (guitar and bass, ported from [ChordBuilder](https://github.com/MaxDac/ChordBuilder)), opt-in:
  turn on **Settings → Chord sequences** (off by default, kept on this device only). Notes with `type: chords` then
  open in a chord editor with a fretboard builder and chord diagrams, and **New chord sequence** appears in the
  floating **New** menu next to **New note** and on the empty-folder screen. While it is off, opening a chord sequence
  asks whether to turn it on; the setting never changes your notes.
- **Appearance** (Settings): theme **System** (default), **Light** or **Dark**, and an accent colour chosen
  from 8 palettes or **Dynamic** (Material You, Android 12+).
- **Dropbox two-way sync** (optional): connect Dropbox accounts (Settings → Add drive), browse to a folder and
  optionally create a root folder inside it (default `MarkNotes`):
  - **Reconnect** on a drive card signs in to the same Dropbox account again, for example after its sign-in
    expired or was revoked, without removing the drive: its folder, options, sync history and base copies are
    kept. Signing in to a different account, cancelling or a failed sign-in leaves the drive as it was. Drives
    connected by an older version save their account identity at their next successful sync; if one's sign-in
    expired before that, its account can't be verified, so disconnect it and add it again;
  - sync runs on app launch and when you tap **Sync now**; there is no background or periodic polling.
    **Sync when reconnected** (Settings, off by default) also syncs when the network comes back while the app
    is open and unlocked (after 2 seconds, retrying temporary failures after 5, 15 and 30 seconds); it needs the
    `ACCESS_NETWORK_STATE` permission and never syncs while the app is closed;
  - only folders, `.md` files and images under `attachments/` are synced, plus a small manifest at
    `.marknotes/manifest.json` on the drive;
  - edits made on both sides are merged line by line (a three-way merge against the last synced version).
    When edits overlap, the newest version is kept and the other is saved next to it as
    `name (conflicted copy).md`; the **Sync conflicts** screen merges a copy back. The copy keeps the losing
    version's edit time, and a durable per-drive journal reuses its verified note ID after an interrupted sync;
  - new files and planned folders never replace or adopt a destination created by another device. Unchanged,
    record-backed remote case-only renames update the stale local spelling. Dropbox does not support a direct
    case-only remote move, so the opposite direction reports an explicit unsupported-operation error instead
    of attempting an unsafe temporary rename. Ambiguous or edited case collisions remain errors;
  - local case renames use kernel-enforced no-replace operations (Linux/Android `renameat2`, or an opened-file
    Windows rename), never a Java existence-check followed by a replacing move. Unsupported kernels/filesystems
    fail explicitly without moving anything. New local sync files/copies are privately staged with their exact
    edit time and flushed before kernel no-replace publication, so a partial copy is never mistaken for an edit;
  - folder removal never recursively deletes unseen entries. Dropbox has no atomic empty-only deletion API,
    so remote folder removal reports an explicit error and keeps its sync record for retry; known files are
    still deleted conditionally. Local sync folder removal is nonrecursive, including for hidden files;
  - the sync algorithm is the shared Rust core [marknotes-sync](https://github.com/MaxDac/marknotes-sync), also
    used by the desktop and web apps. Dropbox sign-in uses the official Dropbox SDK (OAuth 2 PKCE); tokens are
    stored encrypted with an Android Keystore AES-GCM key.
- **Delete local data** (Settings → Privacy): **Delete all local data** disconnects every drive, then deletes the
  notes, images, sync state and cached files stored on this device. When you disconnect the last drive you can
  also tick **Also delete the notes stored on this device**. Nothing is ever deleted from a drive: the deletion is
  local only and is not synced.

Dropbox is a proprietary network service, so F-Droid marks the app with the *NonFreeNet* anti-feature. Without a
Dropbox account the app is fully local.

### Security and privacy

- **Error diagnostics** (Settings): Java/Kotlin crashes and handled application errors are logged locally by
  default. Records contain only timestamps and fixed operation/failure categories, not exception messages,
  stack-frame strings, note contents, names, paths, URLs, accounts, credentials or device identifiers. Native
  crashes and ANRs are not captured. Private logs and exported snapshots together use at most 5 MiB; a bounded
  event history may discard older records sooner. Records expire after seven days when the app next starts,
  records an error or exports logs (nothing runs while the app is force-stopped). **Clear logs** removes local
  logs and snapshots without disabling future recording; **Delete all local data** also clears them.
- **Report a problem** first shows a local review, then asks permission to open a prefilled GitHub issue draft
  for `MaxDac/marknotes-android` (MarkNotes Plus uses `MaxDac/MarkNotes`). Opening the draft transmits the sanitized
  excerpt in a URL to GitHub **before submission**; browser history/sync and server logs may retain it. An issue
  is public after you submit it in the browser. The final encoded URL is limited to 2,000 ASCII characters;
  whole recent events are included in chronological order with an explicit omission count. There is no app
  GitHub authentication, automatic submission or backend upload. **Export full sanitized logs** instead shares
  offline text through Android's chooser. The receiving app can retain/upload its copy; clearing logs or making
  another export invalidates the local shared snapshot but cannot recall received copies.
- Notes live in the app's private storage, which other apps can't read. Android (10 and later, the minimum this
  app supports) encrypts it with file-based encryption tied to your screen lock. The app doesn't add its own note
  encryption on top: the notes stay plain files that the sync core and the other MarkNotes apps read, and a lost
  Keystore key would make them unreadable.
- App data is excluded from Android backups and device transfers, so notes and Dropbox tokens don't leave the
  device that way.
- The preview never loads remote images: it only shows image files stored on the device.
- Optional **app lock** (Settings → Privacy, off by default): opening MarkNotes asks for your fingerprint or face,
  with your screen lock (PIN, pattern or password) as fallback, through Android's own biometric prompt. The app
  locks on start, after a time without use (Immediately, 1, 5 or 15 minutes; 5 by default), when the screen turns
  off and with "Lock now"; the lock screen keeps the screen you were on. Turning the lock on or off asks for the same
  credential, it needs a device screen lock, and it is turned off (with a message) if the device's screen lock is
  removed. The app lock is a privacy screen over the UI, not encryption: it keeps someone holding your unlocked
  phone out of the app, but it does not protect notes on a rooted or compromised device, or against apps or tools
  with privileged access that can hook the app or read its storage. The notes on the device are protected by
  Android's file-based encryption, as above.
- **Hide notes in Recents and block screenshots** (Settings → Privacy) sets `FLAG_SECURE` on the app's windows, so
  Recents shows a blank card and screenshots and screen recordings are blocked. It is turned on with the app lock
  and can be switched on or off on its own.
- R8/ProGuard is off on purpose, so F-Droid can reproduce the release APK byte for byte.

The on-disk note format (IDs, note links, images, chord sequences) is shared with the desktop and web apps and
described in [marknotes-spec](https://github.com/MaxDac/marknotes-spec/blob/main/note-format.md), checked out at
`spec/` as a git submodule (`git submodule update --init`).

## Other MarkNotes apps

The same notes folder works with the other MarkNotes apps (**Settings → About** links to them; the browser opens
only when you tap a link):

- [MarkNotes Plus for Android (GitHub Releases)](https://github.com/MaxDac/MarkNotes/releases)
- [MarkNotes Desktop (GitHub Releases)](https://github.com/MaxDac/marknotes-electron/releases)
- [MarkNotes Web](https://marknotes.fly.dev)

## Support

If you would like to support development, you can [leave an optional one-off tip on Ko-fi](https://ko-fi.com/maxdac).
Tips unlock no features: MarkNotes remains free software with all features available without payment.
**Settings → About → Leave a tip** opens the same page in your external browser only when tapped.
There is no in-app payment processing, payment SDK or automatic contact with Ko-fi; any payment happens on
the external website under its own terms and privacy policy.

## Project layout

| Module | What it contains |
| --- | --- |
| `:core` | Pure Kotlin/JVM: local note store, sync engine (single and multi-drive) executing the plans of the shared [marknotes-sync](https://github.com/MaxDac/marknotes-sync) core, `RemoteDriveStore` interface. Fully unit tested. |
| `:app-shared` | Android library with the whole app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room, manual DI, the Dropbox store and sign-in. Providers plug in through `DriveConnector`; MarkNotes Plus adds OneDrive and Proton Drive this way. |
| `:app` | The libre application (`io.github.maxdac.marknotes.libre`): `LibreApp` with the Dropbox connector. |

## Build

Requirements: JDK 21+ (Android Studio's bundled JBR works) and the Android SDK with platform 37
(compileSdk 37, targetSdk 36, minSdk 29). The Gradle wrapper (9.8.2, AGP 9.4 with built-in Kotlin 2.4) is included:

```sh
./gradlew assembleDebug                      # build the debug APK
./gradlew ktlintCheck detekt lintDebug       # ktlint, detekt and Android Lint (ktlintFormat auto-fixes style)
./gradlew testDebugUnitTest :core:test       # unit tests (:core is a plain JVM module)
./gradlew :core:parityTest                   # marknotes-spec fixtures (git submodule update --init first)
./gradlew :app:connectedDebugAndroidTest :app-shared:connectedDebugAndroidTest # UI and image-loader tests on a device
```

By default the build downloads marknotes-sync (the Kotlin bindings and the native library) from its
[GitHub releases](https://github.com/MaxDac/marknotes-sync/releases), at the version in `gradle/libs.versions.toml`.

The current sync integration is the released **0.4.1**, not a required development source pin.
Gradle verifies the Kotlin JAR, host natives JAR and Android AAR against reviewed checked-in SHA256 entries.
An optional `gradle/marknotes-sync-source.properties` can pin an unreleased API; when present, CI builds it
with Rust 1.99.1, cargo-ndk 4.1.2 and NDK 30.0.16248370. `-Pmarknotes.sync.fromSource=<checkout>` overrides
that default. Source builds require a fresh ignored build receipt and validate the actual selected checkout.
No native binaries are committed. Development pins are rejected by signed release validation, not unsigned
`assembleRelease` development checks. See [the dependency map](docs/dependencies.md) for source receipts,
local experiments, offline resolution, checksum trust limits, Pro root integration and regression commands.

On Windows, use a Visual Studio developer terminal with Git Bash, the same pinned Rust/NDK tools, and
`READELF="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"`.
Install both Android targets for the pinned toolchain (`rustup target add --toolchain 1.99.1
aarch64-linux-android x86_64-linux-android`). Set `CARGO_TARGET_X86_64_PC_WINDOWS_MSVC_LINKER` to the
developer terminal's `link.exe` so Git Bash does not select `/usr/bin/link`.
Run `scripts/build-sync-from-source.sh` in Git Bash, then `.\gradlew.bat` for the Android build.
The source prerequisite packages the matching host library as well as both Android ABIs; an empty native
JAR/AAR is not a usable substitute.

The Dropbox app key is committed in `app/build.gradle.kts` (it is public by design: PKCE, no secret). To use your
own Dropbox app, pass `-Pmarknotes.dropboxAppKey=<key>` or set it in `~/.gradle/gradle.properties`; create the app
in the [Dropbox App Console](https://www.dropbox.com/developers/apps) with "Full Dropbox" access and the scopes
`account_info.read`, `files.metadata.read`, `files.content.read` and `files.content.write`.

### Building from source, like F-Droid

F-Droid doesn't accept prebuilt binaries, so it builds marknotes-sync from source too. On Linux, with rustup, the
Rust toolchain and NDK pinned in `scripts/build-sync-from-source.sh` and cargo-ndk, from the `marknotes-sync/`
submodule (checked out at the release tag `marknotesSync` in `gradle/libs.versions.toml` names):

```sh
rustup toolchain install 1.99.1 --profile minimal --target aarch64-linux-android,x86_64-linux-android
cargo +1.99.1 install cargo-ndk --version 4.1.2 --locked
sdkmanager "ndk;30.0.16248370"
git submodule update --init marknotes-sync
scripts/build-sync-from-source.sh
./gradlew :app:assembleRelease --no-build-cache -Pmarknotes.sync.fromSource=marknotes-sync
```

The F-Droid recipe is [`.fdroid.yml`](.fdroid.yml); see
[docs/fdroid.md](docs/fdroid.md) for how the app is published there.

### CI

The checks live in the reusable `.github/workflows/checks.yml` and are required by the `main` ruleset:
"Lint (ktlint, detekt, Android Lint)", "Unit tests", "Instrumented tests (emulator)", "Build debug APK",
"Parity (marknotes-spec fixtures)" and the three F-Droid checks:

- **F-Droid build** lints the recipe with `fdroid lint` (and checks it is in `fdroid rewritemeta` format and matches
  the app version and the pinned marknotes-sync version), builds marknotes-sync and the release APK from source the
  way F-Droid does, and scans the APK with `fdroid scanner`;
- **Reproducible build** rebuilds the release APK on another runner, at another path, in another time zone, and
  fails unless both APKs are identical;
- **F-Droid server build (fdroid build)** runs the real `fdroid build --on-server` with the recipe in F-Droid's
  buildserver image (`scripts/fdroid-server-build.sh`, needs Docker), as fdroiddata's CI does, and fails unless its
  APK is identical to the F-Droid build's. The Release workflow reruns all three before publishing.

`pr-ci.yml` runs them on every pull request (plus "Dependency review" and "actionlint"), and `main-ci.yml` on
`main`. `codeql.yml` runs CodeQL; `dependency-submission.yml` submits the resolved Gradle graph to feed Dependabot;
`dependabot-automerge.yml` auto-merges Dependabot patch and minor updates once the checks pass.

### Releasing

Releases are reproducible: F-Droid rebuilds each tagged version from source and, when its APK matches ours byte for
byte (apart from the signature), publishes the APK from GitHub Releases signed with our key. So users can switch
between the F-Droid and GitHub APKs without reinstalling.

1. Actions → **Prepare release** → Run workflow with the `version` (`X.Y.Z`) and the release notes (at most 500
   characters). It opens a PR that sets `versionName`/`versionCode` (`X*10000 + Y*100 + Z`) in
   `app/build.gradle.kts`, the build entry in `.fdroid.yml` and
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`. PRs opened with the default `GITHUB_TOKEN` don't
      trigger `pull_request` workflows, so it also dispatches PR CI on the release branch; its checks report on the
      PR's head commit.
2. Merge it, then run Actions → **Release** on `main`. It reruns the checks, builds marknotes-sync and the APK
   from source, signs it, checks the signer against `AllowedAPKSigningKeys` in `.fdroid.yml` and the
   `RELEASE_CERT_SHA256` variable, checks with `apksigcopier compare` that F-Droid's build (the Checks' F-Droid
   build) verifies against it, and publishes `marknotes-libre-X.Y.Z.apk` and `SHA256SUMS` in the `vX.Y.Z` release.
3. F-Droid's checkupdates bot sees the new tag (`UpdateCheckMode: Tags`) and builds it within a few days.

**Repository secrets**: `RELEASE_KEYSTORE_BASE64` (`base64 -w0 release.keystore`), `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. **Variable**:
`RELEASE_CERT_SHA256`, the SHA-256 of the signing certificate. The signing key must never change: F-Droid only
accepts the key in `AllowedAPKSigningKeys`, and Android refuses updates signed with another key.

**Rollback**: Android refuses to install a lower `versionCode` over a higher one, so roll forward with a fix
released as a higher version.

Locally, `assembleRelease` signs only when `MARKNOTES_KEYSTORE_PATH` (plus the matching password/alias env vars) is
set; otherwise it stays unsigned.

## License

MarkNotes is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License](LICENSE) as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version (GPL-3.0-or-later). It is distributed WITHOUT ANY WARRANTY.

Third-party dependencies remain under their own licenses. Importing uses [jsoup](https://jsoup.org) (MIT) for HTML
and [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0) for PDF text.
