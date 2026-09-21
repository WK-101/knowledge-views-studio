# Kairo — Whole-App Code Audit & Improvement Plan

_Comprehensive engineering audit across code quality, maintainability, scalability, data
storage, performance, UI reuse and cross-module consistency, with a phased plan to reach
"top of the line."_

---

# Round 8 — Data-compaction lever verified & closed; the last failing test cleared (2026-09-21)

Round 7 picked _"Performance & Data compaction (backup + revisions gzip, #525/#526)"_ as the next lever.
Tracing the backup byte-path end-to-end showed most of it is **already shipped — and shipped as a
deliberate design decision**. So this round banks the one genuinely-remaining safe win, records honestly
why the rest is done-by-design (rather than force-changing sound code to tick a task title), and clears
the pre-existing failing test that R7 flagged.

### Honest status of #525 / #526 after tracing the byte path
| Piece | Status | Evidence |
|---|:---:|---|
| **#525 compact JSON** (`encodeDefaults=false`, no pretty-print) | ✅ already shipped | `domain/port/Backup.kt` encoder = `Json { ignoreUnknownKeys=true; encodeDefaults=false }`, no `prettyPrint`. Drops well over half the bytes on a real store (whitespace + the many at-default columns); the reader stays tolerant of both the old pretty shape and the new compact one, so old backups still import. |
| **#525 gzip** | ✅ already shipped (encrypted); plaintext plain **by design** | `data/sync/Crypto.kt` `TCENC4` gzips the plaintext *before* AES-GCM (typically ⅓ the size); `CryptoGzipTest` pins the lossless round-trip, the size drop, and wrong-passphrase detection. The **unencrypted** path is intentionally left as plain JSON so a backup stays human-readable and importable by any tool (documented in Crypto.kt). Gzipping it would trade that interop property for a marginal win across 5+ read paths in the app's most data-sensitive corner (restore overwrites the whole store) — a deliberate non-goal, not a gap. |
| **#526 caps** (bound revision growth) | ✅ tasks already 25; notes lowered 50→25 | Task revisions were already capped at `REV_KEEP = 25` (`AppRepository`); note revisions defaulted to 50. Lowered `notesMaxRevisions` default to **25** to match — ample undo depth while halving worst-case per-note history. Existing users keep their setting; only new installs pick up 25. |
| **#526 gzip snapshots** | ⏹ deliberate non-goal | The snapshot/body blobs are already bounded by the caps above **and** they ride the backup — compressing them would push opaque gzip+base64 into the very backup #525 keeps deliberately human-readable. Not worth the surface + inconsistency for an already-bounded blob. |

**Net:** #525 is complete (compact + encrypted-gzip, both already tested); #526's valuable half (bounded
growth) is complete and now consistent across tasks and notes; the two gzip halves are deliberate
non-goals that would have degraded the readable-backup property the codebase already chose. A lever that
turned out **already banked** — recorded honestly rather than churned.

