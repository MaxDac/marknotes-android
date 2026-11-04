# MarkNotes

Simple Android markdown notes app with folders, offline-first. This libre edition has no cloud sync; sync with Dropbox, OneDrive and Proton Drive is in
[MarkNotes Plus](https://github.com/MaxDac/MarkNotes).

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
- **No cloud sync**: the notes stay on this device as plain `.md` files, so you can back them up or copy them
  yourself. Two-way sync (Dropbox, OneDrive, Proton Drive) with the same note format is in
  [MarkNotes Plus](https://github.com/MaxDac/MarkNotes), which builds on this repo's `:core` and `:app-shared`
  modules. Versions before 0.2.0 shipped Dropbox sync here.
- **Delete local data** (Settings → Privacy): **Delete all local data** disconnects every drive, then deletes the
  notes, images, sync state and cached files stored on this device. When you disconnect the last drive you can
  also tick **Also delete the notes stored on this device**. Nothing is ever deleted from a drive: the deletion is
  local only and is not synced.

The app is fully local and every dependency is free software, so it has no anti-features.

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
- App data is excluded from Android backups and device transfers, so notes and any cloud tokens (MarkNotes Plus) don't leave the
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
described in the shared spec, which lives in a private repository; this repository does not depend on it (see
[AGENTS.md](AGENTS.md), "Cross-app parity").

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
| `:core` | Pure Kotlin/JVM: local note store, note IDs and links, attachments, chord sequences, import conversion, diagnostics. Fully unit tested. |
| `:app-shared` | Android library with the whole app: Jetpack Compose + Material 3, Navigation Compose, MVVM with `StateFlow`, Room, manual DI. Sync plugs in through `SyncHooks`; MarkNotes Plus returns its own implementation (Dropbox, OneDrive, Proton Drive) this way. |
| `:app` | The libre application (`io.github.maxdac.marknotes.libre`): `LibreApp`, which has no sync (offline only). |

## Build

Requirements: JDK 17+ (CI uses 21; Android Studio's bundled JBR works) and the Android SDK with platform 37
(compileSdk 37, targetSdk 36, minSdk 29). The Gradle wrapper (9.8.2, AGP 9.4 with built-in Kotlin 2.4) is included:

```sh
./gradlew assembleDebug                      # build the debug APK
./gradlew ktlintCheck detekt lintDebug       # ktlint, detekt and Android Lint (ktlintFormat auto-fixes style)
./gradlew testDebugUnitTest :core:test       # unit tests (:core is a plain JVM module)
./gradlew :core:parityTest -Pmarknotes.specDir=<path>  # shared fixtures, if you have a checkout; otherwise skipped
./gradlew :app:connectedDebugAndroidTest :app-shared:connectedDebugAndroidTest # UI and image-loader tests on a device
```

The build is plain Gradle: no Rust, NDK, submodule or private repository is involved. See
[the dependency map](docs/dependencies.md).

### Building like F-Droid

F-Droid doesn't accept prebuilt binaries, and the build here has none of its own: with the JDK and the Android SDK,

```sh
./gradlew :app:assembleRelease --no-build-cache
```

builds the unsigned release APK that F-Droid rebuilds.

The F-Droid recipe is [`.fdroid.yml`](.fdroid.yml); see
[docs/fdroid.md](docs/fdroid.md) for how the app is published there.

### CI

The checks live in the reusable `.github/workflows/checks.yml` and are required by the `main` ruleset:
"Lint (ktlint, detekt, Android Lint)", "Unit tests", "Instrumented tests (emulator)", "Build debug APK",
and the three F-Droid checks:

- **F-Droid build** lints the recipe with `fdroid lint` (and checks it is in `fdroid rewritemeta` format and matches
  the app version), builds the release APK from source the way F-Droid does, and scans the APK with `fdroid scanner`;
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
2. Merge it, then run Actions → **Release** on `main`. It reruns the checks, builds the APK
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
