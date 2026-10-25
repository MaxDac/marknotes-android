#!/usr/bin/env bash
# Builds marknotes-sync (https://github.com/MaxDac/marknotes-sync: the Rust sync core and its UniFFI Kotlin bindings)
# from source, for the version pinned in gradle/libs.versions.toml, so the app can be built without the prebuilt
# GitHub release artifacts:
#
#   scripts/build-sync-from-source.sh [marknotes-sync checkout]   # default: the marknotes-sync submodule
#   ./gradlew assembleRelease -Pmarknotes.sync.fromSource=<same checkout>   # e.g. =marknotes-sync
#
# The marknotes-sync/ submodule is checked out at tag v<marknotesSync>: bump both together.
# Development integrations may instead name an exact commit in gradle/marknotes-sync-source.properties.
#
# This is the build path of F-Droid (.fdroid.yml) and of the release workflow. Needs Linux, rustup with the pinned
# toolchain and the aarch64-linux-android/x86_64-linux-android targets, cargo-ndk, the pinned NDK (ANDROID_NDK_HOME)
# and a JDK. The flags below keep the native libraries byte-for-byte reproducible across machines.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
version="$(tr -d '\r' < "$root/gradle/libs.versions.toml" | sed -n 's/^marknotesSync = "\(.*\)"$/\1/p')"
[ -n "$version" ] || { echo "marknotesSync is missing from gradle/libs.versions.toml" >&2; exit 1; }
dir="${1:-$root/marknotes-sync}"

# Pinned so rebuilds (F-Droid, the reproducible-build check) produce identical libraries.
export RUSTUP_TOOLCHAIN="${RUSTUP_TOOLCHAIN:-1.99.1}"
export MARKNOTES_NDK_VERSION="${MARKNOTES_NDK_VERSION:-30.0.16248370}"

if [ ! -f "$dir/Cargo.toml" ] && [ "$dir" = "$root/marknotes-sync" ]; then
  git -C "$root" submodule update --init marknotes-sync
elif [ ! -f "$dir/Cargo.toml" ]; then
  git clone --quiet --depth 1 --branch "v$version" https://github.com/MaxDac/marknotes-sync "$dir"
fi
dir="$(cd "$dir" && pwd)"
cd "$dir"
"$root/scripts/check-sync-pin.sh" "$root" "" "$dir"
grep -q "^version = \"$version\"" Cargo.toml \
  || { echo "$dir is not marknotes-sync $version (check out tag v$version)" >&2; exit 1; }

receipt_args=(--root "$root" --source "$dir")
if [ "${MARKNOTES_SYNC_ALLOW_UNREVIEWED_SOURCE:-}" = true ]; then receipt_args+=(--allow-unreviewed); fi
"${PYTHON:-python3}" "$root/scripts/sync-contract.py" "${receipt_args[@]}" --receipt begin

if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$MARKNOTES_NDK_VERSION"
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "Android NDK $MARKNOTES_NDK_VERSION not found (set ANDROID_NDK_HOME)" >&2; exit 1; }
grep -q "Pkg.Revision = $MARKNOTES_NDK_VERSION" "$ANDROID_NDK_HOME/source.properties" \
  || echo "warning: $ANDROID_NDK_HOME is not NDK $MARKNOTES_NDK_VERSION; the build won't be reproducible" >&2

# The upstream scripts remap paths using encoded flags (including Windows paths and paths with spaces).
# GNU build-id flags cannot be passed to the Windows host's MSVC linker.
case "$(uname -s)" in
  Linux*) export RUSTFLAGS="-Clink-arg=-Wl,--build-id=none" ;;
esac
SOURCE_DATE_EPOCH="$(git log -1 --format=%ct 2>/dev/null || echo 1)"
export SOURCE_DATE_EPOCH

# Never package other hosts' stale native libraries left by a previous source checkout.
rm -rf bindings/kotlin/natives/jvm
scripts/android-libs.sh
scripts/kotlin-bindings.sh
case "$(uname -s):$(uname -m)" in
  Linux:x86_64) host=linux-x86-64; library=libmarknotes_sync.so ;;
  Linux:aarch64) host=linux-aarch64; library=libmarknotes_sync.so ;;
  Darwin:x86_64) host=darwin-x86-64; library=libmarknotes_sync.dylib ;;
  Darwin:arm64) host=darwin-aarch64; library=libmarknotes_sync.dylib ;;
  MINGW*:x86_64 | MSYS*:x86_64) host=win32-x86-64; library=marknotes_sync.dll ;;
  *) echo "Unsupported JVM native build host: $(uname -s):$(uname -m)" >&2; exit 1 ;;
esac
mkdir -p "bindings/kotlin/natives/jvm/$host"
cp "target/release/$library" "bindings/kotlin/natives/jvm/$host/$library"
(cd bindings/kotlin && "${GRADLE:-./gradlew}" assemble --console=plain --no-daemon)

"${PYTHON:-python3}" "$root/scripts/sync-contract.py" "${receipt_args[@]}" --receipt finish
ls -l bindings/kotlin/build/libs
echo "Built marknotes-sync $version. Build the app with -Pmarknotes.sync.fromSource=$dir"
