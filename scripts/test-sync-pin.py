"""Exercise development-pin validation without network access or native build tools."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class SyncPinTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="marknotes-sync-pin-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git("init", "--quiet")
        self.git("config", "core.fsmonitor", "false")
        self.git("config", "maintenance.auto", "false")
        self.git("config", "gc.auto", "0")
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text('marknotesSync = "0.3.0"\n')
        self.pin = "1" * 40
        self.write_pin(self.pin)
        shutil.copyfile(Path(__file__).resolve().parent.parent / "gradle/verification-metadata.xml",
                        self.root / "gradle/verification-metadata.xml")
        shutil.copyfile(Path(__file__).resolve().parent.parent / "gradle/marknotes-sync-fdroid-prune.txt",
                        self.root / "gradle/marknotes-sync-fdroid-prune.txt")
        shutil.copyfile(Path(__file__).resolve().parent.parent / ".fdroid.yml", self.root / ".fdroid.yml")
        self.git("add", "gradle")
        self.git("update-index", "--add", "--cacheinfo", f"160000,{self.pin},marknotes-sync")
        self.git(
            "-c", "user.name=Sync pin test", "-c", "user.email=pin-test@example.invalid",
            "commit", "--quiet", "-m",
            "Test source pin\n\nCo-authored-by: Copilot App <223556219+Copilot@users.noreply.github.com>",
        )

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True)

    def write_pin(self, commit, version="0.3.0"):
        (self.root / "gradle/marknotes-sync-source.properties").write_text(
            f"version={version}\ncommit={commit}\n"
        )

    def check(self, *args):
        script = Path(__file__).resolve().with_name("check-sync-pin.sh")
        env = os.environ.copy()
        env.pop("GITHUB_REF_TYPE", None)
        git_bash = Path(os.environ.get("ProgramFiles", "")) / "Git/bin/bash.exe"
        bash = str(git_bash) if os.name == "nt" and git_bash.is_file() else "bash"
        result = subprocess.run(
            [bash, script.as_posix(), ".", *args], cwd=self.root,
            env={**env, "PYTHON": Path(os.sys.executable).as_posix()},
            capture_output=True,
        )
        result.stdout = result.stdout.decode()
        result.stderr = result.stderr.decode()
        return result

    def test_exact_development_gitlink_is_accepted(self):
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_mismatched_gitlink_is_rejected(self):
        self.write_pin("2" * 40)
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match", result.stderr)

    def test_mismatched_version_is_rejected(self):
        self.write_pin(self.pin, "0.2.0")
        result = self.check()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("version mismatch", result.stderr)

    def test_release_mode_rejects_development_source(self):
        result = self.check("--release")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cannot be released", result.stderr)

    def test_app_tag_does_not_block_unsigned_development_build(self):
        self.git("tag", "v1.0.0")
        result = self.check()
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
