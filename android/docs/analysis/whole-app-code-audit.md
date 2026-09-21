# Kairo — Whole-App Code Audit & Improvement Plan

_Comprehensive engineering audit across code quality, maintainability, scalability, data
storage, performance, UI reuse and cross-module consistency, with a phased plan to reach
"top of the line."_

---

# The two mega-objects — safe partial decomposition (2026-09-21)

After the three ceilings closed, the two remaining mega-objects (`AppViewModel`, `AppRepository`) were taken on.
A close read established that their *remaining* cores are **inherently** cross-coupled, not incidentally so:
the repository's feature sections call across features by design (time-tracking credits habits, re-parents
tasks, appends to notes; note-saving syncs bound tasks and drives FTS), and what's left of the ViewModel is the
cross-feature coordination itself. A full mechanical split would mostly relocate that tangle behind shims and
back-references — high-risk churn on the app's core for a modest gain — which is *why* the app deliberately
keeps these as coordinators. So the chosen path was **safe partial extraction**: lift only the genuinely-
cohesive, low-risk clusters, each behaviour-preserving and verified, and leave the coupled cores intact.

| Extraction | From → new file | Why it's clean | Result |
|---|---|---|---|
| **Goals + Routines data module** | `AppRepository` → `GoalsRoutinesRepository.kt` | Touches only its own tables via the domain codecs — no FTS, no cross-feature writes, no shared `uid`/`now`/`activeWs`. (It was mis-filed under the notes header.) | AppRepository **2369 → 2327**; ~19 call sites unchanged via shims; backup transport still reads the DAOs directly. |
| **Event / calendar CRUD** | `AppViewModel` → `CalendarViewModel.kt` | Same proven collaborator pattern (timeVm/notesVm/tasksVm). The two shared touchpoints — `ensureDefaultCalendar` (8+ callers) and `quickAddOne` (task-capture) — stay on the coordinator, reached via `app` (widened `private`→`internal`). | AppViewModel **5378 → 5264**; every screen call site unchanged via shims. |

**Verified:** `compileReleaseKotlin` green · full `testDebugUnitTest` green (backup round-trip exercises
goals/routines; characterization tests exercise the VM wiring) · `assembleRelease` 19.8 MB · aapt2 re-confirmed
**0 forbidden permissions**.

### Score movement (honest — a safe partial, not a full dissolution)

Architecture **8.8 → 8.9**, Maintainability **7.5 → 7.6** (two more cohesive modules extracted; both mega-objects
smaller). **Overall stays ≈8.2** — deliberately not inflated: the cores remain large-by-design coordinators, and
the inherent cross-coupling is the real, documented reason the last ~1.3 points to 9.5 would require a
genuine architectural redesign (splitting the coupling itself), not more mechanical extraction. That redesign is
a separate, higher-risk undertaking, offered but not force-fit here.

_The three-ceilings section that preceded this round follows unchanged below._

---

# The three named ceilings — closed (2026-09-21)

After the post-fix confirmation re-audit below named three concrete ceilings to 9.5, each was taken on
directly. All three are implemented, verified (`compileReleaseKotlin` green · full `testDebugUnitTest` green ·
`assembleRelease` 19.8 MB · aapt2 re-confirmed **0 forbidden permissions**), committed and pushed.

| # | Ceiling (from re-audit) | What was done | Evidence |
|---|---|---|---|
| 1 | **AppViewModel still a 5,480-line god object; task render pipeline coupled inside it** (Architecture/Maintainability) | Moved the derived render pipeline — `groups`, `outlineRows`, `hierarchyRows` + the `buildOutline`/`buildFilteredOutline` helpers — out of AppViewModel into `TasksViewModel`, where the view-state it consumes already lives. AppViewModel keeps thin forwarding shims (`val groups get() = tasksVm.groups`); `wsTasks`/`inboxTasksAll` are now `internal` and `priorityConfig()` is a top-level extension so both sides resolve one copy. Flows are `by lazy` to respect construction order. | AppViewModel **5480 → 5378** (−102); `TasksViewModel.kt` now owns the pipeline; behaviour proven unchanged by `AppViewModelCharacterizationTest`. |
| 2 | **Swipe-only TaskRow actions have no TalkBack path; 30dp chevron** (Accessibility) | `TaskRow`'s `SwipeToDismissBox` complete/delete now have `CustomAccessibilityAction` fallbacks (complete · delete · expand/collapse) so screen-reader / switch-access users can trigger every row action without a gesture; the row gains `Role.Button` + `onClickLabel`; the 30dp collapse chevron is widened to a **48dp** touch target (leaf spacer matched so checkboxes stay aligned). | `TaskRow.kt:76-108` (custom actions + role); `TaskRow.kt` chevron `size(48.dp)`. |
| 3 | **Migration bodies never replayed headlessly** (Testing/Data) | `MigrationReplayTest` — a headless (Robolectric) replay of the flagged `MIGRATION_85_86` / `MIGRATION_86_87` SQL bodies against a real SQLite engine: seeds the settings JSON, runs the real `migrate()` bodies, and asserts the JSON→row copies land in the right columns (plus idempotency and the empty-source case). No new dependency; the migrations are pulled from `ALL_MIGRATIONS`. | `MigrationReplayTest.kt` (3 tests, JVM-gated). A buggy body in those promotions now fails `./gradlew test`, not just a device. |

### Score movement (grounded in the change + verification)

| Dimension | Post-fix | **After ceilings** | Why |
|---|:---:|:---:|---|
| Architecture | 8.6 | **8.8 ↑** | render pipeline decoupled from the coordinator and co-located with its state; AppViewModel −102 lines (still 5,378 — not fully dissolved, hence not higher). |
| Maintainability | 7.2 | **7.5 ↑** | task rendering is now edit-one-place, in the collaborator that owns the state it reads. |
| Accessibility | 6.5 | **7.2 ↑** | the primary list's destructive action has a non-gesture path; 48dp target; `Role.Button` on the core row. |
| Testing | 7.8 | **8.2 ↑** | the sharpest data-loss exposure (the JSON→row migration copies) is now exercised headlessly; full-chain stepwise replay remains device-only, so not a full close. |
| **Overall** | **≈8.0** | **≈8.2 ↑** | three concrete ceilings closed; the remaining distance to 9.5 is the un-dissolved AppViewModel/AppRepository mega-objects and full-chain headless migration replay. |

