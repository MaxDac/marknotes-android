# Publishing on F-Droid

MarkNotes (libre) is published in the main [F-Droid](https://f-droid.org) repository as
`io.github.maxdac.marknotes.libre`. F-Droid builds every release from source on its own servers; because the build
is reproducible, it then publishes **our** signed APK from GitHub Releases (`Binaries:` in the recipe) after
checking that its own build matches it. The same APK is on F-Droid and GitHub, signed with the same key.

## Files

| File | Purpose |
| --- | --- |
| `.fdroid.yml` | The build recipe, in `fdroid rewritemeta` format so it can be copied to fdroiddata (only `commit:` changes, see below). |
| `marknotes-sync/` | Git submodule with [marknotes-sync](https://github.com/MaxDac/marknotes-sync) at the release tag `marknotesSync` in `gradle/libs.versions.toml` names, built from source by `scripts/build-sync-from-source.sh`. fdroiddata prefers submodules to srclibs. |
| `fdroid/inclusion-mr.md` | Title, description and checklist of the open inclusion merge request, [fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034). The `fdroid-inclusion-mr` agent skill (`.claude/skills/`) maintains it until it's merged. |
| `fastlane/metadata/android/en-US/` | Store listing: title, descriptions, icon, screenshots (`images/phoneScreenshots/1.png`...) and one changelog per versionCode. F-Droid reads it from the tagged commit; it doesn't go in the MR. |
| `scripts/fdroid-lint.sh` | Runs `fdroid lint` and `fdroid rewritemeta` against a minimal fdroiddata checkout, and checks the recipe matches the app version and the submodule is the `marknotesSync` tag. |
| `scripts/fdroid-scan-source.sh` | Runs fdroidserver's source scanner (what F-Droid runs before building) on a clean `git archive` copy of this repo with the marknotes-sync submodule, after the recipe's `rm:`. |
| `scripts/fdroid-server-build.sh` | Runs the real `fdroid build --on-server` with the recipe in F-Droid's buildserver image (Docker), like fdroiddata's CI, and writes the unsigned APK. |

## Prerequisites

- **Diagnostics**: the local logger and user-reviewed report/export flow use only existing free software
  dependencies and Android APIs. They require no new permissions, trackers, upload service or crash SDK. Runtime
  log timestamps are not APK build inputs. Keep diagnostics out of the notes/sync tree and preserve the narrow,
  non-exported FileProvider grants. The user explicitly consents before opening a GitHub draft (which sends its
  URL payload before submission); local capture/export works offline. This introduces no automatic telemetry
  and does not change the existing Dropbox `NonFreeNet` anti-feature or source-build recipe.
- **Licences**: F-Droid only builds free software, including every submodule. This app is GPL-3.0-or-later and
  marknotes-sync is GPL-3.0-or-later from v0.1.2 (its `LICENSE` file); keep both licensed. The `spec/` submodules
  (marknotes-spec, test fixtures only) have no licence, so the recipe deletes them with `rm:` before building.
- **No binaries in the source**: the scanner rejects committed jars, APKs and native libraries. The recipe's `rm:`
  also deletes the parts of marknotes-sync the Rust/Kotlin build doesn't use (`bindings/npm`, its root npm
  `package.json`, the Kotlin bindings' `gradle-wrapper.jar`, `gradlew` and `gradlew.bat`); the npm-only entry manifest
  has no root lockfile. The explicit wrapper-script entries also match the real buildserver's automatic cleanup;
  source receipts allow only these reviewed deletions, not arbitrary scanner-driven source edits.
  `scripts/fdroid-scan-source.sh` must report no problems and prints each fatal path, not just a count.
- **Signing key**: the release key lives outside the repo. Its certificate SHA-256 is in three places that must
  match: `AllowedAPKSigningKeys` in `.fdroid.yml`, the `RELEASE_CERT_SHA256` repository variable, and the keystore
  in the `RELEASE_KEYSTORE_BASE64` secret (README, "Releasing"). Keep an offline backup of the keystore and its
  passwords: losing it means F-Droid can't publish updates and users must reinstall. To create one (once):

  ```sh
  keytool -genkeypair -storetype PKCS12 -keystore marknotes-release.p12 -alias marknotes \
    -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=MaxDac, O=MarkNotes"
  keytool -list -v -keystore marknotes-release.p12 | grep SHA256   # lowercase, no colons, for the recipe
  ```

## First submission (once)

What fdroiddata reviewers asked for on [FPInk's inclusion MR](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122),
already applied here: auto-update (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`), reproducible builds
(`Binaries:` and `AllowedAPKSigningKeys`), `subdir: app` without `output:`, upstream code as submodules rather than
srclibs, `commit:` as a full hash, only the latest version in the metadata, LF line endings, no fastlane files in
the MR, and an MR description that always matches the current recipe.

1. Release `v0.1.0` from this repo (README, "Releasing"), so the tag and `marknotes-libre-0.1.0.apk` exist.
2. Fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata) on GitLab, create a branch named
   `io.github.maxdac.marknotes.libre` and add `metadata/io.github.maxdac.marknotes.libre.yml`: a copy of
   `.fdroid.yml` with `commit: v0.1.0` replaced by the tag's full commit hash (`git rev-parse v0.1.0^{commit}`).
   Nothing else: no srclibs, no fastlane files.
3. Check locally with fdroidserver (`pip install fdroidserver`) in the fdroiddata checkout:
   `fdroid lint io.github.maxdac.marknotes.libre` and `fdroid rewritemeta io.github.maxdac.marknotes.libre` (no diff).
4. Push the branch: the fork's pipeline runs `fdroid build`, which must pass and log "compared built binary to
   supplied reference binary successfully" (the reproducible-build check against `marknotes-libre-0.1.0.apk`).
5. Open the merge request with the title, description and checklist in `fdroid/inclusion-mr.md`, linking that
   pipeline and job.
6. Apply reviewers' changes to `.fdroid.yml` here too, so the two stay identical, and update the MR description
   whenever the recipe or the release changes (reviewers check it). If a new version is released before the merge,
   update the MR to it.

After the merge, F-Droid builds the app within a few days and it appears in the client after the next index update.

## New releases

Nothing to do on F-Droid: the recipe has `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, so F-Droid's
checkupdates bot notices the new `vX.Y.Z` tag, copies the last build entry with the new version and commit, and
builds it. It reads `versionName`/`versionCode` from `app/build.gradle.kts`, which is why they are literals. A new
marknotes-sync version needs nothing either: it comes with the tagged commit's submodule.

Update fdroiddata by hand (a small MR) when the build itself changes:

- the Rust toolchain, cargo-ndk or NDK pins, the `rm:` list or the build commands;
- the signing key (avoid: users would have to reinstall).

`scripts/fdroid-lint.sh` fails in CI when `.fdroid.yml` drifts from the app version or the submodule from
`marknotesSync`.

## Donation link metadata

The optional **Settings → About → Leave a tip** entry opens `https://ko-fi.com/maxdac` in an external browser
only after a tap. Tips unlock nothing; there are no payment SDKs, embedded payment pages, reminders or automatic
requests to Ko-fi. Payments are handled by the external website, not the app.

`.fdroid.yml` includes the same URL in its `Donate` field (a scalar for one URL, as required by the pinned
fdroidserver formatter). The upstream README and `.github/FUNDING.yml` also
identify this support destination so F-Droid reviewers can verify it. Changing the local recipe does **not**
publish or update the F-Droid listing: the official metadata is maintained separately in fdroiddata at
`metadata/io.github.maxdac.marknotes.libre.yml`. Include the `Donate` field in the inclusion submission if it is
still pending, or submit a metadata update if the app has already been accepted. Preserve fdroiddata's current
build entries and commit hashes; do not overwrite them with the local recipe. A new release tag alone does not
synchronize donation metadata.

## Reproducibility

F-Droid compares its unsigned build with our signed APK using `apksigcopier compare`. Anything that differs between
builds breaks this and F-Droid then stops publishing updates. What keeps the build reproducible:

- the native library: a pinned Rust toolchain (`1.99.1`), cargo-ndk (`4.1.2`) and NDK (`r30`, `30.0.16248370`),
  with `--remap-path-prefix` for the source and cargo paths, no build ID, and `SOURCE_DATE_EPOCH`
  (`scripts/build-sync-from-source.sh`);
- the APK: no VCS info (`vcsInfo.include = false`) and no dependency metadata block (`dependenciesInfo`), so
  nothing depends on the commit, path or machine.

Three CI checks guard it, on every PR, on `main` and again in the Release workflow before publishing:

- **Checks / F-Droid build** builds the unsigned APK the way the recipe does, on a GitHub runner;
- **Checks / Reproducible build** rebuilds it on a second runner at another path and in another time zone and
  requires identical APKs;
- **Checks / F-Droid server build (fdroid build)** runs the real `fdroid build --on-server` with the recipe in
  F-Droid's `buildserver-trixie` image (the tag fdroiddata's CI uses; the job logs the digest) and requires an
  APK identical to the F-Droid build's.

The Release workflow then runs `apksigcopier compare` between the signed release APK and the F-Droid build, which is
exactly the check F-Droid runs before publishing.