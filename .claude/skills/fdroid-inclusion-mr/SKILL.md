---
name: fdroid-inclusion-mr
description: >-
  Maintain the open F-Droid inclusion merge request for MarkNotes (fdroid/fdroiddata!54034, "New app: MarkNotes"):
  read and answer reviewer comments, update the fdroiddata recipe after a new release or a recipe change, keep the
  MR description in sync, and check the fork pipeline. Use when asked about the F-Droid MR, fdroiddata, reviewer
  feedback on the inclusion, or after a libre release while the MR is still open.
---

# Maintaining the F-Droid inclusion MR

This skill is temporary: once the MR is merged, implement
[#21](https://github.com/MaxDac/marknotes-android/issues/21), which removes it.

## Where things are

| What | Where |
| --- | --- |
| Merge request | [fdroid/fdroiddata!54034](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034), title `New app: MarkNotes` |
| Fork and branch | [MaxDac/fdroiddata](https://gitlab.com/MaxDac/fdroiddata) (GitLab project id `86842778`), branch `io.github.maxdac.marknotes.libre` |
| File in the MR (the only one) | `metadata/io.github.maxdac.marknotes.libre.yml` |
| Upstream copy of the recipe | `.fdroid.yml` here: the same content, but with `commit: vX.Y.Z` instead of the full hash |
| MR description (live copy) | `fdroid/inclusion-mr.md`, the fenced `markdown` block. It must match the MR exactly |
| Background, reproducibility, reviewer expectations | `docs/fdroid.md` |
| A similar MR that reviewers accepted (same author) | [fdroid/fdroiddata!50122](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50122) (FPInk) |

## Access and limits

- **Reading GitLab** needs no token: use the public API and the web JSON endpoints. On `fdroid/fdroiddata` the
  notes API answers 401, so read the discussions through the web endpoint:

  ```sh
  curl -s https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/54034   # state, labels, head sha
  curl -s https://gitlab.com/fdroid/fdroiddata/-/merge_requests/54034/discussions.json   # every comment and thread
  curl -s "https://gitlab.com/api/v4/projects/86842778/pipelines?ref=io.github.maxdac.marknotes.libre"
  curl -s https://gitlab.com/api/v4/projects/86842778/pipelines/<pipeline id>/jobs
  curl -s https://gitlab.com/api/v4/projects/86842778/jobs/<job id>/trace                # job log
  ```

- **Pushing to the fork's branch** works over SSH (`git@gitlab.com:MaxDac/fdroiddata.git`) when the machine has
  the maintainer's GitLab key.
- **Commenting, resolving threads and editing the MR description** need the maintainer's GitLab account; agents
  have no GitLab token. Prepare copy-pasteable fenced code blocks instead: one per reply, saying which thread it
  answers, and the full description from `fdroid/inclusion-mr.md`. Ask the maintainer to post them. If a
  `GITLAB_TOKEN` or an authenticated `glab` is available, ask before using it.

## Working copy of the fork

A sparse clone with only the app's metadata file is enough:

```sh
git clone --filter=blob:none --sparse git@gitlab.com:MaxDac/fdroiddata.git fdd
cd fdd
git sparse-checkout set metadata/io.github.maxdac.marknotes.libre.yml
git checkout io.github.maxdac.marknotes.libre
```

The branch has a single commit, `New app: MarkNotes`, on top of fdroiddata's `master`. Keep it that way: amend
that commit and force-push, and never add commits. Rebase only when there's a conflict or the change needs a newer
`master`; the MR checklist says "Don't rebase your branch if there is no conflict". To rebase:

```sh
git fetch https://gitlab.com/fdroid/fdroiddata.git master
git rebase FETCH_HEAD
```

fdroiddata requires LF line endings, so write the file with LF on Windows too, and stage only the metadata file.

## Task: a new libre release while the MR is open

The MR keeps only the latest build entry, so every release (README, "Releasing") must replace the version in it.

1. Check that the release exists with `marknotes-libre-X.Y.Z.apk` and `SHA256SUMS`. Get the tag's full commit
   hash with `git fetch --tags && git rev-parse "vX.Y.Z^{commit}"`. The versionCode is `X*10000 + Y*100 + Z`.
2. In the fork's metadata file, set `versionName`, `versionCode`, `commit` (the full hash, never a tag),
   `CurrentVersion` and `CurrentVersionCode`. Change nothing else, unless `.fdroid.yml` changed too. In that case
   copy the change across: apart from `commit`, the two files must be identical.
3. Run `git add metadata/io.github.maxdac.marknotes.libre.yml && git commit --amend --no-edit && git push -f`.
4. Wait for the fork pipeline on the new commit. All jobs must pass, and the `fdroid build` job log must contain
   "compared built binary to supplied reference binary successfully". If `fdroid rewritemeta` fails, apply its
   diff; for example, it wants the `Binaries:` URL on the line after the key.
5. Update `fdroid/inclusion-mr.md` in a PR here:
   - the release link, commit and versionCode;
   - the marknotes-sync tag in *Build notes*, if it changed;
   - the pipeline and `fdroid build` job links in *Validation*.
6. Give the maintainer the whole description block to paste into the MR, plus a short MR comment, for example:
   "Updated to vX.Y.Z (versionCode N): <one line on what changed>. Pipeline: <link>."

## Task: reviewer comments

1. Read every discussion (endpoint above) and list the unresolved threads. On FPInk the reviewers were `linsui`
   and `mezinster`. Notes with `"system": true` are only history.
2. For each comment, decide whether to change the recipe, change upstream (this repo or marknotes-sync), or
   explain why not.
   - A recipe change goes in **both** `.fdroid.yml` (a PR here) and the fork's metadata file (amend and
     force-push). Also update the description if it mentions that part.
   - An upstream change that affects the APK needs a new release, then the release task above.
   - Before pushing to the fork, run `scripts/fdroid-lint.sh` (needs fdroidserver), and for build changes also
     `scripts/fdroid-server-build.sh`.
3. Draft one reply per thread: what changed, with links (commit, PR, pipeline, job), or why not. Keep it plain,
   short and factual. Hand the replies to the maintainer as copy-pasteable blocks; the maintainer resolves the
   threads.

What fdroiddata reviewers usually ask for. All of these already hold here; keep them true:

- auto update: `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`;
- reproducible builds: `Binaries` and `AllowedAPKSigningKeys`;
- `subdir` set, and no `output`;
- the full commit hash;
- upstream code as submodules, not srclibs (the only srclib is fdroiddata's `rustup`);
- `ndk:` only because the native build needs r30;
- no stale `prebuild` or `build` steps;
- only the latest version;
- a description that matches the metadata;
- permissions justified;
- prebuilt native libraries explained: JNA's `libjnidispatch.so` from Maven Central and AndroidX's
  `libandroidx.graphics.path.so`, both accepted by F-Droid;
- no trackers or telemetry.

The Checks / F-Droid build job asserts the APK permissions, so a new permission fails CI here before it reaches
the MR.

## Task: status check

Report:

- the MR state and labels;
- the unresolved threads, and who is waiting on whom;
- the latest fork pipeline result;
- whether the MR's version matches the latest GitHub release (`gh release view --repo MaxDac/marknotes-android`).

## After the merge

F-Droid builds and publishes the app within a few days. Later releases are picked up by checkupdates without new
MRs (`docs/fdroid.md`, "New releases"). Then implement
[#21](https://github.com/MaxDac/marknotes-android/issues/21) to remove this skill and the inclusion-only docs.
