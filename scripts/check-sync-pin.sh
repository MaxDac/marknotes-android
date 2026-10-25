#!/usr/bin/env bash
# Offline catalog/gitlink/verification check; optional third argument is the ACTUAL selected checkout.
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
mode="${2:-}"
script="$(cd "$(dirname "$0")" && pwd)/sync-contract.py"
args=(--root "$root")
if [ "$mode" = --release ]; then args+=(--release); fi
if [ -n "${3:-}" ]; then
  args+=(--source "$3")
elif [ -f "$root/marknotes-sync/Cargo.toml" ]; then
  args+=(--source "$root/marknotes-sync")
fi
if [ "${MARKNOTES_SYNC_ALLOW_UNREVIEWED_SOURCE:-}" = true ]; then args+=(--allow-unreviewed); fi
exec "${PYTHON:-python3}" "$script" "${args[@]}"
