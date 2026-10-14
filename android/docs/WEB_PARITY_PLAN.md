# Hexis Web — Mobile-Parity Overhaul Plan

The web companion must be an **extension of the mobile app**, not a parallel app: same icons,
same components, same colors — and, crucially, the **same data, the same settings, and the same
filter/sort/view logic**, so what the web shows is always what the phone would show. This plan is
built from a line-by-line read of the mobile source (`app/src/main/java/com/wkhan/hexis/`).

## Why the current web diverges — three root causes

1. **The web can't read your mobile settings.** The bridge `data` capability exposes only five
   per-entity domains (`DataContract.kt:31-35`); there is **no `settings` domain**. So the web
   *guesses* every display rule the phone actually stores — week-start, default calendar view,
   notebook mode, note sort, habit sort/grouping, matrix thresholds, day-start rollover, smart-list
   visibility/order, density, etc. All of those live in `AppSettings` (`domain/Settings.kt:61-479`)
   and never cross the bridge.

2. **The DTOs are too narrow, and containers/relations aren't exposed.** `TaskDto` is missing
   `flagId/flagColorArgb`, `pinned`, `someday`, `abandoned`, `isGoal/isProject`, `deadlineDate`,
   `isAllDay`, `estimate*`, `energy`, `leadTimeMin`, `waitingForWho`, `rrule`, contexts/tags. There
   are no reads for folders, tags, contexts, flags, or task dependencies. `NoteDto` omits `folderId`;
   `HabitDto` omits `scheduleDays/freqType/targetComparison/reminderTimes`; `TimeActivityDto` omits
   `goalDays`. The web therefore *cannot* reconstruct the mobile views even if it wanted to.

3. **The web invented its own design language.** The nav uses emoji glyphs (✓ ✎ 📅 ⏱ 🔁); mobile
   uses stock **Material Symbols** and a custom **brand mark**. The web uses its own smart-list set,
   its own matrix rule, its own board buckets, a generic checkbox, strikethrough-on-done, and a
   `today`/`overdue` due-chip scheme that mobile does not have.

Fixing #1 and #2 is the keystone — without them, "match the phone" is impossible. #3 is a
design-system port.

---

## Ground-truth reference (what the web must match)

### Design system (`ui/theme/*`, `ui/components/*`)
- **Accent**: `#5B57D9` light / `#8C86FF` dark. **Background** `#F4F5F8` / `#111319`. Full surface
  ramp documented (surfaceContainerLowest…Highest). **Semantic**: good `#1E9E64`, warn `#EA9A16`,
  bad `#E5484D`, info `#3E7BFA`, neutral `#9AA3B2`, star `#F5A623` (dark variants too).
- **Priority colors**: HIGH `#E5484D`, MEDIUM `#EA9A16`, LOW `#3E7BFA`, NONE `#9AA3B2`.
  **Flag colors**: `#E5484D,#F59E0B,#12A594,#3E7BFA,#8B5CF6`. **Categorical chart palette** (fixed
  order): `#5B57D9,#12A594,#EA9A16,#E5484D,#8B5CF6,#0EA5E9,#65A30D,#EC4899`.
- **Radii**: cards/rows/FAB **16px**, chips/pills **8px**, sheets 24px. **Type**: M3 scale, titles
  SemiBold, titleMedium 16. **Density** setting: compact 6 / default 10 / relaxed 14 px row padding.
- **Icons** (Material Symbols, filled): Tasks `format_list_bulleted`, Calendar `calendar_month`,
  Timeline `view_timeline`, Matrix `grid_view`, Habits `local_fire_department`, Time `schedule`,
  Notes `article`, Focus `timer`, Search `search`, Settings `settings`; plus `menu`, `add`, `check`,
  `more_vert`, `auto_awesome`, `bolt`, `repeat`, `bookmark`/`bookmark_border`, `star`/`star_border`.
  **Brand mark**: four chevrons converging on a core (SVG path data captured; stroke=text,
  core=accent).
- Top-level sections on mobile, in order: **Tasks · Calendar · Timeline · Matrix · Habits · Time ·
  Notes · Focus · Search · Settings** (Matrix and Timeline are their own views; Board is a per-list
  display mode of Tasks, not a view).

### Tasks (`domain/view/Views.kt`, `ListPipeline.kt`, `DoNext.kt`, `priority/*`)
- **Open base**: `isOpen = !trashed && !completed && !abandoned && !someday`. Day boundary uses a
  `dayStartHour/Minute` rollover; all-day values at local-midnight are not shifted.
- **15 smart lists**, in order, each with an exact predicate: Inbox (listId=="inbox",
  cross-workspace) · Today (due≤today **incl. overdue**, or start==today) · Tomorrow (due==today+1) ·
  Next 7 Days (due≤today+7, **incl. overdue**) · Do Next (ranked actionable set) · Scheduled
  (due!=null) · Flagged (flagId!=null — **the bookmark, not star**) · Goals (isGoal) · Waiting On
  (waitingForWho or dependency-blocked) · Needs Attention (open, not note, no due, leaf, stale ≥21d) ·
  Someday · All (isOpen) · Completed · Won't Do (abandoned) · Trash (per-workspace). Visibility/order/
  names are user settings; there is **no "Starred" smart list**.
