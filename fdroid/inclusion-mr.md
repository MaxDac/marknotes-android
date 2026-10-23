# fdroiddata merge request: New App: MarkNotes

Text for the first fdroiddata merge request (*App inclusion* template). See `docs/fdroid.md`, "First submission".

**Title**: `New App: io.github.maxdac.marknotes.libre`

**Files**:

- `metadata/io.github.maxdac.marknotes.libre.yml`: a copy of `.fdroid.yml`;
- `srclibs/marknotes-sync.yml`: a copy of `fdroid/srclibs/marknotes-sync.yml`.

**Description**:

```markdown
MarkNotes is an offline-first Markdown notes app: notes are plain `.md` files in folders, with note links,
images, chord sheets and a rendered preview. Sync with Dropbox is optional; the same notes folder is shared
with the MarkNotes desktop (Electron) and web apps.

* License: GPL-3.0-or-later (app) and GPL-3.0-or-later (marknotes-sync srclib, from v0.1.2).
* Source: https://github.com/MaxDac/marknotes-android
* Anti-features: NonFreeNet (optional Dropbox sync). No tracking, analytics, ads or Google Play Services.

Build notes:

* The sync core is [marknotes-sync](https://github.com/MaxDac/marknotes-sync) (Rust, UniFFI Kotlin bindings),
  added as a new srclib and built from source with a pinned Rust toolchain (rustup srclib), cargo-ndk and NDK
  r27d; the unused npm bindings and the Kotlin bindings' gradle-wrapper.jar are removed in prebuild.
* The build is reproducible: `Binaries:` points to the signed APK on GitHub Releases and
  `AllowedAPKSigningKeys` is set, so F-Droid can publish the developer-signed APK. Upstream CI rebuilds the APK
  twice at different paths and compares them, and the release workflow checks the signed APK against the
  F-Droid-style build with `apksigcopier compare`.
* Store metadata (descriptions, icon, screenshots, changelogs) is in `fastlane/` in the source repo.

- [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
- [x] The original app author has been notified (I am the author)
- [x] All related fdroiddata and RFP issues have been referenced in this merge request
- [x] Builds with `fdroid build` and all pipelines pass
```
