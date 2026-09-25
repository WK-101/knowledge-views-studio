# Releasing Parley and Parley Lists

This repository builds two apps, and both are released from it:

| | Parley | Parley Lists |
|---|---|---|
| Package | `app.parley.phone` | `app.parley.lists` |
| Gradle module | `parley-phone/app` | `parley-phone/lists-updater` |
| Tag | `v3.2.0` | `lists-v1.1.1` |
| Release asset | `Parley-3.2.0.apk` | `ParleyLists-1.1.1.apk` |
| Store texts | `parley-phone/fastlane/metadata/android/` | `parley-phone/lists-updater/fastlane/metadata/android/` |
| F-Droid recipe | [`fdroid/app.parley.phone.yml`](../fdroid/app.parley.phone.yml) | [`fdroid/app.parley.lists.yml`](../fdroid/app.parley.lists.yml) |

Both apps are signed with **the same key**. This is required, not a convenience: Parley reads the spam lists through
the signature permission `app.parley.permission.READ_LISTS`, which Android grants only when both apps carry the same
certificate. The certificate's SHA-256 is

```
f5349c318536b08fdeb3315e336ca35136c02f58e2c08d554def75658104284a
```

The F-Droid recipes list this value as `AllowedAPKSigningKeys`.

The rest of this page covers:
1. Versions and tags
2. Signing
3. Release checklist
4. Reproducible builds
5. Where the store texts live
6. Submitting to F-Droid
7. Review pitfalls

## 1. Versions and tags

- **`versionCode` and `versionName` are plain literals** in `app/build.gradle.kts` and `lists-updater/build.gradle.kts`:
  `versionCode = 5` and `versionName = "3.2.0"`. F-Droid's update check reads the build file line by line with a
  regex (`\b[Vv]ersionCode\s*=?\s*["'(]*([0-9][0-9_]*)` and the matching one for `versionName`). It can't follow a
  variable, a `val`, an environment variable or an expression, and when it fails it reports "Couldn't find any
  version information". Don't move them into a variable; there's a comment next to each saying so.
- `versionCode` goes up by one for every release of that app. The two apps count separately.
- **Tags:** `v<versionName>` for Parley (`v3.2.0`); `lists-v<versionName>` for Parley Lists (`lists-v1.1.1`). The
  recipes use `UpdateCheckMode: Tags ^v[0-9.]+$` and `Tags ^lists-v[0-9.]+$`, so neither app ever picks up the
  other's tags. Never create other tags that match `^v[0-9.]+$` (use `v3.2.0-rc1` style names for tests).
- F-Droid reads the version from the build file **at the tagged commit**, not from the tag's name. The tag and the
  literal must agree.
- Each release needs `changelogs/<versionCode>.txt` in every locale folder (see §5). Keep each under 500 characters.
- If a release changes the other app's build file (for example the G1 signing fix in 3.2.0 also touched Parley
  Lists), that app gets a release of its own too: Lists went to 1.1.1 (versionCode 3) for 3.2.0.

Versions so far:

| Parley | versionCode | | Parley Lists | versionCode |
|---|---|---|---|---|
| 1.0.0 | 1 | | 1.0.0 (with Parley 3.0.0) | 1 |
| 2.0.0 | 2 | | 1.1.0 (with Parley 3.1.0) | 2 |
| 3.0.0 | 3 | | 1.1.1 (with Parley 3.2.0) | 3 |
| 3.1.0 | 4 | | | |
| 3.2.0 | 5 | | | |

## 2. Signing

The keystore and its passwords are **never committed**: `.gitignore` excludes `keystore.properties`, `*.jks`,
`*.keystore` and `dist/`. Signing is configured in one of two ways.

A `keystore.properties` file in `parley-phone/` (the path is relative to `app/`, and one file serves both apps):

```
storeFile=/absolute/path/parley-release.jks
storePassword=...
keyAlias=parley
keyPassword=...
```

or the environment variables `PARLEY_KEYSTORE`, `PARLEY_KEYSTORE_PASSWORD`, `PARLEY_KEY_ALIAS` and
`PARLEY_KEY_PASSWORD`.

When neither is present, `assembleRelease` produces an unsigned `*-release-unsigned.apk`. That is what F-Droid builds.

**Why the build file looks the way it does (G1).** Before it builds, F-Droid runs `remove_signing_keys`. This deletes
every `signingConfigs {` block and every line that starts with `signingConfig =` from every `build.gradle(.kts)` in
the repository. Any line that survives the strip and still refers to the config breaks F-Droid's build with
"SigningConfig with name 'release' not found". Before 3.2.0 we had `val release = signingConfigs.getByName("release")`,
which is exactly that. So now:

