"""Exercise the release APK guard without an SDK or native build."""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location("release_apk", Path(__file__).with_name("check-release-apk.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ReleaseApkTest(unittest.TestCase):
    def check_fixture(self, *, marker=True, debug=False, missing_native=False, extra_native=False,
                      missing_permission=False, extra_permission=False):
        with tempfile.TemporaryDirectory(prefix="marknotes-release-apk-") as directory:
            apk = Path(directory) / "release.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                value = {"min-api": 29, "compilation-mode": "debug" if debug else "release", "r8-mode": "full"}
                archive.writestr("classes.dex", b"dex\x00" + (b"~~R8" + json.dumps(value).encode() if marker else b""))
                for abi in ("arm64-v8a", "x86_64"):
                    for library in ("libmarknotes_sync", "libjnidispatch", "libandroidx.graphics.path"):
                        if not (missing_native and abi == "arm64-v8a" and library == "libmarknotes_sync"):
                            archive.writestr(f"lib/{abi}/{library}.so", b"ELF")
                if extra_native:
                    archive.writestr("lib/armeabi-v7a/libunexpected.so", b"ELF")
            permissions = [
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.USE_BIOMETRIC",
                "io.github.maxdac.marknotes.libre.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ]
            if missing_permission:
                permissions.remove("android.permission.USE_BIOMETRIC")
            if extra_permission:
                permissions.append("android.permission.READ_PHONE_STATE")
            with patch.object(checker.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
                run.return_value.stdout = "\n".join(f"uses-permission: name='{name}'" for name in permissions)
                checker.check(apk, Path("aapt2"))
                run.assert_called_once_with(
                    ["aapt2", "dump", "permissions", str(apk)], check=True, capture_output=True, text=True
                )

    def test_expected_apk(self):
        self.check_fixture()

    def test_missing_r8_marker_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "R8 marker"):
            self.check_fixture(marker=False)

    def test_debug_r8_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "R8 marker"):
            self.check_fixture(debug=True)

    def test_missing_native_library_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "native libraries"):
            self.check_fixture(missing_native=True)

    def test_extra_native_library_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "native libraries"):
            self.check_fixture(extra_native=True)

    def test_missing_permission_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "permission changes"):
            self.check_fixture(missing_permission=True)

    def test_extra_permission_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "permission changes"):
            self.check_fixture(extra_permission=True)


if __name__ == "__main__":
    unittest.main()
