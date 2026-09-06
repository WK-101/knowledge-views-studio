# Contributing to Cairn

Thanks for your interest. Cairn is a privacy-first, on-device Android reader; contributions
should keep it that way.

## Principles

1. **On-device only.** No analytics, no accounts, no cloud services, no new network calls to
   third parties without an explicit, off-by-default opt-in and in-app disclosure.
2. **Smarter without AI.** Prefer deterministic, classical algorithms over cloud AI.
3. **Nothing locked in.** New data should be exportable (JSON/Markdown/EPUB) where it makes
   sense.
4. **Fail loud, locally.** Log errors via `util/AppLog` (never swallow them silently); surface
   user-relevant failures in the UI.

## Development

- JDK 17, Android SDK (compileSdk 36).
- Build: `./gradlew :app:assembleDebug`
- Before opening a PR, run the same gates CI runs:
  ```bash
  ./gradlew :app:testDebugUnitTest :app:lintDebug
  ```
- New deterministic/domain logic should come with JVM unit tests under `app/src/test/`.
- Lint runs against `app/lint-baseline.xml`; **new** issues fail the build. Fix them rather
  than widening the baseline, unless the finding is a genuine false positive.

## Style

- Kotlin official style; match the surrounding code's naming, comment density, and idioms.
- Keep Composables focused; hoist state; pass stable `key`s to lazy lists.
- Keep UI strings ready for externalization to `res/values/strings.xml` (i18n is in progress).

## Architecture

See [`ARCHITECTURE.md`](ARCHITECTURE.md). Respect the `ui → domain → data` layering: business
logic belongs in repositories/domain, not in Composables.

## Commits & PRs

- Small, focused commits with clear messages.
- Describe user-facing impact and note any migration or permission changes.
- Never commit secrets or a keystore; release signing comes from env/`keystore.properties`.
