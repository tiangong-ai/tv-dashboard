---
docType: guide
scope: repo
status: current
authoritative: true
owner: tv-dashboard
language: en
whenToUse: "When using or maintaining TV Dashboard."
whenToUpdate: "When configuration, supported devices, builds or release behavior changes."
checkPaths: [app/**, config/**, scripts/**, tests/**, .github/**, gradle.properties]
lastReviewedAt: 2026-10-02
lastReviewedCommit: fa94e49
---

# Tiangong TV Dashboard

[中文](README.zh-CN.md)

An Android TV kiosk client for web dashboards, with a bundled GeckoView browser
engine so it does not depend on the TV's system WebView.

- Fullscreen display and screen-on while foregrounded.
- Remote-control Menu/Back opens refresh, display, workbench, URL settings and system information.
- Failed document loads retry after 15 seconds. Stopped content processes are recreated in the foreground; background recovery waits until resume.
- One APK supports **32-bit ARM (armeabi-v7a) and 64-bit ARM (arm64-v8a)** on Android 8+.
- The Tiangong logo is used directly as the application and TV launcher icon.

## Install

Download the signed APK from [GitHub Releases](https://github.com/tiangong-ai/tv-dashboard/releases), copy it to a USB drive, and open it with your TV's file manager.
An update signed by this project can be installed over the previous app.

The default server is **http://192.168.1.12:8787/**. Launch opens `/display/`;
Workbench opens `/`. This repository contains the client only: run a compatible
dashboard server separately and connect the TV to a network that can reach it.
Use Menu/Back → 修改看板地址 to set the startup URL on a TV. Saved device settings
survive upgrades and take precedence over a new compiled default.

## Configure the build

Edit `config/dashboard.properties`:

```properties
dashboardBaseUrl=http://192.168.1.12:8787/
```

Or override it for a build:

```sh
./gradlew :app:assembleRelease -PdashboardBaseUrl=https://dashboard.example.org/
```

The URL must use HTTP or HTTPS and contain no credentials, query or fragment.
A trailing slash is normalized. A base path is supported, for example
`https://example.org/monitor/` opens `https://example.org/monitor/display/`.
The bundled layout adjustment applies only to recognized Tiangong display pages;
other pages keep their original layout.

## Build and validate

Use JDK 17, Python 3.11+, Android SDK Platform 37.1 and Build Tools 36.0.0.
Install the SDK through Android Studio or Google's command-line tools, then set
`ANDROID_HOME` or `sdk.dir` in untracked `local.properties`.
The Gradle wrapper and exact dependency versions are checked in.

```sh
python3 -m unittest discover -s tests -v
./gradlew :app:assembleRelease :app:lintRelease
python3 scripts/check_config.py --gradle
```

The unsigned output is `app/build/outputs/apk/release/app-release-unsigned.apk`.
A universal ARM APK is the default. No x86, x86_64 or legacy armeabi binaries are
included. See [release and signing instructions](docs/releasing.md) for signed
builds and tag-triggered publication.

## Scope and verification

The client does not provide server authentication, data collection or dashboard
hosting. It does not autostart at boot or prevent users leaving through the
native menu. Website API failures remain the website's responsibility.

Earlier versions were used on a 32-bit Android 10 Xiaomi TV. New public builds
are checked with build/lint, URL/release tests and signed APK inspection;
ARM64 emulator checks are not a substitute for physical ARMv7 testing.

## Contribute

Read [AGENTS.md](AGENTS.md), run the validation commands above, and update the
user/release docs when behavior changes. PR CI builds without signing secrets.
Workspace integration lives in [workspace-suite](https://github.com/tiangong-ai/workspace-suite).

The application code is MIT licensed. GeckoView is MPL-2.0; see
[third-party notices](NOTICE.md) and [source references](docs/third-party.md).
