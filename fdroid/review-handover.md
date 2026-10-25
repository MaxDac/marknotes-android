# F-Droid inclusion: handover for reviewer follow-up

This file gives an agent session what it needs to answer reviewer comments on
[fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034). The procedures are in the
`fdroid-inclusion-mr` skill (`.claude/skills/fdroid-inclusion-mr/SKILL.md`), the background in `docs/fdroid.md`
and the live MR description in `fdroid/inclusion-mr.md`. This file adds the state at handover, the history and the
pitfalls. Delete it with the other inclusion-only docs in
[#21](https://github.com/MaxDac/marknotes-android/issues/21) once the MR is merged.

## State at handover (2026-10-25)

| What | Value |
| --- | --- |
| MR | Open, no labels or reviewer comments. Metadata and live description are updated to v0.1.6; the public API confirms the description matches `fdroid/inclusion-mr.md` |
| Fork branch head | `ca389100d076e0567328cb03ce6be252d0ecd179`: one commit, `New app: MarkNotes`, same parent `25d23ee7ce82bd09c71f6579cd3ca2e7e7fb9117`; no rebase |
| Fork pipeline | [2987009671](https://gitlab.com/MaxDac/fdroiddata/-/pipelines/2987009671): all 9 jobs passed |
| `fdroid build` job | [17451134534](https://gitlab.com/MaxDac/fdroiddata/-/jobs/17451134534): "compared built binary to supplied reference binary successfully", unchanged allowed signer `ffafda02…70e4` |
| Release in the MR metadata | [v0.1.6](https://github.com/MaxDac/marknotes-android/releases/tag/v0.1.6), commit `db4eb433300476c6777919fdc6a903822c747252`, versionCode 106 |
| Release APK | `marknotes-libre-0.1.6.apk`, SHA-256 `7056829d6727a75f92142abb787c948ad18c9e04395ce9e8e73cf59984ddaa66`, 8,276,017 bytes (8.28 MB) |
| Release workflow | [40222414142](https://github.com/MaxDac/marknotes-android/actions/runs/40222414142): all release gates, signature pinning and signed/unsigned comparison passed |
| Quality report | Missing-R8 warning replaced with an informational full-mode release R8 marker; required permissions and metadata/signing/ABI entries remain explained |
| Signing certificate | SHA-256 `ffafda02fa9af06b1927095c8d843ae862173512d4cdaca09bbaaef84d1d70e4` (`AllowedAPKSigningKeys`, repo variable `RELEASE_CERT_SHA256`). It must never change |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_BIOMETRIC`, plus AndroidX's `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Checks / F-Droid build fails on anything else |
| Native libraries per ABI (arm64-v8a, x86_64) | `libmarknotes_sync.so` (built from source, about 0.7 MB), `libjnidispatch.so` (JNA 5.19.1 from Maven Central), `libandroidx.graphics.path.so` (AndroidX) |
| marknotes-sync | Submodule `marknotes-sync/` at tag v0.4.1 (`5e68e80ab6f0731767334e9e7e967fd01e793450`), catalog `marknotesSync = "0.4.1"` |
| Toolchain pins | Rust 1.99.1 (through fdroiddata's `rustup@1.29.2` srclib), cargo-ndk 4.1.2, NDK r30. Identical in `.fdroid.yml`, `scripts/build-sync-from-source.sh` and `.github/actions/setup-sync-toolchain` |
| `rm:` | `spec`, `marknotes-sync/spec`, `marknotes-sync/bindings/npm`, `marknotes-sync/package.json`, and the sync Kotlin bindings' `gradle-wrapper.jar`, `gradlew` and `gradlew.bat` |
| MarkNotes Pro | Last verified adoption: `libre/` at v0.1.5 ([MaxDac/MarkNotes#49](https://github.com/MaxDac/MarkNotes/pull/49)); this release does not update Pro's pin |

Before acting, check this is still true: compare the MR's head SHA and the latest GitHub release with the table.

## History

- v0.1.0 to v0.1.5 were released for this MR. Each release replaced the single build entry in the fork's metadata
  (amend and force-push) and the MR description.
- v0.1.2 requested `READ_PHONE_STATE` and the storage permissions: the marknotes-sync natives AAR had no
  `<uses-sdk>`, so the manifest merger added implied permissions. Fixed at the root in marknotes-sync, and CI now
  asserts the exact permission list.
- v0.1.5 adopted marknotes-sync v0.4.1 and strict Gradle dependency verification (#46,
  `gradle/verification-metadata.xml`, `gradle/marknotes-sync-fdroid-prune.txt`). The real fdroidserver deletes
  `gradlew`/`gradlew.bat` during its Gradle cleanup and scanner run, so those paths went into `rm:` and into the
  reviewed prune profile. The prune profile allows only deletions; `scripts/check-sync-pin.sh` rejects any other
  change to the sync checkout.
- v0.1.6 enabled full-mode R8 and resource shrinking, reducing the signed APK by 87.90% while preserving
  permissions, both ABIs and signing identity. The final minified APK is exercised on an emulator, and CI checks
  its R8 marker, native libraries and exact permissions. No sync/spec pins or public APIs changed.

## Lessons from FPInk (fdroid/fdroiddata!50122, same author, merged)

All requests below were addressed before submission. Keep them true, and expect the same reviewers (`linsui`,
`mezinster`, testers such as `junkerderprovinz`).

| Reviewer request on FPInk | Here |
| --- | --- |
| Turn on auto update | `AutoUpdateMode: Version`, `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$`; version literals in `app/build.gradle.kts` |
| Reproducible builds | `Binaries` plus `AllowedAPKSigningKeys`; checked in the fork pipeline and in three upstream CI jobs |
| `subdir` set, no `output` | `subdir: app`, no `output` |
| Full commit hash, not a tag | Yes, in the fork. `.fdroid.yml` keeps `vX.Y.Z` on purpose |
| Is `ndk:` needed? | Yes: r30 for cargo-ndk |
| Stale build steps left after an upstream change | None today. Re-check `rm`, `sudo`, `prebuild`, `build` and `gradleprops` against the tagged tree after every change |
| **MR description out of date** (FPInk updated the Fastlane text instead) | Update the description in `fdroid/inclusion-mr.md` **and** give it to the maintainer to paste into the MR. Verify afterwards: the MR's `description` field from the public API must contain the new version and pipeline id |
| Prebuilt native libraries, telemetry | Only JNA's and AndroidX's libraries are prebuilt, both from Maven repositories F-Droid trusts; no telemetry or trackers |
| `sudo` line suggestion (`apt-get install -y …`) | Ours already uses `apt-get update` and `apt-get install -y gcc libc-dev` |
| Testers: on-device behaviour, permissions requested when needed | Sync is optional (no network use until the user connects Dropbox); the biometric lock is opt-in |

## Pitfalls

- **GitLab access**: no token. Read with the public API and
  `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034/discussions.json` (the notes API answers 401 or an
  empty list anonymously). Raw job logs: `https://gitlab.com/MaxDac/fdroiddata/-/jobs/<id>/raw` with a browser
  user agent. Only the maintainer can comment, resolve threads or edit the description: hand over fenced
  copy-paste blocks.
- **Public report artifacts**: the job-detail/trace API can answer 401, and the archive-file endpoint can answer
  404 for report-only artifacts. Read logs through the web raw endpoint above. Download quality JSON through
  `https://gitlab.com/MaxDac/fdroiddata/-/jobs/<id>/artifacts/download?file_type=codequality`.
- **Pushing** works over SSH to `git@gitlab.com:MaxDac/fdroiddata.git` on the maintainer's machine. After
  `git push -f`, a "not our ref" message is harmless; confirm with `git ls-remote origin
  refs/heads/io.github.maxdac.marknotes.libre`.
- **Windows**: write the metadata file with LF and absolute paths (`[IO.File]::WriteAllText` resolves relative
  paths against the process directory, not PowerShell's location). Use `.\gradlew.bat`, and set `JAVA_HOME` to
  Android Studio's `jbr` for `apksigner`.
- **Two copies of the recipe**: `.fdroid.yml` (PR here, `commit: vX.Y.Z`) and the fork's metadata file (full
  hash). Apart from `commit`, keep them identical.
- **Upstream changes that alter the APK** need a new release (Prepare release, merge its PR, then Release on
  `main`). The release PR is opened by a bot, so its CI runs through a workflow_dispatch "PR CI" run. Roll forward
  only; versionCode can't go down.
- **marknotes-sync changes** need a sync release tag and the submodule moved to it in the same commit as the
  catalog bump (`scripts/fdroid-lint.sh` and `scripts/check-sync-pin.sh` check it), then
  `gradle/verification-metadata.xml` updated for the new assets.
- **Known CI flakes**: the emulator job ("Unable to connect to adb daemon") and Dependency submission's KSP plugin
  resolution. Rerun the failed jobs once before investigating.
- **Shared code**: `:core` and `:app-shared` are also built by MarkNotes Pro. Tell the Pro side (or open a Pro PR)
  when a public API or the dependency verification changes.

## Reply style

One reply per thread: what changed and where (commit, PR, release, pipeline and job links), or why it stays as
it is. Short, plain and factual, like the maintainer's FPInk replies. After a reply that promises a change, push
the change first, wait for the fork pipeline, and only then hand over the reply.
