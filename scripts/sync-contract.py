#!/usr/bin/env python3
"""Offline sync identity/metadata checks and ignored source-build receipts."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

GROUP = "io.github.maxdac.marknotes"
MODULES = {"marknotes-sync-kotlin": "jar", "marknotes-sync-natives": "jar", "marknotes-sync-android": "aar"}
NS = {"v": "https://schema.gradle.org/dependency-verification"}


def git(root, *args):
    return subprocess.run(
        ["git", "-C", str(root), *args], check=True, capture_output=True
    ).stdout


def properties(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines() if line and not line.startswith("#"))


def pruning_policy(root):
    paths = [line for line in (root / "gradle/marknotes-sync-fdroid-prune.txt").read_text().splitlines()
             if line and not line.startswith("#")]
    if len(paths) != len(set(paths)) or any(
        path.startswith("/") or any(part in ("", ".", "..") for part in path.split("/")) for path in paths
    ):
        raise ValueError("invalid reviewed F-Droid pruning paths")
    recipe = (root / ".fdroid.yml").read_text()
    sections = re.findall(r"(?m)^    rm:\n((?:      - [^\n]+\n)+)", recipe)
    if not sections:
        raise ValueError("F-Droid rm policy is missing")
    actual = {line.removeprefix("      - marknotes-sync/") for line in sections[-1].splitlines()
              if line.startswith("      - marknotes-sync/")}
    if set(paths) != actual:
        raise ValueError("reviewed sync pruning policy differs from .fdroid.yml rm paths")
    return paths


def contract(root, verification_root=None, release=False):
    pruning_policy(root)
    catalog = re.search(r'^marknotesSync = "([^"]+)"$', (root / "gradle/libs.versions.toml").read_text(), re.M)
    if catalog is None:
        raise ValueError("catalog marknotesSync is missing")
    version = catalog[1]
    entry = git(root, "ls-tree", "HEAD", "marknotes-sync").decode().split()
    if len(entry) != 4 or entry[0] != "160000":
        raise ValueError("missing reviewed marknotes-sync gitlink")
    expected = entry[2]
    pin = root / "gradle/marknotes-sync-source.properties"
    if pin.is_file():
        source = properties(pin)
        if release:
            raise ValueError("Development marknotes-sync source pins cannot be released")
        if source.get("version") != version or not re.fullmatch("[0-9a-f]{40}", source.get("commit", "")):
            raise ValueError("Invalid development source pin or version mismatch with catalog")
        if source["commit"] != expected:
            raise ValueError("development pin does not match reviewed gitlink")
    metadata = (verification_root or root) / "gradle/verification-metadata.xml"
    document = ET.parse(metadata).getroot()
    components = document.findall("v:components/v:component", NS)
    sync = [component for component in components if component.get("group") == GROUP]
    if len(sync) != 3 or {component.get("name") for component in sync} != set(MODULES):
        raise ValueError("verification metadata must cover exactly three sync modules")
    for component in sync:
        module = component.get("name")
        # A development pin may retain last-release checksums; changing source components do not use them.
        asset_version = component.get("version")
        if not pin.is_file() and asset_version != version:
            raise ValueError("verification metadata version differs from catalog")
        artifacts = component.findall("v:artifact", NS)
        if len(artifacts) != 1 or artifacts[0].get("name") != f"{module}-{asset_version}.{MODULES[module]}":
            raise ValueError(f"incorrect verification artifact: {module}")
        hashes = artifacts[0].findall("v:sha256", NS)
        if len(hashes) != 1 or not re.fullmatch("[0-9a-f]{64}", hashes[0].get("value", "")):
            raise ValueError(f"missing/unreviewed SHA256: {module}")
    if not pin.is_file() and f"sync-source-commit: {expected}" not in metadata.read_text():
        raise ValueError("verification metadata source commit does not match reviewed gitlink")
    if verification_root is not None and verification_root.resolve() != root.resolve():
        owner_metadata = ET.parse(root / "gradle/verification-metadata.xml").getroot()
        def fingerprint(tree):
            return sorted(
                (component.get("name"), component.get("version"), artifact.get("name"), sha.get("value"))
                for component in tree.findall("v:components/v:component", NS) if component.get("group") == GROUP
                for artifact in component.findall("v:artifact", NS) for sha in artifact.findall("v:sha256", NS)
            )
        if fingerprint(document) != fingerprint(owner_metadata):
            raise ValueError("root sync verification entries differ from Android owner metadata")
    return version, expected


def source_state(source):
    digest = hashlib.sha256()
    digest.update(git(source, "diff", "--binary", "--no-ext-diff", "--no-textconv", "HEAD", "--"))
    paths = sorted(set(git(source, "ls-files", "-z", "--cached", "--others", "--exclude-standard").decode().rstrip("\0").split("\0")),
                   key=lambda path: path.encode())
    for path in paths:
        digest.update((path + "\0").encode())
        file = source / path
        if file.is_symlink():
            blob = hashlib.sha256(os.readlink(file).encode()).hexdigest()
        elif file.is_dir():
            blob = f"tree:{git(file, 'rev-parse', 'HEAD').decode().strip()}:{source_state(file)}" if (file / ".git").exists() else "uninitialized"
        else:
            blob = hashlib.sha256(file.read_bytes()).hexdigest() if file.is_file() else "missing"
        digest.update((blob + "\n").encode())
    return digest.hexdigest()


def source_identity(source, version):
    return {
        "schema": 1, "version": version, "source": source.resolve().as_posix(),
        "commit": git(source, "rev-parse", "HEAD").decode().strip(), "state": source_state(source),
    }


def check_source(source, version, expected, allow=False, protected=False, pruning=()):
    if allow and protected:
        raise ValueError("unreviewed source opt-in is forbidden in CI/signed builds")
    actual = git(source, "rev-parse", "HEAD").decode().strip()
    dirty = git(source, "status", "--porcelain=v1", "-z", "--untracked-files=all").decode().rstrip("\0").split("\0")
    unreviewed = [
        entry for entry in dirty if entry and not (
            entry[:2] in (" D", "D ") and any(entry[3:] == path or entry[3:].startswith(path + "/") for path in pruning)
        ) and not (
            entry[:2] == " M" and (source / entry[3:]).is_file()
            and git(source, "hash-object", "--no-filters", "--", entry[3:]).strip()
            == git(source, "rev-parse", f"HEAD:{entry[3:]}").strip()
            and not git(source, "diff", "--summary", "--no-ext-diff", "HEAD", "--", entry[3:]).strip()
        )
    ]
    if not allow and (actual != expected or unreviewed):
        raise ValueError("selected checkout does not match reviewed commit or has uncommitted files outside reviewed F-Droid deletions")
    if not re.search(rf'^version = "{re.escape(version)}"$', (source / "Cargo.toml").read_text(), re.M):
        raise ValueError("selected source version mismatch with catalog")


def receipt(root, source, action, allow, release):
    version, expected = contract(root, release=release)
    protected = os.environ.get("CI") == "true" or os.environ.get("GITHUB_ACTIONS") == "true" or bool(
        os.environ.get("MARKNOTES_KEYSTORE_PATH") or os.environ.get("RELEASE_KEYSTORE_BASE64")
    )
    check_source(source, version, expected, allow, protected, pruning_policy(root))
    output = source / "bindings/kotlin/build/libs"
    output.mkdir(parents=True, exist_ok=True)
    final = output / "marknotes-sync-receipt.json"
    snapshot = output / "marknotes-sync-receipt.pending.json"
    identity = source_identity(source, version)
    if action == "begin":
        final.unlink(missing_ok=True)
        for module, ext in MODULES.items():
            (output / f"{module}-{version}.{ext}").unlink(missing_ok=True)
        snapshot.write_text(json.dumps(identity, indent=2) + "\n")
        return
    if action == "finish":
        if json.loads(snapshot.read_text()) != identity:
            raise ValueError("source changed during build; rebuild without concurrent source edits")
        outputs = {}
        for module, ext in MODULES.items():
            artifact = output / f"{module}-{version}.{ext}"
            with zipfile.ZipFile(artifact) as archive:
                names = archive.namelist()
                if module.endswith("kotlin") and not any(name.endswith(".class") for name in names):
                    raise ValueError("Kotlin JAR has no classes")
                if module.endswith("natives") and not any(name.endswith((".so", ".dll", ".dylib")) for name in names):
                    raise ValueError("host natives JAR has no native library")
                if module.endswith("android") and not all(
                    f"jni/{abi}/libmarknotes_sync.so" in names for abi in ("arm64-v8a", "x86_64")
                ):
                    raise ValueError("Android AAR is missing a required ABI")
            outputs[artifact.name] = hashlib.sha256(artifact.read_bytes()).hexdigest()
        final.write_text(json.dumps({**identity, "outputs": outputs}, indent=2) + "\n")
        snapshot.unlink()
    else:
        saved = json.loads(final.read_text())
        if {key: saved.get(key) for key in identity} != identity:
            raise ValueError("source receipt is stale")
        expected_names = {f"{module}-{version}.{ext}" for module, ext in MODULES.items()}
        if set(saved.get("outputs", {})) != expected_names:
            raise ValueError("source receipt must cover exactly three artifacts")
        for name, expected_hash in saved["outputs"].items():
            if hashlib.sha256((output / name).read_bytes()).hexdigest() != expected_hash:
                raise ValueError(f"source receipt hash mismatch: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--verification-root", type=Path)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--allow-unreviewed", action="store_true")
    parser.add_argument("--receipt", choices=("begin", "finish", "verify"))
    args = parser.parse_args()
    try:
        version, expected = contract(args.root, args.verification_root, args.release)
        if args.source:
            source = args.source.resolve()
            protected = os.environ.get("CI") == "true" or os.environ.get("GITHUB_ACTIONS") == "true" or bool(
                os.environ.get("MARKNOTES_KEYSTORE_PATH") or os.environ.get("RELEASE_KEYSTORE_BASE64")
            )
            check_source(source, version, expected, args.allow_unreviewed, protected, pruning_policy(args.root))
            if args.receipt:
                receipt(args.root, source, args.receipt, args.allow_unreviewed, args.release)
        elif args.receipt:
            raise ValueError("--receipt requires --source")
        print(f"marknotes-sync {version} contract OK")
    except (ValueError, OSError, subprocess.CalledProcessError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"marknotes-sync: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
