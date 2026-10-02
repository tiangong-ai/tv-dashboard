import base64
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import release


PROPERTIES = "dashboardVersion=1.2.0\ndashboardVersionCode=4\n"


def make_apk(path, abis):
    with zipfile.ZipFile(path, "w") as archive:
        for abi in abis:
            archive.writestr(f"lib/{abi}/libxul.so", b"fixture")


class ReleaseGuardTests(unittest.TestCase):
    def test_version_matches_tag(self):
        self.assertEqual(release.release_version("v1.2.0", PROPERTIES), "1.2.0")

    def test_rejects_bad_tags_and_mismatched_versions(self):
        for tag in ["v1.2.1", "1.2.0", "v1.2.0-beta", "v01.2.0", "v1.2.0;echo bad", "v1.2.0\n"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.release_version(tag, PROPERTIES)

    def test_rejects_ambiguous_or_invalid_properties(self):
        for properties in [PROPERTIES + "dashboardVersion=1.2.0\n", PROPERTIES + "dashboardVersionCode=0\n", PROPERTIES.replace("Code=4", "Code=0"), "dashboardVersion=1.2.0\n"]:
            with self.subTest(properties=properties), self.assertRaises(ValueError):
                release.release_version("v1.2.0", properties)

    @mock.patch("scripts.release.subprocess.run")
    def test_absent_release_requires_http_404(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, "HTTP/2.0 404 Not Found\n", "gh: Not Found")
        release.assert_unpublished("v1.2.0", "tiangong-ai/tv-dashboard")

    @mock.patch("scripts.release.subprocess.run")
    def test_existing_release_is_not_overwritten(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "HTTP/2.0 200 OK\n", "")
        with self.assertRaisesRegex(ValueError, "already exists"):
            release.assert_unpublished("v1.2.0", "tiangong-ai/tv-dashboard")

    @mock.patch("scripts.release.subprocess.run")
    def test_api_failures_do_not_imply_absent_release(self, run):
        for output in ["", "HTTP/2.0 403 Forbidden\n", "HTTP/2.0 500 Internal Server Error\n"]:
            with self.subTest(output=output):
                run.return_value = subprocess.CompletedProcess([], 1, output, "request failed")
                with self.assertRaisesRegex(ValueError, "Could not verify"):
                    release.assert_unpublished("v1.2.0", "tiangong-ai/tv-dashboard")

    def test_universal_apk_contains_exactly_supported_architectures(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            make_apk(apk, release.ARM_ABIS)
            release.verify_abis(apk)
            for abis in [{"armeabi-v7a"}, {"arm64-v8a"}, release.ARM_ABIS | {"x86_64"}, set()]:
                with self.subTest(abis=abis):
                    make_apk(apk, abis)
                    with self.assertRaises(ValueError):
                        release.verify_abis(apk)

    def test_checksum_uses_relative_asset_filename(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "release.apk"
            apk.write_bytes(b"apk fixture")
            release.write_checksum(apk)
            checksum = apk.with_suffix(".apk.sha256").read_text()
            self.assertEqual(checksum, f"{hashlib.sha256(b'apk fixture').hexdigest()}  release.apk\n")

    def test_ancestry_and_tag_use_real_git_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True)
            git("init", "-b", "main")
            git("config", "user.name", "Release test")
            git("config", "user.email", "release-test@example.invalid")
            (root / "gradle.properties").write_text(PROPERTIES)
            git("add", "gradle.properties")
            git("commit", "-m", "Integrated release")
            git("tag", "v1.2.0")
            git("update-ref", "refs/remotes/origin/main", "HEAD")
            with mock.patch.object(release, "ROOT", root):
                self.assertEqual(release.validate_tag("v1.2.0"), "1.2.0")
                git("checkout", "-b", "unmerged-feature")
                (root / "feature").write_text("unmerged")
                git("add", "feature")
                git("commit", "-m", "Unmerged feature")
                with self.assertRaisesRegex(ValueError, "HEAD must match"):
                    release.validate_tag("v1.2.0")
                git("tag", "-f", "v1.2.0")
                with self.assertRaises(subprocess.CalledProcessError):
                    release.validate_tag("v1.2.0")

    def test_signing_uses_env_passwords_and_removes_temporary_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text(PROPERTIES)
            source = root / "unsigned.apk"
            make_apk(source, release.ARM_ABIS)
            env = {
                "ANDROID_HOME": str(root / "sdk"), "RUNNER_TEMP": str(root),
                "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"key fixture").decode(),
                "ANDROID_KEYSTORE_PASSWORD": "test-store-password",
                "ANDROID_KEY_PASSWORD": "test-key-password", "ANDROID_KEY_ALIAS": "test-alias",
            }
            keys = []
            def fake_tool(args, **kwargs):
                if args[1] == "sign":
                    key = Path(args[args.index("--ks") + 1])
                    self.assertEqual(key.stat().st_mode & 0o777, 0o600)
                    keys.append(key)
                    self.assertIn("env:ANDROID_KEYSTORE_PASSWORD", args)
                    self.assertIn("env:ANDROID_KEY_PASSWORD", args)
                    self.assertNotIn(env["ANDROID_KEYSTORE_PASSWORD"], args)
                    self.assertNotIn(env["ANDROID_KEY_PASSWORD"], args)
                    shutil.copyfile(source, args[args.index("--out") + 1])
                return subprocess.CompletedProcess(args, 0)
            with mock.patch.object(release, "ROOT", root), mock.patch.dict(os.environ, env), mock.patch.object(release.subprocess, "run", side_effect=fake_tool):
                output = release.sign_apk("v1.2.0", source, root / "dist")
            self.assertEqual(output.name, "tv-dashboard-1.2.0-universal.apk")
            self.assertTrue(output.with_suffix(".apk.sha256").exists())
            self.assertEqual(len(keys), 1)
            self.assertFalse(keys[0].exists())

    def test_signing_failure_removes_temporary_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "gradle.properties").write_text(PROPERTIES)
            source = root / "unsigned.apk"
            make_apk(source, release.ARM_ABIS)
            env = {"ANDROID_HOME": str(root), "RUNNER_TEMP": str(root), "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"key fixture").decode(), "ANDROID_KEYSTORE_PASSWORD": "fixture", "ANDROID_KEY_PASSWORD": "fixture", "ANDROID_KEY_ALIAS": "fixture"}
            with mock.patch.object(release, "ROOT", root), mock.patch.dict(os.environ, env), mock.patch.object(release.subprocess, "run", side_effect=subprocess.CalledProcessError(1, ["zipalign"])):
                with self.assertRaises(subprocess.CalledProcessError):
                    release.sign_apk("v1.2.0", source, root / "dist")
            self.assertEqual(list(root.glob("tv-dashboard-sign-*")), [])


if __name__ == "__main__":
    unittest.main()
