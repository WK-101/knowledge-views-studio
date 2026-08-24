# Our App — Proposed Skeleton (for discussion)

Working name **TaskTree**. Native Android, Kotlin + Jetpack Compose (Material 3),
Room/SQLite, 100% offline (no `INTERNET` permission), no account, everything free,
lossless JSON/OPML/.ics portability. Derived from `comparison-and-decisions.md`.

> This is a **proposal to discuss**, not final. Nothing here is built yet beyond the
> existing bare-outline skeleton; we lock the model and screens first, then implement.

---

## 1. Architecture (clean, unidirectional)

```
UI (Compose screens, Material 3)          ← state in, events out
  │  collects StateFlow, sends intents
ViewModels (per screen)                    ← hold UI state
  │  call
Domain / UseCases                          ← pure Kotlin, testable
  ├─ PriorityEngine   (computed score)     ← the MLO core, no Android deps
  ├─ ViewEngine       (filter tree + sort)
  ├─ RecurrenceEngine (RRULE next-occurrence)
  └─ Port (export/import: JSON / OPML / ics)
  │  through
Repositories                               ← single source of truth
  │  over
Room (SQLite) DAOs + Entities              ← local, the only persistence
  +  AlarmScheduler (AlarmManager)         ← exact-alarm reminders, local
```

- **Reactive:** DAOs return `Flow`; repositories expose `Flow`; ViewModels map to
  `StateFlow`; Compose collects. Edits write to Room and the UI updates automatically.
- **PriorityEngine is pure Kotlin** (no Android) so it's unit-testable and reusable.
- **DI:** Hilt (or a hand-rolled container to start — decision point).

Package layout (`com.tasktree.app`):
```
data/        entities, daos, TaskTreeDatabase, repositories
domain/      model (plain), usecases, priority, view, recurrence, port
ui/          theme, components, screens/{outline,todo,detail,quickadd,views,settings}
reminders/   AlarmScheduler, boot receiver, notifications
```

---

## 2. Data model (Room entities) — the lossless schema

Derived from MLO's entity set (`mylifeorganized.md` §3) plus TickTick's blocks. UUID
primary keys everywhere (stable across export/import). Soft-delete via tombstones so
imports/merges never lose history.

| Entity | Key fields | Notes / source |
|---|---|---|
| **Task** | `id`, `parentId`, `sortOrder`, `title`, `note`(md), `completed`, `completedAt`, `importance`(1–5), `urgency`(1–5), `startDate`, `dueDate`, `durationMin`, `estimateMin/Max`, `leadTimeMin`, `hideInTodoUntilStart`, `hideInTodoIfBlocked`, `star`, `starAt`, `flagId?`, `isGoal`, `isProject`, `projectStatus`, `reviewEveryDays?`, `completeInOrder`, `colorOverride?`, `createdAt`, `updatedAt` | The spine. Self-referencing tree = unlimited nesting (MLO). |
| **Context** | `id`, `parentId?`, `name`, `icon`, `color`, `latitude?`, `longitude?`, `radiusM?`, `openHours?`(JSON), `active` | GTD context, hierarchical, geo + open-hours availability (MLO). |
| **TaskContext** | `taskId`, `contextId` | M:N join (a task can have several contexts). |
| **Dependency** | `taskId`, `dependsOnTaskId`, `mode`(AND/OR), `postponeToStart` | Task→task predecessor; blocked tasks drop out of "Do next" (MLO). |
| **Reminder** | `id`, `taskId`, `type`(absolute/relativeToDue/relativeToStart/location), `offsetMin?`, `atTime?`, `contextId?`(for location), `annoying`, `tone?` | Multiple per task; time + geofence (MLO+TickTick). |
| **Recurrence** | `id`, `taskId`, `rrule`(iCal RRULE), `mode`(fromDue/fromCompletion), `skip` | One per task; standard RRULE incl. weekly/monthly/yearly; lunar later. |
| **Tag** | `id`, `name`, `color` | Lightweight labels (TickTick). Contexts = availability; tags = grouping. |
| **TaskTag** | `taskId`, `tagId` | M:N join. |
| **Flag** | `id`, `name`, `color` | MLO flags. |
| **Attachment** | `id`, `taskId`, `kind`(file/image/voice), `localPath`, `name`, `sizeBytes` | Stored in app storage; never uploaded (TickTick blocks, local only). |
| **ChecklistItem** | `id`, `taskId`, `sortOrder`, `text`, `checked` | Optional flat checklist inside a task (TickTick), distinct from subtasks. |
| **SavedView** | `id`, `name`, `icon`, `filterJson`(AND/OR predicate tree), `sortJson`(multi-key), `viewType`(list/todo/matrix/calendar/kanban), `smart`(bool) | Composable views + smart lists (MLO engine, TickTick presentation). |
| **ColorRule** | `id`, `predicateJson`, `color`, `priority` | Rule-based row coloring (MLO color-coding). |
| **Setting** | `key`, `value` | App preferences (theme, first-day-of-week, defaults). |
| **Tombstone** | `entity`, `id`, `deletedAt` | Soft-delete record → lossless merge on import. |

Built-in **smart views** (SavedView rows, seeded): Inbox, Today, Tomorrow, Next 7 Days,
Scheduled, Flagged/Starred, **Do Next** (computed-score To‑Do), Completed, Nearby (context
in range), by-Context, by-Tag.

