# Kairo — Reproducible build & APK verification

Kairo is a privacy-first, fully-offline app, so "is the APK I installed actually built from this source?"
is a question the project should let anyone answer for themselves. This document describes how the release
APK is produced and how to verify a build hash.

## What makes the build deterministic

- **Pinned toolchain.** AGP and the Kotlin compiler are pinned in the root `build.gradle.kts` plugins
  block, and `compileSdk`/`targetSdk` plus every dependency version are pinned directly in
  `app/build.gradle.kts` (this project has no `gradle/libs.versions.toml` version catalog) — no dynamic
  (`+`) versions, and only `google()` / `mavenCentral()` / `gradlePluginPortal()` over HTTPS, no snapshot
  repos.
- **One non-code input to expect.** `versionCode`/`versionName` derive from the `GITHUB_RUN_NUMBER`
  environment variable, so a third-party rebuild gets `versionCode=1` / `versionName=0.1.0` and a
  correspondingly different binary manifest. BuildConfig generation is off, so the version is not compiled
  into the dex — which is why the `classes*.dex` comparison below still matches across rebuilds.
- **No network at build-injection time.** The app declares no `INTERNET` permission and pulls in no
  networking transitive; the build does not fetch code at assembly time beyond the pinned dependency graph.
- **R8 is code+resource shrinking only — obfuscation is off** (`proguard-rules.pro`), so class and method
  names in the shipped APK match the source, which keeps two builds of the same source comparable.
- **Locale pinned to `en`** (`resourceConfigurations += "en"`), so the resource table doesn't vary with the
  build machine's locale set.

## Reproduce it

```bash
# 1. Check out the exact commit the release was cut from.
git checkout <release-commit>

# 2. Build the release APK from the android/ module root.
cd android
./gradlew :app:assembleRelease

# 3. Hash it.
sha256sum app/build/outputs/apk/release/app-release.apk
```

## The one variable you must account for: signing

The APK is signed with the project's release keystore (`keystore.properties` / `KEYSTORE_*` env). The
**signing block is not reproducible by a third party** — you don't have the private key, and you shouldn't.
So two things follow:

- A byte-identical hash is only expected between builds signed with the **same** key.
- To compare *your* rebuild against a published APK without the key, compare the **unsigned contents**, not
  the whole file:

```bash
# Extract and hash everything except the signature block from both APKs, then diff.
unzip -p their-app-release.apk 'classes*.dex' | sha256sum
unzip -p your-app-release.apk  'classes*.dex' | sha256sum
# (or use `apksigner verify --print-certs` to confirm the signer, and `diffoscope` to compare the zips
#  ignoring META-INF/*.SF/*.RSA/*.MF.)
```

A build that refuses to configure a real keystore is **rejected** by a build-time guard (see
`app/build.gradle.kts`): a release must never be signed with the debug key. Pass `-PallowInsecureSigning`
only for a deliberate throwaway build.

## Publishing the hash

Each tagged release publishes the SHA-256 of its signed `app-release.apk` alongside the artifact, and the
signer certificate fingerprint (`apksigner verify --print-certs app-release.apk`). Verify both before
trusting an APK you didn't build yourself:

```bash
sha256sum app-release.apk                       # must match the published hash
apksigner verify --print-certs app-release.apk  # signer cert must match the published fingerprint
```

_This document describes intent and current build configuration; `app/build.gradle.kts` is the ground truth._
