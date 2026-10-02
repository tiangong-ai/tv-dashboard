"""Exercise the runtime helper directly and, optionally, actual Gradle configuration."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "org.tiangong.tvdashboard.DashboardUrlsCheck"


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    return str(Path(java_home) / "bin" / name) if java_home else name


def configured_root():
    values = dict(line.split("=", 1) for line in (ROOT / "config/dashboard.properties").read_text().splitlines()
                  if line.strip() and not line.lstrip().startswith("#"))
    return values["dashboardBaseUrl"]


class JavaUrlCheck:
    @classmethod
    def setUpClass(cls):
        cls.compiled = tempfile.TemporaryDirectory(prefix="dashboard-url-check-")
        sources = [ROOT / "app/src/main/java/org/tiangong/tvdashboard/DashboardUrls.java",
                   ROOT / "app/src/test/java/org/tiangong/tvdashboard/DashboardUrlsCheck.java"]
        subprocess.run([java_tool("javac"), "-encoding", "UTF-8", "-d", cls.compiled.name,
                        *map(str, sources)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.compiled.cleanup()

    def java(self, *args):
        return subprocess.run([java_tool("java"), "-cp", self.compiled.name, PACKAGE, *args],
                              check=True, capture_output=True, text=True).stdout.strip()


class DashboardUrlTests(JavaUrlCheck, unittest.TestCase):
    def test_real_runtime_url_validation_and_relative_navigation(self):
        self.assertIn("Passed", self.java())

    def test_checked_in_dashboard_root(self):
        normalized = self.java("root", configured_root())
        self.assertNotEqual("null", normalized, "Checked-in dashboard root must be valid")
        self.assertTrue(normalized.endswith("/"), normalized)


@unittest.skipUnless(os.environ.get("DASHBOARD_TEST_GRADLE") == "1", "enable with scripts/check_config.py --gradle")
class GradleConfigTests(JavaUrlCheck, unittest.TestCase):
    def gradle(self, *args):
        return subprocess.run([str(ROOT / "gradlew"), "--console=plain", *args], cwd=ROOT,
                              capture_output=True, text=True, check=False)

    def test_checked_in_and_custom_roots_are_generated_and_normalized(self):
        for override in (None, "https://dashboard.example.org/tv%20wall"):
            with self.subTest(override=override):
                args = [] if override is None else ["-PdashboardBaseUrl=" + override]
                result = self.gradle(":app:generateDebugBuildConfig", *args)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                normalized = self.java("root", configured_root() if override is None else override)
                build_config = (ROOT / "app/build/generated/source/buildConfig/debug/org/tiangong/tvdashboard/BuildConfig.java").read_text()
                self.assertIn('DASHBOARD_BASE_URL = "' + normalized + '";', build_config)
                self.assertIn('DEFAULT_DASHBOARD_URL = "' + normalized + 'display/";', build_config)

    def test_invalid_build_roots_are_rejected(self):
        for value in ("ftp://host/", "http://user:secret@host/", "http://host/?a=1", "http://host/#fragment", "http://host:65536/"):
            with self.subTest(root=value):
                result = self.gradle(":app:tasks", "-PdashboardBaseUrl=" + value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("dashboardBaseUrl must be an HTTP(S) root", result.stdout + result.stderr)

    def test_unsupported_abis_are_rejected(self):
        for value in ("x86", "armeabi", "arm64-v8a,", "armeabi-v7a,armeabi-v7a"):
            with self.subTest(abis=value):
                result = self.gradle(":app:tasks", "-PdashboardAbis=" + value)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("dashboardAbis must contain only", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
