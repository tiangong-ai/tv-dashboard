---
docType: reference
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

# Third-party components

The app consumes the unmodified GeckoView AAR from Mozilla Maven. Corresponding
source is available at the exact revision linked in NOTICE.md, including the
upstream third-party license notices. MPL-2.0 applies to GeckoView covered source;
the app's separate code is MIT licensed.

Direct/transitive runtime families include Mozilla Gecko and NSS, Kotlin standard
library (Apache-2.0), AndroidX core/collection/lifecycle and Media3 (Apache-2.0),
SnakeYAML (Apache-2.0), and Google Play services FIDO (Google Android SDK terms).
Google Play services is not needed for ordinary dashboard navigation; the client
does not request FIDO authentication. Native Gecko components carry their own
upstream notices. This summary does not replace upstream license files.

Resolve the exact dependency graph with:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

Sources and terms:

- [Mozilla source revision](https://hg.mozilla.org/releases/mozilla-release/rev/8eb25af4acf031ab1e06abf1a912275083c820ed)
- [MPL-2.0](https://www.mozilla.org/MPL/2.0/)
- [GeckoView artifact/POM](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/157.0.20260924084938/)
- [Android SDK terms](https://developer.android.com/studio/terms)
- [Gradle license](https://github.com/gradle/gradle/blob/master/LICENSE)

Preserve upstream notices when redistributing APKs or source. Do not copy private
keystores or local test screenshots into release/source archives.
