---
description: Compare a feature across the Android, desktop and web MarkNotes apps against marknotes-spec
argument-hint: <feature>
---

Check cross-app parity for this feature: **$ARGUMENTS**

MarkNotes ships as three apps sharing one notes folder: this Android repo (MaxDac/marknotes-android; MaxDac/MarkNotes is the same app with OneDrive and Proton Drive),
MaxDac/marknotes-electron (desktop) and MaxDac/marknotes-web (web). The canonical behaviour is in
MaxDac/marknotes-spec, checked out here at `spec/` (`note-format.md`, `features.md`, `fixtures/<area>/*.json`).
Don't change code; produce a report.

Ownership spans six repositories: marknotes-spec owns contracts/fixtures; marknotes-sync owns Rust sync
planning/merge and published bindings; marknotes-android owns shared Android code; MarkNotes owns Pro
providers and root integration; marknotes-electron and marknotes-web own their clients/adapters.
For sync features trace the shared Rust implementation and each adapter; do not treat Pro as a separate algorithm.

1. **Spec.** Find the feature in `spec/features.md` (the parity matrix) and, if it touches the note format,
   note IDs, links, attachments, the sync manifest or merging, in `spec/note-format.md` and the matching
   `spec/fixtures/<area>/*.json` cases (including any `knownDivergence` entries). If `spec/` is missing, run
   `git submodule update --init` or read it with `gh api repos/MaxDac/marknotes-spec/contents/<path> -H "Accept: application/vnd.github.raw"`.
   If the feature isn't in the spec, say so.
2. **Android (this repo).** Locate the implementation (shared logic is in `core/src/main/kotlin`, UI in `app-shared/`)
   and its tests, including the parity tests that run the spec fixtures.
3. **Desktop and web.** Locate the same feature with `gh`, without cloning:
   `gh search code --repo MaxDac/marknotes-electron "<term>"`, `gh search code --repo MaxDac/marknotes-web "<term>"`,
   `gh api repos/MaxDac/<repo>/git/trees/main?recursive=1 --jq '.tree[].path'` and
   `gh api repos/MaxDac/<repo>/contents/<path> -H "Accept: application/vnd.github.raw"`. Check their tests and
   whether they run the spec fixtures too.
   Also inspect MaxDac/marknotes-sync for the shared sync implementation and MaxDac/MarkNotes for Pro-only
   providers/root integration when relevant. Read their gitlinks/catalogs to identify the actually consumed code.
4. **Compare** the observable behaviour: file names and on-disk bytes, IDs and front-matter handling, link
   syntax and escaping, accepted inputs and limits, sync/merge outcomes, error messages and UI entry points.

Report:

- a table with one row per behaviour and columns Spec / Sync core / Android libre / Android Pro / Desktop / Web (✅ matches, ⚠️ differs,
  ❌ missing, ❓ couldn't verify), each cell citing a file path (and line) or fixture case;
- every gap, with the repo that should change and whether the spec itself needs updating first (spec changes
  land in marknotes-spec first, then all three apps);
- fixture coverage: behaviours with no fixture case, and `knownDivergence` entries that look fixable;
- whether `spec/features.md` is accurate for this feature.
