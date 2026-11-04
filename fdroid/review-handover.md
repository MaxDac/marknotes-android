# F-Droid inclusion: handover for reviewer follow-up

This file gives an agent session what it needs to answer reviewer comments on
[fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034). The procedures are in the
`fdroid-inclusion-mr` skill (`.claude/skills/fdroid-inclusion-mr/SKILL.md`), the background in `docs/fdroid.md`
and the live MR description in `fdroid/inclusion-mr.md`. This file adds the state at handover, the history and the
pitfalls. Delete it with the other inclusion-only docs in
[#21](https://github.com/MaxDac/marknotes-android/issues/21) once the MR is merged.

## State at handover (2026-11-04, v0.3.0)

| What | Value |
| --- | --- |
| MR | Open, label `New App`. On 2026-10-27 the reviewer `seekme-seekyou` closed it under the 3-month maintenance policy and the maintainer reopened it citing FPInk (!50122), offering to wait three months; no reply since. Metadata and live description are updated to v0.2.0; the public API confirms the description matches `fdroid/inclusion-mr.md`. An update comment is drafted for the maintainer to post; check the MR notes for whether it was |
| Fork branch head | `37d36e2618`: v0.3.0 recipe (`fc58f35a`) plus a rewritemeta formatting commit, pushed through the GitLab API on top of `146017843f`; no rebase |
| Fork pipeline | [3027648578](https://gitlab.com/MaxDac/fdroiddata/-/pipelines/3027648578): all 9 jobs passed |
| `fdroid build` job | [17739665023](https://gitlab.com/MaxDac/fdroiddata/-/jobs/17739665023): "compared built binary to supplied reference binary successfully" |
| Release in the MR metadata | [v0.3.0](https://github.com/MaxDac/marknotes-android/releases/tag/v0.3.0), commit `b091b82225e56f9e9fc812b99938ca739eaa63df`, versionCode 300 |
| Release APK | `marknotes-libre-0.3.0.apk`, 6,042,056 bytes (6.0 MB), checksum in the release's `SHA256SUMS`; signer `ffafda02…70e4` verified |
| Release workflow | [41846713157](https://github.com/MaxDac/marknotes-android/actions/runs/41846713157): all gates and the signed/unsigned comparison passed |
| Quality report | Missing-R8 warning replaced with an informational full-mode release R8 marker; required permissions and metadata/signing/ABI entries remain explained |
| Signing certificate | SHA-256 `ffafda02fa9af06b1927095c8d843ae862173512d4cdaca09bbaaef84d1d70e4` (`AllowedAPKSigningKeys`, repo variable `RELEASE_CERT_SHA256`). It must never change |
| Anti-features | None: no sync and no network feature since 0.2.0 (Dropbox moved to MarkNotes Plus, marknotes-android#61); `NonFreeNet` was removed from the recipe |
| Permissions | `USE_BIOMETRIC`, plus AndroidX's `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. `INTERNET` and `ACCESS_NETWORK_STATE` are removed since 0.3.0. Checks / F-Droid build fails on anything else |
| Native libraries per ABI (arm64-v8a, x86_64) | `libjnidispatch.so` (JNA 5.19.1 from Maven Central), `libandroidx.graphics.path.so` (AndroidX). Nothing of our own |
| Shared sync library | Not used any more: `.fdroid.yml` has no submodule, `srclibs`, `prebuild`, `build`, `ndk`, `rm` or `gradleprops`. The MR's metadata holds the same build entry as `.fdroid.yml` since 2026-11-04 |
| Toolchain pins | None: plain Gradle with the buildserver's JDK |
| `rm:` | None in `.fdroid.yml` (the MR's v0.2.0 recipe still lists the old sync paths) |
| MarkNotes Pro | Owns Dropbox since [MaxDac/MarkNotes#53](https://github.com/MaxDac/MarkNotes/pull/53); `libre/` re-pin to v0.2.0 in [MaxDac/MarkNotes#54](https://github.com/MaxDac/MarkNotes/pull/54) |

Before acting, check this is still true: compare the MR's head SHA and the latest GitHub release with the table.

## History

- v0.1.0 to v0.1.5 were released for this MR. Each release replaced the single build entry in the fork's metadata
  (amend and force-push) and the MR description.
- v0.1.2 requested `READ_PHONE_STATE` and the storage permissions: the shared sync library's natives AAR had no
  `<uses-sdk>`, so the manifest merger added implied permissions. Fixed at the root in that library, and CI now
  asserts the exact permission list.
- v0.1.5 adopted a newer sync library release and strict Gradle dependency verification (#46). The real fdroidserver
  deletes `gradlew`/`gradlew.bat` during its Gradle cleanup and scanner run, so those paths went into `rm:`. All of
  this is gone with the sync library (see below).
- v0.1.6 enabled full-mode R8 and resource shrinking, reducing the signed APK by 87.90% while preserving
  permissions, both ABIs and signing identity. The final minified APK is exercised on an emulator, and CI checks
  its R8 marker, native libraries and exact permissions. No sync/spec pins or public APIs changed.
- v0.1.7 fixed the folder drawer opening and white screen transitions in dark mode.
- v0.2.0 removed Dropbox sync (moved to MarkNotes Plus, marknotes-android#61): no sync provider, no `NonFreeNet`,
  APK 7.9 MB. The recipe's `AntiFeatures` block was removed; the build entry and `CurrentVersion` moved to 0.2.0 / 200.
- v0.3.0 removed the `INTERNET` and `ACCESS_NETWORK_STATE` permissions and the sync library: APK 6.0 MB, plain Gradle recipe.
- Before v0.3.0 the shared sync library and fixtures became private repositories, so libre dropped every dependency on
  them: no submodules, no Rust or NDK in the recipe, no extra native library of our own. Sync lives in MarkNotes Plus.

## Lessons from FPInk (fdroid/fdroiddata!50122, same author, merged)

All requests below were addressed before submission. Keep them true, and expect the same reviewers (`linsui`,
`mezinster`, testers such as `junkerderprovinz`).

| Reviewer request on FPInk | Here |
| --- | --- |
| Turn on auto update | `AutoUpdateMode: Version`, `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$`; version literals in `app/build.gradle.kts` |
| Reproducible builds | `Binaries` plus `AllowedAPKSigningKeys`; checked in the fork pipeline and in three upstream CI jobs |
| `subdir` set, no `output` | `subdir: app`, no `output` |
| Full commit hash, not a tag | Yes, in the fork. `.fdroid.yml` keeps `vX.Y.Z` on purpose |
| Is `ndk:` needed? | No longer: there is no native build (the v0.2.0 recipe needed r30 for cargo-ndk) |
| Stale build steps left after an upstream change | None today. Re-check `rm`, `sudo`, `prebuild`, `build` and `gradleprops` against the tagged tree after every change |
| **MR description out of date** (FPInk updated the Fastlane text instead) | Update the description in `fdroid/inclusion-mr.md` **and** give it to the maintainer to paste into the MR. Verify afterwards: the MR's `description` field from the public API must contain the new version and pipeline id |
| Prebuilt native libraries, telemetry | Only JNA's and AndroidX's libraries are prebuilt, both from Maven repositories F-Droid trusts; no telemetry or trackers |
| `sudo` line suggestion (`apt-get install -y …`) | The recipe no longer needs a `sudo` step (it installed gcc for the Rust build) |
| Testers: on-device behaviour, permissions requested when needed | No network use at all since 0.2.0 (no sync feature); the biometric lock is opt-in |

## Pitfalls

- **GitLab access**: no token. Read with the public API and
  `https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034/discussions.json` (the notes API answers 401 or an
  empty list anonymously). Raw job logs: `https://gitlab.com/MaxDac/fdroiddata/-/jobs/<id>/raw` with a browser
  user agent. Only the maintainer can comment, resolve threads or edit the description: hand over fenced
  copy-paste blocks. For 0.2.0 the maintainer lent a short-lived token with the `api` scope (revoked afterwards)
  and the agent pushed the recipe through `POST /projects/86842778/repository/commits` and edited the description;
  posting the comment was blocked by the session's write guard, so it stayed with the maintainer. Never copy
  descriptions from a terminal: line wrapping drops text. Pass a file instead.
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
  hash). Apart from `commit`, keep them identical, except that the fork's file is in `fdroid rewritemeta` format
  (`Binaries:` on its own line with the URL indented below). Build the fork's file from the fork's current copy,
  not from `.fdroid.yml`, or `rewritemeta` will report changes.
- **Upstream changes that alter the APK** need a new release (Prepare release, merge its PR, then Release on
  `main`). The release PR is opened by a bot, so its CI runs through a workflow_dispatch "PR CI" run. Roll forward
  only; versionCode can't go down.
- **Known CI flakes**: the emulator job ("Unable to connect to adb daemon") and Dependency submission's KSP plugin
  resolution. Rerun the failed jobs once before investigating.
- **Shared code**: `:core` and `:app-shared` are also built by MarkNotes Plus. Tell the Plus side (or open a Plus PR)
  when a public API (`SyncHooks`, `MarkNotesApp`, ...) changes.

## Reply style

One reply per thread: what changed and where (commit, PR, release, pipeline and job links), or why it stays as
it is. Short, plain and factual, like the maintainer's FPInk replies. After a reply that promises a change, push
the change first, wait for the fork pipeline, and only then hand over the reply.
