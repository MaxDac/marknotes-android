#!/usr/bin/env bash
# Development source commits are explicit and cannot be used for an app release.
set -euo pipefail

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
mode="${2:-}"
version="$(tr -d '\r' < "$root/gradle/libs.versions.toml" | sed -n 's/^marknotesSync = "\(.*\)"$/\1/p')"
pinned="$(git -C "$root" ls-tree HEAD marknotes-sync | awk '{print $3}')"
source="$root/gradle/marknotes-sync-source.properties"
if [ -f "$source" ]; then
  if [ "$mode" = --release ] || [ "${GITHUB_REF_TYPE:-}" = tag ] ||
    [ -n "$(git -C "$root" tag --points-at HEAD --list 'v*')" ]; then
    echo "::error::Development marknotes-sync source pins cannot be released. Promote to a matching published release tag first." >&2
    exit 1
  fi
  expected="$(tr -d '\r' < "$source" | sed -n 's/^commit=//p')"
  source_version="$(tr -d '\r' < "$source" | sed -n 's/^version=//p')"
  if ! [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || [ "$source_version" != "$version" ]; then
    echo "::error::Invalid development source pin or version mismatch with gradle/libs.versions.toml" >&2
    exit 1
  fi
else
  expected="$(git ls-remote https://github.com/MaxDac/marknotes-sync "refs/tags/v$version" "refs/tags/v$version^{}" | awk '{print $1}' | tail -n 1)"
fi
if [ -z "$expected" ] || [ "$pinned" != "$expected" ]; then
  echo "::error::marknotes-sync gitlink $pinned does not match the required source/tag $expected" >&2
  exit 1
fi
if [ -f "$root/marknotes-sync/Cargo.toml" ]; then
  actual="$(git -C "$root/marknotes-sync" rev-parse HEAD)"
  if [ "$actual" != "$pinned" ] || ! grep -q "^version = \"$version\"" "$root/marknotes-sync/Cargo.toml"; then
    echo "::error::marknotes-sync checkout does not match its pinned commit and version" >&2
    exit 1
  fi
fi
echo "marknotes-sync $version source pin OK"
