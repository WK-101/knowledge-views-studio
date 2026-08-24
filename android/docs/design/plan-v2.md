# ToDo Companion — Revised Plan (v2)

Supersedes `skeleton.md`. Written after a deep, evidence-cited teardown of both apps
(`analysis/ticktick.md`, `analysis/mylifeorganized.md`, `analysis/ticktick-web-ui.md`,
`analysis/mlo-web-ui.md`). This version fixes the structural gaps in the Phase 1 build.

## 0. Phase 1 post-mortem — what was wrong

Phase 1 collapsed the whole app into **one flat outline** with a bottom-nav. It was
missing the entire **organizational layer** and the interaction surfaces that make these
apps usable:

- **No side navigation panel.** Both apps are drawer-first. TickTick's
  `TickTickSlideMenuFragment` and MLO's `DrawerLayout` (`main_menu`) are the primary
  navigation. We had none.
- **No containers.** No Folders, no Lists/Projects, no Smart Lists, no Filters, no
  Tags/Contexts in a browsable tree. Everything was one pile.
- **Quick-add was type-only.** TickTick's `fragment_quick_add.xml` has a tappable option
  toolbar (Date / Priority / Tag / List / Reminder / Repeat). We only parsed text.
- **Shallow priority + detail.** Additive ancestor blend instead of MLO's real
  multiplicative-inheritance engine; a thin detail screen without progressive properties.

v2 makes the organizational model, the drawer, and the rich quick-add first-class.

---

## 1. The core model (fusing both apps)

Both apps agree on a **two-level structure**, which we adopt:

```
Folder (group of lists)                     ← TickTick ProjectGroup / MLO view-folder
  └─ List / Project                         ← TickTick Project / MLO top branch
       └─ Task  ── unlimited nesting ──►     ← MLO outline inside each list
            ├─ subtasks (Task, parentId)
            └─ checklist items (flat)
```

Cross-cutting, shown in the drawer:
- **Smart Lists** (built-in, computed): Inbox, Today, Next 7 Days, **Do Next** (computed
  priority), Scheduled, Flagged, All, Completed, Won't Do, Trash, Nearby.
- **Filters** — saved custom smart lists (AND/OR rule tree + sort). MLO's view engine +
  TickTick's custom filters.
- **Tags** (hierarchical) — grouping.
- **Contexts** (GTD) — availability-aware (geo + open-hours), gate the Do-Next list.

Key rule: **a task belongs to one List (`listId`) and nests within that list (`parentId`).**
Moving a task to another list moves its subtree. Smart Lists / Filters / Calendar / Matrix
/ Do-Next aggregate **across** lists. Inbox is a real default List.

---

## 2. Data model v2 (Room)

Additions/changes vs Phase 1 in **bold**.

| Entity | Key fields |
|---|---|
| **Folder** | id, name, sortOrder, collapsed |
| **List** (Project) | id, **folderId?**, name, colorArgb, emoji/icon, sortOrder, **viewMode** (list/outline/kanban/timeline), defaultSortJson, archived |
| **Task** | id, **listId**, parentId, sortOrder, title, note(md), **status** (active/completed/abandoned), completedAt, **trashed**, importance, urgency, startDate, dueDate, isAllDay, durationMin, estimateMin/Max, leadTimeMin, hideInTodoUntilStart, hideInTodoIfBlocked, star, flagColorArgb, isGoal, isProject, projectStatus, reviewEveryDays, completeInOrder, colorArgb, rrule, recurrenceMode, collapsed, createdAt, updatedAt |
| ChecklistItem | id, taskId, sortOrder, text, checked |
| **Filter** | id, name, icon, colorArgb, ruleJson (AND/OR predicate tree), sortJson, sortOrder |
| Tag | id, **parentId?**, name, colorArgb |
| TaskTag | taskId, tagId |
| Context | id, parentId?, name, icon, colorArgb, lat?, lon?, radiusM?, openHoursJson?, active |
| TaskContext | taskId, contextId |
| Dependency | taskId, dependsOnTaskId, mode(AND/OR), postponeToStart |
| Reminder | id, taskId, type(absolute/relToDue/relToStart/location), atTime?, offsetMin?, contextId?, annoying, tone |
| **Column** (kanban, Phase 3) | id, listId, name, sortOrder |
| Attachment (Phase 2) | id, taskId, kind(file/image/voice), localPath, name, sizeBytes |
| Setting | key, value |
| **Tombstone** | entity, id, deletedAt — for lossless import/merge |

All `@Serializable`; the JSON backup now includes Folders, Lists, Filters, Columns.
(Phase 1 has no real user data, so we take the schema change destructively — no migration.)

---

## 3. Computed-priority engine v2 (accurate to MLO)

From MLO's decompiled engine (`ComputedScorePriorityType`, `model.d0`, `xb.l.d`):

- **importance** and **urgency** are values centred on a **neutral** baseline, mapped
  through an **exponential curve** (a value above neutral multiplies up, below divides down).
  Our UI exposes them as 1–5 (simple) or a finer dial (advanced); internally each maps to a
  multiplier.
- **Multiplicative inheritance down the tree:** `effImportance(task) = impMul(task) ×
  effImportance(parent)`; same for urgency. So raising a *list/parent's* importance
  reorders all descendants — MLO's "snowball". (Phase 1 used an additive blend; this
  replaces it.)
- **Date term:** grows as `dueDate` approaches and after it (overdue boost optional); a
  **start term** gates/zeroes the task until `startDate`.
