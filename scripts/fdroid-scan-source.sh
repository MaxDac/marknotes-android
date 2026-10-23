#!/usr/bin/env bash
# Scans the source like F-Droid's build server does before building (`fdroid build` runs the scanner on the app
# source): non-free dependencies, unknown Maven repositories, prebuilt binaries. `fdroid scanner` itself only takes
# APKs or app IDs from an fdroiddata checkout, so this calls fdroidserver's scan_source on copies of the committed app
# source and, if given, of the marknotes-sync checkout (the scanner deletes files such as gradle-wrapper.jar).
# The recipe's scanignore/scandelete apply. Needs fdroidserver (`pip install fdroidserver`) and git.
#
#   scripts/fdroid-scan-source.sh [marknotes-sync checkout]
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fdroid="$(command -v fdroid)" || { echo "fdroid not found (pip install fdroidserver)" >&2; exit 1; }
python="$(sed -n '1s/^#!//p' "$fdroid")"
[ -x "${python%% *}" ] || python=python3

mkdir -p "$work/app"
git -C "$root" archive --format=tar HEAD | tar -x -C "$work/app"
dirs=("$work/app")
if [ -n "${1:-}" ]; then
  mkdir -p "$work/marknotes-sync"
  git -C "$1" archive --format=tar HEAD | tar -x -C "$work/marknotes-sync"
  # The recipe's prebuild removes these before building.
  rm -rf "$work/marknotes-sync/bindings/npm" "$work/marknotes-sync/bindings/kotlin/gradle/wrapper/gradle-wrapper.jar"
  dirs+=("$work/marknotes-sync")
fi

cd "$work"
"$python" - "$root/.fdroid.yml" "${dirs[@]}" <<'PY'
import sys

from fdroidserver import common, metadata, scanner

common.get_config()
with open(sys.argv[1], encoding="utf-8") as recipe:
    build = metadata.Build(metadata.parse_yaml_metadata(recipe)["Builds"][-1])
problems = 0
for path in sys.argv[2:]:
    count = scanner.scan_source(path, build if path.endswith("/app") else metadata.Build())
    print(f"{path}: {count} problem(s)")
    problems += count
sys.exit(1 if problems else 0)
PY
echo "F-Droid source scan OK"
