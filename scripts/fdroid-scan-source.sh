#!/usr/bin/env bash
# Scans the source like F-Droid's build server does before building (`fdroid build` runs the scanner on the app
# source): non-free dependencies, unknown Maven repositories, prebuilt binaries. `fdroid scanner` itself only takes
# APKs or app IDs from an fdroiddata checkout, so this calls fdroidserver's scan_source on a copy of the committed
# source with the marknotes-sync submodule in it, after removing the recipe's `rm:` paths, as F-Droid does.
# The recipe's scanignore/scandelete apply. Needs fdroidserver (`pip install fdroidserver`), git and the
# marknotes-sync submodule (`git submodule update --init marknotes-sync`).
#
#   scripts/fdroid-scan-source.sh
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fdroid="$(command -v fdroid)" || { echo "fdroid not found (pip install fdroidserver)" >&2; exit 1; }
# fdroidserver's own interpreter (pipx or a venv), next to the real `fdroid` script.
python="$(dirname "$(readlink -f "$fdroid")")/python"
[ -x "$python" ] || python=python3

[ -f "$root/marknotes-sync/Cargo.toml" ] \
  || { echo "The marknotes-sync submodule is missing (git submodule update --init marknotes-sync)" >&2; exit 1; }
mkdir -p "$work/app/marknotes-sync"
git -C "$root" archive --format=tar HEAD | tar -x -C "$work/app"
git -C "$root/marknotes-sync" archive --format=tar HEAD | tar -x -C "$work/app/marknotes-sync"
cd "$work"
"$python" - "$root/.fdroid.yml" "$work/app" <<'PY'
import argparse
import logging
import os
import shutil
import sys

from fdroidserver import common, metadata, scanner

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
parser = argparse.ArgumentParser()
common.setup_global_opts(parser)
parser.set_defaults(json=True, refresh_scanner=False)
common.options = parser.parse_args([])
common.get_config()
with open(sys.argv[1], encoding="utf-8") as recipe:
    build = metadata.Build(metadata.parse_yaml_metadata(recipe)["Builds"][-1])
for path in build.rm:
    target = os.path.join(sys.argv[2], path)
    if not os.path.lexists(target):
        continue  # submodules other than marknotes-sync (spec/) aren't copied here
    shutil.rmtree(target) if os.path.isdir(target) and not os.path.islink(target) else os.remove(target)
messages = scanner.MessageStore()
problems = scanner.scan_source(sys.argv[2], build, json_per_build=messages)
for message, path in messages.errors:
    print(f"ERROR: {message} at {path}", file=sys.stderr)
print(f"{problems} problem(s)")
sys.exit(1 if problems else 0)
PY
echo "F-Droid source scan OK"