- **Combine (default BY_BOTH):** `score = (effImportance × effUrgency) + dateTerm`.
  Modes BY_IMPORTANCE / BY_URGENCY also available. Weights (due, start, weekly-goal,
  overdue) are **tunable in Settings**.
- **Do Next** = actionable **leaf** tasks (no incomplete children), not gated (started,
  not blocked by a dependency, context available), ranked by score desc.

Pure Kotlin, unit-tested against fixture trees (including the inheritance behaviour).

---

## 4. UI architecture

**Shell:** a left **navigation drawer** (the organizational tree) + a **bottom nav** of
major sections (configurable, TickTick-style):

- **Drawer** (`ModalNavigationDrawer`): app header + **Search** → **Smart Lists**
  (Inbox/Today/Next 7 Days/Do Next/Scheduled/Flagged/All, each with a count) → **Folders**
  (collapsible) containing **Lists** (color dot + name + count) → **Filters** → **Tags** →
  **Contexts** → bottom **"＋ Add"** (list / folder / filter / tag / context) + **Settings**.
  Drag a list into a folder; reorder. This is the piece Phase 1 lacked.
- **Bottom nav:** Tasks · Calendar · Matrix · Search · Settings (user can show/hide/reorder;
  Pomodoro/Habits added in Phase 3).
- **Top app bar:** current list/smart-list title + view switcher (List/Outline/Kanban) +
  sort/group menu + overflow.

**Content views:**
- **List view** — TickTick grammar: light ground, white rounded cards, **ALL-CAPS
  collapsible group headers with counts**, priority-as-color (checkbox stroke + accent),
  swipe actions, drag-reorder. Group by date/priority/list/tag/none; sort configurable.
- **Outline view** — nested tree within a list: indent/outdent, collapse, drag, the swipe
  actions from Phase 1 (kept).
- **Do Next** — the computed ranked list across all lists.
- **Calendar** — month + agenda (Phase 1), timeline/schedule later.
- **Matrix** — Eisenhower quadrants (kept, improved).
- **Kanban** — columns from a task field (Phase 3).

**Quick-add** (`ModalBottomSheet`, modeled on `fragment_quick_add.xml`):
- Title field with **live NLP highlight** (date / `#tag` / `!priority`) **and** a tappable
  **option toolbar**: **Date** (smart grid: Today/Tomorrow/Next Mon/Next Week/Pick + tabs
  Time/Reminder/Repeat/Duration) · **Priority** · **Tag** · **List** (target list) ·
  **Reminder** · **Repeat** · (**Quadrant** when opened from Matrix). Defaults to the
  current list. Voice capture later.

**Task detail** — TickTick blocks + MLO progressive disclosure: title+check, note (md),
checklist/subtasks, schedule (start/due/duration/all-day), reminders, repeat, priority
(simple default; advanced importance/urgency dials), list, tags, contexts, dependencies,
flag/star, goal/project/review, color — grouped as **Default / More / All** so it isn't
overwhelming (MLO's `EditTaskProperties` idea, TickTick's cleanliness).

**Visual system:** Material 3, light-gray ground + white cards, per-module accent
(tasks/calendar/focus), count badges everywhere, relative dates, priority color, emoji list
icons, Lottie checkbox tick + staged pull-to-refresh (Phase 2 polish). Dark mode + Material
You from day one (MLO lacks dark mode — an easy win).

---

## 5. Revised roadmap

**Phase 1-redo (Foundation & structure)** — the meaty rebuild:
- Data model v2 (Folder/List/Task/ChecklistItem/Tag/Context/Filter/Reminder/Dependency/Setting).
- **Navigation drawer** with Smart Lists + Folders + Lists + Tags + Contexts + Add/Manage.
- **List view** (group/sort, collapsible headers) + **Outline** within a list.
- **Quick-add bottom sheet with the option toolbar** (Date/Priority/Tag/List/Reminder/Repeat).
- **Task detail** with progressive properties.
- **Do Next** (accurate computed engine) + **Matrix** + **Calendar** (month+agenda).
- Local reminders; **JSON export/import v2**; theming (light/dark/Material You).
- Manage Lists/Folders (create/rename/color/move/archive/delete).

*(Suggested split: 1a = model + drawer + List/Outline + quick-add + detail + export;
1b = Do Next + Matrix + Calendar + reminders. Ship 1a, then 1b.)*

**Phase 2 (Depth & polish):** recurrence engine (RRULE + skip), dependencies UI, context
availability + **location reminders**, **custom Filters editor**, drag-reorder + configurable
swipes, Trash/Won't-Do, group/sort everywhere, Lottie micro-interactions, OPML + `.ics`
export, attachments (image/voice/file), search.

**Phase 3 (Extras):** Kanban, Timeline, **Pomodoro/Focus**, **Habits**, **Statistics/yearly
report**, home-screen **widgets**, templates, importers (TickTick/Todoist/Google Tasks),
optional Workspaces (saved sidebar/filter sets).

Everything stays **offline, no account, no network permission, free** (we gate none of
TickTick's paywalled features).

---

## 6. Open decisions to confirm before building

1. **Ambition of Phase 1-redo:** ship it in the two sub-steps above (1a then 1b), or one push?
2. **Nesting scope:** nesting stays *within a list* (moving a task moves its subtree). Agree?
3. **Shell:** drawer for lists + bottom nav for sections (TickTick model) — agree, and which
   bottom-nav tabs for v1 (Tasks/Calendar/Matrix/Search/Settings)?
4. **Kanban/Timeline/Pomodoro/Habits** confirmed as Phase 3 (not v1)?
5. Keep **List view (grouped)** and **Outline view** as two switchable views per list?
