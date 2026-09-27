# Parley 3.4 audit: where the app stands and how to make it top level

*27 Sep 2026. Five independent, read-only audits of the full source (about 75k lines of Kotlin across 6 modules, plus 10k lines of tests). Every finding cites file:line. Before this summary was written, the most serious claims were checked again against the code. The detailed reports are in [`docs/audit/`](audit/):
[UI](audit/UI.md) · [Code](audit/CODE.md) · [Performance](audit/PERFORMANCE.md) · [Security](audit/SECURITY.md) · [Data & telecom](audit/DATA_TELECOM.md).*

## 1. Verdict

Parley is **feature-rich and fundamentally well built**:
- The privacy foundations are unusually strong. There is no network access, the build fails on forbidden permissions, backups are verified and encrypted, every PendingIntent is immutable, and there are no app backups to the cloud.
- `core/common` is a genuinely pure module, with close to 800 good tests.
- The telecom layer is carefully isolated, and there are no `GlobalScope` launches or destructive migrations.

It is **not yet top level**, for one main reason. It grew in feature waves (v3.1 to v3.4), and each wave added its own files, stores, strings, entry points and components without a consolidation step. That left several kinds of debt:
- **Coherence debt.** The same concept has several names and places, and the same idea is implemented several times.
- **Robustness debt.** There is no presentation layer, and nothing outside `core/common` is tested.
- **A few real safety and privacy bugs.** Details are in §3.

None of this requires a rewrite. It requires one release spent **consolidating rather than adding**.

### Scorecard

