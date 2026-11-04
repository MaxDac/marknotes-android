@AGENTS.md

## Claude Code notes

- `AGENTS.md` (imported above) is the source of truth; put shared guidance there, not here.
- This is a Windows-friendly repo: on Windows use `.\gradlew.bat`; Gradle output is long, so pass `-q` or
  `--console=plain` and read the report under `**/build/reports/` when a task fails.
- Run the app on WayDroid with the `waydroid-test` skill (`docs/waydroid.md`); it stops and asks before any `sudo`.
- Cross-app parity areas are listed in `AGENTS.md`; the spec and the sync library are private and this repository
  must not depend on them.
- Read public sibling repos (marknotes-electron, marknotes-web) with `gh` rather than cloning them.
- Dependency work uses `docs/dependencies.md`.