---

## 3. Computed-priority engine (our version of MLO's core)

Pure function `score(task, now, ancestors, deps) → Double`, then the **Do Next** list =
all leaf/actionable tasks, filtered (not completed, not blocked, start-gated, context
available), sorted by score desc. Sketch:

```
base       = normalize(importance) blended with inherited ancestor importance
urgencyAmp = f(urgency) × dueProximity(dueDate, leadTime, now)   // rises as due nears/overdue
gating     = 0 if (startDate>now) or blockedByDependency or contextClosed or hidden
score      = gating × (base × urgencyAmp)
```

Exact weights are tunable and we'll calibrate against MLO's observed behavior. The engine
is unit-tested with fixture trees. (Full MLO reference: `mylifeorganized.md` §4.)

---

## 4. Screen map (TickTick-style shell)

- **Bottom nav (configurable):** Do Next · Outline · Calendar · Matrix · Search.
  Left **drawer** = the view/list/context tree (MLO structure, TickTick drawer UX).
- **Quick-add bar (FAB → bottom sheet):** NLP parse ("call Sam tomorrow 5pm #work !!") with
  live span highlighting + editable chips (date/priority/tag/context/list). On-device parser.
- **Outline screen:** the infinite tree — indent/outdent, drag-reorder, collapse, swipe
  actions (complete / schedule / delete, user-mappable), checkbox Lottie tick.
- **Do Next screen:** flat computed-score list with context/availability filtering.
- **Task detail:** reorderable typed blocks — title, note (md), scheduling (combined bottom
  sheet: start/due/duration/repeat/reminders), contexts, tags, dependencies, subtasks,
  checklist, attachments, color. Page + quick-dialog modes.
- **Views:** Calendar (day→month + agenda), Matrix (importance×urgency quadrants), Kanban
  (group-by column), each reading a SavedView.
- **Settings:** theme/Material You, defaults, reminders, **Backup/Export & Import**, about.
- **Later modules:** Pomodoro (tied to task estimate), Habits, Statistics/Yearly report.

---

## 5. Export / Import (lossless, on-device)

- **Backup (canonical):** one versioned JSON file with every entity + tree order + views +
  settings. `{ "format":"tasktree", "version":1, "exportedAt":…, "tasks":[…], "contexts":[…],
  … }`. Import = full restore or merge (UUIDs + tombstones prevent loss/dupes). This is the
  acceptance test in `comparison-and-decisions.md` §5.
- **Interop out:** OPML (outline), .ics (dated tasks as VTODO/VEVENT).
- **Interop in (later):** OPML; best-effort TickTick/Todoist/Google Tasks CSV/JSON.
- **Mechanism:** Storage Access Framework (user picks the file); no network, no cloud.

---

## 6. Phasing

- **Phase 0 (done):** buildable skeleton + signed-APK CI pipeline (bare outline).
- **Phase 1 — MVP:** Room schema (Task/Context/Tag/Reminder/Recurrence/SavedView/Setting),
  outline CRUD (indent/reorder/collapse), task detail, quick-add (NLP), Today/Do‑Next/Inbox
  smart views, exact-alarm reminders, **JSON export/import**, Material 3 theming, **plus one
  view up front — the Matrix** (importance×urgency quadrants; Calendar is the alternative).
- **Phase 2:** Calendar + Matrix + Kanban views, dependencies, contexts w/ availability +
  location reminders, color rules, swipe customization, OPML/.ics.
- **Phase 3:** Pomodoro, Habits, Statistics/Yearly report, widgets, importers from other apps.

---

## 7. Mapping to the current code skeleton

Today's `MainActivity` renders a hardcoded in-memory tree. Phase 1 replaces that with:
Room-backed `Task` tree → `OutlineViewModel` (Flow→StateFlow) → the same LazyColumn outline,
plus the quick-add sheet and task detail. Package layout in §1 supersedes the single-file
skeleton. Nothing here changes the build/signing/CI setup already in place.

---

## 8. Finalized decisions & remaining questions

**Finalized (agreed):**
1. **Priority model = both.** Default UI shows a simple 4-level priority (None/Low/Med/High)
   that maps onto the underlying importance+urgency fields; an "advanced" toggle exposes the
   two 1–5 dials and drives the computed **Do Next** ranking. Simple by default, powerful when
   wanted.
2. **Contexts and Tags stay distinct.** Contexts = availability-aware (geo/open-hours, gate
   the Do-Next list); Tags = lightweight grouping. (Per MLO.)
3. **MVP = Phase 1 as listed + one view up front.** Provisional pick: **Matrix** (visualizes
   the priority engine, cheaper than a calendar, free vs TickTick's paywall). Alternative:
   Calendar, if we prefer the conventional first impression.

**Still open (not blocking; decide before/while building):**
4. **DI:** lean hand-rolled container to start (no KAPT/KSP overhead), migrate to Hilt only
   if wiring gets painful.
5. **App name & applicationId:** working name **TaskTree** / `com.tasktree.app`. The id must
   stay stable for update-in-place; the display label is free to change. Confirm final name.
6. **NLP quick-add depth:** MVP ships a small on-device parser for date/priority/tag/context;
   richer parsing can grow in Phase 2.
