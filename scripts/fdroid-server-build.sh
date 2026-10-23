#!/usr/bin/env bash
# Builds the checked-out commit with the real `fdroid build --on-server` in F-Droid's buildserver image, the way
# fdroiddata's `fdroid build` CI job does, using the recipe in .fdroid.yml (with the marknotes-sync submodule). The unsigned APK
# must be byte-identical to the Checks / F-Droid build, or F-Droid won't publish our signed release APK.
#
#   scripts/fdroid-server-build.sh OUTPUT_DIR
#
# Writes OUTPUT_DIR/unsigned.apk and OUTPUT_DIR/SHA256SUMS. Needs Docker and a full clone (fetch-depth: 0). F-Droid
# clones the recipe's Repo from GitHub; here the build clones this checkout instead (same commit, same tree), so it
# also works for unpushed commits and PR merge commits. FDROID_BUILDSERVER_IMAGE overrides the pinned image.
set -euo pipefail

# The image fdroiddata's CI builds with (same pin as MaxDac/fpink). Bump the digest deliberately.
readonly default_image="registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie@sha256:9cb68105642ca4e7b295f0ceab10f069f5b3247dc18fa7c36046e9d81aa469a8"
readonly app_id=io.github.maxdac.marknotes.libre

[[ $# -eq 1 ]] || { echo "Usage: $0 OUTPUT_DIR" >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$1"
output="$(cd "$1" && pwd)"
if [[ "$(git -C "$root" rev-parse --is-shallow-repository)" == true ]]; then
  echo "The checkout is shallow; fetch full history (actions/checkout fetch-depth: 0)." >&2
  exit 1
fi
commit="$(git -C "$root" rev-parse HEAD)"
version_name="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$root/app/build.gradle.kts")"
version_code="$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' "$root/app/build.gradle.kts")"
[[ -n "$version_name" && -n "$version_code" ]] || { echo "No versionName/versionCode in app/build.gradle.kts" >&2; exit 1; }

docker run --rm --volume "$root:/src:ro" --volume "$output:/out" \
  --env "APP_ID=$app_id" --env "COMMIT=$commit" --env "VERSION_NAME=$version_name" --env "VERSION_CODE=$version_code" \
  "${FDROID_BUILDSERVER_IMAGE:-$default_image}" bash -euo pipefail -c '
set +u; source /etc/profile.d/bsenv.sh; set -u
export ANDROID_HOME=/opt/android-sdk
apt-get update && apt-get -y dist-upgrade
apt-get install -y sudo git curl openjdk-21-jdk-headless
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java
sdkmanager "platform-tools" "build-tools;31.0.0"
# fdroidserver master, like fdroiddata CI.
rm -rf "$fdroidserver" && mkdir "$fdroidserver"
curl --silent --fail https://gitlab.com/fdroid/fdroidserver/-/archive/master/fdroidserver-master.tar.gz \
  | tar -xz --directory="$fdroidserver" --strip-components=1
git -C "$home_vagrant/gradlew-fdroid" pull

# Run from $home_vagrant, so the app is built in /home/vagrant/build/$APP_ID as on the production buildserver.
data="$home_vagrant"
mkdir -p "$data/metadata" "$data/srclibs" "$data/build" "$data/tmp" "$data/unsigned" "$data/logs"
git config --system --add safe.directory "*"
git clone --quiet --mirror /src "$data/source.git"
curl --silent --fail -o "$data/srclibs/rustup.yml" https://gitlab.com/fdroid/fdroiddata/-/raw/master/srclibs/rustup.yml

# The last build entry of the recipe, pinned to this commit and version and cloned from the local mirror. Binaries
# and the update fields are dropped: there is no signed release to compare against yet.
python3 - "$data/metadata/$APP_ID.yml" <<PY
import sys, yaml
recipe = yaml.safe_load(open("/src/.fdroid.yml"))
build = recipe["Builds"][-1]
build.update(versionName="$VERSION_NAME", versionCode=int("$VERSION_CODE"), commit="$COMMIT")
recipe["Builds"] = [build]
recipe["Repo"] = "$data/source.git"
for key in ("Binaries", "AllowedAPKSigningKeys", "AutoUpdateMode", "UpdateCheckMode", "UpdateCheckData"):
    recipe.pop(key, None)
recipe.update(CurrentVersion="$VERSION_NAME", CurrentVersionCode=int("$VERSION_CODE"))
yaml.safe_dump(recipe, open(sys.argv[1], "w"), sort_keys=False)
PY
cat "$data/metadata/$APP_ID.yml"

for d in "$home_vagrant/.android" "$home_vagrant/.gradle"; do mkdir -p "$d"; done
chown -R vagrant "$data" "$home_vagrant"
export GRADLE_USER_HOME="$home_vagrant/.gradle"
cd "$data"
fdroid() {
  sudo --preserve-env --user vagrant env PATH="$fdroidserver:$PATH" \
    PYTHONPATH="$fdroidserver:$fdroidserver/examples" PYTHONUNBUFFERED=true HOME="$home_vagrant" fdroid "$@"
}
fdroid fetchsrclibs "$APP_ID:$VERSION_CODE" --verbose
(unset CI; fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$APP_ID:$VERSION_CODE")
cp "tmp/${APP_ID}_${VERSION_CODE}.apk" /out/unsigned.apk
(cd /out && sha256sum unsigned.apk > SHA256SUMS && cat SHA256SUMS)
chmod -R a+rwX /out
'
