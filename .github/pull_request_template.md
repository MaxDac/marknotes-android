## Summary

<!-- What does this PR change and why? -->

## Parity impact

<!--
Does this touch behaviour shared with the other MarkNotes apps (note format, note IDs, links, attachments)?
See AGENTS.md, "Cross-app parity". MarkNotes Plus (private) includes this repository as its `libre/` submodule and
runs the shared fixtures against `:core`; the sync hooks (`SyncHooks`) are its extension point.
-->

- [ ] None: Android-only change
- [ ] Shared behaviour: spec change agreed with the maintainers <!-- link or description; no private details -->
- [ ] Desktop/web follow-ups: <!-- links to PRs or issues in marknotes-electron / marknotes-web -->
- [ ] `SyncHooks`, `:core` or `:app-shared` API used by MarkNotes Plus: <!-- none, or what changes for Plus -->

## Checklist

- [ ] `./gradlew ktlintCheck detekt lintDebug testDebugUnitTest :core:test assembleDebug` passes locally
- [ ] Parity tests pass when the fixtures are available (`./gradlew :core:parityTest -Pmarknotes.specDir=<checkout>`, see AGENTS.md); they skip without them
- [ ] UI changes tested on an emulator/device (`./gradlew :app:connectedDebugAndroidTest`)
- [ ] F-Droid: no non-free dependencies or Google Play Services, and `.fdroid.yml` updated when the build changes (Checks / F-Droid build and Reproducible build pass)
