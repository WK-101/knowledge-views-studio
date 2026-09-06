# CLAUDE.md — working notes for this repo

Cairn is a privacy-first, **100% on-device** Android reader (RSS + read-it-later +
library). Kotlin · Jetpack Compose (Material 3) · Hilt · Room · WorkManager · DataStore.
Single module: `app/`. Read `ARCHITECTURE.md` and `PRIVACY.md` first.

## Product guardrails (do not violate)
- **On-device only.** No accounts, no app server, no analytics/telemetry, no cloud AI.
  Any new third-party network call must be opt-in, off by default, and disclosed in-app.
- **Smarter without AI.** Prefer deterministic/classical algorithms over LLM/cloud AI.
- **Nothing locked in.** Keep user data exportable (JSON / Markdown / EPUB).
- **Fail loud, locally.** Log via `util/AppLog` / `Result.orLog {}`; don't swallow errors.

## Build / verify
```bash
export ANDROID_HOME=/home/user/android-sdk
./gradlew :app:assembleDebug                     # compile
./gradlew :app:testDebugUnitTest :app:lintDebug  # the CI gates
```
- Release is R8-minified; keep `app/proguard-rules.pro` in sync when adding libraries that
  use reflection or are instantiated by name (workers, Room, JS bridges).
- Lint uses `app/lint-baseline.xml`: new issues fail; fix them rather than re-baselining.

## Conventions
- Layering `ui → domain → data`; business logic in repositories/domain, not Composables.
- UI state: `StateFlow` + `collectAsStateWithLifecycle`, unidirectional.
- Off-main threading is owned in the data/domain layer (OkHttp on IO; Room suspend/Flow;
  extraction on `Dispatchers.Default`).
- Adding a preference = field in `AppPreferences` + `Keys` entry + map read + setter
  (+ export/import in `PreferencesRepository`).
- Add a new full-screen surface as a `Destination` pane in `CairnApp` (`isPane = true`,
  add to `OWN_TOP_BAR` if it draws its own top bar, wire `renderDest` + a drawer entry).

## Gotchas
- Compose: remember correct import (`mutableStateOf` vs `mutableFloatStateOf`), pass lazy-list
  `key`s, hoist state, keep colours token-based for light/dark.
- Enumerable item state is still stored as magic strings in places (`"ARTICLE"`, `"WATCH"`,
  `"OK"`); prefer enums for new code and reconcile when you touch them.

## Release ritual (maintainer)
Bump `versionCode`/`versionName` in `app/build.gradle.kts`, build a signed release with the
project keystore, verify the signer cert, then commit + push. Update `CHANGELOG.md`.
