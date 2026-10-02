#!/usr/bin/env python3
"""Run real URL/configuration checks. --gradle also checks generated BuildConfig."""
import argparse
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gradle", action="store_true", help="also validate Gradle overrides; requires the Android build toolchain")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    if args.gradle:
        env["DASHBOARD_TEST_GRADLE"] = "1"
    return subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "tests", "-p", "test_config.py", "-v"],
        cwd=root, env=env, check=False,
    ).returncode


if __name__ == "__main__":
    raise SystemExit(main())