| Dimension | Score | In one line |
|---|---|---|
| Privacy by design | **8.5** | No network access, a permission guard, private-by-default choices, lock-screen hygiene; a few leaks (clipboard, lock gaps, plaintext notes) |
| Security engineering | **6.5** | Excellent backup format and IPC hygiene; signing-key handling, a regex hang in screening, companion-app trust and app-lock gaps drag it down |
| Data safety & correctness | **6.2** | Careful diff-only contact writes; backups miss many stores, some deletes are windowed, no transactions across stores |
| Telecom robustness | **6.8** | A solid state machine and fail-open screening; emergency calls still hit prompts, Do Not Disturb interplay, cold-start latency |
| Performance | **6.0** | Good pieces (mmap'd spam packs, paged recents, T9 narrowing); every process start pays for everything, full rescans, main-thread geocoder |
| UI consistency | **6.0** | Icons, typography and colour tokens are good; component, shape and motion tokens are bypassed; 45 hand-built top bars |
| UX coherence | **4.0** | Overlapping concepts (Favourites / Circle / Labels / Frequent), 4 names for "reach via apps", 17-item menus, 109+ settings |
| Code quality & maintainability | **5.0** | Sound modules, but a god ViewModel, 442 direct container reaches from UI, 6 phone-number key functions, 24 preference files, release-named files |
| Tests & release engineering | **4.5** | Strong pure-logic tests and reproducible builds; 0 tests in data, telecom and app; no CI; lint gates only warn |
| Localisation | **4.5** | 8 languages, but 341 strings (9%) on the most visible new screens are English-only, with lint silenced |
| **Overall** | **≈ 6 / 10** | Strong foundations and breadth; needs consolidation, hardening and polish to reach 9 |

## 2. What is genuinely good (keep it)

- **Privacy architecture:**
  - No INTERNET permission, backed by a Gradle guard.
  - A separate, optional companion app for list downloads.
  - Screening fails open with hard timeouts.
  - The private directory provider is off by default and asks for approval per app.
- **Backup format:** authenticated, encrypted, verified after writing, rotated, and restored with a preview and undo.
- **Data layer:**
  - Contact writes are diff-only, and read-only rows are respected.
  - Contact inserts isolate failures.
  - A lost archive key moves the old data aside instead of wiping it.
- **`core/common`:** pure Kotlin, well tested, and a model for the rest of the code.
- **Telecom:**
  - Every call gets exactly one screening response.
  - Call limits survive a crash.
  - The ringer boost is undone at startup.
  - Wake locks are bounded.
- **Performance building blocks:**
  - Two-stage call-log loading.
  - Debounced index building.
  - Sampled and cached avatar decoding.
  - Widgets decoded off the main thread.
  - R8 with resource shrinking.
- **UI:** consistent Rounded icons with RTL mirroring, typography tokens used everywhere, predictive back, and good TalkBack custom actions.

## 3. Must-fix now (safety, privacy, data loss)

Items marked ✔︎ were confirmed again against the code while writing this summary.

| # | Severity | Finding | Where | Fix |
|---|---|---|---|---|
| 1 ✔︎ | **High (safety)** | Emergency numbers still go through "Confirm before calling" and, on dual SIM without a default, the SIM picker. The app-lock screen has no emergency button. | `CallGate.kt:24-39`; `LockScreen` | One `EmergencyPolicy`: skip every prompt and limit for emergency numbers; add an emergency button on the lock screen |
| 2 ✔︎ | **High (security)** | The release signing key is inside the project tree (`dist/`), world-readable, with its passwords in plain text next to it. The only other copy is in a temporary cloud folder. It is git-ignored and not in git history. | `dist/`, `keystore.properties` | Move it to offline encrypted storage you control, use separate passwords, delete the text file, and keep 2 backups. Losing it means no update can install over existing installs. |
| 3 ✔︎ | **High (security)** | Wildcard block rules compile to regexes with unbounded `[0-9]*` runs. A long pattern from a shared template, QR code or list backtracks catastrophically, screening times out and fails open, and a thread is left stuck on every call. | `CallPolicy.kt:594-608` | A linear glob matcher; a test with 200 stars |
| 4 ✔︎ | **High (bug)** | The backup-failure notification uses id 4720, which is also the missed-call base id. Opening Recents cancels the backup alert, and missed-call "seen" and re-alert handling break while it shows. | `BackupWorker.kt:63`, `MissedCallNotifier.kt:52` | A notification id and channel registry with a uniqueness test |
| 5 ✔︎ | **High (data loss)** | The contact editor keeps its draft in plain `remember`, and MainActivity handles no configuration changes. Rotating, changing theme, font or language, or process death loses the edit. The save runs in a UI coroutine that is cancelled if you leave, and cancellation shows as "Save failed". | `ContactEditScreen.kt:233-248, :367` | `EditorViewModel` with `SavedStateHandle`; save as a use case in `viewModelScope`; rethrow cancellation |
| 6 | **High (data loss)** | Backups miss user data: pinned notes, preferred messenger, relation links, call notes, **private-contact call history**, temporary-contact flags, call-time and supervision settings, history settings, user spam packs. The blocked-calls log is exported but never restored. Scheduled backups can capture zero block rules in a freshly started process. | [inventory](audit/DATA_TELECOM.md#5-persistent-store-inventory-backup-restore-and-wipe) | A persistent-store registry that drives backup, restore and wipe. There is no in-app "delete all data" today. |
| 7 | **High (data)** | "Delete history for this number" only reaches the newest 3000 system call-log rows. With the archive on, older calls come back at the next sync. | `CallHistory.kt:401-447` | Delete through the provider's number filter, not the in-memory window |
| 8 | **High (behaviour)** | "Block non-contacts" ignores work-profile contacts, so real colleagues get rejected. | `CallScreener.kt:220` | Include the enterprise lookup |
| 9 | **High (calls, needs a device check)** | The incoming-call notification's `Person` has no contact URI. With Do Not Disturb set to "starred contacts only", the full-screen answer UI is probably suppressed while the phone rings. A lowered channel importance isn't detected. | `CallNotifier.kt:182` | `Person.setUri` / `addPerson`; channel health check |
| 10 | **Medium (privacy)** | The app lock doesn't lock when the app goes to the background, and content may flash before it decides. `NumberActionActivity` (exported) reveals whether a number is a contact or private contact while locked. Private-name "Allow" works from the lock screen. The backup recovery key and numbers are copied to the clipboard without the sensitive flag. | `AppLock.kt`, `NumberActionActivity`, `PrivateNameProvider.kt:97-110`, `Intents.kt:39` | Lock at `onStop`, lock-aware exported screens, authentication-required notification actions, a sensitive clip flag |
| 11 | **Medium (security)** | The companion's provider signature is never verified (`canRead()` is always true), so a fake `app.parley.lists` could feed spam packs. | `ListsUpdaterClient` | Check the package signature against Parley's own |
| 12 | **Medium (perf and calls)** | On a cold start for an incoming call, the whole data graph warms up at once and competes with screening, which has a 1.5 s budget and fails open. The ringing notification waits for screening, and the number geocoder runs on the main thread. | `ParleyApp.kt`, `AppContainer.kt`, `CallManager.kt:544` | A lean call-path graph; defer warm-up; geocode on IO |

## 4. Root causes (why this happened)

1. **Wave-driven delivery without consolidation.** Each release added its own files and names (`*V32.kt`, `strings_v34_*`), stores (about 24 preference files), registries (5 messenger lists), entry points and components, and nothing was folded back into the existing abstractions. About 1,350 item-id comments ("R4:", "P1:") record this history in the code.
2. **No presentation layer.** One `AppViewModel` exposes the whole data container, so composables do workflows, build entities and read-modify-write the database (442 `vm.c.*` calls in 89 files, 203 `scope.launch` in composables). This is why there are no app tests, the editor loses drafts, and saves get cancelled.
3. **No single source of truth for the core concepts:**
   - **Phone numbers:** six key functions, and the old `matchKey` is still used for call notes and caller lookup.
   - **Calls:** the archive feeds Recents, while the system log feeds frequents, the keypad and allowances, so their numbers can disagree.
   - **People:** Favourites, Frequent, Circle, Labels, Speed dial.
   - **Reach via apps:** four names, two sheets.
   - **Persistent stores:** no registry, so backup and wipe are incomplete.
4. **"Never crash" implemented as "never know":** 515 `runCatching`, 42 empty catches, cancellation swallowed. Failures turn into silent misbehaviour, such as missing backup sections or collided notifications.
5. **Tests stop at the module that is easy to test.** Pure logic is well covered, but the riskiest code has no tests: the contact write path, crypto, Room migrations, `CallManager`, `CallGate`, screening. There is also no CI to hold the line.
6. **Design tokens declared but not enforced.** The motion scheme is set but never read, the shape scale is bypassed (13 different corner radii), there is no spacing scale, and screens hand-roll top bars, dialogs and rows.
7. **"English now, translate later"** is suppressed per file, so 9% of strings ship untranslated on the newest and most visible screens.

## 5. Plan to top level

Four phases. Each phase ends with a release, a regression review and device testing (TESTING.md). Estimates assume the current parallel-builder workflow.

### Phase 0: Safety & trust hotfix (v3.4.1, 1–2 days)
Everything in §3 marked High that is small:
- **Emergency calls:** `EmergencyPolicy` (skip prompts and limits; lock-screen emergency button; emergency window from call start).
- **Screening:** linear glob matcher; work-profile contacts count as contacts.
- **Notifications:** id and channel registry (the 4720 clash); `Person` URI on call notifications.
- **Deleting history:** delete by number through the provider.
- **Lock and clipboard:** sensitive clipboard flag; authentication-required private-name actions; lock at `onStop`.
- **Companion:** signature check.
- **Signing key:** moved out of the tree with separate passwords (your action: keep the key and its backups offline).

### Phase 1: Consolidation release (v3.5, 2–3 weeks): *one name, one source, one place*
- **Data model unification:**
  - `PhoneIdentity`: one number key, with a migration re-keying `matchKey` data to `lineKey`.
  - `MessengerCatalog`: one registry, with a test that checks it matches the manifest.
  - `CallHistory` as the only call read model: frequents, keypad and allowances read it.
  - A **persistent-store registry** that drives backup, restore, wipe and tests. Add the missing stores to backup, plus an in-app "Delete all Parley data".
- **Presentation layer:**
  - Per-feature ViewModels, starting with Editor (`SavedStateHandle`), Contact detail, Recents and Keypad.
  - Use cases for multi-step writes, run in `viewModelScope`, with transactions around multi-store writes.
  - `AppViewModel` shrinks to navigation, events and permissions.
- **Error policy:** a `suspendRunCatching` helper that rethrows cancellation; boundary-only catches; failures surfaced in results (for example the backup report).
- **Rename by domain:** no `V32`/`v34` files or string keys; move item ids to the tracker; remove about 1,450 inline fully-qualified names.
- **UX information architecture**, from the UI audit:
  - One glossary: Favourites (quick dial), Circle (people you keep in touch with), Labels (groups). Stop the "Allow through Do Not Disturb" option from starring people as a side effect.
  - One "Message or call on…" sheet and one name.
  - Overflow menus of at most 7 items per tab, with a **Tools** page for the rest.
  - One Undo/History hub.
  - Settings reviewed down to fewer, clearer, fully searchable pages.

### Phase 2: Quality release (v3.6, 2–3 weeks): *polish, performance, proof*
- **Design system in `core/ui`:**
  - `ParleyTopBar`, `SettingsScaffold` and a row kit, `ListSectionHeader` (with heading semantics), `ConfirmDialog`, sheet and banner components.
  - Spacing, shape and motion tokens actually read.
  - One snackbar host.
  - A lint or detekt rule against raw `TopAppBar`, `AlertDialog`, `RoundedCornerShape` and `Toast` outside `core/ui`.
  - Migrate all screens.
- **Accessibility:** contrast fixes (return-to-call chip 1.7:1, lime avatar initials, Accept green), headings everywhere, 48dp targets, and "List density" that really changes density.
- **Performance:**
  - A lean call-path graph with deferred warm-up.
  - Debounced contacts observer; bulk key sweep.
  - Precompute Recents headers and Contacts sections in the ViewModel, `contentType` on lists, keypad search in the ViewModel with pre-normalised names.
  - Windowed call archive; `WhileSubscribed` flows; one constrained maintenance worker.
  - Drop appcompat (framework `BiometricPrompt`); trim geocoder languages.
  - **Baseline profile + Macrobenchmarks** (cold start, Recents fling with 3000 calls, typing on the keypad with 3000 contacts, incoming-call UI).
  - SplashScreen; StrictMode in debug.
- **Tests & CI:**
  - A GitHub Actions workflow: unit tests, lint (errors fail), hardcoded-text, locale key-parity, permission guard on `preBuild` as an allow-list, APK-size budget, reproducible-build check.
  - Robolectric tests for `ContactsRepository`, `CallGate`, `CallScreener` and `CallManager` (a pure reducer extracted to `core/common`).
  - Room migration tests 1 to 6+; crypto round trips.
  - detekt and ktlint with a baseline.
- **Localisation:** translate the 341 English-only strings, remove every `tools:ignore="MissingTranslation"`, and make missing translations a CI error. Native-speaker review of all 8 languages.

### Phase 3: Hardening & excellence (v4.0, 3–4 weeks)
- **Security:**
  - Vault key lifecycle: upgrade to user-authentication-bound keys, `setUnlockedDeviceRequired`, StrongBox where available, no destructive automatic re-seal.
  - Seal pinned and call notes, screened caller names, the journal and time-machine payloads with a small-records key.
  - Signed backups with a device key; Argon2id/scrypt KDF with a strength meter.
  - An encrypted folder-sync mode (or explicit consent for plaintext).
  - Bounded readers for every import (vCard, CSV, QR, packs, setup files).
  - Tapjacking protection; `file://` rejected on shares.
  - A `LockedActivity` base and an exported-component audit test.
  - Gradle dependency verification; signing from CI secrets or an air-gapped machine.
- **Robustness:**
  - Version-checked contact saves with a "changed elsewhere" flow.
  - A supervised-allowance ledger that fails closed.
  - Streaming, incremental folder sync and indexing for 10k contacts and 50k calls.
  - The `CallManager` refactor (a per-call session instead of about 25 parallel maps); a split `TelecomDependencies`.
- **Type-safe navigation** per feature graph; saved state for every long form.
- **Measurable targets for "top level":**

| Metric | Target |
|---|---|
| Cold start to interactive Recents | < 400 ms on a mid-range phone (Macrobenchmark) |
| Incoming call: ring to full-screen UI, cold process | < 300 ms; screening p95 < 250 ms |
| Jank in Recents fling (3000 calls) / keypad typing (3000 contacts) | < 1% slow frames |
| Crash-free / ANR-free sessions (opt-in local crash store) | > 99.9% |
| APK size | ≤ 12 MB |
| Tests | core/data, telecom and app ViewModels ≥ 60% line coverage; all migrations tested |
| Lint / detekt | 0 errors, baseline shrinking each release |
| Translations | 100% key parity in all shipped languages |
| Accessibility | All text ≥ 4.5:1 contrast; every interactive element labelled and ≥ 48dp |

## 6. Status after the four phases (4.0.0)

All four phases shipped: 3.4.1 (safety hotfix), 3.5 (consolidation), 3.6 (quality) and 4.0 (hardening). Each ended with an independent review of its changes and a fix pass, and every release passed unit tests, detekt, lint (errors fail), the hard-coded-text check, the permission allow-list and the APK-size budget.

| Dimension | Before | Now (estimate) | What changed |
|---|---|---|---|
| Privacy by design | 8.5 | **9.0** | Clipboard, lock-screen and plaintext-note leaks closed; small records sealed; tapjacking protection on sensitive screens |
| Security engineering | 6.5 | **8.5** | Key lifecycle with safe upgrades, signed backups, scrypt with a strength meter, encrypted sync with replay detection, bounded readers, `LockedActivity` and an exported-component test, dependency verification, pinned pack keys |
| Data safety & correctness | 6.2 | **8.5** | Store registry drives backup, restore and wipe; version-checked saves with "Changed elsewhere"; draft rebase after process death; incremental sync that never deletes on an incomplete listing |
| Telecom robustness | 6.8 | **8.5** | `EmergencyPolicy`; per-call `CallSession` with separate screening, ringer, limits and notification collaborators; lean call-path start |
| Performance | 6.0 | **7.5** | Lean call path, deferred warm-up, debounced observers, precomputed lists, one maintenance worker, baseline profile. Not yet measured on a device |
| UI consistency | 6.0 | **8.0** | One design kit in `core/ui` enforced by detekt rules; all screens migrated |
| UX coherence | 4.0 | **7.5** | One glossary, one "Message or call on…" sheet, 7-item menus plus Tools, one History & undo hub, searchable settings |
| Code quality & maintainability | 5.0 | **7.5** | Per-feature ViewModels and use cases, one number key, one call read model, type-safe navigation, domain naming |
| Tests & release engineering | 4.5 | **7.5** | CI workflow; Robolectric tests for data, telecom and ViewModels with fake providers; migration tests; detekt with a shrinking baseline |
| Localisation | 4.5 | n/a | Parley is English-only for now by decision; translation work is paused |
| **Overall** | **≈ 6** | **≈ 8** | The remaining points need real devices and measurement, not more code |

**Still open, and why:**
- **Device verification.** The performance targets in §5 (cold start, ring-to-UI, jank) have Macrobenchmarks but have not been run on a phone. Run them and the TESTING.md checklist (§21) on a real device before calling 4.0 final.
- **APK size.** 4.0.0 is about 13 MiB against the ≤ 12 MB target; the budget check stops growth but the geocoder data and Compose still need trimming.
- **Call-history archive key loss.** If Android loses the archive key, the archive is still set aside and a new one started (the vault and small records now keep data through transient errors). Old archive entries are not recoverable in that case; a backup is the safety net.
- **KDF.** scrypt was chosen over Argon2id, because it is available without a native library.
- **Sync limits.** A phone that joins a sync folder later cannot detect an old file that someone put back before it joined.
- **Signing.** The release key is outside the repository; keep it and its backup offline (signing from CI secrets remains optional).

## 7. Recommended next step (original)

Start with **Phase 0 (v3.4.1)** right away. It is small, it fixes the real safety and data-loss issues, and it doesn't conflict with the larger work. Then run **Phase 1**, the consolidation release, *before* adding any new features, because every new feature built on today's duplicated foundations adds to the debt described in §4.