```kotlin
val releaseStorePath: String? = keystoreProps.getProperty("storeFile") ?: System.getenv("PARLEY_KEYSTORE")
...
release {
    if (releaseStorePath != null) {
        signingConfig = signingConfigs.findByName("release")   // the only reference; F-Droid deletes this line
    }
}
```

After the strip, only an empty `if` is left.

`tools/fdroid-strip-check.sh` proves this. It copies the project to a temporary folder and strips the copy exactly as
F-Droid does. If `pip install fdroidserver` is available it uses the real `remove_signing_keys`; otherwise it uses a
bundled copy of the same regexes. The script then fails if any live `signingConfig` reference is left, and builds
both apps unsigned. `--static` skips the build and takes a few seconds; run that after any change to a build file.

```bash
tools/fdroid-strip-check.sh --static
tools/fdroid-strip-check.sh -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g --max-workers=2
```

To check which key signed an APK:

```bash
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs Parley-3.2.0.apk | grep SHA-256
```

The value must be the one at the top of this page (lowercase, without colons). If the keystore is ever lost, no
update can install over existing installs, from F-Droid or anywhere else. Keep an offline backup of the `.jks` and
the passwords.

## 3. Release checklist

For Parley `X.Y.Z` (and, when needed, Parley Lists `A.B.C`):

1. Bump the literals: `versionCode` +1 and `versionName = "X.Y.Z"` in `app/build.gradle.kts`, and the same in
   `lists-updater/build.gradle.kts` if Lists is released too.
2. Write `changelogs/<versionCode>.txt` in all 8 locale folders (en-US, de, es, fr, pt-BR, hi, ur, ar), and update
   `full_description.txt` if features changed. Check that `changelogs/5.txt` lists only what actually shipped.
3. Run the usual checks: `./gradlew :core:common:test lintDebug assembleDebug`.
4. `tools/fdroid-strip-check.sh` must pass: both apps build with the signing config stripped.
5. `tools/repro-check.sh` must report both APKs as byte-identical (§4).
6. Build the signed release: `./gradlew assembleRelease` with `keystore.properties` in place. Copy the outputs to
   `dist/Parley-X.Y.Z.apk` and `dist/ParleyLists-A.B.C.apk` (`dist/` is ignored by git), then check the certificate
   with `apksigner` (§2).
7. Compare the signed APK with an unsigned rebuild of the same commit:
   `tools/repro-check.sh --app phone --signed dist/Parley-X.Y.Z.apk`. This is the comparison F-Droid makes. With
   `pip install apksigcopier` installed it is exact.
8. Commit, then tag the release commit: `git tag vX.Y.Z` (and `git tag lists-vA.B.C`). Push the branch and the tags.
9. Create a GitHub release for each tag and attach the APK under exactly the name the recipe's `Binaries:` expects:
   `Parley-X.Y.Z.apk` on `vX.Y.Z`, `ParleyLists-A.B.C.apk` on `lists-vA.B.C`. Never delete or replace an asset
   after F-Droid has used it.
10. F-Droid only: after the first inclusion, nothing more is needed. `UpdateCheckMode: Tags` plus
    `AutoUpdateMode: Version` notice the new tag, add a build entry, build it, compare it with the `Binaries` APK and
    publish with our signature, usually within a few days.

If a build-affecting change has to go in after tagging, move the tag (delete the release and the tag, then recreate
them on the new commit), rebuild and re-upload the signed APK, and update `commit:` in the recipe.

## 4. Reproducible builds

**How it works on F-Droid.** With `Binaries:` and `AllowedAPKSigningKeys:` in the recipe:
1. F-Droid builds the tag from source, unsigned, after stripping the signing config.
2. It downloads our APK from the `Binaries` URL (`%v` = versionName, `%c` = versionCode).
3. It checks that our APK is signed by a certificate in `AllowedAPKSigningKeys`.
4. It copies our signature onto its own build (`apksigcopier`) and verifies the result. This works only if the two
   APKs are identical apart from the signature.
5. If they match, F-Droid publishes our signed APK. If not, that version isn't published, and the build log shows
   the difference.

We need this for both apps. Without it, F-Droid signs each app with **its own per-app key**, so Parley and Parley
Lists would have different signatures and the lists permission would never be granted. Users also couldn't move
between F-Droid and GitHub installs without uninstalling.