### Also cleared: the pre-existing failing test flagged in R7
`NoteRichRendererTest.blockquoteAndNestedListsAndRuleAndLink` was red on the R7 baseline (and every
baseline before it). Root cause: the renderer runs commonmark with `sanitizeUrls(true)`, which emits
`<a rel="nofollow" href="…">` — the href is present but not the first attribute, so the test's exact
`<a href="…">` literal never matched. The renderer behaviour is **correct and desirable** (a link from
imported/shared Markdown must not pass link equity to an attacker's URL), so the fix corrects the *test*:
it now asserts the href order-independently **and** pins the `rel="nofollow"` hardening, so a future
config change that silently drops it is caught here. **The unit suite is fully green again — no known
failing tests remain.**

### Scorecard delta (R7 → R8)
| Dimension | R7 | **R8** | Why |
|---|:---:|:---:|---|
| Data storage | 8.0 | **8.0** | compaction confirmed already-optimal (compact JSON + encrypted-gzip, both tested); note-revision growth now bounded to match tasks. |
| Testing | 7.5 | **7.5** | the suite is fully green again — the last failing test is fixed and a security property (`rel=nofollow` on untrusted-Markdown links) is now pinned; no net-new suites. |
| Architecture / Cross-module / Performance / UI / Security | 7.0 / 7.0 / 7.0 / 7.0 / 8.5 | **7.0 / 7.0 / 7.0 / 7.0 / 8.5** | unchanged this round. |
| **Overall** | **≈7.6** | **≈7.6** | a verification/hygiene round: the data-compaction lever is banked and the last red test is cleared. The lift to 9.5 stays the structural Architecture work (NavHost + further VM decomposition); the next unit-verifiable slice is #527 — pure habit day-set / frequency helpers. |

_The Round 7 and earlier logs follow unchanged below._

---

# Round 7 — Phase 3: the time-tracking slice carved out of the god-VM (device-verified) (2026-09-21)

The single largest self-contained slice of the 6.6k-line `AppViewModel` — time tracking, read by ~16
screens and by the VM's own capacity/recap/coach logic — is now decomposed out, in three device-verified
stages. Each stage was behaviour-preserving and confirmed by the user exercising the app (the correctness
here is interactive — running timers across surfaces, workspace isolation, rotation — not something a
startup log can show), so this ran as an interactive-checklist loop rather than the Room log loop.

### Shipped this round (Stages 1–3)
| Stage | What landed | Status | Evidence |
|---|---|:---:|---|
| **1 — seam** | A `TimeTrackingViewModel` the two dedicated Time screens use | ✅ Done | A thin type forwarding to the VM's single controller + scoped flows; zero-risk (identical state). Device-confirmed: 6/6 checks (timer start/stop/pause, cross-surface, workspace isolation, stats). |
| **2 — self-contained controller** | `TimeTrackingController` reads its own workspace-scoped data | ✅ Done | The controller no longer takes VM closures: it reads settings + the ACTIVE-workspace activities/entries straight from the repo (replicating `.scopedBy { workspaceId }`) and refreshes the habit widgets itself, so it's constructed once at the composition root. `TimeTrackingControllerTest` reworked to the 2-arg ctor (7 cases incl. workspace-scoped reassign). Device-confirmed: 7/7 checks incl. workspace isolation of timer ops. |
| **3 — lifted out** | The time surface leaves `AppViewModel` | ✅ Done | `TimeTrackingViewModel` becomes the real owner (a plain class on the VM's `viewModelScope`): it holds the workspace-scoped `timeActivities`/`timeEntries` flows and the controller's live actions. The VM deletes its 2 flow declarations + ~15 action wrappers + `pausedTrack`, declares `timeVm` early/non-lazy, and routes its ~45 internal reads through it; **17 UI files** now read time through the one object. Device-confirmed: 8/8 checks across Time, Calendar, Focus, Statistics, Day-Review, Momentum, Done, Habits, Goals, and workspace switch. |

**What this buys:** the god-VM no longer *owns* time tracking — the flows, the single controller, and the
timer actions live in one cohesive, independently-testable place, and the whole UI depends on that object
rather than reaching into `AppViewModel`'s time API. The controller went from VM-coupled (untestable in
isolation) to self-contained (7 unit tests). This is the audit's #13 (carve time-tracking out of the VM)
delivered and verified end-to-end, on the seam the codebase already had (`TimeTrackingController`).

**Honest scope:** raw line count of `AppViewModel` moved only ~25 lines (the time *substance* was ~55
lines; the ~45 internal reads gained a `timeVm.` prefix). The win is **decoupling and testability**, not a
dramatic line-count drop — the god-VM is still large, and a real NavHost (the other Phase-3 lever) remains,
genuinely interactive-verification territory.

**Test note:** the full unit suite is green except one **pre-existing** failure unrelated to this work —
`NoteRichRendererTest.blockquoteAndNestedListsAndRuleAndLink`, which fails identically on the pre-Stage-3
baseline (a Markdown-renderer test, no overlap with time tracking).

### Scorecard delta (R6 → R7)
| Dimension | R6 | **R7** | Why |
|---|:---:|:---:|---|
| Architecture & maintainability | 6.5 | **7.0** | the largest self-contained VM slice is decomposed out onto a proven seam; the time controller is self-contained + single-instance + unit-tested, and the whole UI depends on one time object, not the god-VM's time API. |
| Testing | 7.5 | **7.5** | the controller test was reworked to the self-contained ctor (still 7 cases); no net-new suites this round. |
| Data storage / Cross-module / Performance / UI / Security | 8.0 / 7.0 / 7.0 / 7.0 / 8.5 | **8.0 / 7.0 / 7.0 / 7.0 / 8.5** | unchanged this round. |
| **Overall** | **≈7.5** | **≈7.6** | the time-carve is banked and device-verified. The remaining levers to 9.5: the NavHost (interactive-verification Architecture), and the still-unbanked Performance/Data compaction (backup + revisions gzip) which is unit/round-trip verifiable — the next lever picked below. |

_The Round 6 and earlier logs follow unchanged below._

---

# Round 6 — Phase 3 groundwork: composition root + testable write-path use-cases (2026-09-20)

Phase 3 (architecture decomposition) is the ceiling-raiser and its felt half — rotation,
back-stack, state restoration under a real NavHost — is genuinely device-gated. This round banks
the half that is **not**: the dependency seam and the first write-path use-cases, each
behavior-preserving and covered by JVM/Robolectric tests that need no device.

### Shipped this round
| Step | What landed | Status | Evidence |
|---|---|:---:|---|
| **A2 — composition root** | VM dependency wiring moved out of the god-VM | ✅ Done | `AppViewModelFactory` (a `ViewModelProvider.Factory`) is now the single place the repository is resolved from the `App` locator; both `viewModel()` sites (`AppRoot`, `QuickCaptureActivity`) pass it. The VM's service-locator secondary constructor is gone — it has exactly one ctor, `(app, repo)`, and never looks up its own dependencies. `AppViewModelFactoryTest` wires an in-memory repo through the production path and asserts the guard rejects a foreign VM class. |
| **A3 — roll-forward use-case** | Recurring-completion rules pulled into a pure class | ✅ Done | `domain/task/RecurringRollForward` (`onComplete` / `onSkip` / `subtasksToReset`) owns the date-bundle advance (due/start/deadline shift by the SAME delta), the "recurrence ended?" guard, and the subtask-reset policy. `toggleComplete` and `skipOccurrence` had **two copies** of that shift; now both call the one source of truth and keep only side effects. `RecurringRollForwardTest` (pure JVM) pins the same-delta invariant — the exact regression that once left the deadline frozen and permanently overdue — plus the guards, skip-preserves-completed, and the three reset modes. |
| **A3 — cadence ease + reminder dedup** | Two more inline blocks moved | ✅ Done | `Recurrence.ease(Recur)` is the pure Q5 adaptive-cadence step, unit-tested beside `advance`; `easeCadence` is now parse→ease→encode→label. The identical "shift each absolute reminder by delta and re-arm" loop in both roll-forward paths is one private VM helper, `shiftAbsoluteReminders`. |

**Test pyramid:** +9 tests (`AppViewModelFactoryTest` ×2, `RecurringRollForwardTest` ×7) reaching the
production construction path and the most correctness-critical write-path rules — recurring roll-forward
— that the pre-existing 520 pure tests never touched. All green with the R4/R5 suites.

### Explicitly not shipped this round (needs a device, not skippable by log)
- **Parallel per-feature ViewModels** (audit #13/#15). Time-tracking looked "self-contained" but its
  entry flows are read cross-surface (calendar tracked stripe, coherent Today, capacity, reports); splitting
  them into a second VM risks breaking those readers, and the failure mode (a surface reading a stale/empty
  flow) is a rotation/recomposition behaviour only a device shows. Deferred rather than done blind.
- **Real NavHost** (audit #16) replacing the flag/overlay navigation + 28 `BackHandler`s — rotation and
  back-stack restoration can only be validated interactively.

### Scorecard delta (R5 → R6)
| Dimension | R5 | **R6** | Why |
|---|:---:|:---:|---|
| Architecture & maintainability | 6.0 | **6.5** | the VM no longer resolves its own dependencies (one composition root), and the first write-path rules are out of the VM in pure, tested classes — the seam the sub-VM split builds on is proven, not just present. |
| Testing | 7.0 | **7.5** | 9 new tests reach the production VM-construction path and the recurring roll-forward rules (same-delta invariant, ended-recurrence guards, subtask-reset modes, cadence ease). |
| Data storage / Cross-module / Performance / UI / Security | 8.0 / 7.0 / 7.0 / 7.0 / 8.5 | **8.0 / 7.0 / 7.0 / 7.0 / 8.5** | unchanged this round. |
| **Overall** | **≈7.3** | **≈7.5** | the verifiable Phase-3 groundwork is banked. The remaining lift to 9.5 is the device-gated structural work: parallel per-feature ViewModels over the cross-cutting flows, and a real NavHost — both needing on-device rotation/back-stack verification. |

_The Round 5 and earlier logs follow unchanged below._

---

# Round 5 — Retiring the Second Persistence Substrate (device-verified) (2026-09-20)

Round 4 banked the *model* half of the plan and flagged the *storage* half as device-gated.
Round 5 closes that gate. Each data-carrying step shipped as a **staged Room promotion**
(Increment 1 = additive: create the table + migration that copies the parsed settings-JSON
into rows while the app still reads/writes JSON, so behaviour is unchanged and the copy can be
*proven exact on real data* before anything flips; Increment 2 = flip the runtime read/write
path onto the table, keeping the settings-JSON only as a backward-compatible **backup
transport** plus a one-time startup reconciliation safety net). Every increment was verified on
the user's own device through an in-app copyable diagnostics dialog (no adb), then the next
increment shipped. With all flips confirmed, the temporary diagnostics were removed.

### Shipped this round
| Step | What landed | Status | Evidence |
|---|---|:---:|---|
| **W2b** | Core task list → index-backed SQL | ✅ Done | `TaskDao.observeWorkspaceScoped(ws, inboxId)` replaces the in-memory workspace filter; VM `wsTasks` routed via `flatMapLatest`. The query mirrors the old inbox-ownership if/else exactly. `TaskScopeQueryTest` proves `SQL == old filter` **and** an Inbox-trap case (an Inbox task captured in a non-default workspace must not leak into `default`). Device log: `[tasks] SQL workspace tasks = 56` matched the on-screen count. |
| **W3 Goals → Room** | Goals & reviews out of settings-JSON | ✅ Done | `GoalEntity`/`GoalReviewEntity` (+ `@Index(workspaceId)` / `@Index(goalId)`), `GoalDao`, `MIGRATION_85_86` (v86) copies parsed goals/reviews into rows; Increment 2 flips VM `goals()`/`goalReviews()` and every write onto the DAO. Nested `milestones`/`keyResults` ride as JSON columns via lossless entity↔domain mappers. `GoalRoomParityTest` pins mappers/DAO/workspace-isolation/indices. Device log confirmed the flip: `[goals] table goals=1 (live source) · legacy JSON goals=0`. `86.json` exported. |
| **W3 Routines → Room** | Routines & runs out of settings-JSON | ✅ Done | `RoutineEntity`/`RoutineRunEntity` (rowId autoGen, `@Index` on `workspaceId`/`routineId`), `RoutineDao` (with `trimRunsTo(keep)` matching the 400-run backup cap), `MIGRATION_86_87` (v87); Increment 2 flips VM `routines()`/`routineRuns()`, the write paths, the `AlarmScheduler`/`Receivers` reminder reads, and repoints `allReminders` at `repo.observeRoutines()`. `RoutineRoomParityTest` pins mappers (incl. nested `steps`/`days`), cadence, DAO, indices. Device log confirmed: `[routines] table routines=2 runs=2 (live source) · legacy JSON routines=1`. `87.json` exported. |
| **Backup stays lossless** | Transport ⇄ table | ✅ Done | `exportableSettings()` regenerates the goals/reviews/routines/runs k/v from the live tables at export; `importJsonReplace()` clears + repopulates the tables from the imported transport inside one `withTransaction`; folder-sync `applyMerged()` keeps its device-local replace-only semantics. `BackupRoundTripTest` seeds goals **and** routines in the tables and asserts they round-trip back into the destination **table** (not just the JSON). |
| **Cleanup** | TEMP-DIAG scaffolding removed | ✅ Done | Once all flips were confirmed, the in-app `DiagDialog`, the startup diagnostic block, the four migration `Diag.log` lines and `util/Diag.kt` were deleted (166-line net removal). The permanent `reconcile*FromLegacyJson` startup safety nets and the `last_crash.txt` capture were kept. Release rebuilt green, 0 forbidden permissions. |

**What this closes:** Round 4 named *"per-domain pattern divergence"* and a *"second persistence
substrate"* (Goals + Routines living in `AppSettings` JSON blobs, invisible to Room's schema,
migrations, indices and transactions) as a top structural fact. That substrate is now **fully
retired** — every domain persists through Room, under one migration discipline, with real indices
and transactional backup. This is Phase 4 item #18 ("Promote Goals & Routines to Room entities")
shipped and verified, plus the task-list half of #21 (push list filters into SQL).

**Test pyramid:** +3 parity suites (`TaskScopeQueryTest`, `GoalRoomParityTest`,
`RoutineRoomParityTest`) and an extended `BackupRoundTripTest`, all green alongside the R4 suite.

### Scorecard delta (R4 → R5)
| Dimension | R4 | **R5** | Why |
|---|:---:|:---:|---|
| Data storage & integrity | 7.0 | **8.0** | Goals & Routines now in Room with indices, real migrations and transactional backup; the task list is index-backed SQL — the "second substrate" is gone. All proven on-device. |
| Cross-module consistency | 6.0 | **7.0** | every domain persists the same way (Room + DAO Flows); the reminder read path is unified onto `repo.observeRoutines()` end-to-end, not just the model. |
| Testing | 6.5 | **7.0** | 3 new DAO/parity suites reach the exact behaviours a JSON→table flip is most likely to break (workspace isolation, inbox-trap, nested sub-lists, backup round-trip through the table). |
| Architecture & maintainability | 6.0 | **6.0** | unchanged — the god-VM split + NavHost (Phase 3) is the remaining structural lever and is genuinely device-gated (interactive rotation/back-stack), not skippable by log. |
| Performance, UI, Security | 7.0 / 7.0 / 8.5 | **7.0 / 7.0 / 8.5** | unchanged this round (task-list SQL scale win is correctness-proven; felt-jank at 5k+ rows still wants on-device measurement + a `PagingSource`). |
| **Overall** | **≈6.9** | **≈7.3** | the device-gated storage half of the plan is now banked and verified. The remaining lift to 9.5 is Phase 3: constructor-inject the repo (#12, gates the rest), carve the ~6.6k-line `AppViewModel` into per-feature VMs, and swap the flag/overlay navigation for a real NavHost — the one large lever the startup-log loop cannot verify. |

_The Round 4 execution log and the Round 3 re-audit + pressure-tested plan follow unchanged below._

---

# Round 4 — Executing the Pressure-Tested Plan (W0–W3) (2026-09-20)

The forward plan from Round 3 was executed in validated increments. Everything below is
**built green** (`assembleRelease`, 0 forbidden permissions verified by `aapt2`), covered by
**passing JVM/Robolectric unit tests**, committed and pushed. Where a step's *user-felt* win
(scroll smoothness, alarm reliability, rotation/back-stack) or a data-carrying migration can
only be proven on real hardware, that part is called out as **device-gated** rather than
shipped blind — the honest reading of "meticulous rigor" under a no-device constraint.

### Shipped this round
| Step | What landed | Status | Evidence |
|---|---|:---:|---|
| **W0** | First-ever `AppViewModel` tests | ✅ Done | `AppViewModelCharacterizationTest` (3): VM constructs under the injected repo (executable proof the A2 seam works), workspace scoping of the task list, R64 inbox-ownership. Locks the read-models a decomposition is most likely to break. |
| **W1** | Extracted-seam tests | ✅ Done | `TimeTrackingControllerTest` (6): start/stop/pause/resume, pin, reassign, delete-clears-paused. The R84/R88 controllers were extracted but untested; now the pattern is a **proven** seam. |
| **W2** | Coherent data-path reversal (notes) | ✅ Done | `NoteDao.observeByWorkspace(ws, trashed)` + covering index `(workspaceId, trashed)` + `MIGRATION_83_84` (v84); VM `notes`/`trashedNotes` routed to SQL via `flatMapLatest`. `NoteScopeQueryTest` (2) proves `SQL == old in-memory filter` for every workspace×trashed and that the index exists. `84.json` exported. |
| **W3** | One reminder MODEL | ✅ Done | `domain/reminders/UnifiedReminder.kt` normalizes all six shapes (task/habit/event/note/routine/occasion) into one type; VM `allReminders` aggregates them. `UnifiedReminderTest` (8) pins every normalizer. |

**Test pyramid:** the VM went from **0 → 3** tests, the decomposition controllers from **0 → 6**,
the data layer gained **2** DAO-parity tests and **8** reminder-model tests — **19 new tests**,
all green alongside the pre-existing suite (RepositoryTest 11, BackupRoundTrip 2 re-run clean).

### Explicitly device-gated (not shipped blind)
- **W1 NavHost swap** (21 overlays + 73 `BackHandler`s → routes) — rotation/state-restoration and
  back-stack feel can't be validated without a device; `androidx.navigation` stays on the classpath
  as the intended host. The verifiable half (controller extraction under test) is done.
- **W2 full adoption + paging** — the `tasks` inbox-rule filter and a `PagingSource` for 5k+ rows
  need on-device jank measurement; notes proves the pattern end-to-end and the instrumented
  `MigrationTest` (which replays `84.json`) is the migration's final on-device gate.
- **W3 storage unification** — folding the six reminder shapes into one **table** is a data-carrying
  migration whose safety net is the instrumented `MigrationTest`; the MODEL (normalizers + aggregate)
  is unified and tested now, the storage is the device-gated next step.

### Scorecard delta (R3 → R4)
| Dimension | R3 | **R4** | Why |
|---|:---:|:---:|---|
| Architecture & maintainability | 5.5 | **6.0** | VM + controllers now under test; injection/controller seams are *proven*, not just present. |
| Testing | 5.5 | **6.5** | 19 new tests reach the VM, the DAO behaviour, and the reminder model — the layers the 520 pure tests never touched. |
| Data storage & integrity | 7.0 | **7.0** | index-backed SQL notes path proven equal to the old filter (correctness held; the scale win is device-gated). |
| Cross-module consistency | 5.5 | **6.0** | one reminder MODEL over six shapes (storage still divergent, device-gated). |
| Performance, UI, Security | 7.0 / 7.0 / 8.5 | **7.0 / 7.0 / 8.5** | unchanged this round. |
| **Overall** | **≈6.6** | **≈6.9** | the plan's cheap, high-integrity half is banked; the remaining lift to 9.5 is the device-gated structural work (nav host, paging, storage unification, full VM split). |

_The Round 3 re-audit and pressure-tested plan follow unchanged below._

---

# Round 3 — Deep Re-Audit on the Revised Code + Pressure-Tested Forward Plan (2026-09-20)

**Method.** This round does not re-read from memory. Three independent read-only
evidence passes were run against the **actual current source tree** (289 Kotlin files,
82,045 LOC) — one on the data/persistence layer, one on cross-module consistency, one on
architecture & navigation — each required to return `file:line` citations and exact
counts. The findings below are those measurements, not impressions. Everything claimed as
"shipped" is **built green (`assembleRelease`, 4m52s), 0 forbidden permissions** (verified
via `aapt2 dump permissions`: no INTERNET / LOCATION / storage / media / contacts / audio /
camera), committed and pushed.

## 3.1 Shipped since Round 2 (this round)

| Item | Finding | Status | Evidence |
|---|---|:---:|---|
| **X1 — editor discard-confirm unified** | Silent-discard data loss | ✅ Done | Event, Goal, Habit, Occasion, Routine editors now snapshot editable fields on open, compute `dirty`, and route every back/close/cancel/scrim path through a `ConfirmDialog("Discard changes?")` when dirty. Save paths untouched. ModalBottomSheet editors re-expand the sheet under the dialog so it is never left hidden-but-composed. Auto-seeded / immediately-persisted fields excluded so the prompt never fires at rest. |
| **U7 — QuickAdd dark-mode token hues** | Theme-correctness gap | ✅ Done | `QuickAddHighlight.kt` now carries a light **and** a dark hue per token and picks the set from `surface.luminance() < 0.5f`; fixed light-mode hexes no longer bleed onto dark/AMOLED. |

X1 matters beyond polish: before this round, only Task and Notes editors confirmed
unsaved edits. The other five editors silently discarded on back — a real, unbounded
data-loss class for the user, now closed everywhere.

## 3.2 Verified current-state ground truth (the numbers that cap the score)

These are the measurements that decide how far the app still is from 9.5. They are
**unchanged in kind** since Round 2 because the contained wins were correctness/consistency
fixes, not the large structural workstreams — which were deliberately *not* rushed.

**Architecture (still the dominant ceiling).**
- `AppViewModel.kt` — **6,656 lines, 752 `fun`, 30 exposed `StateFlow`, 506
  `viewModelScope.launch`**, still **one** `AndroidViewModel`. Grep for any other
  `: ViewModel()` / `: AndroidViewModel()` in the whole module → **none**. No per-feature
  ViewModels exist yet.
- The repo **is** constructor-injected (`internal constructor(app, repo)` at
  `AppViewModel.kt:114`; production path via secondary ctor) — the A2 seam from Round 2 is
  real. **But `Context` is not injected:** `appCtx get() = getApplication<App>()`
  (`AppViewModel.kt:124`) is used **155×**, plus `AlarmManager`/`NotificationManager`/
  `SecurePrefs`/`ThemePrefs`/widget/Intent surface (74 fully-qualified `android.*` refs).
  This — not the repo — is why **zero tests instantiate the VM** and why any VM test needs
  Robolectric.
- Three thin controllers already exist as delegation seams: `TimeTrackingController` (136),
  `ReminderController` (118), `FocusController` (60). A large slice of pure logic is already
  extracted to `domain/` and unit-tested. **The decomposition target has its seams cut.**
- `AppRoot.kt` (2,330 lines) does **100% manual navigation**: ~**114** `remember`/
  `rememberSaveable` nav flags, ~**21** sibling boolean/nullable overlay blocks, **73**
  `BackHandler`s across 30 files. `androidx.navigation:navigation-compose:2.8.3` is
  **declared but entirely unused** (`build.gradle.kts:148`; 0 `NavHost`/`NavController`
  references anywhere).

**Data pipeline & scalability (the #1 scale risk, internally coherent).**
- The reactive pattern is fully intact: **41 bare `SELECT * FROM <table>` `Flow`s vs 6
  WHERE-filtered** (7:1), and **0** `LIMIT`/`OFFSET`/`PagingSource` anywhere. All 6 WHERE
  flows are per-parent detail queries (task/note children); **no** top-level list filters by
  `workspaceId`/`listId`/`trashed`/`done`/`dueDate` in SQL.
- All workspace/list/trash/calendar filtering is in-memory Kotlin: `scopedBy`
  (`AppViewModel.kt:168`) is the single choke point for ~30 flows; **254** in-memory
  `.filter{`/`.groupBy{`/`combine(` occurrences in the VM; ~40 `getAll().filter{}` in the repo.
- **Indices were deliberately *dropped* to match this pattern.** `MIGRATION_81_82`
  (`AppDatabase.kt:995`) DROPs `tasks.workspaceId/folderId/completed/someday/dueDate`,
  `events.calendarId`, `time_entries.taskId`, `notes.updatedAt` — with the comment "the DAO
  loads whole tables and filters in memory." The `habits` table has **zero** indices despite
  heavy filtering. This is the key pressure-test insight: **it is not a bug to patch, it is a
  design to reverse coherently** (SQL filters + matching indices + paging, together).
- `flowOn` is centralized in the `state()` helper (`AppViewModel.kt:135`, 38 flows offloaded
  to `Default`), but **8 direct-`stateIn` derivations bypass it** — most run on light
  single-field reads, but `estimateBias` (`:5340`) recomputes over the full tasks +
  time-entries lists **on Main**. Subtree ops (`setTrashed`, `deleteSubtree`, `emptyTrash`)
  are N+1; several whole-table-scan-for-one-row remain where a `getById` already exists.

**Cross-module consistency (the maintainability tax).**
- **Goals and Routines are both settings-JSON, not Room** (`domain/Goals.kt:57`,
  `domain/Routines.kt:17`; stored as `goalsJson`/`routinesJson` blobs). A second persistence
  substrate with no schema, no indices, no migration story.
- `AppSettings` is **one flat 215-field data class** (`Settings.kt:56–461`), **23** of them
  opaque `…Json` blobs, mirrored to a key/value `settings` table via `toMap()`/`fromMap()`.
- **6–7 distinct reminder shapes.** `ReminderEntity` is labelled "the unified reminder
  abstraction" but is **task-only**; habits use a CSV of minutes, events a CSV of
  minutes-before, notes four separate columns, routines a nullable-Int in JSON, occasions
  lead-day ints. Only the *picker* presets are shared (`ReminderPresets`), never the storage
  model.
- **Two tag systems** (relational `TagEntity` M2M for tasks+notes; a separate CSV `tags`
  string on time entries) plus a parallel `ContextEntity` M2M — no single cross-domain tag.
- **Trash is universal on only 5 of 9 domains** (tasks/lists/folders/habits/notes; notes use
  `deletedAt`, the rest `trashedAt` — naming drift). Events, occasions, goals, routines have
  no trash/restore path.

**Security & testing (unchanged this round).** Security stays strong (KeyStore-wrapped
SQLCipher, per-blob FileVault, gzip'd AES-GCM backups, marker-versioned KDF). Tests: **73
files / 520 `@Test` methods**, all JVM/Robolectric on *pure domain logic* — still **no VM,
DAO-behaviour, or Compose-navigation coverage** of the orchestration layer.

## 3.3 Revised scorecard (honest)

| Dimension | R1 | R2 | **R3** | Why R3 sits here |
|---|:---:|:---:|:---:|---|
| Data storage & integrity | 5.0 | 7.0 | **7.0** | Integrity is genuinely good (atomic restore, full-store sync, gzip, indices where kept). Held, not raised: the whole-table-filter + stripped-index + no-paging design is a hard scale ceiling. |
| Performance & efficiency | 4.5 | 7.0 | **7.0** | Fine at today's data (≈544 tasks); jank sources fixed. Not raised: `estimateBias`-on-Main, N+1 subtree ops, and whole-table scans mean it degrades with data, not gracefully. |
| UI reuse & consistency | 5.5 | 6.5 | **7.0** | X1 unified discard-confirm across the 5 divergent editors; U7 closed a theme-token gap. Editors remain 5 structures; some dialog/shape/hex token debt remains. |
| Architecture & maintainability | 4.5 | 5.5 | **5.5** | No structural change this round. One 6.6k-line VM, 100% manual nav, unused nav lib. The injection + controller seams exist but are unspent. |
| Cross-module consistency | 4.5 | 5.0 | **5.5** | X1 made *editor behaviour* consistent. Substrates unchanged: JSON Goals/Routines, 6 reminder shapes, 2 tag systems, partial trash. |
| Security & privacy | 8.5 | 8.5 | **8.5** | Unchanged; already the strongest dimension. |
| Testing | 5.0 | 5.5 | **5.5** | 520 tests, but 0 exercise the VM / DAO behaviour / navigation. Context-coupling still blocks plain-JVM VM tests. |
| **Overall** | **≈5.1** | **≈6.5** | **≈6.6** | Contained wins closed a data-loss class and a theme gap. The ceiling to 9.5 is unmoved and is entirely the four workstreams below. |

**The honest read:** the app is a solid, secure, feature-complete ~6.6 that *works and
ships*. Every remaining point to 9.5 is gated by the same three facts Round 1 found —
now re-confirmed with fresh numbers — and no amount of further contained polish moves them.
Reaching 9.5 requires the four structural workstreams in §3.4, each of which is multi-day,
high-blast-radius, and (for the parts that claim a *user-felt* win like scroll smoothness or
reliability) genuinely needs on-device validation this environment can't provide.

## 3.4 Pressure-tested forward plan

Each workstream is stress-tested on five axes: **blast radius** (how much code moves),
**failure mode** (what breaks if done wrong), **verify-without-device** (the proof available
here), **device-gated** (what only real hardware confirms), and **rollback**. They are
ordered so each unlocks the next and so the cheapest confidence comes first.

### W0 — Lock behaviour before refactoring (prerequisite, ~2 days, low risk)
*The single highest-leverage cheap move, and it is available today.*
- **Do:** write Robolectric characterization tests that construct `AppViewModel` via the
  existing `(app, repo)` ctor with a fake repo and assert the current outputs of the hottest
  flows (`tasks`, `groups`, `outlineRows`, smart-list membership, `estimateBias`). Robolectric
  already runs here (7 suites). This does **not** need Context injection — the repo seam is
  enough for the read-model flows.
- **Blast radius:** additive only (new test files). **Failure mode:** none for prod.
- **Verify-without-device:** the tests themselves, green. **Rollback:** delete files.
- **Why first:** it turns the god-VM's current behaviour into an executable spec, so W1's
  decomposition can be proven equivalent instead of hoped equivalent. Also directly lifts
  the Testing score.

### W1 — Architecture: inject Context, then carve the first feature VM (~1 wk, high blast, medium risk)
- **Do, in order:** (1) inject an `AppContext`/`Application` abstraction so `appCtx`'s 155
  call-sites and the `AlarmManager`/`NotificationManager`/`SecurePrefs` touchpoints go
  through injected collaborators; (2) stand up the **unused `androidx.navigation`** as a real
  `NavHost` and migrate the ~21 overlay blocks + 73 `BackHandler`s onto routes incrementally
  (start with the leaf overlays: Stats, Done, Attachments); (3) extract the ~2.5k-line
  time-tracking slice into a `TimeTrackingViewModel` behind the existing
  `TimeTrackingController` seam.
- **Failure mode:** a missed `getApplication<App>()` or a route that drops saved state on
  rotation. **Verify-without-device:** W0's characterization tests stay green through each
  extraction; `assembleRelease` green; `rememberSaveable`/`SavedStateHandle` unit checks.
- **Device-gated:** rotation/state-restoration across the new NavHost, back-stack feel.
- **Rollback:** each overlay→route and each VM extraction is an independent commit; revert
  one without the others.

### W2 — Scale the data path *coherently* (SQL filters + indices + paging together) (~1 wk, medium blast, medium risk)
- **The pressure-test:** do **not** just re-add indices — the queries don't use them. Move
  the workspace/list/trashed/dueDate filters that live in `scopedBy`/`wsTasks` **into SQL
  WHERE**, re-add the `MIGRATION_81_82`-dropped indices to match *those exact predicates*, and
  add a `PagingSource` for the task and note lists. All three or none: SQL filters without
  indices are slower, indices without SQL filters are dead weight (which is why they were
  dropped).
- **Blast radius:** DAO queries + the ~30 `scopedBy` consumers + list composables' item
  source. **Failure mode:** a WHERE that changes smart-list membership; a paging boundary
  that drops the last item. **Verify-without-device:** `RoomDaoTest` asserting each new WHERE
  returns exactly what the old in-memory filter did (run old-path vs new-path on the same
  fixture); `EXPLAIN QUERY PLAN` shows index use; MigrationTest covers the index adds.
- **Device-gated:** the actual jank/scroll win at 5k+ items.
- **Rollback:** feature-flag the paged source; keep the in-memory path until the DAO path is
  proven equal.

### W3 — Cross-module unification (~1–2 wk, medium blast, **data-migration risk**)
- **Do (each independently shippable):** promote **Goals & Routines to Room** entities +
  DAOs (retire the `goalsJson`/`routinesJson` blobs) with a v→v migration that parses the
  existing JSON into rows; introduce **one `ScheduledReminder`** table the six shapes fold
  into; extend the `TagEntity` M2M to time entries (retire the CSV); add trash to events /
  occasions / goals / routines and rename notes' `deletedAt`→`trashedAt` for one convention;
  begin decomposing the 215-field `AppSettings` into feature sub-objects.
- **Failure mode:** the real risk in the whole plan — a migration that loses a goal, a
  reminder that stops firing, a backup that no longer round-trips. **Verify-without-device:**
  every promotion must move with its **backup contract and round-trip test** in the same
  commit (the app already has `BackupRoundTripTest`/`RepositoryTest` patterns); instrumented
  `MigrationTest` for each schema bump; assert JSON→Room parity on a fixture.
- **Device-gated:** that promoted reminders actually alarm on a real device across reboot.
- **Rollback:** each domain is a separate migration + commit; Room's exported schemas make a
  down-path reviewable before shipping.

### Sequencing & realistic ceiling
`W0 → W1 → (W2 ‖ W3)`. W0 is days and pure upside. W1 is the keystone (it unlocks testable,
splittable code and real navigation). W2 and W3 are independent of each other and can run in
parallel once W1's seams are in.

**Definition of done for 9.5:** Architecture ≥9 needs W1 (no single file >~1.5k lines,
real nav, ≥1 feature VM extracted with tests) **and** W0 (VM behaviour under test).
Scalability/Perf ≥9 needs W2 (SQL-filtered, indexed, paged lists proven equal to the old
path). Cross-module ≥9 needs W3 (one persistence substrate, one reminder model, one tag
model, universal trash). Testing ≥9 needs W0 + a DAO-behaviour + navigation-restoration
suite. Security is already there. **None of these is startable-and-shippable blind in one
pass without device validation for the user-felt parts** — which is exactly why this round
stopped at the verified contained wins and is handing back a sequenced, de-risked plan
rather than a half-finished refactor.

---

> ## Round 2 — Progress & Re-Audit (2026-09-20)
>
> Implementation began against this plan. Everything below is **built green
> (`assembleRelease`), 0 forbidden permissions, and — for the data-layer changes — covered
> by passing unit tests** (`CryptoGzipTest`, `RepositoryTest`, `BackupRoundTripTest`,
> `PortableCryptoTest`), then committed and pushed.
>
> ### Shipped this round
> | Item | Finding | Status | Evidence |
> |---|---|:---:|---|
> | Atomic restore/merge (`withTransaction`) | D1 (Critical) | ✅ Done | RepositoryTest 11/11 green |
> | Folder-sync covers ALL tables | D2 (High) | ✅ Done | snapshot/merge/applyMerged extended; BackupRoundTrip green |
> | `rememberSaveable` nav state | A5 (High) | ✅ Done | tab/overlay/editing/search/calendar survive rotation |
> | `flowOn(Default)` on reactive pipeline | P1 (Critical) | ✅ Done | one-line-in-`state()`; whole pipeline off main thread |
> | Calendar per-day memo + reuse parsed rrule | P2/P3 (Critical/High) | ✅ Done | per-cell recompute removed |
> | `remember(id)` on `observe*(id)` flows | P5 (High) | ✅ Done | 5 sites in Task/Note detail |
> | SCORE sort → `score()` once per task | P7 (High) | ✅ Done | no per-comparison `String.format` |
> | Per-habit / DayReview / Tasks-strip memo | P4/P6 (High) | ✅ Done | keyed `remember` on real inputs |
> | `nudge_events` index + migration v82→v83 | D5 (High) | ✅ Done | schema 83.json exported & verified |
> | `HabitDao.getById` (drop whole-table scans) | D6 (Medium) | ✅ Done | 5 hot paths |
> | `KairoScreenScaffold` + 10 screens migrated | U1 (High) | ✅ Done | Scaffold+TopAppBar duplication removed from 10 |
> | `Spacing` tokens + `KairoTopBar` | U3 (High, partial) | ◑ Partial | tokens exist; adoption ongoing |
> | Constructor-inject repo into VM | A2 (Critical) | ✅ Done | `internal (app, repo)` ctor; VM now unit-testable |
> | gzip encrypted backup/sync (TCENC4) | D4 / #525 (High) | ✅ Done | CryptoGzip 3/3; backward-compatible |
>
> ### Revised scorecard (was → now)
> | Dimension | Was | Now | Why |
> |---|:---:|:---:|---|
> | Data storage & integrity | 5.0 | **7.0** | 2 data-loss bugs fixed (D1/D2) + index + gzip + getById |
> | Performance & efficiency | 4.5 | **7.0** | main-thread pipeline offloaded (P1) + calendar + memo + sort |
> | UI reuse & consistency | 5.5 | **6.5** | shared scaffold adopted + spacing tokens |
> | Architecture & maintainability | 4.5 | **5.5** | VM injectable (A2) + scaffold; god-VM/nav still open |
> | Cross-module consistency | 4.5 | **5.0** | folder-sync now whole-store; editor/tag/reminder divergence open |
> | Security & privacy | 8.5 | **8.5** | gzip neutral-positive; threat model unchanged |
> | Testing | 5.0 | **5.5** | +CryptoGzipTest; VM now testable (tests still to write) |
> | **Overall** | **≈5.1** | **≈6.5** | two critical data-loss bugs + the #1 jank source + a rotation bug fixed and verified |
>
> ### Remaining path to 9.5 (the large architectural workstreams)
> These are the high-value items that still separate the app from 9.5. Each is a
> multi-day, high-blast-radius refactor touching critical paths, best done as its **own
> validated workstream with device testing** rather than rushed in one pass — they are
> deliberately **not** attempted blindly here, to keep the app shippable:
> - **Decompose the 6,642-line `AppViewModel`** into per-feature ViewModels (start with the
>   self-contained ~2,494-line time-tracking block) + per-feature `UiState` (A1/A3/F11). The
>   injection unlock (A2, done) is the prerequisite and is in place.
> - **Real navigation host + route table** replacing the ~60 `remember` flags and 20 sibling
>   `if`-overlays in AppRoot, centralising the 28 duplicated `BackHandler`s (A4/F6).
> - **One editor contract + unified back-button semantics** across the 5 divergent editors
>   (X1) — fixes silent-discard data loss outside Tasks/Notes.
> - **Promote Goals & Routines to Room** with `scopedBy` flows (X4) — removes the second
>   (settings-JSON) persistence substrate.
> - **Push list filters into SQL + restore indices + add `PagingSource`** (D3/X2) — the core
>   scalability item; needs the v82-dropped indices back and a paged task/note list.
> - **Universal `TagEntity` cross-refs + soft-delete Trash across all domains** (X5);
>   **one `ScheduledReminder` abstraction** (X3); **decompose the 215-field `AppSettings`**
>   (A8); pay down the remaining shape/hex token debt and route dialogs through `ConfirmDialog`
>   (U2/U3/U7); fill VM/use-case tests now that the VM is injectable.
>
> _The full original audit and phased plan follow unchanged below._

**Date:** 2026-09-20 · **Scope:** `android/app/src/main/java/com/todocompanion/app/`
· **Method:** static read-only audit of the whole source tree (287 Kotlin files, ~81,745
LOC) across five parallel dimensions, cross-referenced.

---

## 1. Executive summary

Kairo is a large, genuinely feature-complete offline productivity suite with real
engineering investment behind it — an exported Room schema with an instrumented migration
test, KeyStore-wrapped SQLCipher encryption, R8 shrinking, a lint gate, a UI-coherence
ratchet, 72 JVM/Robolectric tests, and a well-factored `domain/` layer of small pure-logic
files. **The bones are good.**

What holds it back from "top of the line" is not the feature set and not the security — it
is **three structural facts that every dimension of this audit kept colliding with:**

1. **One 6,642-line god `ViewModel`** (`AppViewModel`, 745 functions, 71 StateFlows, 506
   `viewModelScope.launch` sites) is the entire orchestration + business-rules layer for
   ~30 feature domains. It is threaded whole into 44 screen files and cannot be
   unit-tested (it's bound to the `App` singleton, not injected). This one file drives the
   architecture, testability, coupling, and merge-conflict problems at once.

2. **The reactive data pipeline loads whole tables and filters in memory, on the main
   thread.** Every list is `SELECT * FROM <table>` as a `Flow`; all
   workspace/list/trashed/calendar filtering happens in `combine{}.filter{}` in the VM;
   and there is **no `flowOn` anywhere**, so every re-derivation (grouping 544 tasks,
   counting, scoring) runs on `Dispatchers.Main`. This is simultaneously the #1
   scalability risk and the #1 jank source.

3. **Each feature domain reinvented the domain-level patterns** — five different
   editor/save/back paradigms, two saved-filter engines, four reminder data shapes, three
   tag implementations, non-universal trash/archive — on top of otherwise-good shared
   low-level primitives. Consistency is maintained by hand, not by structure.

None of these is a crisis in isolation — the app works and ships — but together they cap
the ceiling on quality, testability and scale. **The good news: the refactor path is
low-risk because the seams already exist** (a `domain/` layer, thin `TimeTracking/Focus/
Reminder` controllers, a component library, a design-token system, `scopedBy`/`state()`
helpers). The plan below is mostly about *finishing* seams that were started, not inventing
new architecture.

Two findings, however, **are** correctness bugs that can lose user data and should be fixed
before any refactor:

- **Restore/merge is non-atomic** — `importJsonReplace` does ~20 `clear()` calls then ~40
  `upsertAll()` calls with **zero transactions**; a kill mid-restore leaves a
  partially-wiped database with no rollback.
- **Folder-sync silently omits ~26 tables** (all notes, events, calendars, time-tracking,
  life-systems, revisions) — a note or event created on device A never reaches device B.

### Scorecard (current state → target)

| Dimension | Score | One-line verdict |
|---|:---:|---|
| Architecture & maintainability | 4.5 / 10 | Excellent `domain/` seam; one god-VM + no DI + no nav host cap everything |
| Data storage & integrity | 5.0 / 10 | Clean additive schema + great crypto, but non-atomic restore + sync gaps + no paging |
| Performance & efficiency | 4.5 / 10 | Strong-skipping helps, but main-thread derivations + per-frame calendar recompute |
| UI reuse & consistency | 5.5 / 10 | Real component library, but no screen scaffold, 143 hand-rolled dialogs, unused token scales |
| Cross-module consistency | 4.5 / 10 | Good primitives; every domain-level pattern diverges (dangerous back-button drift) |
| Security & privacy | 8.5 / 10 | Genuinely strong; permission-free, encrypted at rest, honest threat model |
| Testing | 5.0 / 10 | Good pyramid middle (domain + Robolectric DAO), but 0% on the god-VM |
| **Overall** | **≈ 5.1 / 10** | Feature-rich, secure, shippable — one monolith + one main-thread pipeline + per-domain drift below the ceiling |

---

## 2. Where the weight is

| Layer | LOC | Files | Note |
|---|---:|---:|---|
| `ui/` | 49,183 (60%) | 75 | `ui/screens` alone = 34,896 LOC |
| `domain/` | 16,817 | 126 | **The healthy part** — many small pure-logic files |
| `data/` | 7,307 | 31 | Room + SQLCipher + backup/sync |
| `util/` | 3,437 | 18 | |
| `widget/` | 2,367 | 22 | |
| `reminders/` | 1,790 | 7 | One shared `AlarmScheduler` |
| `ui/theme/` | **218** | — | The token layer everything leans on is razor-thin |

**Top files:** `AppViewModel.kt` 6,642 · `DayReviewScreen.kt` 3,236 · `CalendarScreen.kt`
2,528 · `AppRoot.kt` 2,325 · `AppRepository.kt` 2,213 · `SettingsScreen.kt` 2,203 ·
`TaskDetailScreen.kt` 1,867. **30+ composable functions exceed 150 lines**
(`SettingsScreen` body 1,664; `AppRoot` 1,316; `TaskDetailScreen` 1,125).

The distribution tells the story: `domain/` is 126 small, testable files (good), but 60% of
the app is `ui/`, and the two orchestration monoliths (`AppViewModel` + `AppRoot`) plus the
oversized screens are where all the debt concentrates.

---

## 3. Findings by dimension

Severity legend: **Critical** (data loss / crash) · **High** (scale wall / broad
maintainability / user-visible bug) · **Medium** · **Low**.

### 3.1 Architecture, god-files & maintainability

| # | Sev | Finding | Evidence |
|---|---|---|---|
| A1 | **Critical** | `AppViewModel` is a ~30-domain monolith | `AppViewModel.kt:112` — 6,530-line class body, ~750 `fun`, ~135 observable props (38 `MutableStateFlow`), 506 `viewModelScope.launch`. Time-tracking alone is lines 3106–5600 (~2,494 LOC, 293 fns). |
| A2 | **Critical** | VM is untestable — bound to `App` singleton, not injected | `App.kt:18-19` global `by lazy` DB/repo; VM reaches them via `getApplication<App>().repository` (155 refs). Ctor is `AppViewModel(app: Application)`; **never constructed in any of 72 tests** → 0% coverage on the largest, most logic-dense class. |
| A3 | High | Business rules live in the VM, not use-cases | `toggleComplete()` (`:2236`) inlines recurrence roll-forward, date-bundle shifting, subtask-reset policy, reminder rescheduling, celebration rules. No interactor/use-case layer exists. |
| A4 | High | All navigation is `remember { mutableStateOf }` in one composable | `AppRoot.kt:349-465` — ~60 nav/overlay state vars; ~20 flat sibling `if(showX) Screen(...)` blocks at `:1160-1216`; deep links are a 40-branch `when(action)`. No nav library (0 `NavHost`), no route table, no back stack. Nav state is also split between AppRoot vars **and** VM flows. |
| A5 | High | Nav state does not survive process death or rotation | `rememberSaveable` in `AppRoot.kt` = **0**; `SavedStateHandle` app-wide = **0**. Rotation / dark-mode toggle / font-scale change / process kill drops the user back to `Tab.TASKS`, all overlays closed, scroll & search lost. **User-visible correctness bug.** |
| A6 | High | Room `@Entity` types leak straight to the UI | No domain-model layer; 42 entities passed into composables (`TasksScreen` refs `TaskEntity` 14×, `HabitsScreen` `HabitEntity` 16×). Schema = UI model = wire model, so a persistence change ripples into 40+ screens. |
| A7 | High | Heavy Android-framework coupling inside the VM | 69 `AlarmScheduler` refs, 14 widget `.refresh()`, 82 `Uri`/`ContentResolver`, 123 `System.currentTimeMillis()` — all non-substitutable, so time/notification logic is unverifiable off-device. |
| A8 | Medium | `AppSettings` is a 215-field god-config with hand-maintained serialization | `Settings.kt:56` — 215 `val`s, 218 `Keys`, `toMap()`+`fromMap()` map every field twice. Adding one setting = 4 coordinated edits with no compiler check they stay in sync. |
| A9 | Medium | Thin controllers exist but aren't leveraged | `TimeTrackingController` (136 LOC), `FocusController` (60), `ReminderController` (118) are the right seam but anemic — 314 LOC combined vs ~2,500 lines of time logic still in the VM. |

**Positive:** DAOs are properly private inside the repo; pure logic is already extracted into
testable `domain/` objects (`Recurrence`, `PriorityEngine`, `ListPipeline`, `TimeReports`,
`LifeReadModels`) with a Robolectric in-memory `RepositoryTest`. The seam is proven — it
just hasn't been applied to the VM.

### 3.2 Data storage, schema, integrity & backup

| # | Sev | Finding | Evidence |
|---|---|---|---|
| D1 | **Critical** | No transactions anywhere; restore is destructive-then-non-atomic | `grep @Transaction/withTransaction/runInTransaction` = **0**. `importJsonReplace` (`AppRepository.kt:2056-2107`) = ~20 `clear()` then ~40 `upsertAll()` as independent calls. Kill mid-restore → partially-wiped DB, no rollback. `applyMerged` same shape; `createTask`/`deleteNote` cascades also non-atomic. |
| D2 | High | Folder-sync silently omits ~26 tables | `SyncEngine.merge()`/`snapshot()`/`applyMerged()` only handle the "core 19" (tasks, habits, folders, tags…). Notes, notebooks, events, calendars, time-tracking, life-systems, revisions, smart-views, cards are **never synced**. A note/event on device A never reaches device B via folder sync. |
| D3 | High | Whole-table reactive reads + in-memory filtering; no pagination | ~40 `val allX = dao.observeAll()` (`SELECT *`); VM filters in `combine{}.filter{}` (`AppViewModel.kt:155-251`). Room invalidation is table-level, so one task write re-materializes all 544+ rows for every subscriber. **0** `PagingSource`/`LIMIT`/`OFFSET` in the app. |
| D4 | High | Backup holds multiple full copies of the store in RAM; no gzip | `exportJson()` inlines every attachment as Base64 (`hydratedAttachments()`), then `Crypto.encrypt` Base64-encodes the whole blob again (+33%), then `toByteArray()` again. Auto-backup uses this nightly. OOM risk on large stores; JSON is highly compressible but uncompressed. |
| D5 | High | `nudge_events` has no indices but two filtered SQL queries | `LifeSystems.kt:189` no `indices`; `Daos.kt:304-307` filters `WHERE habitId=? AND epochDay=?` and `WHERE acted=0 AND epochDay>=?` — full scans, grows one row per (habit, day). Add `Index("habitId","epochDay")`, `Index("epochDay")`. |
| D6 | Medium | Single-row habit edits scan the whole ~90-col table | `HabitDao` has no `getById`; `awardFreeze`/`setHabitPaused`/`setHabitArchived`/`setHabitTrashed` all do `habits.getAll().firstOrNull{it.id==id}` (`AppRepository.kt:491,497,527,533,577`). O(N) deserialization per single edit. |
| D7 | Medium | JSON-blob / CSV / Base64 packed into row columns | `countdowns.momentsJson` (a hidden one-to-many), `day_logs.dailyScoresJson`, `habits.scheduleDays/reminderTimes` CSV, `time_entries.tags` CSV; `lists.backgroundBase64` + `countdowns.photoBase64` always-inline image bytes pulled into every `SELECT *`. Route images through `FileVault`; promote `momentsJson` to a table. |
| D8 | Medium | `task_activity` append-only log never pruned, loaded whole reactively | `allActivity = activity.observeAll()` feeds a score; unlike revisions (trimmed to 25) it has no cap → unbounded storage + unbounded full-table Flow. |
| D9 | Medium | Revision snapshots store full objects, not deltas, and ride the backup | `task_revisions.snapshotJson` = entire `TaskEntity`, up to 25/task; `note_revisions` full title+body. Both included in `exportJson`. |
| D10 | Low | No migration path below v5; dead indices; SQLCipher key string-interpolated into ATTACH | Chain starts `MIGRATION_5_6` → pre-v5 install crashes on open. Dead: `craving_events`/`witness_events` `Index(habitId)`, redundant `habit_checkins Index(habitId)`. `SecureDb.migrate` builds `KEY '$pass'` by interpolation (safe today — Base64 alphabet — but fragile). |

**Positive:** 77 hand-written additive migrations, `exportSchema=true`, instrumented replay
test, `fallbackToDestructiveMigrationOnDowngrade()` only (no forward data-loss). All
many-to-many joins correctly modeled with composite PKs indexed both directions. Backup JSON
already compact (`encodeDefaults=false`, no prettyPrint). Crypto design (SQLCipher +
KeyStore/StrongBox, FileVault AES-GCM per-blob, PBKDF2 600k, atomic encrypt/decrypt
reconcile with integrity+rowcount verify) is genuinely strong.

> Note: the v81→v82 "index cleanup" **deliberately dropped** most task/note/event indices
> with the rationale "the DAO loads whole tables and filters in memory, so no query uses
> them." That reasoning is correct *given the current design* — but the design (D3) is the
> bug. Fixing D3 (filters → SQL) requires restoring those indices:
> `tasks(workspaceId,trashed)`, `tasks(listId,sortOrder)`, `tasks(dueDate)`,
> `notes(workspaceId,trashed)`, `events(calendarId,startMillis)`.

### 3.3 Performance, recomposition & efficiency

_Context: Kotlin 2.0.20 compose-compiler → **strong-skipping is on**, so raw lambda
allocations are mostly a non-issue; but any param whose instance changes every emission
still recomposes, and heavy compute is unaffected. The app has **1** `@Immutable`/`@Stable`
and **0** `derivedStateOf` in total._

| # | Sev | Finding | Evidence |
|---|---|---|---|
| P1 | **Critical** | All VM reactive derivations run on the **main thread** (no `flowOn`) | `.state()` = `stateIn(viewModelScope, …)` on `Main.immediate`; `grep flowOn` = **0**. `groups` (`ListPipeline.compute` over 544 tasks: filter+groupBy+sortedWith+per-task priority), `smartCounts`, `entryCounts`, `taskReliability` all run on UI thread on every task/tag/settings emission. **Biggest single win: `.flowOn(Dispatchers.Default)`.** |
| P2 | **Critical** | CalendarScreen MonthView recomputes per-day data for all ~42 cells every recomposition | `CalendarScreen.kt:1361-1374` calls un-memoized `eventOccForDay`/`habitBlocksFor`/`countdownsFor`/`trackedDayInfo` per cell; `eventOccForDay`→`CalendarEngine.expand` walks each recurring event's rrule **forward from its origin** (2000-step guard). Thousands of rrule steps per frame on day-select or any emission. |
| P3 | High | `CalendarEngine.expand` re-parses each rrule up to 2000× per event | `CalendarEngine.kt:41` parses `r`, but the loop advances via `Recurrence.next(ev.rrule, …)` (`:56`) passing the **raw string** → re-parse every step. Add a `next(parsed, …)` overload. |
| P4 | High | Every `HabitRow` re-derives per-habit stats from the full checkins list; rows aren't lazy | `HabitsScreen.kt:611-637` — each row runs `daysFor`/`gradedCredit`/`strength`/`displayStreak` (un-remembered) over the whole checkins list, all inside a single `item{}` (renders every row eagerly). Check-in → O(habits × checkins) on main thread. |
| P5 | High | `collectAsState` on freshly-created Room flows re-subscribes every recomposition | `TaskDetailScreen.kt:189/716/1780`, `NoteEditorScreen.kt:152-153` — `vm.observeTask(id).collectAsState()` gets a **new Flow instance** per recomposition → collector torn down & query re-run on every keystroke; shows previous item's data for a frame on id change. Wrap in `remember(id)`. |
| P6 | High | DayReview & Tasks compute heavy aggregates in the composable body without `remember` | `DayReviewScreen.kt:216-243` (`missedHabits` is O(expected²), `quitToday` filters checkins twice/habit) re-runs on every dialog toggle; `TasksScreen.kt:933-937` "Habits due" strip is O(habits × checkins) un-remembered. |
| P7 | High | SCORE sort recomputes a string-formatting breakdown O(n log n) times | `ListPipeline.kt:174` `sortedByDescending { PriorityEngine.explain(...).total }` — `explain` builds ~7 `String.format` lines per call, invoked per comparison. Use `score()` and `map{it to score}.sortedByDescending` once per task. |
| P8 | Medium | `AppSettings` unstable object re-emitted wholesale, read in 51 places | Lists/Sets/Maps make it Compose-unstable; rebuilt on any single KV change; children passed the whole object recompose for unrelated settings. |
| P9 | Medium | No `distinctUntilChanged` on scoped source flows; `DoneRecord.build` computed in 3 screens | Redundant recompute on emissions that don't change the scoped result; `feed` build duplicated across DoneScreen + DayReview (×2). |
| P10 | Low | Regex compiled per call in composition; 0 `derivedStateOf`; per-frame `ZoneId/LocalDate.now()` | `TaskDetailScreen.kt:160-178` (4 regex/call), word-count `split(Regex)` in note bodies; derived predicates recompute where `derivedStateOf` would gate. Hoist regex to top-level `val`. |

### 3.4 UI code reuse & consistency

_A real, good shared library exists (`components/` — `AppCard`, `AppTextField`,
`EmptyState`, `ConfirmDialog`, `AppColorPicker`, `OptionChips`, `PrioritySheet`,
`KairoColors`). Adoption is strong in places (0 raw Material `Card(`; `EmptyState` ×23;
`OptionChips` ~290×) but collapses at three seams._

| # | Sev | Finding | Evidence |
|---|---|---|---|
| U1 | High | No shared screen scaffold / top bar — the header block is copy-pasted across ~26 screens | `Scaffold(` 56× / 26 files; `TopAppBar(` 37× / 26 files; `expandedHeight = 52.dp` repeated 36×; the exact `navigationIcon = { IconButton(onClick=onBack){ Icon(ArrowBack,"Back") } }` byte-identical in 24 places. `LifeSystemsScreens.kt:124` already has a private `LSScaffold` that proves the abstraction — trapped in one file. **Promote to `KairoScreenScaffold`.** |
| U2 | High | Dialogs hand-rolled 143× vs 16× via shared `ConfirmDialog` | Raw `AlertDialog(` 143× / 34 files; `ConfirmDialog(` 16× / 10. Textbook dup: `TaskDetailScreen.kt:856` re-implements `ConfirmDialog(destructive=true)` by hand-tinting the error button. Route simple yes/no through `ConfirmDialog`; add a `KairoDialog` scaffold for field-bearing ones. |
| U3 | High | Shape token scale exists but is used **0×**; no spacing scale at all | `Theme.kt:81` defines `AppShapes` — `MaterialTheme.shapes` referenced **0 times**. Instead **439 inline `RoundedCornerShape(n.dp)`** across ~22 distinct radii; `AppCard` itself hardcodes `RoundedCornerShape(16.dp)`. **4,944 raw `.dp`** literals, no `Spacing`/`Dimens` object; `padding(16.dp)` (the de-facto gutter) undeclared. `NotesTokens.kt` even re-solves this locally, duplicating `AppShapes`. |
| U4 | High | 30+ oversized screen composables | `SettingsScreen` body 1,664 · `AppRoot` 1,316 · `TaskDetailScreen` 1,125 · `DayReviewScreen` 1,043 · `NoteEditorScreen` 1,018. These monoliths are *why* the chrome/dialog/shape duplication proliferates — each re-inlines its own. |
| U5 | Medium | Stat/tile pattern reimplemented 5× | Canonical `StatTile`/`MetricTile` are `internal` to `ReviewComponents.kt`; `DoneScreen.Stat`, `NotesWave2Ui.LedgerStat`, two near-identical `StatRow`s (differ only 2.dp vs 3.dp). Promote + delete clones. |
| U6 | Medium | Date-picker fragmentation | `Pickers.kt` already has 4 overlapping date entry points; `NoteEditorBody.kt:975` hand-rolls a 5th with raw `DatePickerDialog`. |
| U7 | Medium | 63 `Color(0x…)` literals in 11 screens despite `KairoColors` | e.g. `QuickAddHighlight.kt:20-35` fixes NLP highlight colors as light-mode hexes that don't adapt to dark/AMOLED. Map onto `LocalKairoColors`. |

> The UI-coherence ratchet (`build.gradle.kts`) already tracks these bypasses — the
> committed baseline is `roundedCornerShape=380`, `colorLiteral=63`, `fontSizeLiteral=27`.
> It stops *new* drift but has frozen the existing debt in place. U3/U7 are about paying it
> down and lowering the baseline.

### 3.5 Cross-module internal consistency

| # | Sev | Finding | Evidence |
|---|---|---|---|
| X1 | High | **Five incompatible editor/save/back paradigms** — dangerous back-button divergence | Tasks = staged draft + dirty + **confirm-on-discard**; Notes = **autosave, saves on back**; Events/Goals/Habits/Countdowns/Routines = per-field `var`, explicit Save, **discard silently on back**. A user who learns "back keeps my edits" in Notes loses every field in 5 other domains. Only Tasks protects against accidental loss. |
| X2 | High | Task list pipeline is task-only; two non-interoperable saved-filter engines | `ListPipeline` used only by Tasks/QuickAdd/Settings; Notes has its own `SmartViewEntity`+`NotePredicate`; Habits/Events/Countdowns filter inline. `FilterEntity/FilterQuery` (tasks) vs `SmartViewEntity/NotePredicate` (notes) double the maintenance. Multi-select & swipe exist in only 2–3 domains each. |
| X3 | High | Reminders: one shared `AlarmScheduler`, but **four data shapes** and a task-only controller | Tasks = typed `ReminderEntity` table via `ReminderController`; Habits = minute-of-day CSV; Events = minutes-before CSV; Notes = epoch-millis slots; Routines = single int in JSON. Intensity tiers/relative/location types exist only for tasks; reschedule-on-boot duplicated 6×. |
| X4 | High | Goals & Routines are non-reactive JSON blobs in the settings row | `goals()`/`routines()` re-parse `settings.goalsJson` on every call (not Flows); screens re-derive on `settings` emissions. Two persistence substrates (Room vs settings-JSON) double backup/scoping/migration logic and won't scale like the Room domains. |
| X5 | High | Organizational features: three tag models; non-universal trash & archive | Tags: cross-ref tables (Tasks/Notes share `TagEntity`) vs CSV string (`time_entries.tags`) vs single grouping string (`habits.category`). **Trash/restore** exists for Tasks/Notes/Habits/Lists but Events/Countdowns/Goals/Routines are **hard-deleted, no undo**. `star` vs `favorite` vs `pinned` overlap with different names. |
| X6 | Medium | Naming & terminology divergence | Emoji field is `emoji` vs `coverEmoji` vs `icon` (3 names); free-text is `note` vs `notes` vs `description`; "note" means 3 different objects (`TaskEntity.isNote`, `NoteEntity`, `kind=="note"`); ~8 string-enums on `HabitEntity` alone with no type safety; `workspaceId` default is `"default"` literal vs `WorkspaceEntity.DEFAULT_ID` constant vs `""` — inconsistent even within one file. |

**Positive:** Low-level primitives are genuinely unified — one `AlarmScheduler`, one
`AppColorPicker`/`EmojiGridPicker`, one `EmptyState`/`AppCard`, one `AppRepository`,
workspace-scoping funneled through one `scopedBy` choke point.

---

## 4. Cross-cutting themes

The five audits independently converged on the same root causes. These are the real levers:

- **The god-VM is the center of gravity.** It appears as the architecture problem (A1/A2),
  the performance problem (P1 — its derivations run on Main), and the consistency problem
  (X4 — Goals/Routines live inline in it as JSON). Shrinking it fixes three dimensions.
- **"Load the whole table, filter in memory" is one decision with two faces.** It's the
  scalability wall (D3) and, because there's no `flowOn`, the jank source (P1). Pushing
  filters into SQL + restoring indices + `flowOn` fixes both.
- **Seams were started and not finished.** `domain/` extraction, the thin controllers,
  `ListPipeline`, the component library, the design tokens, `LSScaffold` — every fix below
  is *finishing* an existing pattern, which is why the risk is low.
- **Consistency is maintained by hand.** Comments like "match every other screen's top bar
  height" (`NoteEditorScreen.kt:266`) prove it. Where a shared component/contract exists,
  adoption is high; the gaps are exactly where no component exists (scaffold, editor
  contract, reminder abstraction).

---

## 5. Improvement plan (phased, sequenced by impact ÷ effort ÷ risk)

Each phase is independently shippable and verifiable (build + `assembleRelease -x lint` +
permission check + tests). Phases 0–1 are small and high-value; 3–4 are the structural bets.

### Phase 0 — Data-safety & correctness (do first; small, non-refactor)
These are user-visible bugs, not code smells.
1. **Wrap restore/merge/apply + multi-write cascades in `withTransaction`** (D1). All-or-nothing; kill-safe. Highest priority in the whole document.
2. **Make folder-sync cover all tables** (D2) — extend `snapshot()`/`merge()`/`applyMerged()` reusing the additive `mergeNewerTables` logic. Add a round-trip test that a note+event survive a sync cycle.
3. **`rememberSaveable` the primitive nav state** in `AppRoot` (tab, open overlay, `editing`, search, calendar anchor) (A5). Fixes the rotation/process-death data-loss.

### Phase 1 — Performance quick wins (small edits, large felt improvement)
4. **`.flowOn(Dispatchers.Default)`** upstream of every list-transforming `.state()` (P1). One-line-per-flow; the single biggest jank fix.
5. **Memoize CalendarScreen** per-day data into `remember(visEvents, monthRange)` maps + add `Recurrence.next(parsed,…)` and fast-forward to window start (P2/P3).
6. **`remember(id)` around `observe*(id)` flows** in Task/Note detail (P5) — kills per-keystroke DB re-subscription.
7. **Add the missing `remember`s** in DayReview & Habits-due strip (P6); **fix the SCORE sort** to use `score()` once per task (P7); **per-habit stat map** + real lazy `items(key=id)` for habits (P4).
8. **Add `nudge_events(habitId, epochDay)` index** (D5) and **`HabitDao.getById`** to stop `getAll().firstOrNull{}` on hot paths (D6).

### Phase 2 — UI consistency foundation (unblocks decomposition)
9. **Extract `KairoScreenScaffold` + `KairoTopBar`** (promote `LSScaffold`) and migrate the 26 screens (U1) — removes the single most-copied block.
10. **Introduce `Spacing` tokens + adopt `MaterialTheme.shapes`**, delete `NotesTokens`, point `AppCard` at `shapes.medium` (U3); lower the coherence-ratchet baseline as counts drop.
11. **Route simple dialogs through `ConfirmDialog`; add a `KairoDialog` scaffold** (U2). Promote `StatTile`/`StatRow` (U5), consolidate date pickers (U6), migrate stray hex to `KairoColors` (U7).

### Phase 3 — Architecture decomposition (the structural bet)
12. **Constructor-inject the repository + controllers into the VM** via a `ViewModelProvider.Factory` (A2). *Prerequisite for everything else* — immediately unlocks VM unit tests.
13. **Carve time-tracking out of the VM** (~2,494 lines) into `TimeTrackingViewModel` on the existing `TimeTrackingController` (A1/A9) — biggest single reduction, self-contained, low risk.
14. **Extract per-feature use-cases** for write paths, starting `CompleteTaskUseCase`/recurrence (A3) — moves rules into JVM-testable plain classes.
15. **Repeat the split** for notes/habits/calendar/settings VMs; give each screen a narrow `UiState` instead of the whole VM (A1/A6).
16. **Introduce a real nav host + route table** (Navigation-Compose or a typed sealed back stack) replacing the ~60 flags + 20 `if`-overlays and centralizing the 28 duplicated `BackHandler`s (A4).

### Phase 4 — Cross-module unification (consistency + scale)
17. **One editor contract** — a reusable `rememberEditorDraft<T>()` (staged-draft + dirty + confirm-on-discard, the Tasks model) applied to all domains; **unify back semantics immediately** even before the full refactor (X1). This is the most dangerous inconsistency.
18. **Promote Goals & Routines to Room entities** with DAO-backed `scopedBy` Flows (X4) — removes the second persistence substrate.
19. **One `ScheduledReminder(ownerType, ownerId, spec)` abstraction** + universal `ReminderController` over `AlarmScheduler` (X3); route all pickers through `ReminderPresets`.
20. **Extend `TagEntity` cross-refs + the soft-delete Trash lifecycle to every domain**; collapse `star`/`favorite`/`pinned` to one convention (X5).
21. **Generalize `ListPipeline<T>`** to back both `FilterQuery` and `NotePredicate` (X2); **push list filters into SQL `WHERE` + restore indices + add `PagingSource`** (D3).
22. **Decompose `AppSettings`** into per-feature groups with generated/serialized maps (A8).

### Phase 5 — Scale & polish
23. **Stream + gzip the backup envelope; keep attachment bytes out of routine/auto backup** (D4) — folds in deferred #525.
24. **Store revision diffs / exclude history from routine backup**; cap `task_activity` (D8/D9) — folds in #526.
25. **Add a domain-model boundary** so the Room schema stops being the UI/wire model (A6); **own dispatching in the repo** (wrap export/import/crypto in `withContext(IO)`).
26. **Fill VM/use-case test coverage** now that it's injectable; standardize field names & replace string-enums with typed enums + `TypeConverter` (X6).

### Sequencing at a glance
```
Phase 0  ██ data-safety      (days)      → ship immediately, fixes real data loss
Phase 1  ███ perf quick wins (days)      → biggest felt improvement per line changed
Phase 2  ███ UI foundation   (1–2 wks)   → unblocks screen decomposition
Phase 3  █████ architecture  (weeks)     → the ceiling-raiser; #12 gates the rest
Phase 4  █████ unification   (weeks)     → consistency + scale
Phase 5  ███ scale & polish  (ongoing)
```

---

## 6. Quick wins (highest value : effort)

| Fix | Effort | Payoff |
|---|---|---|
| `withTransaction` around restore/merge (D1) | XS | Removes the worst data-loss risk in the app |
| `.flowOn(Default)` on VM list combines (P1) | XS | Kills main-thread jank on every task edit |
| `remember(id)` on `observe*(id)` flows (P5) | XS | Ends per-keystroke DB re-subscription |
| `nudge_events` index + `HabitDao.getById` (D5/D6) | XS | Removes two O(N)-per-edit scans |
| Memoize Calendar per-day maps (P2/P3) | S | Fixes the worst per-frame cost in the app |
| SCORE-sort `explain`→`score` (P7) | S | Thousands fewer `String.format`/pipeline run |
| Fix editor back-button semantics (X1) | S | Ends silent data loss in 5 of 7 editors |
| Promote `KairoScreenScaffold` (U1) | S–M | One edit changes app chrome instead of 26 |

---

## 7. Guardrails — what NOT to break during the refactor

These are the parts that are already excellent; preserve their invariants:

- **Permission-free constraint** — every release must keep 0 INTERNET/LOCATION/STORAGE/
  MEDIA/CAMERA/MIC permissions (verify with `aapt2 dump permissions`). No refactor may add a
  dependency that drags one in.
- **Encryption design** — SQLCipher + KeyStore/StrongBox, FileVault per-blob AES-GCM, the
  atomic encrypt/decrypt reconcile with integrity+rowcount verification. Don't route new
  data around FileVault or weaken the verify.
- **Additive migration discipline** — `exportSchema=true`, the instrumented replay test, and
  `fallbackToDestructiveMigrationOnDowngrade()`-only (never forward-destructive). Any schema
  change (indices, Goals/Routines→Room, image-columns→FileVault) needs a real migration + a
  test, not a destructive fallback.
- **UI-coherence ratchet** — keep it green; lower the baseline as U3/U7 pay down debt rather
  than raising it.
- **The `domain/` seam & 72-test pyramid** — extend it (use-cases, VM tests), don't bypass it.

---

## 8. Bottom line

Kairo is a secure, permission-free, feature-rich app with a healthy pure-logic core and
excellent data-at-rest engineering. It is **not yet** top-of-the-line on *engineering*
because three structural facts — a god-ViewModel, a main-thread whole-table reactive
pipeline, and per-domain pattern divergence — cap testability, scale and consistency. The
path to fix them is unusually low-risk because every fix finishes a seam the codebase
already started. **Do Phase 0 now** (it prevents real data loss), **Phase 1 next** (the
felt-performance jump per line changed is the highest in the plan), then work the structural
phases as capacity allows — gated on #12 (inject the repo), which unlocks everything else.