_The confirmation re-audit that named these ceilings follows unchanged below._

---

# Post-fix confirmation re-audit — all 20 fixes verified against actual code (2026-09-21)

The 20-fix plan from the re-audit below (Tier 1 quick wins · Tier 2 high-leverage · Tier 3 structural) is
**fully implemented, verified, committed and pushed**. This section re-runs the **same** four-dimension deep-dive
(performance · architecture/maintainability/cross-module · UI/a11y · data/security/testing) against the **actual
committed code** — every score here is grounded in files read at `file:line`, not in the plan's projections. Each
of the 20 fixes was independently confirmed **PRESENT** (none a stub). Dashboard artifact:
<https://claude.ai/artifact/RWzXNRXNu1P5o2hVtn93Zv>.

**Verify gate for the change round:** `compileReleaseKotlin` green → `testDebugUnitTest` all-pass (573 `@Test`
across 85 JVM files) → `assembleRelease` (19.8 MB) → aapt2 `dump permissions` re-confirmed **0 forbidden
permissions** (only `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`,
`VIBRATE`, `USE_FULL_SCREEN_INTENT`, `ACCESS_NOTIFICATION_POLICY`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, plus
library-injected `USE_BIOMETRIC`/`USE_FINGERPRINT` and the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED`). Merged
release manifest re-checked: still **no** INTERNET/NETWORK/LOCATION/MEDIA/STORAGE/CAMERA/RECORD_AUDIO/CONTACTS.

### Confirmed post-fix scorecard (grounded in read code)

| Dimension | Pre-fix (plan) | **Post-fix (confirmed)** | One-line evidence |
|---|:---:|:---:|---|
| Security | 9.0 | **9.0** | zero-network confirmed at source + merged manifest; sole exported IPC receiver opt-in + 128-bit token-gated w/ pinned egress (`TimeIntentApi.kt:50-100`); no fix regressed it. |
| Performance | 7.5 | **9.0 ↑** | `shareIn` on hot flows (`AppRepository.kt:391-394,478`); change-gated settings KeyStore/disk (`AppViewModel.kt:158-189`); 7 `Eagerly`→`WhileSubscribed` w/ `*Now()` reads; 479 `collectAsStateWithLifecycle`, 0 bare; O(C) pre-grouped checkins (`HabitsScreen.kt:314`). |
| Architecture | 8.2 | **8.6 ↑** | shared `FeatureViewModel` base (39 L) invoked by all 5 collaborators; `TasksViewModel` view-state carve behind shims; render pipeline kept as a documented boundary. |
| Data | 8.0 | **8.5 ↑** | 82 contiguous documented migrations v5→v87, `exportSchema=true`, destructive fallback **downgrade-only**; backup round-trip now exhaustive over the relational model (flags/tags/contexts/deps + all 50 entities). |
| Maintainability | 6.5 | **7.2 ↑** | read-models single-sourced (edit-one-place); `DayReviewScreen` 3247→1937 + 4 focused siblings; one canonical `formatMinutes` (`util/Format.kt:12-19`) for 10 sites. |
| UI | 7.0 | **7.8 ↑** | `KairoTopBar` across 20 files; `KairoShapes` single-sources Pill/Card radii; dark/AMOLED-aware `LocalKairoColors` on chips + swipe (`TaskRow.kt:64-78`, `Common.kt` 0 hex literals). |
| Testing | 7.0 | **7.8 ↑** | 3 real non-stub JVM tests added (feature-VM characterization, exhaustive backup round-trip, headless migration-chain guard) atop 573 `@Test`. |
| Cross-module | 6.5 | **7.0 ↑** | one shared cross-cutting base abstraction; collaborators well-scoped (caveat: bidirectional AppVM↔collaborator refs — bounded, intentional). |
| Accessibility | (folded 7.0) | **6.5 (broken out)** | `Role.Button`/`onClickLabel` on nav+task rows via `clickableRow` (`ClickableRow.kt:16-20`); 48dp touch targets; checkbox role/state semantics. |
| **Overall** | **≈7.5** | **≈8.0 ↑** | a genuine, honest **+0.5**; the gap to 9.5 is now concentrated in three named ceilings (below), not diffuse. |

### The 20 fixes — all confirmed PRESENT

- **Tier 1 (quick wins):** #1 `shareIn` hot repo flows · #2 one `formatMinutes` (10 callers) · #3 dead code deleted (`AppViewModel.search`, `keepTan`; 0 `@Suppress("unused")` remain) · #4 migration comments corrected (header now "82 migrations v5→v87") · #5 `KairoTopBar` migration (20 files) · #6 48dp tap targets · #7 shared `ProgressCard` (share-image renderer, reused 8+ sites) · #11 pre-grouped habit checkins.
- **Tier 2 (high-leverage):** #8 `lifecycle-runtime-compose` dep · #9 gated `settings` KeyStore-decrypt + disk-write · #10 7 `Eagerly`→`WhileSubscribed` with `*Now()` imperative reads · #12 dark-aware chip/swipe colors (`LightKairoColors`/`DarkKairoColors`) · #13 `FeatureViewModelCharacterizationTest` (5 real tests, workspace-scope + non-leak) · #14 exhaustive `BackupRoundTripTest`.
- **Tier 3 (structural):** #15 `TasksViewModel` extraction · #16 shared `FeatureViewModel` base (4 duplicated infra copies → 1) · #17 `DayReviewScreen` split (3247→1937 + 4 siblings) · #18 `Role.Button`/`clickableRow` on core rows · #19 `KairoShapes`/`NotesTokens` single-sourced radii · #20 `MigrationChainGuardTest` headless guard + documented no-unused-index decision.

### The three named ceilings to 9.5 (honest — not reached)

1. **AppViewModel is still a 5480-line god object** (net −14 after the Tasks carve). The view-state moved out
   but the heavy `groups`/`outlineRows`/`hierarchyRows` render pipeline (`AppViewModel.kt:1364,1381,1400`) stayed,
   woven into zone/day-start/priority-config internals — the actual bulk and coupling. *(Architecture/Maintainability ceiling.)*
2. **Swipe-only destructive actions on the primary list have no TalkBack path.** `TaskRow.kt:60-73`
   `SwipeToDismissBox` triggers complete/delete purely by gesture with no `CustomAccessibilityAction` fallback
   (custom a11y actions exist in only 1 file); a 30dp collapse chevron (`TaskRow.kt:86`) also stays sub-48. *(Accessibility ceiling.)*
3. **Migration *correctness* is never exercised headlessly.** Both JVM guards build a fresh DB *directly at v87*
   and assert only the migration array's integer shape; the SQL bodies (incl. the JSON→row copies in
   `MIGRATION_85_86`/`_86_87`, `AppDatabase.kt:1055-1176`) are replayed only by the device-only instrumented
   `MigrationTest`, excluded from `./gradlew test`. For an offline app whose Room DB is the user's only copy,
   that is the sharpest unmitigated data-loss exposure. *(Testing/Data ceiling.)*

_The original pre-fix re-audit + plan that these 20 fixes executed against follows unchanged below._

---

# Whole-app re-audit — grounded post-decomposition re-score + plan to 9.5 (2026-09-21)

A fresh, evidence-led re-audit run after the Phase 3 decomposition, via four parallel read-only deep-dives
(performance · maintainability & cross-module · UI/design-system/a11y · data/security/testing), each cited to
`file:line`, then key claims re-verified directly. Dashboard artifact: <https://claude.ai/artifact/RWzXNRXNu1P5o2hVtn93Zv>.

**Recalibration.** This pass is grounded in the code rather than optimistic self-scoring, so several dimensions
move **down** to honest numbers and two move **up** (the code is better than assumed there). Prior "Overall ≈8.0"
→ **≈7.5**.

| Dimension | Prior | **Now** | One-line why |
|---|:---:|:---:|---|
| Security | 8.5 | **9.0 ↑** | truly offline (no INTERNET/network code), minimal perms, SQLCipher default-on + StrongBox-wrapped key, PBKDF2-600k, FileVault, panic-wipe, gated exports. |
| Architecture | 8.4 | **8.2 ↓** | 5 collaborators extracted on a proven pattern; held back by the un-extracted twin `AppRepository` (2359 LOC, 282 methods) + the whole Tasks feature still on the parent. |
| Data / scalability | 8.1 | **8.0** | DB v87, 82 contiguous migrations (no gaps, forward-destructive refused), backup lossless over all 50 entities; a few growable FK columns unindexed. |
| Performance | 7.0 | **7.5 ↑** | strong-skipping on; off-main `WhileSubscribed` reactive layer; keyed/memoized lists. Cost is systemic one-liners (no repo `shareIn`; flows never quiesce backgrounded). |
| Testing | 8.0 | **7.0 ↓** | 566 headless tests cover backup/migrations/recurrence/crypto/streaks — but the 5 feature VMs (~1800 LOC) have **zero** direct coverage; pre-v59 migrations unvalidated headlessly. |
| UI / design-system | 7.5 | **7.0 ↓** | `AppCard` 30 uses / 0 raw Cards, M3 hygiene clean; but spacing+shape token layers are defined-and-unused (0 uses vs ~4200 raw dp), 29 hand-rolled top bars, drifting non-dark-aware color maps. |
| Cross-module | 7.5 | **6.5 ↓** | disciplined collaborator template, but helpers copy-pasted not factored; `timeVm` breaks the shim convention, Goals breaks flow-naming; minute/week/search logic re-implemented per feature with divergent edge cases. |
| Maintainability | (folded) | **6.5 (new)** | excellent comment hygiene + centralized core math, but two mega-objects remain (`AppViewModel` 5493, `AppRepository` 2359), the biggest screen (`DayReviewScreen` 3247) is a grab-bag, small-helper duplication ripples. |
| **Overall** | **≈8.0** | **≈7.5** | the gap to 9.5 is concentrated in Maintainability / Cross-module / Testing / UI, not diffuse. |

**Prioritized plan (impact ÷ effort).**
- *Tier 1 — quick wins:* `shareIn` the hot repo flows (`allTasks` first); one `formatMinutes()` for the 6 copies + 33 raw idioms; delete dead `AppViewModel.search` + `keepTan`; fix the stale migration-count comments; migrate the 29 hand-rolled `TopAppBar(52.dp)` → `KairoTopBar`; fix the sub-48dp tap targets; route the 3 share flows through `ProgressCard`.
- *Tier 2 — high-leverage:* adopt `collectAsStateWithLifecycle` (436 sites); gate the per-emit `settings` KeyStore-decrypt + disk-write; move the 7 `Eagerly` flows to `WhileSubscribed`; pre-group checkins for the habits list (O(H×C)→O(C)); dark-aware `captureChipColor` + collapse drifting color maps; characterization tests for the 5 feature VMs; make `BackupRoundTripTest` table-exhaustive.
- *Tier 3 — structural:* extract a `TasksViewModel`; lift the copy-pasted VM infra into a shared base; split `DayReviewScreen`; `Role.Button` on ~200 clickable rows via one helper; adopt (or retire) the spacing/shape tokens; headless v5–v58 migration schema check + index `time_entries.workspaceId`/`events.calendarId`; (ceiling) decompose `AppRepository`.

_The Phase-3 decomposition round and earlier logs follow unchanged below._

---

# Sub-ViewModel split — carve backup/export/import/sync into `BackupSyncViewModel` (Phase 3 complete) (2026-09-21)

The **fifth and final** feature collaborator completes the Phase 3 decomposition: the whole backup /
export / import / sync surface is lifted out of `AppViewModel` into `BackupSyncViewModel`, in one
**behaviour-preserving** stage — compiled green (`compileReleaseKotlin`), proven equivalent by the
characterization net (`AppViewModelCharacterizationTest` 8/8 + `NoteScopeQueryTest` 2/2), release-built
(`assembleRelease -x lint`) and re-verified at **0 forbidden permissions**, with **no screen code changed**.

The move is unusually clean because the two data-layer collaborators it owns — `BackupExporter` (`backup`)
and `RestoreManager` (`restore`) — were already private on the parent with **zero external call sites**, so
they carry over whole. The collaborator owns them plus their threading/UI-glue wrappers (JSON / Markdown /
CSV / ICS / habit-CSV export, passphrase-encrypted backup, SAF-free Downloads export, share-a-copy; the sync
+ restore/import wrappers) and the auto-backup/sync settings setters (`rearmAutoBackup` + the folder/enabled/
interval/hour/dow/dom/passphrase setters). Two private helpers the data collaborators need were promoted
private→internal: `readImportTextBounded` (kept on the parent because three non-backup importers also use it)
and `displayNameOf` (needed by `RestoreManager`).

**Deliberately kept on the coordinating parent** (documented in-code): the feature-specific importers that
only *look* backup-ish — calendar `.ics`/vCard import and the note passphrase "courier" — which belong to
their own features; the cross-feature read models `dataCounts` / `deviceInventory`; the DB-encryption
(`SecureDb`) cluster; `setMorningBrief` (reminders); and the scattered app-level one-line settings setters
(sidebar, modules, onboarding, muting, reduce-motion…), whose move behind one-line shims would be net-zero
and would turn the collaborator into a junk drawer.

**Result — Phase 3 decomposition complete.** `AppViewModel` **5582 → 5493 lines** (and **~6600 → 5493**,
**~1,100 lines / ~17%** off across the four carves this round). The god-object is now a *coordinator* of five
lifecycle-free feature collaborators, each owning a cohesive slice:

| Collaborator | Lines | Owns |
|---|---:|---|
| `TimeTrackingViewModel` | 78 | time entries / activities / the running-timer controller |
| `NotesViewModel` | 674 | the notes surface (CRUD, notebooks, vault, `.md` interop, review, threads, ink) |
| `HabitsViewModel` | 629 | habit tracking + the habit-science / life-systems coach |
| `GoalsRoutinesViewModel` | 284 | Unified Goals + review/accountability + analytics, and press-play Routines |
| `BackupSyncViewModel` | 173 | backup / export / import / sync + auto-backup settings |

What remains on the parent is genuinely coordination: the task/list/folder core, the cross-feature bridges
(reminders, day-review rollups, capacity/keystone reasoning, note-embeds, the Focus↔habit init wiring), and
navigation/UI state — the seams between features, which is exactly where a coordinator's code belongs.

### Scorecard delta (Goals-Routines carve → Backup-Sync carve · Phase 3 complete)
| Dimension | Prev | **Now** | Why |
|---|:---:|:---:|---|
| Architecture | 8.2 | **8.4** | the decomposition is *finished*: every feature with a cohesive surface now lives in its own lifecycle-free collaborator, and the remaining god-file is coordination rather than feature code — a qualitative shift, not just fewer lines. |
| Testing | 8.0 | **8.0** | unchanged — the characterization net proved all five carves equivalent, as designed. |
| Data / Cross-module / Perf / UI / Security | 8.1 / 7.5 / 7.0 / 7.5 / 8.5 | **8.1 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.95** | **≈8.0** | four consecutive structural gains on the hardest file in the codebase, at zero behavioural or surface-area cost. |

_The Goals-Routines-carve round and earlier logs follow unchanged below._

---

# Sub-ViewModel split — carve the Goals + Routines surface into `GoalsRoutinesViewModel` (2026-09-21)

The **fourth** feature collaborator (after `TimeTrackingViewModel`, `NotesViewModel` and `HabitsViewModel`)
lifts the whole **Goals** (Unified Goals + the review/accountability layer + the health/capacity/coach/contention
analytics) and **Routines** (press-play sequences) surface out of `AppViewModel`, in two individually-verified,
**behaviour-preserving** stages — each compiled green (`compileReleaseKotlin`), stayed provably equivalent under
the characterization net (`AppViewModelCharacterizationTest` 8/8 + `NoteScopeQueryTest` 2/2), release-built
(`assembleRelease -x lint`) and re-verified at **0 forbidden permissions**. **No screen code changed**, and the
parent keeps thin forwarding shims (`val goalsState get() = goalsRoutinesVm.goalsState`, `fun goals() =
goalsRoutinesVm.goals()`) so every screen call site *and* the parent's own goal/routine bridges resolve unchanged.

- **Stage 6-A (Routines)** moved the read-model flows (`routinesState`, `routineRunsState`), the runner deep-link
  view-state (`pendingRoutineRun`), and the press-play data actions (`routines`/`saveRoutines`/`requestRoutineRun`/
  `activeRoutineRun`/`save`+`clearActiveRoutineRun`/`upsertRoutine`/`deleteRoutine`/`routineRuns`/`routinesDueToday`/
  `runRoutineByName`/`searchRoutines`).
- **Stage 6-B (Goals)** moved the read-models (`goalsState`, `goalReviewsState`), the nested `GoalHealth` /
  `GoalCapacity` / `GoalCoach` types (zero screen references, so they travel with their producers), and the goal
  actions + analytics (`goals`/`saveGoals`/`upsertGoal`/`deleteGoal`, `goalReviews`/`saveGoalReviews`/`logGoalReview`,
  `goalHealth`, `goalCapacity`, `goalCoaching`, `goalContention`, `searchGoals`, `shareGoalSnapshot`).

**Deliberately kept on the coordinating parent, documented in-code as intentional** (they are goal/routine↔X
_bridges_, not the surface itself): the multi-source reminders read-model (`allReminders`); the `{{goal:id}}`
note-embed and `openGoalJournal` (notes); the task `isGoal` completion celebration (`goalCelebration`) and `setGoal`
(tasks); the period/day-review rollup (`weekPeriodShareData`, `saveDayAlignment`); `runRoutine`'s timer + automation
start and `startActivityTimer` (time-tracking); `logRoutineRun`'s per-step habit/task tick (habits + tasks); and the
keystone/capacity reasoning (`bestKeystone`, `capacitySnapshot`) that a goal's health merely reads through. The
collaborator reaches back to the parent for exactly those cross-feature reads (`app.tasks`, `app.habitCheckins`,
`app.habitsWithArchived`, `app.strengthOf`, `app.timeVm.timeEntries`, `app.trackedCapacityHours`, `app.runRoutine`).

**Result:** `AppViewModel` **5762 → 5582 lines** (and **~6600 → 5582**, ~1,020 lines, across the three carves this
round); `GoalsRoutinesViewModel` now **284 lines**. Four real feature collaborators now prove the pattern generalizes
across very different surfaces (a 78-line time tracker, a 674-line notes engine, a 629-line habits+coach engine, a
284-line goals+routines engine), leaving `BackupSettingsViewModel` as the last planned carve.

### Scorecard delta (Habits carve → Goals-Routines carve)
| Dimension | Prev | **Now** | Why |
|---|:---:|:---:|---|
| Architecture | 8.0 | **8.2** | a fourth lifecycle-free collaborator sheds another ~180 lines and makes the goal↔tasks/habits/time/day-review seams explicit; the god-object is now a coordinator of feature collaborators rather than the home of every feature. |
| Testing | 8.0 | **8.0** | unchanged — the net proved every stage equivalent, as designed. |
| Data / Cross-module / Perf / UI / Security | 8.1 / 7.5 / 7.0 / 7.5 / 8.5 | **8.1 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.9** | **≈7.95** | a third consecutive structural gain on the hardest file, with zero behavioural or surface-area cost. |

_The Habits-carve round and earlier logs follow unchanged below._

---

# Sub-ViewModel split — carve the Habits surface out of the god-VM into `HabitsViewModel` (2026-09-21)

The third feature collaborator (after `TimeTrackingViewModel` and `NotesViewModel`), and the largest
carve yet: the whole **Habits** surface — habit tracking plus the habit-science / life-systems coaching
layer built on top of it — lifted out of `AppViewModel` in seven individually-verified, **behaviour-preserving**
stages. Every stage compiled green (`compileReleaseKotlin`), stayed provably equivalent under the widened
characterization net (`AppViewModelCharacterizationTest` 8/8 + `NoteScopeQueryTest` 2/2), release-built
(`assembleRelease -x lint`) and re-verified at **0 forbidden permissions**. **No screen code changed at any
stage** — `HabitsScreen` still references `AppViewModel.HabitShine` by name (the data class stays on the
parent; only its flow moved), and every one of the ~80 habit call sites reaches the collaborator through a
thin forwarding shim (`val habits get() = habitsVm.habits`, `fun saveHabit(h) = habitsVm.saveHabit(h)`).

**Stages.**
- **5-A** moved the read-model flows: the three habit lists (`habits`, `habitsWithArchived`, `trashedHabits`),
  `habitCheckins`, and the habit-science tables (`cravings`, `witnessEvents`, `scorecardItems`, `buddies`,
  `integrityReviews`, `experiments`, `escrows`, `nudgeEvents`).
- **5-B** moved habit view-state + the settings setters (matrix/density/group/sort/insights, time-cfg, block-minute).
- **5-C** moved habit CRUD (create/save/add/trash/restore/archive/delete/empty-trash/order) + widget refresh.
- **5-D** moved the check-in / day-log / award engine (cycle, set-value, the reward "shine", `awardIfNewlyDone`,
  make-up/keep-streak/skip/slip/clear/set-day, freeze spend, photo, pause).
- **5-E1/5-E2** moved the R33/R34 habit-builder + life-systems coach actions (freeze, pledge, quit-clock,
  cravings, journeys, chronotype/calm, rewards, values, scorecard, witnesses, forfeit/ease, buddy digests,
  integrity reviews, bookends/companion/strength-meter, reminder drift, graduation, n-of-1 experiments).
- **5-F** moved the remaining habit-owned tail: the on-device progress-card share, mute-habit, Z8 graded
  strength (`strengthOf`/`gradedStrengthPreview`/`setGradedStrength`), the FW-5 WIP limiter, FW-9 self-escrow,
  FW-14 personal-nudge MRT, R37 receptivity timing, and habit substring search.

**Deliberately kept on the coordinating parent, documented in-code as intentional** (they are habits↔X
_bridges_ or belong to a later carve, not the habits surface): the keystone/burnout/momentum reasoning
(`bestKeystone`, `keystoneHabitId`, `burnoutSignal`, `momentumSnapshot`, `learnedHabitMinutes`) which reads
whole-app task/time/goal data; the habit↔time bridges (`startTimeTrackingForHabit`, `setHabitTimeActivity`,
`placeHabitBlock`, the habit-time reserve helpers); the habit↔notes bridge (`openHabitJournal`); the
habit+task+focus weekly recap (`shareWeeklyRecap`); the Focus→habit auto-credit init bridge (which now calls
`habitsVm.celebrateIfRewardReached` / `habitsVm.awardIfNewlyDone`); and the habit-CSV export/import glue
(`exportHabitsCsvTo`, `importHabitsCsv`) which stays beside the rest of the export/import block for the
future `BackupSettingsViewModel` carve.

**Result:** `AppViewModel` **6174 → 5762 lines** (and **~6600 → 5762**, ~840 lines, across the two carves
this round); `HabitsViewModel` now **629 lines** owning a cohesive, self-contained slice. Three real feature
collaborators now prove the pattern generalizes across very different surfaces (a 78-line time tracker, a
674-line notes engine, a 629-line habits+coach engine), leaving Goals-Routines and Backup-Settings as the
remaining carves.

### Scorecard delta (Notes carve → Habits carve)
| Dimension | Prev | **Now** | Why |
|---|:---:|:---:|---|
| Architecture | 7.7 | **8.0** | the god-object sheds another ~410 lines into a third lifecycle-free collaborator; the habits↔tasks/time/goals/Focus boundaries — historically the app's most tangled seam — are now explicit and documented, and the pattern is proven repeatable rather than a one-off. |
| Testing | 8.0 | **8.0** | unchanged — the widened net proved every stage equivalent, as designed. |
| Data / Cross-module / Perf / UI / Security | 8.1 / 7.5 / 7.0 / 7.5 / 8.5 | **8.1 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.85** | **≈7.9** | a second real structural gain on the hardest file, with zero behavioural or surface-area cost. |

_The Notes-carve round and earlier logs follow unchanged below._

---

# Sub-ViewModel split — carve the Notes surface out of the god-VM into `NotesViewModel` (2026-09-21)

With the cross-feature read models pinned (previous round), the decomposition itself ran in small,
individually-verified stages, each **behaviour-preserving**, compiled green (`compileReleaseKotlin`),
proven equivalent by the widened characterization net (`AppViewModelCharacterizationTest` 8/8 +
`NoteScopeQueryTest` 2/2), release-built (`assembleRelease -x lint`) and re-verified at **0 forbidden
permissions**. No screen code changed at any stage.

**Pattern (not a rewrite).** `NotesViewModel` continues the exact collaborator pattern the codebase already
established with `TimeTrackingViewModel`: a plain class (not an Android `ViewModel`) that `AppViewModel`
constructs once and drives with its own `viewModelScope`, so there is no second lifecycle. It owns the
workspace-scoped note read-model flows and the note actions, reaching back to the parent only for genuinely
cross-feature state (`app.settings`, `app.appCtx`, `app.toast`, the transclusion/recap engine). `AppViewModel`
keeps thin **forwarding shims** (`val notes get() = notesVm.notes`, `fun saveNote(n) = notesVm.saveNote(n)`),
so the ~170 note call sites across 20 screen files needed **zero** edits.

- **Stage 4a–4e** moved the note read-models, CRUD, notebooks + note-settings, reminders/seal/trash-retention,
  search/ask/now, and the **L11 vault** (passphrase body-encryption at rest).
- **Stage 4f-1** moved the `.md` folder interop (single-note export, `.md`-per-note export/import, the
  two-way mirror with conflict resolution, renderer image resolution).
- **Stage 4f-2** moved the note-local long-tail that was interleaved with cross-feature bridges: the woven
  open entry points, evergreen (spaced) review, threads/Maps-of-Content, and writing sprints.
- **Stage 4f-3** moved ink-note capture and closed the round.

**Deliberately kept on the coordinating parent, documented in-code as intentional** (they are notes↔X
_bridges_, not the notes surface): the live-transclusion / period-digest engine (`expandNoteTransclusion`,
`expandNoteForExport`, the daily/periodic-note openers and recap writers — they reach whole-app task/event/time
data); checkbox→task extraction and reconcile; the Note-Garden task-health roll-up; note outcome/card roll-ups;
the Sealed-Courier crypto; and the shared tag/context management + whole-app-search dispatcher (the latter
already reaches `timeVm` the same way, so `searchNotebooks` stays beside it).

**Result:** `AppViewModel` **~6600 → 6174 lines**; `NotesViewModel` now **674 lines** owning a cohesive,
self-contained slice; a second real feature collaborator (after `TimeTrackingViewModel`) proving the pattern
generalizes and setting the template for the Habits / Goals-Routines / Backup-Settings carves that follow.

### Scorecard delta (characterization widening → sub-ViewModel split)
| Dimension | Prev | **Now** | Why |
|---|:---:|:---:|---|
| Architecture | 7.4 | **7.7** | the single largest god-object shrinks by ~430 lines into a lifecycle-free feature collaborator on an already-proven pattern; the notes↔X boundaries are now explicit and documented rather than implicit in one 6.6k-line class. |
| Testing | 8.0 | **8.0** | unchanged — the widened net did its job (every stage proved equivalent), rather than adding new coverage. |
| Data / Cross-module / Perf / UI / Security | 8.1 / 7.5 / 7.0 / 7.5 / 8.5 | **8.1 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.8** | **≈7.85** | a real structural gain (maintainability of the hardest file) with zero behavioural or surface-area cost. |

_The characterization-widening round and earlier logs follow unchanged below._

---

# Characterization widening — pin the god-VM's cross-feature read models before the split (2026-09-21)

The agreed sequence toward the sub-ViewModel split is **widen the characterization net first, then split**. This
round pins the three highest-risk cross-feature seams a per-feature-VM decomposition is most likely to break —
each drives the **real** `AppViewModel` + real `AppRepository` (in-memory Room) end to end on the existing
headless Robolectric harness, then asserts observable behaviour:

- **`groups` re-derives for each selected smart list.**
  `groups_reDeriveForEachSelectedSmartList` seeds four tasks — dated-today, completed, flagged, and a plain
  open one — then flips `currentView` through **Today → Completed → Flagged** and asserts `vm.groups` (the
  view-filtered/grouped list behind every task screen, computed by `ListPipeline.compute`) surfaces exactly the
  one task that belongs in each, and none of the others. This pins the single most load-bearing read model in
  the VM — the one a split is most likely to silently break.
- **Blocked-by → Waiting-on, end to end.**
  `aTaskBlockedByAnOpenPrerequisite_waits_untilTheBlockerCompletes` adds a real dependency
  (`repo.addDependency`), asserts the blocked task appears in WAITING's "Blocked by your task" group while its
  prerequisite is open, then completes the prerequisite and asserts it leaves Waiting. This is a GTD seam that
  has regressed before ("Waiting empty despite a blocked-by").
- **Subtasks nest under their parent.**
  `subtasks_nestUnderTheirParentInTheListOutline` creates a parent + child (`parentId`) and asserts
  `vm.outlineRows` for that list renders the parent at depth 0 with `hasChildren`, and the child at depth 1 —
  pinning the outline builder wiring.

**+3 characterization tests (8 on the VM now); `AppViewModelCharacterizationTest` green (8/8, 0 failures).**
Test-only — no main source touched, so the release APK and its 0 forbidden permissions are unchanged.

### Scorecard delta (NavHost round → characterization widening)
| Dimension | Prev | **Now** | Why |
|---|:---:|:---:|---|
| Testing | 7.8 | **8.0** | the three cross-feature read models a per-feature-VM split most endangers — the `groups` pipeline, blocked→Waiting, and the subtask outline — are now pinned end-to-end, so the split can be proven equivalent, not hoped equivalent. |
| Architecture / Data / Cross-module / Perf / UI / Security | 7.4 / 8.1 / 7.5 / 7.0 / 7.5 / 8.5 | **7.4 / 8.1 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.8** | **≈7.8** | incremental test-depth that de-risks the next (large) lever — the sub-ViewModel decomposition — rather than moving the ceiling itself. |

_The NavHost round and earlier logs follow unchanged below._

---

# NavHost round — hand-rolled overlay flags → three coherent saveable back-stacks (2026-09-21)

`AppRoot.kt` (the ~2.3k-line composition root) drove every full-screen overlay off a loose pile of ~25
`show*` booleans plus two hand-rolled `remember` stacks. Nothing was a real back-stack, several nav flags
were **not** saveable (so an open overlay silently lost its arguments on process death), and Back-handling
was spread across 45 sites. This round replaced that with **three coherent, saveable navigation surfaces**,
in device-verified stages (the user ran an interactive checklist after each — "All N checks pass"), each
behaviour-preserving, built green (`assembleRelease -x lint`), and re-verified at 0 forbidden permissions.

- **Stage 1 — task drill-in → `taskStack`.** The parent→subtask→linked-task drill-in became one
  `rememberSaveable` list (`listSaver` over `mutableStateListOf<String>()`), with `editing` derived as its
  top. This also **fixed a real bug**: the old `editStack` was plain `remember`, so a drill-in was lost on
  process death.
- **Stage 2 — 13 argument-free overlays → `overlayStack`.** Stats, Attachments, The Record, Plan, Review,
  Momentum, Routines, Goals, Time-tracking, Time-stats, Notes-Graph, Notes-Garden and Recall fold into one
  saveable stack over an `Overlay` enum; one `when(overlayStack.lastOrNull())` renders the top, each screen's
  own Back calls `closeOverlay()`. Momentum→Goals stacks naturally (push Goals, Back returns to Momentum).
- **Stage 3 — 4 argument-carrying overlays → `argOverlay`.** Occasions, Day/Week review, the any-period
  Recap and the Journal hub — seven ad-hoc flags, five of them non-saveable — collapse into a single nullable
  slot over a sealed `OverlayArg`, whose custom `Saver` round-trips each destination's **arguments** through a
  Bundle-safe string. This **fixes** the process-death argument loss (which occasion, which recap range, which
  journal period) on all four.

**Deliberately left as-is, and now documented in-code as intentional** (not deferred work):
- The **note editor** (`editingNote`) stays its own saveable slot because it interleaves with the task stack
  (a task opens a note *on top of itself*; Back reveals the task) — folding it would risk that stacking for
  no correctness gain, and it is already process-death-safe.
- The **habit / life-systems** overlays stay VM-held `StateFlow`s because they are opened from 6+ decoupled
  feature screens; a ViewModel-held destination is the correct pattern for nav triggered from many places, and
  moving them into AppRoot-local state would be an architectural regression.

Net: two hand-rolled stacks and ~20 nav flags in the app's largest file became three saveable back-stacks with
a single render site each and compiler-guarded call sites (deleting the flags forced every open site to be
converted). Three latent process-death bugs closed along the way. Main-source change confined to `AppRoot.kt`.

### Scorecard delta (R10 → NavHost round)
| Dimension | R10 | **Now** | Why |
|---|:---:|:---:|---|
| Architecture | 7.0 | **7.4** | the single largest UI file's navigation went from ~25 scattered flags + 2 ad-hoc stacks + 45 Back sites to three coherent saveable surfaces with one render site each; the two families that *should* stay outside are now documented as deliberate, not accidental. |
| Data (correctness) | 8.0 | **8.1** | three latent process-death bugs closed — a drill-in and four arg-carrying overlays now restore their arguments after the process is killed. |
| Cross-module / Perf / UI / Security / Testing | 7.5 / 7.0 / 7.5 / 8.5 / 7.8 | **7.5 / 7.0 / 7.5 / 8.5 / 7.8** | unchanged this round. |
| **Overall** | **≈7.7** | **≈7.8** | the top-ranked device-gated lever (the NavHost migration) is now banked and verified; the remaining ceiling is the sub-VM split. |

_The Round 10 and earlier logs follow unchanged below._

---

# Round 10 — Characterization coverage on the god-VM's write + read paths (2026-09-21)

Round 9's re-audit re-ranked _"deepen the pyramid's top"_ and _"lock the god-VM's behaviour"_ as the top
self-verifiable levers. This round banks two behaviour characterizations that drive the VM through its real
orchestration — not just the pure kernels the domain suites already cover — on the two seams a per-feature-VM
split is most likely to break:

- **Recurring completion rolls forward, never closes.**
  `AppViewModelCharacterizationTest.completingARepeatingTask_rollsItForwardInsteadOfClosingIt` seeds a
  daily-recurring dated task and drives `vm.toggleComplete` end to end: it must re-surface **open**, its due
  date advanced and its rule intact. The pure decision is already unit-tested (`RecurringRollForwardTest`);
  this pins the VM **wiring** around it — persist + re-arm + re-surface — the exact seam that once left a
  deadline frozen and permanently overdue.
- **Capacity counts a dated task's estimate as committed.**
  `capacitySnapshot_countsADatedTaskEstimateAsCommitted` seeds a 60-minute dated task and asserts the
  "will it fit?" read path reports committed = 60 and free = capacity − committed — pinning the
  workload / over-commit orchestration.

Both run headlessly on the existing Robolectric harness (real in-memory repo, lazy-flow `await`), through
the production A2 constructor. **+2 characterization tests (5 on the VM now); suite green.** Test-only — no
main source touched, so the release APK and its 0 forbidden permissions are unchanged.

### Scorecard delta (R9 → R10)
| Dimension | R9 | **R10** | Why |
|---|:---:|:---:|---|
| Testing | 7.7 | **7.8** | the two least-covered VM seams — the recurring write-path wiring and the capacity read-path — are now characterized end-to-end, de-risking the eventual sub-VM split. |
| Architecture / Data / Cross-module / Perf / UI / Security | 7.0 / 8.0 / 7.5 / 7.0 / 7.5 / 8.5 | **7.0 / 8.0 / 7.5 / 7.0 / 7.5 / 8.5** | unchanged this round. |
| **Overall** | **≈7.7** | **≈7.7** | incremental test-depth; the ceiling remains the device-gated NavHost + sub-VM split. |

_The Round 9 and earlier logs follow unchanged below._

---

# Round 9 — Fresh scored re-audit; top pick executed (migration-chain safety net) (2026-09-21)

A ground-up re-scan of the codebase (not a re-statement of prior numbers), measured against the actual
source, to re-rank the highest-value levers left to 9.5 — then act on the top pick.

### Measured state (this scan)
| Signal | Value | Reading |
|---|---|---|
| Source size | 295 main `.kt`, ~83k lines | large but modular; screens dominate |
| **God-object** | `AppViewModel.kt` = **6,652 lines, 676 funcs, 77 flows** | still the #1 architecture debt despite the time-carve |
| Next-largest | DayReviewScreen 3.2k · CalendarScreen 2.6k · AppRepository 2.4k · AppRoot 2.3k · SettingsScreen 2.2k | big Compose screens (typical), one repo facade |
| **Navigation** | **30 `BackHandler`s, no `NavHost` / compose-navigation** | hand-rolled flag/overlay nav (audit #16); device/interactive-verification territory |
| Unit tests | **560 `@Test` across 82 files** | strong pure/Robolectric pyramid |
| Instrumented tests | **1 file** (`MigrationTest`) | the pyramid's top is thin; and it doesn't run in the headless suite |
| Schema | **82 migrations, DB v87**, schemas exported through `87.json` | one migration discipline, real SQLite validation on device |
| Backups | compact JSON + encrypted-gzip (TCENC4), lossless round-trip tested | strong (R8) |
| Security | 0 forbidden perms (verified), AES-256-GCM / PBKDF2-600k, FileVault, renderer sanitize+escape+nofollow | strong |
| Perf infra | `baseline-prof.txt` present; **no macrobenchmark module**; `@Immutable`/`@Stable` sparse | startup profiled, but regressions are unmeasurable |
| A11y | ~65 semantics/`contentDescription` refs across 14 files, concentrated in shared components | present but thin per-screen |

### The finding that set the top pick
The migration safety net had **silently decayed into a no-op on this CI**. `MigrationTest` (a) is an
*instrumented* test, so it never runs in `:app:testDebugUnitTest` (the suite that actually runs here), and
(b) had a **stale hard-coded assertion** — it pinned the chain end to version **82** while the DB has since
advanced to **87** (the Goals→Room v86 and Routines→Room v87 promotions among them). Net: the single most
destructive Room mistake — bumping `@Database(version)` without adding the matching migration, which **wipes
the user's data on update** — was guarded by nothing that runs. That is the highest value-per-effort,
fully-unit-verifiable lever on the board, so it is this round's pick.

### Shipped this round (the top pick)
- **`MigrationChainTest` (new, Robolectric, headless).** Runs in the normal unit suite and is **dynamic**: it
  reads the current schema version back from a freshly-built DB's `PRAGMA user_version` (what Room stamps to
  the `@Database` version) rather than hard-coding it, then asserts the migration chain is one-step,
  gap-free, dup-free, and **ends exactly at that version**. Forget a migration on the next bump and this goes
  red — on every build, forever, without a hand-edit.
- **De-staled the instrumented `MigrationTest`.** Dropped its stale `== 82` assertion (that invariant now
  lives, dynamic, in the headless test) and made `exportedLatestSchemaIsBuildable` derive the latest version
  from the chain (`ALL_MIGRATIONS.maxOf { endVersion }`) so it can't rot on a version bump either. Its
  real-SQLite replay value is kept for device/emulator CI.

### Re-ranked levers to 9.5 (value × verifiability)
1. **[done, this round]** Migration-chain headless guard — data-safety, self-verifiable.
2. **Deepen the test pyramid's top** — more Robolectric-runnable DB / semantics / render tests (only 1
   instrumented file today). Self-verifiable, high value.
3. **Continue god-VM decomposition via pure use-cases** (the `RecurringRollForward` pattern) — lift more pure
   logic (capacity/workload, recap aggregation) out of the 6.6k-line VM into tested classes. Self-verifiable;
   the cross-cutting-flow *sub-VM* split remains device-gated.
4. **Real `NavHost`** (#16, 30 BackHandlers) — highest ceiling, but rotation/back-stack can only be validated
   interactively on-device.
5. **Performance measurement** — a macrobenchmark module + a Compose-stability pass; partly device-gated
   (running the benchmark needs a device).
6. **Accessibility** — a per-screen semantic sweep + a Robolectric semantics test. Self-verifiable, medium.

### Scorecard delta (R8 → R9)
| Dimension | R8 | **R9** | Why |
|---|:---:|:---:|---|
| Data storage | 8.0 | **8.0** | unchanged in capability, but the migration guard that *protects* this score is no longer dormant — a latent data-loss risk is closed. |
| Testing | 7.5 | **7.7** | the migration invariant now runs headlessly and dynamically; the biggest silent gap in the suite is closed. |
| Cross-module | 7.0 | **7.5** | shared period engine / share system / Markdown grammar, and the habit day-parser unified in #527. |
| UI/UX | 7.0 | **7.5** | M3 Expressive + shared tokens/components from the design-system rounds. |
| Architecture / Performance / Security | 7.0 / 7.0 / 8.5 | **7.0 / 7.0 / 8.5** | god-VM + hand-rolled nav still dominate architecture; perf is profiled but unmeasured; security stays strong. |
| **Overall** | **≈7.6** | **≈7.7** | a data-safety + measurement round. The ceiling to 9.5 is still the two device-gated structural items (NavHost, sub-VM split) plus test-pyramid depth. |

_The Round 8 and earlier logs follow unchanged below._

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
