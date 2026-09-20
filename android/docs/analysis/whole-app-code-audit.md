# Kairo — Whole-App Code Audit & Improvement Plan

_Comprehensive engineering audit across code quality, maintainability, scalability, data
storage, performance, UI reuse and cross-module consistency, with a phased plan to reach
"top of the line."_

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
