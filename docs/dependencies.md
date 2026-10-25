# Android dependency map

<!-- marknotes-workflow: dependency-handbook-v1 -->

This is the **local map**, not another cross-repo policy. Common dependency/release workflows and ownership
live in [marknotes-spec](https://github.com/MaxDac/marknotes-spec), `docs/dependencies.md` and the
`marknotes-dependencies` / `marknotes-release` skills. Load them through the local same-name skill entrypoints.
An unmerged handbook is not an approved fallback; never bump the fixture submodule merely to obtain instructions.

## What this repository consumes and exports

| Boundary | Authoritative input | Consumer |
| --- | --- | --- |
| Shared sync API/native code | `gradle/libs.versions.toml` `marknotesSync=0.4.0`; `marknotes-sync/` gitlink at `48bdbdeda451963154d3a8defb7efc0d9a6f4cbb` (`v0.4.0`) | `:core` Kotlin JAR + host natives JAR for JVM tests; `:app-shared` Android AAR |
| Published sync bytes | Root `gradle/verification-metadata.xml`, all three assets | Native Gradle dependency verification, strict by default |
| Temporary unreleased sync API | Optional `gradle/marknotes-sync-source.properties`, `version=` + exact `commit=` matching catalog/gitlink | Defaults to source mode; currently **absent**, not a required setup step |
| Format/parity contract | `spec/` gitlink, JSON fixtures | Core parity tests and `:app-shared` mention tests; not a package registry |
| Pro code/catalog | This repo's `:core`, `:app-shared`, `gradle/libs.versions.toml`, settings helper | `MaxDac/MarkNotes` includes them via `libre/`; Pro owns its root settings and root verification metadata |
| Android release | `app/build.gradle.kts`, fastlane changelog, `.fdroid.yml` | Signed GitHub APK and F-Droid rebuild; never change signing identity |

Android does not consume the sync npm package; desktop/web and Rust changes belong to their respective
repositories. Provider-neutral shared APIs can break Pro: coordinate its gitlink/API updates explicitly.
There is no automatic downstream PR creation.

## Published assets and trust

`gradle/marknotes-sync.settings.gradle` exclusively routes `io.github.maxdac.marknotes` to the selected
source repository **or** GitHub releases, never Maven Central or a fallback repository. Other groups retain
their existing routing and are explicitly outside this verification policy. Gradle downloads/caches artifacts;
there is no custom download cache. Artifact-only Ivy has no POM/Ivy metadata to authenticate.

The checked-in SHA256 entries cover `marknotes-sync-kotlin-0.4.0.jar`,
`marknotes-sync-natives-0.4.0.jar`, and `marknotes-sync-android-0.4.0.aar`. Bootstrap on 2026-10-25 downloaded
the three GitHub release assets and matched their hashes to that release's `SHA256SUMS` (also agreeing with
GitHub asset digests). **This is authorized trust on first use, not independently authenticated provenance.**
Review subsequent release identity/checksum changes; do not generate metadata from an unexplained failing download.

Warm caches work with `--offline` and are still subject to native verification. An empty cache cannot resolve
unavailable artifacts offline. Missing sync entries or changed bytes fail; a cache refresh is not a trust reset.
CI and signed builds reject verification `off`/`lenient` or metadata-writing flags.

## Source builds and local experiments

JDK 21+, Gradle wrapper **9.8.2**, Git and Python 3 are needed for plumbing checks.
For native builds also use the pinned Rust/NDK/cargo-ndk tools in `scripts/build-sync-from-source.sh` and
`.github/actions/setup-sync-toolchain`. On Windows use Git Bash for shell scripts and `.\gradlew.bat`
for Gradle; set `PYTHON=python` if `python3` is unavailable.

```sh
git submodule update --init marknotes-sync
scripts/build-sync-from-source.sh
./gradlew :core:test assembleDebug --dependency-verification strict -Pmarknotes.sync.fromSource=marknotes-sync
```

`-Pmarknotes.sync.fromSource=<path>` wins over the optional development-pin default. Relative explicit
paths are relative to the **root settings directory**, including under Pro; the default pin/source path is
relative to the **Android owner**. Absolute paths and paths with spaces work.

The builder validates the **actual selected checkout**, not merely Android's nested submodule.
Same-version/wrong-commit checkouts fail before native work. The ignored
`bindings/kotlin/build/libs/marknotes-sync-receipt.json` binds the canonical checkout path, commit,
all non-ignored source file content, catalog version, and hashes of exactly three output artifacts.
At build start the old receipt and the three old outputs are removed; stale JVM-native staging is cleared
(upstream also regenerates Android staging and Kotlin sources). A receipt is written only after
the native/bindings/Gradle build succeeds, required native/class entries exist, and source state is unchanged.
Missing/stale receipts or mixed/modified outputs fail before resolution; rerun the source builder, do not hand-edit receipts.
Receipts are local consistency evidence, not signatures or proof of a reproducible/independently trusted build.

