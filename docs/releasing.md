---
docType: runbook
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

# Build, sign and release

## Release contract

Publish only reviewed commits on `main`. Update `dashboardVersion` and increment
`dashboardVersionCode` in `gradle.properties`; never reuse an existing release
tag or APK version code. The first public version is 1.2.0 (code 4).

PR and main CI run tests, Android lint and a universal ARM release build, then
retain an **unsigned** artifact for inspection. Release publication is triggered
by a pushed `v*` tag. It verifies tag/version equality and main ancestry, reruns
validation, signs the universal APK, verifies its signature and alignment, and
publishes `tv-dashboard-<version>-universal.apk` in a GitHub Release with an adjacent `.apk.sha256` checksum. Tags/releases are immutable;
fix an already published error with a new patch version.

This follows the organization's CLI and Wiki conventions of `v*` tag triggers,
version equality checks, validation before publication and restricted job
permissions. APK distribution uses GitHub Releases rather than npm.

## Signing configuration

The repository owner configures Actions secrets:

| Secret | Content |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoded private JKS/PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing alias |
| `ANDROID_KEY_PASSWORD` | Private key password |

Use the same key for every update. Existing development APKs and the public
release use the same signing identity; secret material stays outside Git.
Release signing decodes the keystore only under the runner's temporary directory,
never into an uploaded artifact. PR workflows have no access to these secrets.
Never print environment variables, base64 key data, passwords or keystore files.
Keep an offline backup of the key; GitHub secrets cannot be downloaded later.

## Publish

1. Open a PR with the version/configuration changes and updated documentation.
2. Require CI, documentation checks and review before merging.
3. Fetch `origin/main`, check out the merged commit and confirm a clean worktree.
4. Confirm the release tag does not exist locally or remotely.
5. Create and push an annotated tag matching the version:

```sh
git tag -a v1.2.0 -m 'Release 1.2.0'
git push origin v1.2.0
```

6. Wait for the Release workflow. Verify the resulting tag SHA, APK version,
   both ARM libraries, signing certificate and downloaded checksum.
7. Record the exact released child commit in workspace-suite through its
   integration workflow.

The published root URL comes from `config/dashboard.properties`; override
builds are possible locally, while official tags publish the reviewed default.
Already installed devices preserve their saved startup URL.

## Local validation and recovery

`./gradlew :app:assembleRelease :app:lintRelease` does not require signing keys.
Use `python3 -m unittest discover -s tests -v` for URL and release guard tests.
Keep signed binaries out of Git. Inspect with `aapt dump badging`,
`apksigner verify --verbose --print-certs`, and `zipalign -c -P 16 4` from Build
Tools 36.0.0. The release script provides the same signing checks in CI.

If publication fails, inspect the exact run and remote release before retrying.
Do not replace existing assets or retarget a tag. A run failure before any public
release can be retried on the same immutable tag if its code is correct; code
fixes require a new reviewed version and tag.