**What keeps our build deterministic:**
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`: no Google-encrypted dependency blob.
- No build timestamps, VCS info, generated dates or `BuildConfig` fields that change per build; no native code.
- R8 (minify + resource shrinking) is on and deterministic for the same inputs, AGP and JDK.
- Baseline profiles come only from the libraries' committed profiles. Nothing is generated from a device.
- `org.gradle.caching=true` is fine for local work. `tools/repro-check.sh` turns the cache off.

**The check.** `tools/repro-check.sh` copies the project into two separate folders, with different absolute paths as
on F-Droid's server. It runs a clean unsigned release build in each, with no build cache and no keystore, and
compares:
- the APKs' SHA-256;
- if the hashes differ, the unzipped contents (ignoring the `META-INF` signature files) and the zip entry lists.

`--signed <apk>` also compares an unsigned build with our signed APK (`apksigcopier compare` when installed).

```bash
tools/repro-check.sh -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.daemon.jvmargs=-Xmx1g --max-workers=2
```

**Results:**

| Date | Commit | Toolchain | Parley | Parley Lists |
|---|---|---|---|---|
| 2026-09-25 | `efb4b71` (3.1.0 code plus the G1 fix; Lists 1.1.1) | OpenJDK 21.0.10, Gradle 8.14.3, AGP 8.13.2, Linux | byte-identical (`9d85beb2…96d2d354`) | byte-identical (`cf4a7260…97580133`) |

Nothing needed fixing: the two builds, in different folders with no cache, produced the same bytes. Check again
before every release (§3), because a new dependency or plugin can bring in nondeterminism.

The JDK matters: F-Droid builds with the build server's JDK, and a different JDK can change the output. If an
F-Droid build doesn't reproduce and the diff points at compiled classes, add a `sudo:` step to the recipe to install
the JDK we used (for example `apt-get install -y openjdk-21-jdk-headless` and `update-alternatives --auto java`), and
record the JDK in the table above.

## 5. Where the store texts live

F-Droid reads the fastlane layout: `title.txt` (≤ 50 characters), `short_description.txt` (≤ 80),
`full_description.txt` (≤ 4000, simple HTML such as `<b>` and `<i>`), `changelogs/<versionCode>.txt` (≤ 500) and
`images/phoneScreenshots/*.png`. Locale folders use F-Droid's names: `en-US`, `de`, `es`, `fr`, `pt-BR`, `hi`, `ur`,
`ar`. Screenshots are placeholders for now; see the README in each `phoneScreenshots` folder. Other locales fall back
to the en-US screenshots.

- Parley: `parley-phone/fastlane/metadata/android/<locale>/`
- Parley Lists: `parley-phone/lists-updater/fastlane/metadata/android/<locale>/`. It gets its own tree next to its
  module, so the two apps' texts never mix.

**Monorepo limitation.** fdroidserver scans only the **root** of the source repository for texts: `fastlane/`,
`src/<flavor>/fastlane/` and `metadata/<locale>/` (see `insert_localized_app_metadata` in `update.py`). It also reads
`metadata/<appid>/<locale>/` inside fdroiddata. Parley lives in `parley-phone/` of a larger repository, so F-Droid
won't find either tree by itself. Until that changes, the MR for each app copies its tree into fdroiddata:

```bash
# in a fdroiddata checkout; PARLEY=path/to/knowledge-views-studio/parley-phone
mkdir -p metadata/app.parley.phone metadata/app.parley.lists
cp -r "$PARLEY"/fastlane/metadata/android/* metadata/app.parley.phone/
cp -r "$PARLEY"/lists-updater/fastlane/metadata/android/* metadata/app.parley.lists/
find metadata/app.parley.* -name README.md -delete
```

The texts in fdroiddata take precedence over the source repository's. The downside is that every new changelog needs
a small fdroiddata MR. The clean fix is to give Parley its own repository with `parley-phone/` as the root
(`git subtree split --prefix=parley-phone`). Then F-Droid reads `fastlane/` for Parley directly. Parley Lists keeps
its texts in fdroiddata, which also overrides the root texts it would otherwise inherit from Parley. Update `Repo`,
`SourceCode`, `Binaries` and `subdir` (to `app` and `lists-updater`) if that happens.

## 6. Submitting to F-Droid

Each app is a **separate** fdroiddata merge request. Submit Parley first, then Parley Lists; its description should
say that it's the companion to `app.parley.phone`.

1. **Fork** `https://gitlab.com/fdroid/fdroiddata`. Make a branch named after the app id (`app.parley.phone`).
2. **Add the recipe:** copy `fdroid/app.parley.phone.yml` to `metadata/app.parley.phone.yml`. Delete the comment
   header, and set `commit:` to the **full 40-character hash** that `v3.2.0` points to (`git rev-parse v3.2.0^{commit}`).
   Reviewers reject tags and branches here. Add the texts as in §5.
3. **Validate locally** (Docker image `registry.gitlab.com/fdroid/fdroidserver:buildserver`, or
   `pip install fdroidserver`):
   ```bash
   fdroid rewritemeta app.parley.phone   # commit its output as is; field order and blank lines matter
   fdroid lint app.parley.phone          # outside a full fdroiddata checkout it wrongly calls every category invalid
   fdroid checkupdates --allow-dirty app.parley.phone   # should find 3.2.0 (5) at v3.2.0
   fdroid build -v -l app.parley.phone   # the real build, including the strip and the scanner
   ```
   A quick version-parser check without fdroiddata:
   ```python
   import fdroidserver.common as c, fdroidserver.metadata as m
   from pathlib import Path
   a = m.App(); a['id'] = 'app.parley.phone'
   print(c.parse_androidmanifests([Path('parley-phone/app/build.gradle.kts')], a))   # ('3.2.0', 5, 'app.parley.phone')
   ```
4. **Open the MR** into `fdroid/fdroiddata:master`, titled `New app: Parley (app.parley.phone)`, using the "App
   inclusion" template. Keep `/label ~"New App"`.
5. **Watch the pipeline.** It runs about nine jobs (fdroid build, checkupdates, rewritemeta, lint, schema validation,
   check apk, check source code, …). Fix the first red one, push, and read the pipeline again.
6. Repeat for `app.parley.lists` with `fdroid/app.parley.lists.yml`, `lists-v1.1.1` and the Lists texts.

After the merge, the app appears in the F-Droid client within one or two days. Later releases need no MR, only the
tag and the release asset (§3). The exception is new store texts while §5's limitation applies.

## 7. Review pitfalls

Every item here has broken someone's F-Droid submission:

- **Signing config referenced outside the stripped lines**: "SigningConfig 'release' not found". Fixed by G1 and
  checked by `tools/fdroid-strip-check.sh`.
- **Version not a literal**: checkupdates says "Couldn't find any version information". Keep the literals (§1).
- **Tag or branch in `commit:`**: use the full hash.
- **CRLF line endings** in the recipe make `rewritemeta` fail with a diff where every `-` line ends in `^M`. They can
  also make `fdroid build` fail after "BUILD SUCCESSFUL". Upload the file with GitLab's "Replace" (raw upload), not
  the web text editor. `grep -c $'\r' file` should print 0.
- **`AutoUpdateMode: Version v%v`** fails schema validation; use bare `Version`, as the recipes do.
- **Missing `AutoName`**: checkupdates fails with a diff that adds it. The recipes already have `AutoName: Parley` and
  `AutoName: Parley Lists`.
- **Categories** must exist in fdroiddata's category list and be in alphabetical order. We use `Phone & SMS`; if a
  reviewer suggests another (a contacts category, for example), take it with "Apply suggestion".
- **`WebSite` equal to `SourceCode`**: reviewers remove it, so the recipes leave it out.
- **Other files in the repository.** The repository also holds unrelated projects and a `Test/` folder with
  third-party APKs. F-Droid's scanner deletes `*.apk` files before building, so the build isn't blocked, but
  reviewers may ask about them. Answer that the app is self-contained in `parley-phone/`, or move Parley to its own
  repository (§5). Never commit an APK, JAR or AAR inside `parley-phone/`. The one exception is
  `gradle/wrapper/gradle-wrapper.jar`: the scanner removes it and F-Droid uses its own Gradle of the same version.
- **Anti-features.** Parley has none: no network, no trackers, no non-free dependencies (ZXing is Apache-2.0).
  Parley Lists uses the internet only to fetch public-sector open data (FTC, ARCEP) and links the user adds, with no
  account or key. Reviewers may still ask about `NonFreeNet`; the answer is that the services and the data are
  public, not proprietary.
- **Both apps need the same signature.** If only one of them reproduces, F-Droid signs the other with its own key and
  the lists permission stops working. Treat a reproducibility failure in either app as a release blocker.
- **Don't delete `Binaries` assets.** F-Droid downloads them again when it rebuilds.
- **Build memory.** F-Droid's `assembleRelease` runs `lintVitalRelease` and R8. `gradle.properties` asks for a 4 GB
  heap; if the build server runs out of memory, reviewers may add `gradleprops` or a `prebuild` step that lowers it.
