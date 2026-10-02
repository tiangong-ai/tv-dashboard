---
docType: agent-contract
scope: repo
status: current
authoritative: true
owner: tv-dashboard
language: en
whenToUse: "Before changing the Android TV client, build configuration or release automation."
whenToUpdate: "When ownership, validation commands or delivery constraints change."
checkPaths: [app/**, config/**, scripts/**, tests/**, .github/**, .docpact/**]
lastReviewedAt: 2026-10-02
lastReviewedCommit: fa94e49
---

# TV Dashboard contributor contract

This repository owns the Android TV client, not the dashboard server. Read
`.docpact/config.yaml`, `README.md` and `docs/releasing.md` before implementation.
Use `scripts/docpact route --root . --paths <csv> --format json` to locate affected
documentation. Workspace integration policy lives in workspace-suite.

Preserve HTTP(S) URL validation, remote-control navigation, same-origin menu
routes, fullscreen display and foreground-only recovery. Keep the bundled Gecko
engine current with Android 8+ and both ARM ABIs. Never treat an ARM64 emulator
pass as evidence of physical ARMv7 TV execution.

Use `./gradlew :app:assembleRelease :app:lintRelease` and
`python3 -m unittest discover -s tests -v`. Validate positive and invalid custom
root configuration before release. Inspect final APK ABIs, version, signature
and checksum. Run `scripts/docpact validate-config --root . --strict` and scoped
`lint --staged` or `--base <sha> --head HEAD --mode enforce`.

Never commit keystores, passwords, local SDK paths, device screenshots containing
internal infrastructure, or local build outputs. Preserve signing identity for
updates. PR CI must never access release secrets. Publish immutable v<version>
tags from reviewed main commits only; release tags must match gradle.properties.
