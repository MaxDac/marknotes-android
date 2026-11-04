# fdroiddata merge request: New app: MarkNotes

The live description of the open inclusion merge request,
[fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034), in the format the
reviewers accepted for [FPInk](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122). Keep it identical to
the MR description and in sync with the recipe and the latest release: reviewers check it. The
`fdroid-inclusion-mr` skill (`.claude/skills/fdroid-inclusion-mr/SKILL.md`) explains how to maintain the MR.

**Source branch**: `io.github.maxdac.marknotes.libre` on [MaxDac/fdroiddata](https://gitlab.com/MaxDac/fdroiddata)

**Title**: `New app: MarkNotes`

**Posting status**: The v0.3.0 description below is prepared; it is not live until the maintainer pastes it (no GitLab
access from the agent session) and the recipe commit is pushed. The v0.2.0 description still describes the Rust build.

**Files** (nothing else): `metadata/io.github.maxdac.marknotes.libre.yml`, a copy of `.fdroid.yml` with
`commit: vX.Y.Z` replaced by the tag's full commit hash (`git rev-parse "vX.Y.Z^{commit}"`).

**Description**:

```markdown
## New app: MarkNotes

MarkNotes (`io.github.maxdac.marknotes.libre`) is an offline-first Markdown notes app. Notes are plain `.md` files in folders, with note links, images, chord sheets and a rendered preview. They stay on the device; the same folder format works with the MarkNotes desktop and web apps. This edition has no cloud sync.

* Upstream: https://github.com/MaxDac/marknotes-android (GPL-3.0-or-later). I am the upstream author.
* Release: [`v0.3.0`](https://github.com/MaxDac/marknotes-android/releases/tag/v0.3.0), commit `b091b82225e56f9e9fc812b99938ca739eaa63df`, versionCode 300.
* Fastlane metadata (en-US title, summary, description, icon, screenshots, changelogs) lives upstream in `fastlane/metadata/android/en-US/`.
* Anti-features: none. There is no sync or other network feature, no tracking, analytics, ads or Google Play Services. Error diagnostics stay on the device unless the user reviews a report and sends it themselves.
* Permissions: `USE_BIOMETRIC` (optional app lock), plus AndroidX's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The APK requests no network permission: `INTERNET` and `ACCESS_NETWORK_STATE` are removed from its manifest. Upstream CI fails if the APK requests anything else.

### Build notes

* There are no submodules, srclibs or native build steps: the build is plain Gradle (`gradle: [yes]`, `subdir: app`) with dependencies from Google Maven, Maven Central and the Gradle Plugin Portal only. Local note handling is plain Kotlin in `:core`.
* There is no `rm`, `prebuild`, `build` or `ndk` entry.
* Native code is arm64-v8a and x86_64, under 1 MB per ABI, all from Maven Central: `libjnidispatch.so` from JNA 5.19.1 (LGPL-2.1/Apache-2.0, used for the no-replace rename of notes) and `libandroidx.graphics.path.so` from AndroidX (Compose). An ABI split would save little because the native libraries are a small part of the APK.
* Release builds use full-mode R8 optimization and resource shrinking. Upstream CI checks the final APK's R8 marker, exact permissions and exact native libraries, and exercises the actual minified APK on an emulator, including notes, storage and PDF import.

### Auto update and reproducible builds

* **Auto update:** `UpdateCheckMode: Tags` with `AutoUpdateMode: Version`. `versionName`/`versionCode` are literals in `app/build.gradle.kts`, set by the release workflow, and the build block has no version-specific values, so checkupdates can copy it unchanged.
* **Reproducible builds:** enabled. `Binaries` points at the signed GitHub release APK and `AllowedAPKSigningKeys` holds its certificate. Upstream CI builds the APK three ways on every change and requires identical results: the recipe's steps on a runner, the same at another path and time zone, and the real `fdroid build --on-server` in `fdroidserver:buildserver-trixie`. The release workflow publishes only if `apksigcopier compare` verifies the signed APK against that build.

### Validation

* `fdroid lint` and `fdroid rewritemeta` produce no changes.
* The fork pipeline for the v0.3.0 commit is pending (link added after it runs). The same recipe steps are built and compared in upstream CI (`fdroid build --on-server`), and the release workflow's `apksigcopier compare` against the signed APK passed: https://github.com/MaxDac/marknotes-android/actions/runs/41846713157.
* The remaining permission entries in the quality report are explained above; the Fastlane metadata, reproducible-build APK, signing key, ABI/size and R8-marker entries are informational.

## Checklist

### Policy

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy).
* [x] The original app author has been notified (and does not oppose the inclusion). I am the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure. The summary and description must be included and images, icon, and changelog should also be provided for better user experience. The `en-US` locale must be included.

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected. See <https://docs.gitlab.com/user/project/repository/branches/protected/>.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request. There are none.
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file.
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo. Please check the Changes tab to make sure there is no other unrelated files added in the MR.
* [x] Releases are tagged and auto update is enabled unless there is a special reason.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [x] External repos are added as git submodules instead of srclibs. Not applicable: the app has no external source repositories.
* [x] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds). We'll use your signature for improved security/reliability, also allowing users to switch between different channels. Do note that if you don't enable reproducible build then the apk will be signed with our key so you can't enable it later. If you can't enable this, please add the reasons here.
* [x] Setup abi split if the APK is large and the splitted ones can be much smaller. Not needed: the native libraries are under 1 MB per ABI.
* [x] Only the latest versions should be kept in the metadata before it's merged. If you update the metadata, please replace the old versions with the new ones.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash. Please don't use tag or branch in commit.

### Pipeline

* [x] All pipelines should pass.
* [x] All warnings and errors in the Reports tab should be fixed or explained.
* [x] F-Droid CI runners are under GitLab's FOSS program, so there's no need for you to pay for any CI time. If Gitlab starts asking for phone numbers or credit cards don't submit anything, just leave a note in the MR so we know we need to trigger the CI.
```