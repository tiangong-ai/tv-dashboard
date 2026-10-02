#!/usr/bin/env python3
"""Guard and sign tag releases without storing credentials in the checkout."""

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TAG_PATTERN = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
ARM_ABIS = {"armeabi-v7a", "arm64-v8a"}


def release_version(tag, properties):
    if not TAG_PATTERN.fullmatch(tag):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH without a prerelease suffix")
    versions = re.findall(r"^dashboardVersion\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(versions) != 1 or tag != "v" + versions[0]:
        raise ValueError("Release tag must match the single dashboardVersion in gradle.properties")
    codes = re.findall(r"^dashboardVersionCode\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(codes) != 1 or not re.fullmatch(r"[1-9][0-9]*", codes[0]):
        raise ValueError("gradle.properties must have one positive dashboardVersionCode")
    return versions[0]


def validate_tag(tag):
    version = release_version(tag, (ROOT / "gradle.properties").read_text())
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    tagged = subprocess.check_output(["git", "rev-parse", f"refs/tags/{tag}^{{}}"], cwd=ROOT, text=True).strip()
    if head != tagged:
        raise ValueError("Checked-out HEAD must match the release tag")
    # Releases must come from integrated main, never from an unmerged feature branch.
    subprocess.run(["git", "merge-base", "--is-ancestor", head, "origin/main"], cwd=ROOT, check=True)
    return version


def assert_unpublished(tag, repository):
    if not TAG_PATTERN.fullmatch(tag) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid GitHub repository or release tag")
    result = subprocess.run(
        ["gh", "api", "--include", f"repos/{repository}/releases/tags/{tag}"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode == 0:
        raise ValueError("This GitHub release already exists; releases must not be overwritten")
    # A failed request is not proof that a release is absent. Only HTTP 404 is.
    if not re.search(r"^HTTP/\S+ 404(?:\s|$)", result.stdout, re.MULTILINE):
        raise ValueError("Could not verify that the GitHub release is absent")


def verify_abis(apk):
    with zipfile.ZipFile(apk) as archive:
        abis = {name.split("/")[1] for name in archive.namelist() if re.match(r"^lib/[^/]+/[^/]+\.so$", name)}
    if abis != ARM_ABIS:
        raise ValueError(f"Release APK must contain exactly both supported ARM ABIs, found {sorted(abis)}")


def write_checksum(apk):
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    apk.with_suffix(apk.suffix + ".sha256").write_text(f"{digest.hexdigest()}  {apk.name}\n", encoding="ascii")


def sign_apk(tag, source, output_dir):
    version = release_version(tag, (ROOT / "gradle.properties").read_text())
    verify_abis(source)
    required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD", "RUNNER_TEMP", "ANDROID_HOME")
    if any(not os.environ.get(name) for name in required):
        raise ValueError("Release signing requires all four Android signing secrets and runner SDK paths")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    output_dir.mkdir(parents=True, exist_ok=True)
    apk = output_dir / f"tv-dashboard-{version}-universal.apk"
    if apk.exists() or apk.with_suffix(".apk.sha256").exists():
        raise ValueError("Release output already exists")
    try:
        key_bytes = base64.b64decode(os.environ["ANDROID_KEYSTORE_BASE64"], validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ValueError("Keystore secret is not valid base64") from exc
    if not key_bytes:
        raise ValueError("Keystore secret must not be empty")
    with tempfile.TemporaryDirectory(prefix="tv-dashboard-sign-", dir=os.environ["RUNNER_TEMP"]) as directory:
        temp = Path(directory)
        key = temp / "release.jks"
        key.write_bytes(key_bytes)
        key.chmod(0o600)
        aligned = temp / "aligned.apk"
        subprocess.run([str(tools / "zipalign"), "-f", "-P", "16", "4", str(source), str(aligned)], check=True)
        subprocess.run([
            str(tools / "apksigner"), "sign", "--ks", str(key),
            "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
            "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
            "--v4-signing-enabled", "false", "--out", str(apk), str(aligned),
        ], check=True)
        subprocess.run([str(tools / "apksigner"), "verify", "--verbose", str(apk)], check=True)
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    verify_abis(apk)
    write_checksum(apk)
    return apk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("validate", "assert-unpublished", "sign"))
    parser.add_argument("--tag", default=os.environ.get("DASHBOARD_RELEASE_TAG", ""))
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY", "tiangong-ai/tv-dashboard"))
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "dist")
    args = parser.parse_args()
    try:
        if args.command == "validate":
            print(f"Validated release version {validate_tag(args.tag)} on main")
        elif args.command == "assert-unpublished":
            assert_unpublished(args.tag, args.repository)
            print("No existing release found")
        else:
            print(f"Signed release: {sign_apk(args.tag, args.apk, args.output_dir).name}")
    except (ValueError, subprocess.CalledProcessError) as exc:
        parser.exit(1, f"Release guard failed: {exc}\n")


if __name__ == "__main__":
    main()
