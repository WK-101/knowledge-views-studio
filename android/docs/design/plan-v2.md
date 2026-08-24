# ToDo Companion — Revised Plan (v2.1)

Supersedes `skeleton.md`. Built on the deep teardown in `analysis/*.md`. This revision
folds in the confirmed decisions and the expanded feature set requested during review.

## 0. Confirmed decisions

- **Delivery:** Phase 1-redo ships in two steps — **1a** (structure) then **1b** (views/engine).
- **Shell:** left **navigation drawer** for the organizational tree + **bottom nav**:
  **Tasks · Calendar · Matrix · Search · Settings** (show/hide/reorder later).
- **Per-list views:** both a grouped **List** view and a nested **Outline** view, switchable.
- **Nested folders:** yes — folders can contain folders and lists (deeper than TickTick's flat folders).
- **Full smart-list set, dedicated Calendar (multi-mode), configurable Matrix, extensive
  Settings, and rich reminders** — all specified below.
- **Always:** offline, no account, no network permission, everything free.

## 1. Phase 1 post-mortem (why v2 exists)

Phase 1 was one flat outline with no organizational layer. Missing: the **side panel**,
**Folders/Lists/Smart Lists/Filters/Tags/Contexts**, the **quick-add option toolbar**, a
real **computed-priority engine**, dedicated **Calendar/Matrix/Search**, and **extensive
Settings**. v2 makes all of these first-class.

---

## 2. Core model

```
Folder (nestable: parentId)                 ← groups folders & lists
  └─ Folder / List
        └─ List / Project                   ← primary container; has a color + icon
             └─ Task ── unlimited nesting ──►← MLO outline inside each list
                  ├─ subtasks (Task, parentId)
                  └─ checklist items (flat, quick ticks)
```

**A task belongs to one List (`listId`) and nests within it (`parentId`).** Moving a task
across lists moves its subtree. Cross-cutting entities shown in the drawer:

- **Smart Lists** (built-in, computed — the full set): **Inbox · Today · Tomorrow · Next 7
  Days · Do Next** (computed priority) **· Scheduled · Flagged · All · Completed · Won't Do
  · Trash · Nearby**. Each shows a live count; each can be hidden/reordered in Settings.
- **Filters** — saved custom smart lists (AND/OR rule tree + sort), MLO's view engine.
- **Tags** (nestable) and **Contexts** (GTD, availability-aware, gate Do-Next).

**Task lifecycle / status:** `active → completed` or `active → abandoned (Won't Do)`; any
task can be `trashed` (soft-delete → **Trash**, restore or purge). Completed and Won't Done
tasks live in their smart lists and remain in their original list history.

---

## 3. Data model v2 (Room, all `@Serializable` for lossless backup)

| Entity | Key fields |
|---|---|
| **Folder** | id, **parentId?**, name, sortOrder, collapsed |
| **List** (Project) | id, folderId?, name, colorArgb, emoji/icon, sortOrder, viewMode(list/outline/kanban), defaultSortJson, defaultGroupJson, archived |
| **Task** | id, listId, parentId, sortOrder, title, note(md), **status**(active/completed/abandoned), completedAt, **trashed**, trashedAt, importance, urgency, startDate, dueDate, isAllDay, durationMin, estimateMin/Max, leadTimeMin, hideInTodoUntilStart, hideInTodoIfBlocked, star, flagColorArgb, isGoal, isProject, projectStatus, reviewEveryDays, completeInOrder, colorArgb, rrule, recurrenceMode, collapsed, createdAt, updatedAt |
| ChecklistItem | id, taskId, sortOrder, text, checked |
| **Filter** | id, name, icon, colorArgb, ruleJson(AND/OR), sortJson, groupJson, sortOrder |
| Tag | id, parentId?, name, colorArgb |
| TaskTag | taskId, tagId |
| Context | id, parentId?, name, icon, colorArgb, lat?, lon?, radiusM?, openHoursJson?, active |
| TaskContext | taskId, contextId |
| Dependency | taskId, dependsOnTaskId, mode(AND/OR), postponeToStart |
| Reminder | id, taskId, type(absolute/relToDue/relToStart/location), atTime?, offsetMin?, contextId?, trigger(enter/leave), annoying, tone |
| Column (kanban, P3) | id, listId, name, sortOrder |
| Attachment (P2) | id, taskId, kind(file/image/voice), localPath, name, sizeBytes |
| Setting | key, value |
| Tombstone | entity, id, deletedAt — lossless import/merge |

Phase 1 has no real data → schema change is destructive (no migration needed).

---

## 4. Computed-priority engine v2 (accurate to MLO)

`importance`/`urgency` centred on a neutral baseline via an exponential curve;
**multiplicatively inherited down the tree** (raise a list/parent → all descendants
re-rank — the "snowball"); **date term** grows toward/after due; **start term** gates until
start; default **`score = (effImportance × effUrgency) + dateTerm`** (modes BY_IMPORTANCE /
BY_URGENCY too). Weights (due/start/weekly-goal/overdue) tunable in Settings. **Do Next** =
actionable leaves, not gated, ranked. Pure Kotlin, unit-tested (incl. inheritance).

---

## 5. UI — shell & core surfaces

**Drawer** (`ModalNavigationDrawer`): app header → **Search box** → **Smart Lists** (the full
set, with counts) → **Folders** (nested, collapsible) → **Lists** (color dot + count) →
**Filters** → **Tags** → **Contexts** → bottom **"＋ Add"** (list/folder/filter/tag/context) +
**Settings**. Drag lists into folders; reorder; density options (Classic/Compact).

**Tasks tab** — the current List or Smart List, in either:
- **List view** — light ground, white rounded cards, **ALL-CAPS collapsible group headers +
  counts**, priority-as-color, swipe actions, drag-reorder. Group by date/priority/list/
  tag/none; sort by computed/priority/date/title/manual.
- **Outline view** — nested tree: indent/outdent, collapse, drag, swipe (from Phase 1).
- Top bar: title · view switch · **sort/group** menu · show-completed toggle · overflow.

**Quick-add** (`ModalBottomSheet`): title with **live NLP highlight** + a **tappable option
toolbar** — **Date** (smart grid Today/Tomorrow/Next Mon/Next Week/Pick + tabs
Time/Reminder/Repeat/Duration) · **Priority** · **Tag** · **List** (target) · **Reminder** ·
**Repeat** · (**Quadrant** from Matrix). Defaults to the current list.

**Task detail** — TickTick blocks + MLO progressive disclosure (**Default / More / All**):
title+check, note(md), checklist/subtasks, schedule (start/due/duration/all-day), reminders,
repeat, priority (simple default; advanced importance/urgency dials), list, tags, contexts,
dependencies, flag/star, goal/project/review, color.

---

## 6. Dedicated Calendar (its own tab, multi-mode)

Mode switcher: **List (Agenda) · Day · 3-Day · Week · Month · Year**.
- **List/Agenda** — chronological, grouped by day, undated section.
- **Day** — hour timeline; timed blocks + all-day row; drag to reschedule (P2).
- **3-Day / Week** — day columns with timed blocks; week starts per Settings.
- **Month** — grid with per-day dots/counts; tap a day → that day's agenda.
- **Year** — 12-month overview with density dots; tap a month → Month view.
- Options: show/hide completed, show tasks without time as all-day, tap empty slot to add,
  color by list/priority. (Device-calendar overlay is out — we stay offline.)

## 7. Dedicated Matrix (its own tab, configurable)

Four Eisenhower quadrants (Do First / Schedule / Delegate / Later). **Matrix settings:**
- Which **lists/filters** feed it (all or a selection).
- **Importance & urgency thresholds** that define "important"/"urgent".
- **Show/hide completed**, **hide empty quadrants**, per-quadrant tap-to-add.
- Quadrant labels/colors; sort within a quadrant (computed/priority/date).

## 8. Search everywhere (its own tab + drawer box)

Global search across **task titles, notes, checklist items, tags, contexts, list & folder
names**. Live results grouped by type; **scope filters** (list, tag, context, priority,
date range, status incl. completed/Won't Do/Trash); recent searches; open result → detail.

## 9. Reminders (rich)

Per task: **multiple** reminders — absolute time, relative (before due/start), or
**location** (enter/leave a context geofence, P2). **Annoying alert** (re-notify until
dismissed), custom tone, snooze grid. App-level: **daily summary** notification at a set
time, optional **persistent "today" ongoing** notification, full-screen alarm option.
Local `AlarmManager` exact alarms; rescheduled on boot. No network.

## 10. Extensive Settings (dedicated tab, full tree)

- **General** — first view, week start, default list, default priority, date/time format.
- **Appearance** — theme (system/light/dark), Material You, accent, list density, badge counts.
- **Navigation** — configure bottom-nav tabs (show/hide/reorder), sidebar density.
- **Quick add** — default list/date, parsing toggles, option-button order.
- **Smart Lists** — show/hide/reorder, show-completed behavior.
- **Priority engine** — mode (BY_BOTH/importance/urgency), weights (due/start/weekly-goal/
  overdue), advanced dials on/off.
- **Calendar** — default mode, week start, show completed, color-by.
- **Matrix** — the settings in §7.
- **Dates & recurrence** — defaults; **Reminders** — defaults, daily summary, tone, annoying.
- **Backup** — Export / Import JSON (and OPML/.ics later); optional auto-export.
- **Security** — app lock / biometric (P2).
- **About** — version, offline/privacy statement.

---

## 11. Roadmap

**Phase 1a — structure (first APK):** data model v2 · **navigation drawer** (nested folders,
full smart lists, lists, tags, contexts, add/manage) · **List + Outline** views with
group/sort · **quick-add with option toolbar** · **task detail** (progressive) · **Search**
(global) · **Settings** (General/Appearance/Navigation + Backup export/import) · reminders
(absolute + relative) · theming (light/dark/Material You).

**Phase 1b — views & engine (second APK):** accurate **computed-priority + Do Next** ·
**Matrix** (configurable, §7) · **Calendar** (all modes, §6) · full **reminders** (annoying,
daily summary, snooze) · smart-list completeness (Completed/Won't Do/Trash flows).

**Phase 2 — depth & polish:** recurrence engine (RRULE + skip) · dependencies UI · context
availability + location reminders · **custom Filters editor** · drag-reorder + configurable
swipes · Lottie micro-interactions · OPML + `.ics` export · attachments (image/voice/file) ·
app-lock/biometric.

**Phase 3 — extras:** Kanban · Timeline · Pomodoro/Focus · Habits · Statistics/yearly ·
home-screen widgets · templates · importers (TickTick/Todoist/Google Tasks) · Workspaces.

---

## 12. Remaining open decisions

1. **Week start & date format defaults** (Mon vs Sun; 24h vs 12h) — I'll default to system
   locale unless you prefer fixed.
2. **Visual direction:** before I build 1a, do you want me to produce **clickable wireframe
   mockups** of the key screens (drawer, list/outline, quick-add, calendar modes, matrix,
   settings) so we lock the look — or go straight to a working 1a APK?