- Sidebar also lists user **Folders & lists**, **Tags**, **Contexts**, saved **Filters** — all
  workspace-scoped; archived/trashed containers are hidden from active views & counts.
- **Row**: leading = **priority checkbox** (rounded square, ring+tint in priority color, solid+check
  when done); title (grey, **no strikethrough**, when done); **due chip** = borderless colored text
  via `formatDue` (overdue=red, else neutral — no "today" color); **flag** (bookmark) + **star**
  (gold) trailing; then up to 2 @context, 2 #tag, and the list name as labels. Pinned float first.
- **Group/Sort** (ephemeral UI state, defaults Today view / group DATE / sort MANUAL): GroupMode
  {NONE,DATE,PRIORITY,CONTEXT,FLAG}; SortMode {MANUAL,PRIORITY,DUE,TITLE,FLAG,COMPLETED,SCORE}.
  MANUAL/Do-Next force ungrouped. DATE buckets: Overdue/Today/Tomorrow/Next 7 days/Later/No date.
- **Priority**: stored as **two ints `importance` & `urgency`, 1..5, default 3**. Level =
  `max(imp,urg)` → ≥5 HIGH, ≥4 MED, ≥3 LOW, else NONE.

### Board (`ui/screens/KanbanScreen.kt`) — a per-list display mode, not a tab
- Columns grouped by **Priority (default) / Flag / List** — *not* due-date buckets. Priority →
  4 fixed columns High/Medium/Low/None. Shows the **currently selected view's** task set
  (active-workspace, non-completed/trashed/abandoned/someday), subtasks flattened as their own cards,
  ordered by the view's sort. Moving a card sets priority/flag/list.

### Matrix (`ui/screens/MatrixScreen.kt`, `priority/PriorityEngine.kt:242`) — its own view
- Quadrant = `importance ≥ importanceThreshold` (important) × `urgency ≥ urgencyThreshold` (urgent),
  thresholds default **4**, configurable 2–5. Order/labels/colors: 0 "Urgent & Important"/Do first/
  red · 1 "Not Urgent & Important"/Schedule/amber · 2 "Urgent & Unimportant"/Delegate/blue · 3 "Not
  Urgent & Unimportant"/Later/teal; row-major 2×2. Completed excluded unless `matrixShowCompleted`;
  within-quadrant sort default = `max(imp,urg)` desc then star. Settings: list/folder filter, date
  filter, overdue-only, max-duration, custom names, hide-empty. Reclassify by drag (sets imp/urg to
  5 or threshold−1).

### Task editor (`ui/screens/TaskDetailScreen.kt`) — staged edit, 20 ordered fields (tiered)
Title + priority-checkbox · Notes (Markdown, linked full note) · Progress · **Schedule** (unified
Date sheet: due date/time/all-day/duration/estimate/start/deadline/repeat/reminders) · Lead-time ·
Time-tracking · **Priority** (4-level picker + advanced imp×urg 1–5 steppers) · **List/folder** ·
Repeat insight · Checklist · **Subtasks** · Tags & contexts · **Flag** · Energy · Attachments ·
Blocked & waiting · Advanced (goal/project/someday, estimate, review cadence) · Activity log ·
Reflection. Fields have a tier (Always/More/Hidden) that is itself a user setting.

### Calendar (`ui/screens/CalendarScreen.kt`)
- Modes: **Agenda(list) / Day / 3-Day / Week / Month / Year**; default `cal_mode` (="month"),
  `cal_remember` reopens last. **Week start** `week_start` (0=locale, 1..7=Mon..Sun). Tasks with due
  dates appear on the grid; completed shown only if `cal_show_completed`. Per-calendar `visible` +
  `cal_filter`. `time_format`/`time_zone` drive formatting.

### Notes (`ui/screens/NotesScreen.kt`)
- `notes_notebook_mode` = **folderTree** (group by shared folder tree via `folderId`) vs
  **notebooks** (via `notebookId`). `note_default_view` grid/list. `notes_sort`
  updated/created/titleAsc/titleDesc. Pinned always float first, then grouping. Reading themes/fonts
  exist (lower priority).

### Habits (`domain/habit/HabitStats.kt`, `ui/screens/HabitDetailScreen.kt`)
- "Due today" respects **scheduleDays** + **freqType** (weekly/times_week/times_month/interval); a
  habit is not shown as due on an off-day; **break habits are never "due"**. `habit_sort`
  manual/name/streak/strength/created; `habit_group_by_category`. Detail shows strength %, frequency
  label, a **52-week year heatmap** (Mon-anchored) with goal-aware shading (done→solid, partial→
  color@.4, skip→outline, future/pre-start dimmed), and a month calendar.