For an explicitly opted-in local experiment with unreviewed commits or uncommitted/untracked files:

```sh
MARKNOTES_SYNC_ALLOW_UNREVIEWED_SOURCE=true scripts/build-sync-from-source.sh "/path/to/experimental checkout"
./gradlew assembleRelease -Pmarknotes.sync.fromSource="/path/to/experimental checkout" -Pmarknotes.sync.allowUnreviewedSource=true
```

Unsigned `assembleRelease` is a development check, not publication, and remains allowed.
CI and signed builds forbid this opt-in and require the reviewed gitlink and clean build inputs. There is one
reviewed sanitization profile: `gradle/marknotes-sync-fdroid-prune.txt` permits **only deletions** of the
non-build npm/spec/wrapper paths already removed by `.fdroid.yml`. The consistency checker compares this
list to the recipe; edits, renames, untracked files, and deletions elsewhere still fail. This is not an
unreviewed-source opt-in. The receipt binds the actual pruned file state, so changing between clean/pruned
trees also requires rebuilding.
The profile explicitly includes the Kotlin `gradlew` and `gradlew.bat` scripts as well as the wrapper
JAR: real `fdroid build` removes the scripts during build-system cleanup/scanning, even when a plain
source build leaves them present. F-Droid builds the bindings with its supplied `GRADLE=gradle`.
Git can report an upstream CRLF/text-normalization mismatch immediately after checkout. An unstaged
modification is accepted only if its raw Git blob hash exactly equals `HEAD:<path>`; actual edits and
staged modifications still fail. Dependency-submission actions pass `--dependency-verification strict`
through `additional-arguments`, overriding the action's default verification-off system property without
relaxing the protected-build guard.
Signed releases additionally reject development pins. Protected builds are identified by `CI=true`,
`GITHUB_ACTIONS=true`, or the signing-keystore environment used by the release workflow.

Source outputs are not expected to match release bytes. Only the sync source components are marked
**changing**, which Gradle 9.8.2 excludes from native byte verification, with zero changing-module cache TTL.
The receipt supplies the separate local check; verification is never globally disabled and published sync
components remain non-changing. Switching source/release mode does not require deleting shared caches.
Source mode rejects configuration-cache reuse (`--no-configuration-cache`) so every invocation rechecks receipts.
F-Droid and reproducible builds use this same source path; receipts never enter the APK or committed tree.

## Pro root integration

After normal root repositories / `repositoriesMode`, before including projects:

```kotlin
extra["marknotesSyncOwner"] = file("libre")
apply(from = file("libre/gradle/marknotes-sync.settings.gradle"))
```

Remove Pro's old sync Ivy routing. Do not duplicate the catalog, development pin, receipt checker or
gitlink in Pro. Copy the reviewed sync entries and source-commit comment into **Pro's root**
`gradle/verification-metadata.xml`; nested metadata is not used by Gradle. Preserve unrelated existing
verification policy. The checker accepts `--verification-root <Pro root>` for deterministic equivalence
checks (coordinates, extensions, version, SHA256, source commit), without downloading anything.
Pro must adopt this interface only after the supplying Android change is available; do not point a
released gitlink at an unmerged file.

## Update, validate, recover

For a released sync update, review the upstream tag/commit and all three release asset hashes, then update
the catalog, sync gitlink, source-commit comment and verification entries together. Remove any development
pin. Keep source build toolchain/F-Droid pins consistent. Do not bump `spec/` for dependency docs.
For an unreleased API, review an exact source commit and update the gitlink/catalog plus optional development
pin together; retain last-release checksums until promotion. Use the shared dependency skill for the
cross-repo order, and the release skill for promotion/publication authorization.

```sh
python3 scripts/sync-contract.py
scripts/check-sync-pin.sh
python3 scripts/test-sync-pin.py
python3 scripts/test-sync-verification.py
./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug --dependency-verification strict
```

The Gradle fixtures use the pinned distribution, an isolated cache and local HTTP server. They cover all three
asset types, modified/missing metadata, warm offline cache checks, source/release switching, receipts,
same-version wrong external commits, opt-in experiments, protected builds and standalone/Pro-shaped roots.
Initialize the wrapper once (`./gradlew --version`) before running them. No Rust or Android SDK is needed
for fixtures. Shared behaviour/fixture changes also require the parity commands in `AGENTS.md`.

When a check fails, correct the reviewed input or rebuild the actual source checkout; do not disable verification,
replace checksums blindly, silently fall back to published binaries, or delete a user's cache/source tree.
Publication, merging, tagging, signing, deploys and downstream PRs each require explicit authorization.
