#!/usr/bin/env python3
"""Check the optimized Libre APK without relying on Gradle configuration alone."""

import argparse
import json
import re
import subprocess
import zipfile
from pathlib import Path


def check(apk: Path, aapt2: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        markers = [
            json.loads(marker)
            for name in names
            if re.fullmatch(r"classes\d*\.dex", name)
            for marker in re.findall(rb"~~R8(\{[^}\x00]+\})", archive.read(name))
        ]
        if not any(
            marker.get("min-api") == 29
            and marker.get("compilation-mode") == "release"
            and marker.get("r8-mode") == "full"
            for marker in markers
        ):
            raise ValueError("Release APK has no full-mode release R8 marker for minSdk 29")
        libraries = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        expected = {
            f"lib/{abi}/{library}.so"
            for abi in ("arm64-v8a", "x86_64")
            for library in ("libmarknotes_sync", "libjnidispatch", "libandroidx.graphics.path")
        }
        if libraries != expected:
            raise ValueError(f"Unexpected native libraries: {sorted(libraries ^ expected)}")
    permissions = subprocess.run(
        [str(aapt2), "dump", "permissions", str(apk)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    actual = set(re.findall(r"^uses-permission: name='([^']+)'", permissions, re.MULTILINE))
    expected_permissions = {
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.USE_BIOMETRIC",
        "io.github.maxdac.marknotes.libre.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    if actual != expected_permissions:
        raise ValueError(f"Unexpected permission changes: {sorted(actual ^ expected_permissions)}")
    print(f"Optimized APK: {apk.stat().st_size:,} bytes; R8, permissions and both native ABIs verified")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path, required=True)
    args = parser.parse_args()
    check(args.apk, args.aapt2)
