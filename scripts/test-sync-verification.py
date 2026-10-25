"""Real Gradle 9.8.2 fixtures: scoped verification, changing artifacts, receipts and Pro roots.

No Android SDK, Rust, or network repository is needed (only the cached pinned Gradle distribution).
"""

from functools import partial
import hashlib
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

REPO = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("sync_contract", REPO / "scripts/sync-contract.py")
CONTRACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONTRACT)
VERSION = "0.4.0"


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass


class VerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        override = os.environ.get("GRADLE_TEST_EXECUTABLE")
        if override:
            cls.gradle = Path(override)
        else:
            home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
            suffix = "gradle.bat" if os.name == "nt" else "gradle"
            candidates = list((home / "wrapper/dists/gradle-9.8.2-bin").glob(f"*/gradle-9.8.2/bin/{suffix}"))
            if not candidates:
                raise RuntimeError("Run the pinned wrapper --version first, or set GRADLE_TEST_EXECUTABLE")
            cls.gradle = candidates[0]

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="marknotes-verification-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "pro"
        self.owner = self.root / "libre"
        self.owner.mkdir(parents=True)
        (self.owner / "gradle").mkdir()
        self.source = self.base / "external source"
        self.source.mkdir()
        self.git(self.source, "init", "--quiet")
        self.git(self.source, "config", "core.fsmonitor", "false")
        (self.source / ".gitignore").write_text("bindings/kotlin/build/\nbindings/kotlin/natives/\ntarget/\n")
        (self.source / "Cargo.toml").write_text('[package]\nname = "marknotes-sync"\nversion = "0.4.0"\n')
        for path in ("package.json", "bindings/npm/index.js", "bindings/kotlin/gradle/wrapper/gradle-wrapper.jar"):
            file = self.source / path
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("fixture non-build input\n")
        (self.source / "scripts").mkdir()
        (self.source / "scripts/android-libs.sh").write_text(
            '#!/usr/bin/env bash\nset -euo pipefail\nif [ "${MARKNOTES_TEST_FAIL:-}" = true ]; then exit 23; fi\n',
            newline="\n",
        )
        (self.source / "scripts/kotlin-bindings.sh").write_text(
            '#!/usr/bin/env bash\nset -euo pipefail\nmkdir -p target/release bindings/kotlin\n'
            'printf stub > target/release/marknotes_sync.dll\n'
            'printf stub > target/release/libmarknotes_sync.so\n'
            'printf stub > target/release/libmarknotes_sync.dylib\n',
            newline="\n",
        )
        for script in (self.source / "scripts").iterdir():
            script.chmod(0o755)
        self.git(self.source, "add", ".")
        (self.source / ".gitattributes").write_text("*.bat text eol=crlf\n")
        self.git(self.source, "add", ".gitattributes")
        wrapper = self.source / "bindings/kotlin/gradlew.bat"
        wrapper.write_bytes(b"@echo off\r\nrem committed upstream CRLF blob\r\n")
        blob = subprocess.run(
            ["git", "-C", str(self.source), "hash-object", "-w", "--stdin"],
            input=wrapper.read_bytes(), check=True, capture_output=True,
        ).stdout.decode().strip()
        self.git(self.source, "update-index", "--add", "--cacheinfo", f"100644,{blob},bindings/kotlin/gradlew.bat")
        self.commit(self.source)
        self.commit_id = self.git(self.source, "rev-parse", "HEAD").strip()
        self.git(self.owner, "init", "--quiet")
        self.git(self.owner, "config", "core.fsmonitor", "false")
        (self.owner / "gradle/libs.versions.toml").write_text(f'[versions]\nmarknotesSync = "{VERSION}"\n')
        shutil.copyfile(REPO / "gradle/marknotes-sync-fdroid-prune.txt", self.owner / "gradle/marknotes-sync-fdroid-prune.txt")
        shutil.copyfile(REPO / ".fdroid.yml", self.owner / ".fdroid.yml")
        self.git(self.owner, "add", ".")
        self.git(self.owner, "update-index", "--add", "--cacheinfo", f"160000,{self.commit_id},marknotes-sync")
        self.commit(self.owner)
        self.release = self.base / "release"
        self.release.mkdir()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(self.release)))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop_server)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.assets = {}
        for module, ext in CONTRACT.MODULES.items():
            name = f"{module}-{VERSION}.{ext}"
            path = self.release / f"v{VERSION}" / name
            path.parent.mkdir(exist_ok=True)
            self.write_asset(path, module, b"release")
            self.assets[name] = path
        helper = (REPO / "gradle/marknotes-sync.settings.gradle").read_text()
        helper = helper.replace("https://github.com/MaxDac/marknotes-sync/releases/download", self.url)
        (self.owner / "gradle/marknotes-sync.settings.gradle").write_text(helper)
        self.metadata = self.root / "gradle/verification-metadata.xml"
        self.metadata.parent.mkdir()
        self.write_metadata()
        shutil.copyfile(self.metadata, self.owner / "gradle/verification-metadata.xml")
        self.configure(self.root, "libre")
        self.cache = self.base / "cache"
        self.environment = {key: value for key, value in os.environ.items() if key not in (
            "CI", "GITHUB_ACTIONS", "MARKNOTES_KEYSTORE_PATH", "RELEASE_KEYSTORE_BASE64",
        )}
        environment_patch = patch.dict(os.environ, self.environment, clear=True)
        environment_patch.start()
        self.addCleanup(environment_patch.stop)
        self.addCleanup(self.stop_gradle)

    def stop_gradle(self):
        subprocess.run([str(self.gradle), "-g", str(self.cache), "--stop"], env=self.environment, capture_output=True)

    def stop_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def git(self, root, *args):
        return subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True).stdout

    def commit(self, root):
        self.git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "--quiet", "-m", "fixture")

    def write_asset(self, path, module, payload):
        with zipfile.ZipFile(path, "w") as archive:
            if module.endswith("kotlin"):
                archive.writestr("Fixture.class", payload)
            elif module.endswith("natives"):
                archive.writestr("linux-x86-64/libmarknotes_sync.so", payload)
            else:
                for abi in ("arm64-v8a", "x86_64"):
                    archive.writestr(f"jni/{abi}/libmarknotes_sync.so", payload)

    def write_metadata(self):
        text = (REPO / "gradle/verification-metadata.xml").read_text().replace(
            "48bdbdeda451963154d3a8defb7efc0d9a6f4cbb", self.commit_id
        )
        original = ET.fromstring(text)
        for component in original.findall("v:components/v:component", CONTRACT.NS):
            artifact = component.find("v:artifact", CONTRACT.NS)
            old = artifact.find("v:sha256", CONTRACT.NS).get("value")
            text = text.replace(old, hashlib.sha256(self.assets[artifact.get("name")].read_bytes()).hexdigest())
        self.metadata.write_text(text)

    def configure(self, root, owner):
        (root / "settings.gradle").write_text(f"""
rootProject.name = 'fixture'
dependencyResolutionManagement {{
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {{
        ivy {{
            url = uri('{self.url}/decoy')
            allowInsecureProtocol = true
            patternLayout {{ artifact('[module]-[revision].[ext]') }}
            metadataSources {{ artifact() }}
        }}
    }}
}}
ext.marknotesSyncOwner = file('{owner}')
apply from: file('{owner}/gradle/marknotes-sync.settings.gradle')
dependencyResolutionManagement.repositories.configureEach {{
    if (it instanceof IvyArtifactRepository && it.url.scheme == 'http') it.allowInsecureProtocol = true
}}
""")
        (root / "build.gradle").write_text("""
configurations { kotlinAsset; nativesAsset; androidAsset; unrelated }
dependencies {
    kotlinAsset 'io.github.maxdac.marknotes:marknotes-sync-kotlin:0.4.0@jar'
    nativesAsset 'io.github.maxdac.marknotes:marknotes-sync-natives:0.4.0@jar'
    androidAsset 'io.github.maxdac.marknotes:marknotes-sync-android:0.4.0@aar'
    unrelated 'example.unrelated:unverified:1.0@jar'
}
['kotlin', 'natives', 'android'].each { kind ->
    tasks.register("resolve${kind.capitalize()}") {
        doLast {
            configurations["${kind}Asset"].files.each {
                println("ASSET ${it.name} " + java.security.MessageDigest.getInstance('SHA-256').digest(it.bytes).encodeHex())
            }
        }
    }
}
tasks.register('resolveUnrelated') { doLast { configurations.unrelated.files.each { println(it.name) } } }
tasks.register('assembleRelease') { dependsOn 'resolveKotlin' }
""")

    def run_gradle(self, *args, ok=True, contains=None, env=None):
        start = time.monotonic()
        verification = [] if "--dependency-verification" in args else ["--dependency-verification", "strict"]
        result = subprocess.run(
            [str(self.gradle), "-p", str(self.root), "-g", str(self.cache), "--console=plain",
             "--no-watch-fs", *verification, *args],
            env=env or self.environment, capture_output=True, text=True,
        )
        output = result.stdout + result.stderr
        print(f"  Gradle fixture {args}: {'passed' if result.returncode == 0 else 'rejected'} ({time.monotonic() - start:.1f}s)", flush=True)
        self.assertEqual(ok, result.returncode == 0, output)
        if contains:
            self.assertIn(contains, output)
        return output

    def build_source(self, source=None, allow=False):
        source = source or self.source
        CONTRACT.receipt(self.owner, source, "begin", allow, False)
        directory = source / "bindings/kotlin/build/libs"
        for module, ext in CONTRACT.MODULES.items():
            self.write_asset(directory / f"{module}-{VERSION}.{ext}", module, b"local source")
        CONTRACT.receipt(self.owner, source, "finish", allow, False)

    def test_published_verification_matrix(self):
        for kind in ("kotlin", "natives", "android"):
            with self.subTest(kind=kind, case="positive"):
                self.run_gradle(f"resolve{kind.capitalize()}")
        # A warm cache remains usable without a server or network.
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", "--offline")
        for kind, module in (("kotlin", "marknotes-sync-kotlin"), ("natives", "marknotes-sync-natives"), ("android", "marknotes-sync-android")):
            ext = CONTRACT.MODULES[module]
            path = self.assets[f"{module}-{VERSION}.{ext}"]
            original = path.read_bytes()
            with self.subTest(kind=kind, case="tamper"):
                self.write_asset(path, module, b"tampered")
                self.run_gradle(f"resolve{kind.capitalize()}", "--refresh-dependencies", ok=False, contains="Dependency verification failed")
            path.write_bytes(original)
            self.run_gradle(f"resolve{kind.capitalize()}", "--refresh-dependencies")
            with self.subTest(kind=kind, case="missing metadata"):
                saved = self.metadata.read_text()
                xml = ET.fromstring(saved)
                components = xml.find("v:components", CONTRACT.NS)
                for component in list(components):
                    if component.get("name") == module:
                        components.remove(component)
                ET.ElementTree(xml).write(self.metadata, encoding="unicode")
                self.run_gradle(f"resolve{kind.capitalize()}", "--offline", ok=False, contains="Dependency verification failed")
                self.metadata.write_text(saved)
        decoy = self.release / "decoy"
        decoy.mkdir()
        (decoy / "unverified-1.0.jar").write_bytes(b"unrelated unverified group")
        self.run_gradle("resolveUnrelated")
        # A valid same-coordinate artifact in the normal repository is never a sync fallback.
        name = "marknotes-sync-kotlin-0.4.0.jar"
        shutil.copyfile(self.assets[name], decoy / name)
        original = self.assets[name].read_bytes()
        self.assets[name].unlink()
        original_cache = self.cache
        self.cache = self.base / "empty-cache"
        self.run_gradle("resolveKotlin", ok=False, contains="Could not find")
        self.stop_gradle()
        self.cache = original_cache
        self.assets[name].write_bytes(original)
        # Warm caches still check against edited metadata (no success-shaped cache bypass).
        self.metadata.write_text(self.metadata.read_text().replace(
            hashlib.sha256(self.assets["marknotes-sync-kotlin-0.4.0.jar"].read_bytes()).hexdigest(), "0" * 64
        ))
        self.run_gradle("resolveKotlin", "--offline", ok=False, contains="Dependency verification failed")

    def test_source_receipts_switching_and_nested_root(self):
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid")
        self.build_source()
        override = f"-Pmarknotes.sync.fromSource={self.source}"
        output = self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", override, "--offline")
        receipt_path = self.source / "bindings/kotlin/build/libs/marknotes-sync-receipt.json"
        receipt = json.loads(receipt_path.read_text())
        for digest in receipt["outputs"].values():
            self.assertIn(digest, output)  # Local bytes differ; changing components really bypass release-byte checks.
        for name in receipt["outputs"]:
            path = receipt_path.parent / name
            saved = path.read_bytes()
            path.write_bytes(b"mixed output")
            self.run_gradle("resolveKotlin", override, "--offline", ok=False, contains="source receipt hash mismatch")
            path.write_bytes(saved)
        self.run_gradle("resolveKotlin", override, "--configuration-cache", ok=False, contains="requires --no-configuration-cache")
        self.run_gradle("resolveKotlin", "-Pmarknotes.sync.fromSource=../external source", "--offline")
        saved_receipt = receipt_path.read_text()
        receipt["state"] = "0" * 64
        receipt_path.write_text(json.dumps(receipt))
        self.run_gradle("resolveAndroid", override, "--offline", ok=False, contains="source receipt is stale")
        receipt_path.write_text(saved_receipt)
        # Development-pin default is owner-relative under Pro; the explicit root-relative path still wins.
        default_source = self.owner / "marknotes-sync"
        self.git(self.owner, "clone", "--quiet", "--no-hardlinks", str(self.source), str(default_source))
        self.git(default_source, "config", "core.fsmonitor", "false")
        self.build_source(default_source)
        pin = self.owner / "gradle/marknotes-sync-source.properties"
        pin.write_text(f"version={VERSION}\ncommit={self.commit_id}\n")
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", "--offline")
        self.run_gradle("assembleRelease", override, "--offline")
        self.run_gradle("help", ok=False, contains="signed releases cannot use a development source pin",
                        env={**self.environment, "MARKNOTES_KEYSTORE_PATH": "fixture-signing-marker"})
        pin.unlink()
        receipt_path.unlink()
        self.run_gradle("resolveNatives", override, "--offline", ok=False, contains="source receipt missing")
        receipt_path.write_text(saved_receipt)
        (self.source / "Cargo.toml").write_text((self.source / "Cargo.toml").read_text() + "# same version, different commit\n")
        self.git(self.source, "add", ".")
        self.commit(self.source)
        self.run_gradle("resolveKotlin", override, ok=False, contains="selected checkout must match reviewed gitlink")
        checked = subprocess.run(
            [os.sys.executable, str(REPO / "scripts/sync-contract.py"), "--root", str(self.owner), "--source", str(self.source)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, checked.returncode)
        self.assertIn("selected checkout", checked.stderr)
        # Explicit, local experiment may use a same-version unreviewed/dirty tree after rebuilding a receipt.
        (self.source / "experiment.txt").write_text("untracked experiment")
        self.build_source(allow=True)
        experiment = "-Pmarknotes.sync.allowUnreviewedSource=true"
        self.run_gradle("resolveKotlin", override, experiment, "--offline")
        (self.source / "experiment.txt").write_text("changed untracked source")
        self.run_gradle("resolveKotlin", override, experiment, ok=False, contains="source receipt is stale")
        self.run_gradle("resolveKotlin", override, experiment, ok=False, contains="forbidden in CI", env={**self.environment, "CI": "true"})
        # Return to published assets in the same Gradle cache, including offline.
        published = self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", "--offline")
        for path in self.assets.values():
            self.assertIn(hashlib.sha256(path.read_bytes()).hexdigest(), published)
        saved_metadata = self.metadata.read_text()
        self.metadata.write_text(saved_metadata.replace(
            hashlib.sha256(self.assets["marknotes-sync-kotlin-0.4.0.jar"].read_bytes()).hexdigest(), "0" * 64
        ))
        self.run_gradle("resolveKotlin", "--offline", ok=False, contains="Dependency verification failed")
        self.metadata.write_text(saved_metadata)
        # Standalone Android root uses exactly the same helper and owner pins.
        self.root = self.owner
        self.configure(self.owner, ".")
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", "--offline")
        pin.write_text(f"version={VERSION}\ncommit={self.commit_id}\n")
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", "--offline",
                        env={**self.environment, "CI": "true"})
        pin.unlink()

    def test_receipt_builder_rejects_stale_or_incomplete_outputs(self):
        git_bash = Path(os.environ.get("ProgramFiles", "")) / "Git/bin/bash.exe"
        bash = str(git_bash) if os.name == "nt" and git_bash.is_file() else "bash"
        builder = subprocess.run(
            [bash, (REPO / "scripts/build-sync-from-source.sh").as_posix(), self.source.as_posix()],
            env={**self.environment, "PYTHON": Path(os.sys.executable).as_posix()},
            capture_output=True, text=True,
        )
        self.assertNotEqual(0, builder.returncode)
        self.assertIn("selected checkout does not match", builder.stderr)
        self.build_source()
        receipt_path = self.source / "bindings/kotlin/build/libs/marknotes-sync-receipt.json"
        CONTRACT.receipt(self.owner, self.source, "begin", False, False)
        self.assertFalse(receipt_path.exists())
        self.assertFalse(any(receipt_path.parent.glob("marknotes-sync-*.jar")))
        with self.assertRaises(FileNotFoundError):
            CONTRACT.receipt(self.owner, self.source, "finish", False, False)
        self.assertFalse(receipt_path.exists())
        (self.source / "untracked.rs").write_text("// modified during build\n")
        with self.assertRaisesRegex(ValueError, "source changed during build"):
            CONTRACT.receipt(self.owner, self.source, "finish", True, False)
        self.assertFalse(receipt_path.exists())

    def test_builder_success_and_failure_receipt_lifecycle(self):
        # Execute the real owner builder; only the expensive Rust/Gradle packaging tools are fixture stubs.
        for path in ("package.json", "bindings/npm/index.js", "bindings/kotlin/gradle/wrapper/gradle-wrapper.jar"):
            (self.source / path).unlink()
        scripts = self.owner / "scripts"
        scripts.mkdir()
        for name in ("build-sync-from-source.sh", "check-sync-pin.sh", "sync-contract.py"):
            shutil.copyfile(REPO / "scripts" / name, scripts / name)
            (scripts / name).chmod(0o755)
        packager = self.base / "package-fixture.py"
        packager.write_text("""
from pathlib import Path
import zipfile
output = Path('build/libs')
output.mkdir(parents=True, exist_ok=True)
for module, entries, ext in (
    ('kotlin', ['Fixture.class'], 'jar'),
    ('natives', ['linux-x86-64/libmarknotes_sync.so'], 'jar'),
    ('android', ['jni/arm64-v8a/libmarknotes_sync.so', 'jni/x86_64/libmarknotes_sync.so'], 'aar'),
):
    with zipfile.ZipFile(output / f'marknotes-sync-{module}-0.4.0.{ext}', 'w') as archive:
        for entry in entries:
            archive.writestr(entry, b'built fixture')
""")
        gradle = self.base / "fixture-gradle"
        gradle.write_text('#!/usr/bin/env bash\nset -euo pipefail\n"$PYTHON" "$MARKNOTES_TEST_PACKAGER"\n', newline="\n")
        gradle.chmod(0o755)
        ndk = self.base / "ndk"
        ndk.mkdir()
        (ndk / "source.properties").write_text("Pkg.Revision = 30.0.16248370\n")
        git_bash = Path(os.environ.get("ProgramFiles", "")) / "Git/bin/bash.exe"
        bash = str(git_bash) if os.name == "nt" and git_bash.is_file() else "bash"
        environment = {
            **self.environment, "PYTHON": Path(os.sys.executable).as_posix(), "GRADLE": gradle.as_posix(),
            "MARKNOTES_TEST_PACKAGER": packager.as_posix(), "ANDROID_NDK_HOME": ndk.as_posix(),
        }
        command = [bash, (scripts / "build-sync-from-source.sh").as_posix(), self.source.as_posix()]
        stale_native = self.source / "bindings/kotlin/natives/jvm/stale-platform/libmarknotes_sync.so"
        stale_native.parent.mkdir(parents=True)
        stale_native.write_bytes(b"old checkout native")
        result = subprocess.run(command, env=environment, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(stale_native.exists())
        CONTRACT.receipt(self.owner, self.source, "verify", False, False)
        result = subprocess.run(command, env={**environment, "MARKNOTES_TEST_FAIL": "true"}, capture_output=True, text=True)
        self.assertEqual(23, result.returncode, result.stdout + result.stderr)
        output = self.source / "bindings/kotlin/build/libs"
        self.assertFalse((output / "marknotes-sync-receipt.json").exists())
        for module, ext in CONTRACT.MODULES.items():
            self.assertFalse((output / f"{module}-{VERSION}.{ext}").exists())

    def test_reviewed_fdroid_pruning_is_not_an_unreviewed_source_bypass(self):
        for path in ("package.json", "bindings/npm/index.js", "bindings/kotlin/gradle/wrapper/gradle-wrapper.jar"):
            (self.source / path).unlink()
        self.build_source()
        override = f"-Pmarknotes.sync.fromSource={self.source}"
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", override, "--offline",
                        env={**self.environment, "CI": "true"})
        # An edit even in a prunable non-build path is not an approved deletion.
        (self.source / "package.json").write_text("edited package rather than removed")
        self.run_gradle("help", override, ok=False, contains="must match reviewed gitlink")
        with self.assertRaisesRegex(ValueError, "uncommitted files"):
            CONTRACT.receipt(self.owner, self.source, "verify", False, False)
        (self.source / "package.json").unlink()
        extra = self.source / "unreviewed.rs"
        extra.write_text("// not reviewed\n")
        self.run_gradle("help", override, ok=False, contains="must match reviewed gitlink")
        with self.assertRaisesRegex(ValueError, "uncommitted files"):
            CONTRACT.receipt(self.owner, self.source, "verify", False, False)
        extra.unlink()
        self.git(self.source, "mv", "scripts/android-libs.sh", "scripts/renamed.sh")
        self.run_gradle("help", override, ok=False, contains="must match reviewed gitlink")
        with self.assertRaisesRegex(ValueError, "uncommitted files"):
            CONTRACT.receipt(self.owner, self.source, "verify", False, False)
        self.git(self.source, "mv", "scripts/renamed.sh", "scripts/android-libs.sh")
        (self.source / "Cargo.toml").unlink()
        self.run_gradle("help", override, ok=False, contains="must match reviewed gitlink")
        with self.assertRaisesRegex(ValueError, "uncommitted files"):
            CONTRACT.receipt(self.owner, self.source, "verify", False, False)

    def test_protected_mode_and_consistency(self):
        # The submission action injects -D...=off; its additional CLI argument must win, not bypass the guard.
        self.run_gradle("help", "-Dorg.gradle.dependency.verification=off", "--dependency-verification", "strict",
                        env={**self.environment, "CI": "true"})
        for mode in ("off", "lenient"):
            self.run_gradle("help", "--dependency-verification", mode, ok=False, contains="require --dependency-verification strict",
                            env={**self.environment, "CI": "true"})
        self.run_gradle("help", "--write-verification-metadata", "sha256", ok=False, contains="cannot write verification metadata",
                        env={**self.environment, "CI": "true"})
        self.metadata.unlink()
        self.run_gradle("help", ok=False, contains="root gradle/verification-metadata.xml is required")
        self.write_metadata()
        CONTRACT.contract(self.owner, self.root)
        self.metadata.write_text(self.metadata.read_text().replace('version="0.4.0"', 'version="0.3.0"'))
        with self.assertRaisesRegex(ValueError, "version differs"):
            CONTRACT.contract(self.owner, self.root)
        self.write_metadata()
        self.metadata.write_text(self.metadata.read_text().replace(
            hashlib.sha256(self.assets["marknotes-sync-kotlin-0.4.0.jar"].read_bytes()).hexdigest(), "0" * 64
        ))
        with self.assertRaisesRegex(ValueError, "differ from Android owner"):
            CONTRACT.contract(self.owner, self.root)

    def test_checkout_normalization_is_accepted_but_actual_wrapper_edits_fail(self):
        status = self.git(self.source, "status", "--porcelain=v1")
        self.assertIn(" M bindings/kotlin/gradlew.bat", status)
        self.build_source()
        override = f"-Pmarknotes.sync.fromSource={self.source}"
        self.run_gradle("resolveKotlin", "resolveNatives", "resolveAndroid", override, "--offline",
                        env={**self.environment, "CI": "true"})
        wrapper = self.source / "bindings/kotlin/gradlew.bat"
        wrapper.write_bytes(wrapper.read_bytes() + b"rem actual edit\r\n")
        with self.assertRaisesRegex(ValueError, "uncommitted files"):
            CONTRACT.receipt(self.owner, self.source, "verify", False, False)
        self.run_gradle("help", override, ok=False, contains="must match reviewed gitlink")


if __name__ == "__main__":
    unittest.main()