### Time (`ui/screens/TimeStatsScreen.kt`, `domain/TimeStats.kt`)
- Period selector **Day / Week / Month / Year** (default Week) with prev/next pager; a **donut** of
  per-activity share with center total; a ranked list with progress bars + %; a Breakdown↔Trends
  toggle; per-activity drill-down with stat tiles + "vs previous period". `goalMinutesPerDay` +
  `goalDays`. `time_grid_columns` for activity tiles. One running timer at a time.

---

## The plan

### P0 — Foundation (nothing ships to a view until this is in)
1. **Bridge `settings` read domain** — project the display-relevant subset of `AppSettings` into a
   `SettingsDto` (+ `settings.get` op), read-only, gated by a new `data.settings.read` scope. Keys:
   week_start, time_format, time_zone, dayStart*, cal_mode/cal_remember/cal_show_completed/cal_filter,
   notes_notebook_mode/note_default_view/notes_view_mode/notes_sort, habit_sort/habit_group_by_category/
   strength_meter/calm_mode/forgiving_streaks, time_grid_columns, density, matrix* (thresholds/names/
   filters/sort/show-completed), smartListVis/smartListNames/smartOrder, activeWorkspaceId.
2. **Widen DTOs + new reads**: TaskDto (flagId, flagColorArgb, pinned, someday, abandoned, isGoal,
   isProject, deadlineDate, isAllDay, estimateMin/Max, durationMin, energy, leadTimeMin, progressPct,
   waitingForWho, rrule, tagIds, contextIds); new read ops `folders`, `tags`, `contexts`, `flags`,
   `dependencies`; NoteDto `folderId`; HabitDto (scheduleDays, freqType, freqParam, targetComparison,
   reminderTimes, startDate, sortOrder); HabitDayDto (add target/goalMet for shading); TimeActivityDto
   (goalDays, sortOrder). All additive (tolerant decode); core stays **0 forbidden permissions**.
3. **Design-system port (web)**: replace invented icons with the real Material Symbols SVG set +
   the brand mark; adopt the exact token ramp, priority/flag/semantic/categorical palettes, 16px/8px
   radii, the M3 type scale, and the **Density** setting; the nav rail reflects mobile's sections.

### P1 — Tasks parity (largest)
Port the pure logic (`isOpen`, all 15 smart predicates, buckets, priority levels, Do-Next-lite,
Waiting) to JS; sidebar = Smart lists (+visibility/order/names/counts) + Folders & lists + Tags +
Contexts + Filters; the real list row (priority checkbox, due-chip rules, flag+star, labels,
density, no strikethrough); Group/Sort controls with mobile defaults; outline/nesting; Board as a
per-list toggle grouped by Priority/Flag/List; Matrix as its own view honoring all `matrix*` settings
with drag-to-reclassify.

### P2 — Task editor parity
Staged-edit model; the tiered field set; priority as 4-level + advanced imp/urg steppers; the unified
Date sheet (due/time/all-day/duration/estimate/start/deadline/repeat/reminders); subtasks; checklist;
tags/contexts; flag; energy; goal/project/someday; attachments (view); dependencies/waiting;
reflection. Needs new write ops (setPriority, setFlag, setDates, setTags/Contexts, add/remove
subtask/checklist/reminder/dependency, recurrence) and reads (dependencies, checklist, reminders).

### P3 — Calendar parity
Add Day / 3-Day / Year modes; `week_start`; tasks-with-due-dates overlaid (+completed per setting);
calendar visibility filter; quick-add; default-view/remember.

### P4 — Notes parity
Honor `notes_notebook_mode` (folder-tree sidebar vs notebooks), `note_default_view`, `notes_sort`;
pinned float; (reading themes later).

### P5 — Habits parity
`scheduleDays`/`freqType` "due today"; `habit_sort`; `habit_group_by_category`; frequency labels;
strength; 52-week year heatmap + month calendar with goal-aware shading.

### P6 — Time parity
Day/Week/Month/Year selector; donut + ranked bars + stat tiles + per-activity drill-down; `goalDays`;
`time_grid_columns`.

### P7 — Shell / nav / polish
Nav rail mirroring mobile sections (incl. Timeline/Focus if in scope); final density + polish pass.

Each phase: unit tests green, 0 forbidden permissions verified, headless-screenshot check, committed
with the attribution trailer, and (per your instruction) a signed APK.

## Decisions (confirmed)
- **Section scope**: **FULL parity including Timeline + Focus** — every mobile top-level section.
- **Editor depth**: **FULL editor parity now** — all ~20 fields (reminders, dependencies, recurrence,
  attachments, reflection included).
- **Delivery cadence**: **signed APK at larger milestones** (not every phase).

### Milestones (APK at each)
- **Milestone 1** — P0 Foundation + P1 Tasks + P2 full editor (the area flagged first).
- **Milestone 2** — P3 Calendar + P4 Notes.
- **Milestone 3** — P5 Habits + P6 Time.
- **Milestone 4** — P7 Timeline + Focus + shell/nav/Settings view + final polish.
