# MyLifeOrganized (MLO) for Android — Build-Grade Teardown

Evidence-backed reverse-engineering of the decompiled MLO Android APK (`net.mylifeorganized.mlo`,
v5.0.0 / build 5030), written to inform a new native **Kotlin/Compose** task manager that replicates
MLO's task-management engine. This revision keeps the full data-model detail and adds deep coverage
of **navigation, the side panel, Outline vs To-Do, the task-property editor, the views/filter
engine, the exact computed-priority algorithm, contexts, recurrence/reminders, dependencies,
settings, and every activity**.

**Evidence base.** Decoded resources under `dec/mlo/` — `res/layout/*` (616 files), `res/menu/*`
(40), `res/xml/*`, `res/values/{strings,arrays}.xml`, `AndroidManifest.xml` (139 `<activity>`);
the class list `mlo_classes.txt` (1376 classes, **largely unobfuscated**); and **jadx-decompiled
Java** (7304 sources) — MLO's own classes read cleanly. Every claim cites a real
identifier (layout id, `@string`, `arrays.xml` array, class, or decompiled method). Inferences are
marked as such.

MLO is a port of the Windows MLO engine: the greenDAO/SQLite schema uses the desktop's CamelCase
table/column names with `Ver`+`UID` columns for delta sync, so the mobile app round-trips losslessly
with desktop `.ml` files. That fidelity requirement is why our app must persist **every** field
below, not just title/date.

---

## 0. Identity, stack, permissions (context)

| Property | Value | Evidence |
|---|---|---|
| Package / version | `net.mylifeorganized.mlo`, 5.0.0 (5030) | manifest, `apktool.yml` |
| min/target/compile SDK | 21 / 35 / 35 | `apktool.yml`, manifest |
| Persistence | **greenDAO** (`de.greenrobot.dao`) over SQLite; `*EntityDescription` + `$Properties` per entity; `de/greenrobot/dao/merge/*` policies | dex strings, `*EntityDescription` classes |
| Date/time | **Joda-Time** (`net.danlew.android.joda`, `JodaTimeInitializer`) | manifest |
| Obfuscation | Very low: model/activity/controller classes fully named; only leaf helpers are single-letter (`model/d0`, `xb/l`, `bc/l`) | `mlo_classes.txt` |
| Cloud/push | Firebase FCM + Crashlytics + Analytics; push only signals "sync now" (`MLOMessagingService`, `SyncListenerService`) | manifest |
| Maps/geo | Google Maps + Places (context locations, Nearby, geofences) | manifest `com.google.android.geo.API_KEY`, `places.widget.AutocompleteActivity` |
| Backup | `android:allowBackup="false"` — MLO owns its own backup/sync | manifest |

**Permission → feature:** `ACCESS_*_LOCATION` + `ACCESS_BACKGROUND_LOCATION` → location contexts /
geofenced Nearby reminders (`NearbyService`, `LocationReminderReceiver`, `FetchAddressIntentService`);
`SCHEDULE_EXACT_ALARM` (+`AlarmPermissionStateChangedReceiver`) → exact reminders;
`READ_CALENDAR` → Today calendar overlay; `READ_CONTACTS` → `@`-mention in notes;
`USE_BIOMETRIC`/`USE_FINGERPRINT` → profile unlock; `REQUEST_INSTALL_PACKAGES`
(+`UpdateDownloadReceiver`) → self-update of the site build; `RECEIVE_BOOT_COMPLETED`
(`ReceiverStarter`) → re-arm reminders/geofences after reboot.

---

## 1. Navigation & side panel

### 1.1 Shell = a nav-drawer over the outline
The main screen is **`activity_tree_task.xml`**, whose root is a custom
**`net.mylifeorganized.android.widget.DrawerLayout`** containing three regions:
- `@id/main_menu` — the **left side panel** (drawer), hosting `fragment_main_menu`.
- `@id/main_container` — the **outline / task list** (`fragment_tree_task`).
- `@id/task_property_panel` — a **detail panel** (task preview/edit), shown as a right pane on tablet
  (`activity_tree_task_tablet.xml`, `MainActivityTablet`, `TabletUIModeSettingsActivity`) and as a
  pushed screen on phone.

Entry flow: `StartActivity` (launcher) → `MainActivity` / `MainActivityTablet`. Back-button behavior
is configurable (`BACK_BUTTON_OPTIONS` = *Exit app* vs *Open/close main menu*).

### 1.2 Side-panel anatomy (`fragment_main_menu` + `featured_header_main_menu`), top → bottom
1. **Search all tasks** — `featured_header_main_menu` `@id/start_search_panel`, hint
   `PLACEHOLDER_SEARCH_ALL_TASKS`; typing swaps in `@id/main_menu_searching_result_list` +
   `@id/btn_search_settings` (search scope). Backed by `SearchResultsActivity`, `res/xml/searchable.xml`.
2. **Featured tiles** (`main_menu_header_part`) — three side-by-side cells:
   **Inbox** (`INBOX_VIEW_TITLE`, counter), **Starred** (`STARRED_VIEW_TITLE`, counter),
   **Nearby** (`NEARBY_VIEW_TITLE`, `MARK_PRO`). A phone-alternate stacks them
   (`main_menu_header_part_alter`).
3. **Today** — `@id/today` row (`ic_menu_today`, `MARK_PRO`) → Today view (tasks + device-calendar
   events + weekly workload; `SettingsTodayViewActivity`, `fragment_my_events`).
4. **Views list** — `@id/main_menu_view_list` (`ListView`, `choiceMode="singleChoice"`). Rows use four
   item types:
   - `item_main_menu_view_list_type_0` — **group / view-folder header**: collapse arrow
     (`arrow_opened`) + name. (Folders = `GroupViewEntityDescription`.)
   - `item_main_menu_view_list_type_1` — **a View**: `CheckableLinearLayout` with icon
     (`ic_menu_all_tasks`), name, `MARK_PRO` badge, and **two counters** (`count1`/`count2` with icons
     + `ProgressBar` while loading). Counters configurable via `SettingsCountersActivity`
     (`counters_mode` = all tasks vs only root).
   - `item_main_menu_view_list_type_2` — **current-profile** link (`BUTTON_CURRENT_PROFILE` + value).
   - `item_main_menu_view_list_type_3` — plain link row.
5. **Bottom toolbar** (`@id/main_menu_toolbar`): **Edit views** (`@id/edit`, `BUTTON_EDIT_VIEWS` →
   `EditViewsActivity`), **Add to Inbox** (`@id/add_to_inbox`, `add_to_inbox` icon → `AddToInboxActivity`),
   **Settings** (`@id/settings` → `SettingsActivity`).

**Switch view:** tap a type_1 row (single-choice list updates the outline).
**Switch workspace:** the action bar hosts `actionbar_custom_view_workspace` (logo + `@id/workspace_name`
+ `workspaces_count` badge with `@id/counter`); tapping opens **`WorkspacesActivity`**
(`fragment_workspaces` = RecyclerView of `item_workspace` **MaterialCardView** cards, each with view
name, drag handle `view_handler`, `workspace_preview_image`, and a per-workspace settings gear).
`DEFAULT_WORKSPACE_TITLE = "MLO"`. Workspaces can render as a list (`WORKSPACES_AS_LIST_LABEL`).

A **Workspace** = an independent set of views + UI state (`WorkspaceEntityDescription`); views live in
folders (`GroupViewEntityDescription`) inside a workspace. This is the top of the navigation
hierarchy: **Workspace → View-folder → View → (Outline | To-Do projection)**.

---

## 2. Outline vs To-Do (Active Actions)

MLO has two ways of looking at the same task graph; both are just Views over one tree.

### 2.1 The Outline (editing/organizing surface)
`fragment_tree_task` renders the hierarchical tree. Task cell = **`item_task_list.xml`**:
`@id/arrow` (expand/collapse), `@id/checkbox_option` (complete), `@id/title_editable`, `@id/star`,
`@id/flag`, `@id/contexts`, `@id/main_date`/`@id/data_due`, `@id/progress_bar`(+text) for project
completion %, note/reminder glyphs (`icon_notes*`, `icon_reminder*`), `@id/text_tag_in_tree`,
`@id/project`, `@id/multi_select_view`, drag `@id/handler_view`, and `@id/swipe_left_buttons`/
`@id/swipe_right_buttons`. Grouping headers = **`item_task_group.xml`** (`@id/arrow` collapse,
`group_icon`, `group_title`, `group_value` count, `group_multi_select`); collapse state persists via
`GroupStatusEntityDescription`.

**Outline bottom toolbar** (`toolbar_tree_task.xml`, fully user-configurable via
`ToolbarMenuSettingsActivity` + `arrays.xml ADDITIONAL_TOOLBAR_MENU_ACTION`, 32 actions):
new task/subtask, undo/redo, multiselect, **filter by contexts**, **filter by flag**,
**zoom in/out**, **collapse all/expand all**, selected-task history prev/next,
**Show completed** (`@id/action_show_all`, `MENU_TASK_VISIBILITY_SHOW_COMPLETED`,
`CONTENT_DESCRIPTION_SWITCH_SHOW_COMPLETED`), nav arrow, create-from-template, add
folder/project/reminder, quick-format, and a **More** button expanding a 2-row overflow
(`toolbar_expanded_menu_row_1/2`).

### 2.2 The To-Do (Active Actions) projection
`INTRO_2_TODO_TEXT`: *"MLO automatically prepares a simple list of actions ("Active Actions" view)
which require your immediate attention."* `INTRO_3_NEXTACTIONS_TEXT`: *"The generated To-Do list is
updated automatically once you complete a task, change your location or assign new context."*

The To-Do is a **View** whose filter selects actionable leaves and whose sort is **Computed-Score**
(§5). The **Active** filter has five modes (`arrays.xml ACTIVE_ACTION_FILTER_OPTION`, filter fields
`FILTERED_FIELD_ACTIVE_ACTION`/`_AVAILABLE_ACTION`/`_NEXT_ACTION`):
`ACTIVE_FILTER_OPTION_ACTIVE` · `_AVAILABLE` · `_NEXT_ACTIVE` · `_COMPLETED` · `_ALL`.
A task is **suppressed** from the To-Do when it is complete, a folder (`IsFolder`), has
`HideInToDo`/`HideInToDoThisTask` set, its **start date is in the future**, it has an **incomplete
dependency** (`HasIncompleteDependency`), or its **assigned context is filtered out / closed**. Under
`CompleteInOrder` only the first incomplete leaf of a branch is the "next action".

### 2.3 Switching modes, zoom/hoist, star, completed
- **Outline ↔ To-Do** is done by **selecting a different View** in the side panel (e.g. "All Tasks"
  outline vs "Active Actions"), not a mode switch on one screen.
- **Zoom / hoist:** `@id/action_zoom_in` / `_zoom_out` hoist into a subtree; the zoomed root shows a
  breadcrumb bar — `outline_panel.xml` (icon + title + close) or, with history,
  `outline_navigation_panel.xml` (adds `<` `>` = `to_prev_element`/`to_next_element` and
  `CONTENT_DESCRIPTION_OLD_ZOOMS_PREV/NEXT_BUTTON`). History persists in the **`ZOOM`** table
  (`ZoomEntityDescription`, `ZoomListActivity`, `FEATURE_OLD_ZOOMS_*`).
- **Star:** `@id/star` in the cell + `TASK_PREVIEW_STAR_HINT`; starred tasks feed the Starred tile and
  are filterable/sortable (`IsStarred`, `StarredDateTime`).
- **Show/hide completed:** the `action_show_all` toggle.
- **Quick text filter:** `text_filter_panel.xml` — an inline filter bar with scope buttons **Title /
  Notes / Contexts / Text tag** (`FILTER_BY_TAKS_TITLE`/`_NOTES`/`_CONTEXTS`, `LABEL_TEXT_TAG`).

---

## 3. Task-property editor

### 3.1 Editor shell
Preview = **`fragment_preview_task.xml`** (read-only card: checkbox+title, flag, start/due,
recurrence + `BUTTON_RECURRENCE_SKIP`, reminder + Snooze/Dismiss, notes, contexts, text-tag, review…).
Edit = **`fragment_edit_task(_total).xml`**: title `EditTextBackEvent` + complete checkbox, then a
**dynamic `@id/edit_task_properties` container** into which property fragments are inflated, a
progressive-disclosure control, and a red **Delete** button.

Disclosure is three-state (`arrays.xml EDIT_TASK_SHOW_PROPERTIES_MODE`):
`BUTTON_HIDE_DEFAULT` → `BUTTON_MORE_DETAILS` → `BUTTON_ALL_DETAILS` (`SWITCH_MODE_EXPLANATION`).
Which properties appear and their order are configurable in
**`EditTaskPropertiesMenuSettingsActivity`** (available set = `arrays.xml ADDITIONAL_TASK_PROPERTY`,
18 entries). The editor also has a promoted **action bar** (`actionbar_tree_task_editor_mode.xml`):
Notes · Contexts · Dates · Reminder · **Parse preview** (`BUTTON_PARSE_PREVIEW`) · More.

### 3.2 Every field / property fragment (`fragment_property_*` → `layout_*`)

| Property | Layout | UI & fields | Task column(s) |
|---|---|---|---|
| **Title** | `fragment_edit_task` | `EditTextBackEvent` `edit_task_title_editable_value`; complete `edit_task_checkbox` | `TaskCaption`, `IsComplete`, `CompletionDateTime` |
| **Importance & Urgency** | `layout_gauge` | one **7-notch slider** `@id/gauge_bar` labelled `SLIDER_MIN·LESS·LITTLE·NORMAL·MORE·ALOT·MAX`; `LABEL_IMPORTANCE_AND_URGENCY` | `Importance`(`N`), `Urgency`(`R`) — shorts, default **100 = Normal** |
| **Start & Due** | `layout_start_and_due` | rows Start / Due / Recurrence / Reminder + clears; switches `switch_time` (time-of-day on/off), `switch_lock_period` (`LABEL_LOCK_PERIOD` — keep gap when moving one), `switch_inherited_date` (`LABEL_INHERITED_DATE` — inherit from parent); long-press = quick date (`START_AND_DUE_DATES_LONG_CLICK_EXPLANATION`, `OPTIONS_QUICK_DATE_SELECTION` open due/start/neither) | `StartDateTime`, `DueDateTime`, `LeadTime`, `ScheduleType` |
| **Recurrence** | `layout_recurrence_*` (see §7) | type + pattern + end + regenerate | `RecType`, `Rec*` columns |
| **Reminder** | `layout_reminder` | `SET_REMINDER_SWITCH` + date/time fragment; anchor `REMINDER_DATE_TIME_TO` = None/Start/Due/Both | `Reminders` table |
| **Contexts** | `layout_contexts` | checkable `@id/list_contexts`; empty-state `BUTTON_CREATE_DEFAULT_CONTEXTS` | `TodoItemPlaces` join |
| **Text tag** | `layout_text_tag` | `edit_text` + autocomplete `list_results`; `LABEL_TEXT_TAG` | free-text tag |
| **Notes** | `layout_notes` | `task_notes_edit/view` + `notes_toolbar` (actions `arrays ADDITIONAL_NOTES_MENU_ACTION`: search, go up/down, insert date/time/from-keyboard, copy-all, show task title); **Markdown** (`MarkdownSyntaxActivity`, `BUTTON_PREVIEW_MARKDOWN`); `@`-contact insert | `Notes` table via `TaskNoteID` |
| **Flag** | (list) `FlagEditActivity`/`FlagListActivity`/`FlagIconSelectActivity` | colored/iconed marker; `BUTTON_CREATE_DEFAULT_FLAGS`; `flag_not_set` default | `FlagID` |
| **Goal** | `layout_goal` | RadioGroup None/Weekly/Monthly/Yearly (`GOAL_ENUM`) + `inherited_goal_explanation` | `GoalFor` |
| **Project** | `layout_project` | `is_project` switch + status radio Not started/In progress/Suspended/Completed (`PROJECT_STATUS`) | `IsProject`, `ProjectStatus`, `ProjectCompletion` |
| **Effort / Time required** | `layout_time_required` | Min + Max items (`LABEL_MINIMUM_SHORT`/`_MAXIMUM_SHORT`); Effort (`LABEL_EFFORT`) | `Effort`, `EstimateMin`, `EstimateMax` |
| **Review (GTD)** | `layout_review` | Next Review date, **Review Every** (two pickers = number + unit `REVIEW_EVERY_TYPES` daily/weekly/monthly/yearly), Last Reviewed, `BUTTON_MARK_REVIEWED` | `NextReviewDate`, `LastReviewed`, `ReviewEvery`, `ReviewRecurrenceType` |
| **Dependencies** | `layout_dependencies` (+ `layout_add_dependencies`) | list of predecessors; add via `SelectTaskActivity`; combine `DEPENDENCIES_TYPE` = All/Any; `DEPENDENCY_DELAY` | `TodoPredecessors`, `DependOper`, `DependPostpone` |
| **Folder / Hide / Order** | (toggles) | `LABEL_FOLDER`, `LABEL_HIDE_BRANCH_IN_TODO`, `LABEL_SUBTASKS_IN_ORDER` | `IsFolder`, `HideInToDo`/`HideInToDoThisTask`, `CompleteInOrder` |
| **Format (color coding)** | `layout_format` | `USE_CUSTOM_FORMAT_TASK`; **font** (color/bold/italic/underline/strikethrough + underline color), **highlight** (color, side-bar color), **background** (color / gradient top-1/2 & bottom-1/2 stops / `FORMAT_TASK_BACKGROUND_GRADIENT_TO_CENTER`), `SUBTASKS_INHERIT_CUSTOM_FORMAT_TASK` | `COLOR_CODING` table |

The **natural-language parser** (`BUTTON_PARSE_PREVIEW`) fills these from typed text using keys
`-i/-u/-d/-s/-e/-t/-every/-rec/-fl/-go/-star/-tag/-h/-f/-p/-o/-to/context…` (`PARSER_KEY_*`,
`PARSER_KEYWORD_*`).

---

## 4. Views & filtering engine

A **View** (`ViewEntityDescription` → `Views`) = **filter tree + up-to-4-key sort + grouping +
hierarchy behavior**, saved in a workspace/folder.

**View management:** `EditViewsActivity` (`edit_views_actionbar`: `ADD_VIEW_BAR_BUTTON`,
`ADD_GROUP_BAR_BUTTON`, `RESTORE_DEFAULT_VIEWS_BAR_BUTTON`, `IMPORT_VIEWS_BAR_BUTTON`; action-mode:
Remove / **Duplicate** / **Export views**). Create/edit via `CreateNewViewActivity` / `EditViewActivity`.

**Filter tree** (`TaskFilter (FilterId, ParentFilterGroupId, FilterCriterion, FilterParams BLOB,
isInverse)`):
- `AdvancedFilterActivity` (`menu_advanced_filter`, `LABEL_MOVE`) → **`ConditionGroupActivity`**
  (nestable **AND/OR** groups; `arrays.xml any_all_type` = "Any context / All contexts") →
  **`ConditionActivity`** (a single condition = field + operator + value; each condition invertible).
- Condition editors by field type:
  - `layout_condition_basic`/`_integer` — numbers (`BASIC_FILTER_CONDITION`: =, ≠, >, <, ≥, ≤).
  - `layout_condition_string` — text (`STRING_FILTER_CONDITION`: is empty / not empty / equals /
    not equals / contains / does not contain / starts with / does not start with / ends with / does
    not end with) + `switch_case_sensitive` (`LABEL_CASE_SENSITIVE`).
  - `layout_condition_date_time` — dates (**20 operators**, `DATE_FILTER_CONDITION`: exists / does not
    exist / equal / after / before / equal-or-after / equal-or-before / not equal / today / yesterday /
    tomorrow / has time / this week / this month / in next X days·weeks·months / in last X
    days·weeks·months).
  - `layout_condition_boolean` — `switch_value` (`BOOL_FILTER_TRUE`).
  - `layout_condition_contexts` — `CONTEXT_FILTER_CONDITION`: **contains (consider open/closed)** /
    contains / does not contain / is exactly / is empty / is not empty. Dedicated
    `ContextFilterActivity`, `FlagsFilterActivity`.
- Filterable fields (`FILTER_FIELD_*`, ~35): Caption, Complete, Completed/Created/Modified/Due/Start
  DateTime, Contexts/ContextsText, Effort, Flag, FolderName, Goal/GoalMaster, HasDependency/
  HasIncompleteDependency/DependencyCounter, HasSubtasks/HasIncompleteSubtasks, HideInToDo,
  Importance/Urgency, IsFolder/IsProject, LastReviewed/NextReview, Notes, OccurrencesLeft, ParentName,
  ProjectCompletionPercent/ProjectName/ProjectStatus, Recurrence, Reminder, Starred/StarToggleDateTime,
  TimeRequiredMin/Max, TopLevelFolder/Parent/ProjectName, ActiveAction.

**Sort** (`TaskSortDescriptor`, `TaskSort.Collation1..4` + Direction) — up to **4 keys** from
`arrays.xml SORT_BY` (Completed Date, Importance, Due, Caption, Urgency, Modified, Created,
**Computed-Score**, Start, Next Alert, Goal, Starred/Starred Date, Next Review, Effort, Time Required
Min/Max, Project Status, Top-Level Parent, Project, Recurrence, Flag, Text Tag, Path, Folder, Project
Completion %).

**Group by** (`TaskBuncher`, `arrays.xml GROUP_TASK_BY`, 11): Completed Date, Context, Due Date, Hide
in To-Do, Is Folder, Modified Date, Starred, Starred Date, Start Date, Text Tag, Next Review Date.

**Hierarchy behavior per view** (`Views.Hierarchy/IncludeParents/IncludeChildren/ProcessBranch`):
flat list vs keep ancestors, include/exclude children, whole-branch processing.

**Built-in views** (`*_VIEW_TITLE`): Active Actions, To-Do/Next Actions, Active by
Context/Project/Flag, Active Goals, Active Starred, Goals, Due Next 7/30 days, By Next Alert,
Completed in Outline, Completed by Context/Project/Flag, Inbox, Starred, Nearby, Today.

**Membership caching:** `ViewIndexes` (`ViewTaskIndexEntityDescription`) stores per-view membership +
manual order (so a view's manual drag order and "don't vanish until refresh" behavior persist);
`ManualTaskIndexSetEntityDescription` for manual sets; `ViewUISettingsEntityDescription` per-view UI.
Import/export of views uses **`.mfv`** (`ImportViewsActivity`, `BUTTON_EXPORT_VIEWS`; Pro).

---

## 5. Computed-Score priority — the exact algorithm

This is MLO's signature: it collapses the outline into one automatically ranked To-Do. The math is
**fully recoverable** from the decompiled source. Sort key `SORT_BY_COMPUTED_SCORE_PRIORITY`
("Computed-Score", `TaskSortDescriptor` case 8) builds a comparator **`bc.l`**, which scores each task
(`n0` = the Task entity) via a cached per-task helper **`xb.l`**.

### 5.1 Tunable inputs (per-profile preferences, class `net.mylifeorganized.android.model.d0`)
Defaults set in `d0.I(...)`:

| Preference key | Default | Meaning |
|---|---|---|
| `ComputedScore.computedScorePriorityType` | `BY_BOTH` | mode enum `ComputedScorePriorityType` = `BY_IMPORTANCE`(0) / `BY_URGENCY`(1) / `BY_BOTH`(2) |
| `ComputedScore.dueDateWeight` | **3.0** | weight of due-date proximity term |
| `ComputedScore.startDateWeight` | **2.0** | weight of start-date term |
| `ComputedScore.weeklyGoalWeight` | **5.0** | boost added to urgency for weekly-goal tasks |
| `ComputedScore.isOverdueBoosting` | **false** | multiply overdue term by `1.25 × daysOverdue` |

There are separate score **profiles** (`Main`/`Alter`/`Default`/`Group`/`Contexts`ComputedScore) so
different views/groupings can rank differently. Importance `N` and urgency `R` are **shorts, default
100** (`n0` ctor sets `this.N = this.R = 100`).

### 5.2 The formula (`xb.l.d(...)`, verified from source)
For each task, three components are computed, then combined by mode.

**(a) Importance/urgency multipliers.** A 201-entry lookup table `xb.l.f17181n` maps a short value
`0..200` → a multiplier, **centered at exactly 1.0 for value 100**, exponential with ratio
≈`1.0000352` per step (index 0 → 0.99644, index 200 → 1.00356). `impMul = table[N]`, `urgMul = table[R]`.

**(b) Inherited components (walks up to the parent `n0.Z()` recursively):**
- **Importance component** `b = parent.b × impMul` — importance **compounds multiplicatively down the
  outline**, so subtasks of an important ancestor float up. Root default = 1.0.
- **Urgency component** `c = parent.c × urgMul`; if the task (or an ancestor) is a **weekly goal**,
  `c = (weeklyGoalWeight/100 + urgMul) × parent.c` (weekly-goal-ness propagates: `f17185d`).

**(c) Date/proximity term.** Using effective (inherited) start `x1(true)` and due `Q1(true)`, both
rolled to end-of-day for date-only values (`+86390 s`):
- **Due present** → `b(task, due, now, overdueBoost, dueWeight, …)`:
  `dueTerm = ((now − due) × dueDateWeight / 86.4e6 / 500) × overdueFactor`, where `now − due` is
  positive (and the term grows) as the deadline nears / passes; `overdueFactor = max(1, 1.25 ×
  (now−due))` **only when `isOverdueBoosting` is on and overdue**. A small "due today" nudge (+0.0055
  if already started, else +0.05) is added.
- **Start present** → `startTerm = (a(task,now) × startWeight × (start − now) / 86.4e6 / 500)`, where
  **`a(...)` is the start gate**: it returns **1.0** normally but **500 or 1000** once the task's start
  day has arrived (with due today), sharply promoting just-activated tasks. Tasks whose start is in the
  future contribute little (and are filtered out of Active anyway).
- **Both present** → sum of the start and due terms (with a `lockPeriod`-style `1.25×gap` damping when
  `overdueBoost` and the start/due gap > 1 day).

**(d) Combine by mode (`xb.l.c`):**
- `BY_IMPORTANCE` → `score = b` (importance component only)
- `BY_URGENCY`   → `score = c + dateTerm`
- `BY_BOTH` (default) → `score = (b × c) + dateTerm`

`bc.l.compare` computes `d(...)` for both tasks and returns `sign(scoreA − scoreB) × direction`.
Results cache on the task (`n0.N0`) and recompute only when an input (importance, urgency, dates,
weights, mode, "today" anchor) changes — cheap enough to re-rank the whole To-Do reactively on every
complete / context change / location change (as `INTRO_3_NEXTACTIONS_TEXT` promises).

**Takeaway for our engine:** score = **combine(importance-product-down-tree, urgency-product-down-tree,
date-proximity)** where the combine is user-selectable, weights are user-tunable, importance/urgency
use a gentle exponential curve around a neutral 100, due proximity dominates as deadlines approach,
overdue can be super-boosted, and start date acts as a hard gate + activation boost.

---

## 6. Contexts (GTD)

**Model:** `ContextEntityDescription` → `Places (ContextID, ContextCaption, ContextNoteID,
HideFromTodo, HideFromItemProps, OpenHours BLOB, ContextUID, Latitude, Longitude, Radius,
NotifyEntering, NotifyExiting, Ver)`. Task↔Context is **M:N** via `TodoItemPlaces`
(`ContextToAssignedTaskEntityDescription`); Context↔Context **hierarchy** via `PlaceRelations`
(`DependentContextToMasterContextEntityDescription`). Default set `arrays.xml DEFAULT_CONTEXT`.

**Management UI:** `ContextListActivity` (`fragment_context_list`; add via `ADD_NEW_CONTEXT_PLACEHOLDER`,
search) → **`ContextEditActivity`** (`fragment_edit_context`) with fields:
- **Title** (`@id/context_name`, `LABEL_TITLE`).
- **Location** (`@id/context_location`, `LABEL_LOCATION`) → **`ContextLocationActivity`**
  (`activity_context_location`: Google map `@id/context_location_map`, **radius seek-bar**
  `@id/context_location_seekbar` `CONTEXT_RADIUS`, address search `@id/search_address_icon`,
  `AutocompleteActivity`).
- **Radius** (`@id/context_radius`, `LABEL_RADIUS`).
- **Open hours** (`@id/context_open_hours`, `LABEL_OPEN_HOURS`) → **`ContextOpenHoursActivity`**
  (`activity_context_open_hours_settings`, `item_open_hours`; `actionbar_open_hours` toggles
  `LABEL_OPEN_HOURS_SHOW_AS_TEXT`) — weekly open/closed schedule stored in the `OpenHours` BLOB.
- **Included Contexts** (`@id/included_context`, `LABEL_INCLUDED_CONTEXTS`) — nesting/hierarchy.
- **Notify on arrive / leave** (`@id/notify_arrive`/`@id/notify_leave`, `NOTIFY_ON_ENTER_LABEL`/
  `NOTIFY_ON_EXIT_LABEL`) — geofence enter/exit → `NotifyEntering`/`NotifyExiting`.
- **Show in:** (`LABEL_SHOW_IN`) Task properties (`@id/task_properties`) / **To-Do Filter**
  (`@id/to_do_filter`, `LABEL_TO_DO_FILTER`) → `HideFromItemProps` / `HideFromTodo`.

**Gating the To-Do:** a view's **context filter** (`CONTEXT_FILTER_CONDITION_CONTAINS_CONSIDER_OPEN_CLOSED`)
hides tasks whose contexts are excluded or currently **closed** by open-hours. **Assignment UI**:
`AssignContextsToTaskSettingsActivity` + the per-task Contexts fragment. **Nearby**:
`NearbySettingsActivity`, `LocationMonitoringActivity`, `NearbyService` compute geofenced availability;
`actionbar_nearby` `NEARBY_MAP_LABEL` shows "tasks nearby on map" (Pro).

---

## 7. Recurrence & reminders

### 7.1 Recurrence (`arrays.xml RECURRENCE_TYPE`: None/Hourly/Daily/Weekly/Monthly/Yearly)
Stored inline on the task (`Rec*` columns); edited via `layout_recurrence_*` +
`edit_task_property_save_cancel_menu` (`LABEL_RECURRENCE_ADVANCED_OPTION`).
- **Hourly** (`layout_recurrence_hourly`): every N hours (`RecHourDelta`; long-tap explanation).
- **Daily** (`layout_recurrence_daily`): every N days **or every weekday** (`RECURRENCE_LABEL_EVERY_WEEKDAY`).
- **Weekly** (`layout_recurrence_weekly`): every N weeks **on** day-of-week checkboxes (`RecDOWMask`;
  Mon…Sun full names).
- **Monthly** (`layout_recurrence_monthly`): **day N of every M months** *or* **the [order]
  [weekday] of every M months** (RadioButton + Spinners; `RecInstance`, `RecDayOfMonth`).
- **Yearly** (`layout_recurrence_yearly`): **every [month] the [day]** *or* **the [order][weekday] of
  [month]** (`RecMonthOfYear`).
- **End** (`layout_recurrence_end_occurrence`): No end date / End by [date] / **End after N
  occurrences** (`RecOccurrences`, `OccurrencesLeft`).
- **Regenerate** (`RECURRENCE_LABEL_REGENERATE_*`): next occurrence measured **from completion date**
  vs schedule (`RecUseCompletionDate`).
- **Advanced** (`layout_recurrence_advanced`): **Automatic recurring** = disable / **when all
  subtasks completed** / **when any subtask completed** (`LABEL_AUTOMATIC_RECURRING_*`,
  `RecRecurWSC`); **Subtask resetting** on recur (disable / reset to uncompleted / reset if all
  completed — `LABEL_SUB_TASK_RESETTING_*`, `RecUncompleteSubtasks`/`RecUncomplIfCompl`); **Create
  completed copy** (`CREATE_COMPLETED_COPY_LABEL`, `RecDNCCCopy`).
- **Skip:** `BUTTON_RECURRENCE_SKIP` / `…SKIP_ALL_UP_TO_TODAY` / `…SKIP_ONLY_CURRENT`
  (`review_btn_skip_recurrence` in preview). Advanced pattern authoring is Pro
  (`fragment_property_recurrence_advanced/full`, `ADVANCED_RECURRENCE_EXPLANATION`).

### 7.2 Reminders (`ReminderEntityDescription` → `Reminders`)
- Set via `layout_reminder` (`SET_REMINDER_SWITCH`) + date/time; **anchor** `REMINDER_DATE_TIME_TO` =
  None / Start date / Due date / Start & Due. Multiple reminders per task; exact-alarm scheduled
  (`SCHEDULE_EXACT_ALARM`). `ReminderPropertyActivity`, `ReminderAlertsActivity`, `ReminderDialog`.
- **Auto-repeat:** `AutoAlert` on/off, `AutoAlertDelta` (re-alert interval),
  `MaxAutoAlertCount`/`LimitAutoAlertCount`/`AutoAlertIndex`.
- **Alert profile:** default sounds, vibration, LED, repeat (`DEFAULT_REMINDERS_ACTION_SUMMARY` =
  *"Default sounds, vibration, LED and repeat…"*, `ReminderSettingsActivity`,
  `layout_duration_vibration`, `LABEL_DURATION_VIBRATION`); custom `AudioFile`; **email** action
  (`Email`); `AlertAction`; state in `ReminderState`. Configurable notification action buttons
  (`ReminderNotificationActionsSettingsActivity`, `ReminderOnDeleteNotificationSettingsActivity`).
- **Snooze:** `BUTTON_REMINDER_SNOOZE` = "Snooze 5 min", `BUTTON_REMINDER_SNOOZE_LAST_PERIOD`, and a
  snooze menu `context_menu_preview_snooze` (`SNOOZE_1`/`_15`/`_30`/`_HOUR`/`_DAY`/`_MINUTES`).
  Preview buttons `@id/reminder_multi_button` (snooze) + `@id/reminder_dismiss_button`.
- **Location / geofence reminders** (separate): `LocationReminderAlertsActivity`,
  `LocationReminderReceiver`, `NearbyReminders` table, `NearbyService`;
  `ENTER_INTO_CONTEXT_NOTIFICATION_MESSAGE = "Arrive %1$s (%2$d) : %3$s"`. Enter/leave driven by
  `Places.NotifyEntering/Exiting`.

---

## 8. Dependencies

**Model:** `TodoPredecessors` join (`DependentTaskToMasterTaskEntityDescription`) + task columns
`DependOper` and `DependPostpone`. **UI:** `fragment_property_dependencies` / `layout_dependencies`
lists predecessors; add via `layout_add_dependencies` (`@id/list_all_task`) → `SelectTaskActivity`.
- **Combine operator** `DependOper` = `arrays.xml DEPENDENCIES_TYPE`: **All tasks** (AND,
  `TYPE_DEPENDENCIES_ALL`) / **Any task** (OR, `TYPE_DEPENDENCIES_ANY`).
- **Postpone/Delay** `DependPostpone` = auto-shift start/due while blocked (`DEPENDENCY_DELAY = "Delay"`).
- **Suppression:** a task with an incomplete predecessor set (per operator) is **hidden from the
  To-Do** — filter field `HasIncompleteDependency` (`MESSAGE_BUY_ACCESS_TO_DEPENDENCY`; dependencies
  are Pro). Multiselect toolbar can bulk-manage. Once predecessors complete, the task becomes
  available and re-enters the ranked list.

---

## 9. Settings

Root **`SettingsActivity`** organizes ~70 setting activities under five groups
(`SETTINGS_GROUP_TITLE_*`): **Main · Profile · Views · Registration · Help & Troubleshooting**. Key
screens (all real activities under `…activities.settings.*`):

- **General / UI & interaction:** `GeneralSettingsActivity`, `UIAndInteractionSettingsActivity`,
  `AnimationAndScrollingSettingsActivity`, `FastScrollingSettingsActivity`,
  `InteractionFeedbackSettingsActivity`, `SettingAppearanceActivity`/`SelectAppearanceActivity`
  (`appearance_props/_text_size/_progress`), `SwitchThemeSettingsActivity` (light/dark),
  `SwitchLanguageSettingsActivity` (`LANGUAGE_NAMES`), `FormatDateAndTimeSettingsActivity`,
  `ReplaceEmojiSettingsActivity`, `TabletUIModeSettingsActivity`, `CurrentTimeDisplayActivities`.
- **Task actions & properties:** `TaskActionsAndPropertiesSettingsActivity`,
  `EditTaskPropertiesMenuSettingsActivity`, `ContextMenuSettingsActivity`, `ToolbarMenuSettingsActivity`,
  `MultiselectToolbarMenuSettingsActivity`, `PromotedActionMenuSettingsActivity`,
  `SwipeActionSettings`/`SwipeMenuSettingsActivity`, `ActionsInNotesSettingsActivity`,
  `QuickDateSelectionSettingsActivity`, `AssignContextsToTaskSettingsActivity`,
  `AddToInboxSettingsActivity`.
- **Reminders / notifications:** `ReminderSettingsActivity`, `ReminderNotificationActionsSettingsActivity`,
  `ReminderOnDeleteNotificationSettingsActivity`, `PersistentNotificationActionSettingsActivity`,
  `NearbySettingsActivity`, `LocationMonitoringActivity`.
- **Views / counters / today:** `MainMenuSettingsActivity`, `SettingsCountersActivity`,
  `SettingsTodayViewActivity`, `ShowInActionbarSettingsActivity`, `StatisticsSettingsActivity`
  (`GENERAL/PERIOD/PROJECTS/CONTEXTS/FLAGS/DELETED/TOTAL_TODO_STATISTICS`).
- **Profile / security:** `ProfileManageSettingsActivity`, `ProfileCreateSettingsActivity`,
  `ProfileLockSettingsActivity` (passcode + **biometric** `BIOMETRIC_PROMPT_*`; note passcode is Pro,
  `PASSCODE_HAS_BEEN_RESETED_IN_FREE_MODE`), `LOCK_SCREEN_VISIBILITY`.
- **Sync / backup / templates:** `SyncSettingsActivity`, `CloudSyncSettingsActivity`,
  `WiFiSyncSettingsActivity`, `BackupProfileSettingsActivity`, `RestoreProfileActivity`,
  `ArchiveCompletedTasksSettingsActivity` (`ARCHIVE_ACTIONS` copy/move/purge),
  `CreateTemplateFromProfileSettingsActivity`, `TemplateInfoSettingsActivity`.
- **Shortcuts / integrations:** `HomeScreenShortcutsSettingsActivity`,
  `shortcuts_app.AppShortcutsMenuSettingsActivity` (`SETTINGS_APP_SHORTCUTS_*`),
  `tile.TileConfigurator` (`TILE_ACTION`), `RecognizerLanguageSettingsActivity` (voice),
  `SharingSettingsActivity` (`OPTIONS_FOR_SHARING`).
- **System / troubleshooting:** `SystemSettingsNotificationsActivity`,
  `SystemSettingsIgnoreBatteryOptimizationActivity`, `SystemSettingsAutoResetPermissionsActivity`,
  `TroubleshootingSettingsActivity`, `ResolvingPushIssuesActivity`, `ResolvingCalendarIssuesActivity`,
  `ResolvingTabletUIModeIssuesActivity`, `HelpSettingsActivity`, `ReferenceActivity`,
  `AboutSettingsActivity`, `RegistrationSettingsActivity`/`TrialSettingsActivity`.
- **Search within settings:** `SearchSettingsActivity` + `SettingsSuggestionProvider`
  (`actionbar_search_settings`, history).

(No `res/xml` PreferenceScreen files exist — MLO builds settings as bespoke activities, which is part
of why the surface feels heavy.)

---

## 10. Import / export / backup / templates / sync

| Artifact | Ext | Direction | Notes |
|---|---|---|---|
| Profile backup | **`.mlobak`** | export + restore | ZIP (Apache Commons Compress) of the profile's multi-section CSV; password-encryptable (`BACKUP_IMPORT_BAD_PASSWORD_OR_ARCHIVE`). `RestoreProfileActivity` (intent-filtered for `*.mlobak`, `application/zip`); `BackupProfileSettingsActivity`. Scope options Tasks / +Contexts / +Flags, plus Views/Triggers/Auto-format/Settings; can email. |
| Views | **`.mfv`** | import (+export) | `ImportViewsActivity` (`application/xml`); `BUTTON_EXPORT_VIEWS` (Pro). |
| Template | **`.mlt`** | bundled + create | `assets/templates/*.mlt` (GTD, GTD+FranklinCovey Roles, FranklinCovey, ControlJournal, GTD Zoom4 Focused Action, GTD Beginners Action, Do It Tomorrow — en/de/ru); `CreateTemplateFromProfileSettingsActivity`, `CREATE_TASK_FROM_TEMPLATE`. |
| Desktop file | **`.ml`** | sync | Windows profile SQLite; the schema in §12 is the round-trip map. |
| Sync payload | internal CSV | sync only | **Not a user file.** Versioned **multi-section CSV** (`SYNC send to server CSV for version greater than %d`); each entity = a section; `Ver` columns drive delta sync; `DeletedItems` carry tombstones. |
| Single task | — | share in/out | Share as text/link (`SHARING_OPTIONS`, `LiNK_TO_TASK_*`, Pro); "Add to calendar…" (`CREATE_CALENDAR_EVENT`). |

**Sync mechanisms:** (a) **MLO Cloud** REST (`sync/rest/CloudApi`, `CloudSyncSettingsActivity`,
cloud files `CloudFilesListActivity`/`CloudFileSharingActivity`/`CloudFileEditingActivity` for
collaboration); (b) **Wi-Fi sync with MLO Desktop** (`WiFiSyncSettingsActivity`, `wifi_sync_versions`,
Pro); (c) legacy HTTP autosync (`autosync.mylifeorganized.net`). **Conflict UX:** field-level —
`CONFLICT_SYNC_SESSION` / `CONFLICT_ENTITY` / `CONFLICT_PROPERTY` (keeps local + remote value per
property) surfaced in **`ConflictPropertiesActivity`** (`CONFLICT_RESOLUTION_USE_LOCAL` / `USE_REMOTE`,
`conflict_manage_menu`, `conflict_text_value_menu`). Per-entity `sync/conflict/*MergePolicy` layer on
greenDAO merge policies. Autosync can self-disable after a crash
(`AUTOSYNC_WAS_DISABLED_DUE_TO_CRASH`); "too many tasks / much data" push warnings
(`ManyCloudChangesActivity`, `SETTINGS_TROUBLESHOOTING_PUSH_WARNINGS_TITLE`).

**Explicitly NOT supported:** OPML, user-facing CSV/Markdown/JSON, WebDAV, Dropbox — interop is
MLO-ecosystem-only. (A big improvement opportunity for us: ship open, non-paywalled export.)

---

## 11. Activity inventory (137 MLO + 2 Google, grouped)

| Group | Activities |
|---|---|
| **Entry / shell** | `StartActivity`, `MainActivity`, `MainActivityTablet` |
| **Outline / task** | (tree hosted by `MainActivity`), `PreviewActivity`, `PreviewPopupActivity`, `NotesPropertyActivity`, `ReminderPropertyActivity`, `SelectTaskActivity` (dependency/parent picker), `AddToInboxActivity` |
| **Side panel / views** | `EditViewsActivity`, `CreateNewViewActivity`, `EditViewActivity`, `AdvancedFilterActivity`, `ConditionActivity`, `ConditionGroupActivity`, `ContextFilterActivity`, `FlagsFilterActivity`, `ImportViewsActivity`, `WorkspacesActivity`, `ZoomListActivity`, `MainMenuSettingsActivity`, `SettingsCountersActivity`, `SettingsTodayViewActivity` |
| **Contexts / flags / location** | `ContextListActivity`, `ContextEditActivity`, `ContextLocationActivity`, `ContextOpenHoursActivity`, `AssignContextsToTaskSettingsActivity`, `NearbySettingsActivity`, `LocationMonitoringActivity`, `LocationReminderAlertsActivity`, `FlagListActivity`, `FlagEditActivity`, `FlagIconSelectActivity`, `places.widget.AutocompleteActivity` |
| **Reminders** | `ReminderAlertsActivity`, `reminder.ReminderDialog`, `ReminderSettingsActivity`, `ReminderNotificationActionsSettingsActivity`, `ReminderOnDeleteNotificationSettingsActivity`, `PersistentNotificationActionSettingsActivity`, `PersistentNotificationMenuSettingsActivity` |
| **Sync / cloud / backup / conflict** | `CloudSyncSettingsActivity`, `SyncSettingsActivity`, `WiFiSyncSettingsActivity`, `CloudFilesListActivity`, `CloudFileDescriptionActivity`, `CloudFileEditingActivity`, `CloudFileSharingActivity`, `ManyCloudChangesActivity`, `BackupProfileSettingsActivity`, `RestoreProfileActivity`, `ArchiveCompletedTasksSettingsActivity`, `ConflictPropertiesActivity`, `FailAutoSyncActivityDialog` |
| **Profiles / security** | `ProfileManageSettingsActivity`, `ProfileCreateSettingsActivity`, `ProfileLockSettingsActivity`, `PassAlertActivity` |
| **Settings (config)** | `SettingsActivity`, `GeneralSettingsActivity`, `UIAndInteractionSettingsActivity`, `TaskActionsAndPropertiesSettingsActivity`, `EditTaskPropertiesMenuSettingsActivity`, `ContextMenuSettingsActivity`, `ToolbarMenuSettingsActivity`, `MultiselectToolbarMenuSettingsActivity`, `PromotedActionMenuSettingsActivity`, `SwipeActionSettings`, `SwipeMenuSettingsActivity`, `ActionsInNotesSettingsActivity`, `QuickDateSelectionSettingsActivity`, `AnimationAndScrollingSettingsActivity`, `FastScrollingSettingsActivity`, `InteractionFeedbackSettingsActivity`, `SettingAppearanceActivity`, `SelectAppearanceActivity`, `SwitchThemeSettingsActivity`, `SwitchLanguageSettingsActivity`, `FormatDateAndTimeSettingsActivity`, `ReplaceEmojiSettingsActivity`, `TabletUIModeSettingsActivity`, `ShowInActionbarSettingsActivity`, `StatisticsSettingsActivity`, `AddToInboxSettingsActivity`, `RecognizerLanguageSettingsActivity`, `SharingSettingsActivity`, `CurrentTimeDisplayActivities` |
| **Templates / help / registration** | `CreateTemplateFromProfileSettingsActivity`, `TemplateInfoSettingsActivity`, `MarkdownSyntaxActivity`, `HelpSettingsActivity`, `ReferenceActivity`, `AboutSettingsActivity`, `RegistrationSettingsActivity`, `ActivationCodeActivity`, `TrialSettingsActivity`, `RegistrationUpdatedInfoActivity`, `FeatureForReviewActivity` |
| **Onboarding / paywall dialogs** | `ProductTourActivity`, `Mlo3ProFeaturesTourActivity`, `Mlo4ProFeaturesTourActivity`, `MloProTourActivity`, `MloNewProFeaturesTourActivity`, `MloLightProTourActivity`, `MloLightNewProFeaturesTourActivity`, `FreeLimitationDialogActivity`, `InfoDialogActivity`, `TooltipDialogActivity`, `FailUpdateActivityDialog`, `ProtectionPushLoopingInfoActivity` |
| **System / troubleshooting** | `SystemSettingsNotificationsActivity`, `SystemSettingsIgnoreBatteryOptimizationActivity`, `SystemSettingsAutoResetPermissionsActivity`, `TroubleshootingSettingsActivity`, `ResolvingIssueSettingsActivity`, `ResolvingPushIssuesActivity`, `ResolvingCalendarIssuesActivity`, `ResolvingTabletUIModeIssuesActivity`, `SearchSettingsActivity`, `SettingsViewModeUpdateActivity` |
| **Widgets / tiles / share / voice** | `widget_app.AddTaskActivity`, `DynamicWidgetConfigurator`, `DynamicWidgetMoreOptionsActivity`, `DynamicWidgetSelectDateActivity`, `DynamicWidgetSelectFlagActivity`, `DynamicWidgetSelectViewActivity`, `DynamicWidgetShowParsedActionsActivity`, `ExternalActionActivity`, `ShortcutConfigurator`, `tile.TileConfigurator`, `shortcuts_app.AppShortcutsMenuSettingsActivity`, `SharedReceiverActivity`, `utils.file_picker.MloFilePickerActivity`, `tests.TestsActivity` |
| **Google (support)** | `gms.common.api.GoogleApiActivity`, `places.widget.AutocompleteActivity` |

---

## 12. Data model (retained — lossless-parity map)

Entities = greenDAO `*EntityDescription`; columns = dex `CREATE TABLE` literals. `TodoItems` is the spine.

### 12.1 Task — `TaskEntityDescription` → `TodoItems`
```
TodoItemID(PK), ParentItemID, ItemIndex, TaskCaption,
IsComplete, IsStarred, StarredDateTime,
Importance, Urgency, HideInToDo, HideInToDoThisTask,
GoalFor, ScheduleType, CompletionDateTime, DueDateTime, StartDateTime, LeadTime,
EstimateMin, EstimateMax,
RecType, RecStartDate, RecEndDate, RecOccurrences, RecInterval, RecInstance,
RecDOWMask, RecDayOfMonth, RecMonthOfYear, RecUseCompletionDate, RecUncompleteSubtasks,
RecGeneratedCount, RecHourDelta, RecDNCCCopy, RecRecurWSC, RecUncomplIfCompl,
CompleteInOrder, Effort, Satisfaction,
IsProject, ProjectStatus, ProjectCompletion, IsFolder,
TaskNoteID, FlagID, COLOR_CODING_ID,
CreatedDate, LastModified, IsExpanded,
NextReviewDate, LastReviewed, ReviewEvery, ReviewRecurrenceType,
DependOper, DependPostpone, TaskUID, Ver
```
Hierarchy = `ParentItemID` + `ItemIndex` (ordered outline) + `IsExpanded`. Two distinct hide flags
(`HideInToDo` branch vs `HideInToDoThisTask`). Prioritization inputs `Importance`/`Urgency` (short,
default 100), `Effort`, `EstimateMin/Max`, `Satisfaction`. Scheduling `Start/DueDateTime`, `LeadTime`,
`ScheduleType`. Review cadence quartet. Dependency operator + postpone. Sync `TaskUID` + `Ver`.

### 12.2 Other entities
- **Context** `ContextEntityDescription` → `Places` (geo + open-hours + geofence flags; §6).
- **Flag** `FlagEntityDescription` (named/colored/iconed; `assets/flags/*.ico`; 1:1 via `FlagID`).
- **Note** `NoteEntityDescription` → `Notes(NoteID, Note TEXT)` (Markdown; per task & per context).
- **Reminder** `ReminderEntityDescription` → `Reminders` (time + auto-alert + action + audio/email; §7).
- **Recurrence** `RecurrenceEntityDescription` wraps the inline `Rec*` columns (§7).
- **ColorCoding** `ColorCodingEntityDescription` → `COLOR_CODING` (font/highlight/background/side-bar +
  `CHILDREN_INHERIT_COLOR_CODING`; plus an auto-format rule engine `TaskCellTheme`, `AUTO_FORMAT_*`).
- **Preference** `PreferenceEntityDescription` → `Preferences(Key,Value)` (per-profile; holds the
  `ComputedScore.*` weights).
- **GroupStatus** `GroupStatusEntityDescription` (group header collapse state).
- **Views subsystem** `model/view/*`: `ViewEntityDescription`→`Views`, `TaskFilter`(tree),
  `TaskSort`(4 keys), `GroupViewEntityDescription`(folders), `WorkspaceEntityDescription`,
  `ViewTaskIndexEntityDescription`→`ViewIndexes`, `ManualTaskIndexSetEntityDescription`,
  `ViewUISettingsEntityDescription`, `ZoomEntityDescription`→`ZOOM`.
- **Sync bookkeeping**: `DeletedItems` (tombstones, composite PK `DeletedUID,ObjectType`),
  `CONFLICT_SYNC_SESSION`/`CONFLICT_ENTITY`/`CONFLICT_PROPERTY` (field-level local/remote capture).
- **Aux**: `Attachments(TaskId,Uri)`, `Bookmarks`, `NearbyReminders`, `PlaceRelations`,
  `TodoItemPlaces`, `TodoPredecessors`.

### 12.3 Relationships
```
Task ──self ParentItemID──▶ Task           (outline, ordered by ItemIndex)
Task ──1:1──▶ Note / Flag / ColorCoding ;  Task ──1:N──▶ Reminder
Task ──M:N via TodoItemPlaces──▶ Context ;  Context ──M:N via PlaceRelations──▶ Context
Task ──M:N via TodoPredecessors (DependOper AND/OR, DependPostpone)──▶ Task
View = TaskFilter(tree) + TaskSort(≤4) + Grouping + Hierarchy ; ∈ Workspace ; ∈ GroupView folder
View ──cached──▶ ViewIndexes ; ZOOM = hoist history ; DeletedItems/CONFLICT_* = sync
```

---

## 13. UI polish, gestures, widgets, weaknesses

**Configurability (a genuine strength):** nearly every menu is user-editable, backed by
`arrays.xml` action lists and split into *available* vs *used* groups:
- Long-press **context menu** (`context_menu_task`, dynamically built; `CONTEXT_MENU_ACTION`, 22
  actions — Notes, Contexts, Date, Reminder, Clipboard, Delete, More details, Recurrence, Importance,
  Urgency, Text tag, Goal, Folder, Hide branch in To-Do, Subtasks in order, Project, Effort, Time
  required, Review, Dependencies, Format, Flag).
- **Toolbar** (`ADDITIONAL_TOOLBAR_MENU_ACTION`, 32), **multiselect** toolbar
  (`MULTISELECT_ADDITIONAL_TOOLBAR_MENU_ACTION`, 14), **promoted actions**, **persistent-notification**
  actions, **app shortcuts**, **quick-settings tiles** (`TILE_ACTION`).
- **Swipe gestures:** left/right per-row buttons (`item_task_swipe_left_buttons`/`_right_buttons`),
  `SwipeActionSettings`, mode `SWIPE_RIGHT_ACTION_MENU_MODE` = **Auto by task properties** vs Custom,
  with interaction/vibration feedback (`InteractionFeedbackSettingsActivity`).
- **Widget:** dynamic list widget (`dynamic_widget_layout*`, `dynamic_widget_task_row(_compact)`,
  `DynamicWidgetConfigurator`, per-view, flags, Today chart, dark theme `WIDGET_DARK_THEME`,
  `WIDGET_ICON_STYLES`, `WIDGET_TOOLBAR_MODE`); add-task/add-reminder flows
  (`widget_add_task_more_options`, `widget_add_reminder_more_options`).
- **App-lock / profiles:** passcode + biometric per profile (`ProfileLockSettingsActivity`,
  `BIOMETRIC_PROMPT_*`), multiple profiles, lock-screen visibility.
- **Themes:** light/dark (`SwitchThemeSettingsActivity`), per-task color coding + auto-format,
  appearance (fonts, cell theme, notes length `NOTES_LENGTH_MODE` compact/standard/long/full).

**Where the UI is dated / weak (improve on these):**
1. **Holo-era chrome** — `ProgressBar` styled `@android:style/Widget.Holo.ProgressBar`, custom
   `DrawerLayout`, RelativeLayout-heavy hand-built cells, dozens of `AppTheme.Popup` activities and
   modal dialogs. A single adaptive Compose layout with a bottom-sheet editor replaces most of it.
2. **Config overload** — power is buried in ~70 settings activities and many "available/used" action
   editors. Great flexibility, poor discoverability. Ship sane defaults + progressive disclosure.
3. **Desktop tether** — advanced recurrence, UI filters, open-hours authoring and some view creation
   are "do it on desktop then sync" (`ADVANCED_RECURRENCE_EXPLANATION`, `UI_FILTER_EXPLANATION`,
   `MESSAGE_BUY_ACCESS_TO_IMPORT_VIEWS`). We should author everything on-device.
4. **Pervasive Pro gating** — dependencies, Wi-Fi sync, undo/redo, workspaces, review, complex
   recurrence, location alerts, widgets, passcode, import views are all Pro
   (`MESSAGE_BUY_ACCESS_TO_*`, multiple "MLO2/3/4/5 pack" tiers). Confusing tier sprawl.
5. **Closed ecosystem** — no OPML/CSV/Markdown/JSON export; `.mlobak`/cloud/wifi only.
6. **Sync friction** — autosync self-disables after crash; conflicts resolved property-by-property;
   "too much data" warnings push manual archiving. Firebase/Crashlytics/Analytics run counter to a
   private/offline promise — drop them.

---

## 14. Build checklist for our Compose app (parity + improvements)

**Replicate exactly:** the full `TodoItems` field set (§12.1 — do not trim importance/urgency/effort/
estimate-min-max/lead-time/both hide flags/goal/project-status+completion/review quartet/dependency
operator+postpone/star+star-date/tag/complete-in-order); the **outline** (`parentId` + explicit
`sortIndex`, `isExpanded`, folder/project) with a derived **To-Do projection**; the **Computed-Score**
engine exactly as §5 (importance/urgency exponential curve around neutral 100, multiplicative
down-tree inheritance, user-tunable due/start/weekly-goal weights + overdue boosting + `BY_IMPORTANCE/
URGENCY/BOTH` mode, start-gate `a()` activation boost), recomputed reactively; **contexts** (M:N,
hierarchical, geo lat/long/radius + enter/leave, open-hours availability gating); **dependencies**
(AND/OR + postpone, suppress blocked); **views** (nestable AND/OR filter over ~35 fields with 20 date
ops + case-sensitive string ops + open/closed context ops, ≤4-key sort, 11 group-bys, per-view
hierarchy) grouped into **workspaces** with cached membership/manual order; **recurrence** (all
`Rec*` semantics incl. nth-weekday, completion-date regeneration, subtask reset, occurrence limits,
skip); **reminders** (multiple, auto-repeat, sound/vibration/LED/email, snooze, start/due anchor,
geofenced); flags, tags, Markdown notes, attachments, color-coding + auto-format, GTD review, goals,
statistics, Today, templates, NL parser.

**Improve:** open lossless export (JSON + documented SQLite mirror + OPML/Markdown) plus a `.mlobak`
importer, all non-paywalled; no cloud/analytics by default (fully offline, optional E2E sync with
stable UUIDs + per-entity `Ver` + tombstones + field-level conflict capture, auto-resolving the easy
cases); one adaptive Compose UI with a bottom-sheet property editor mirroring these fragments but with
progressive disclosure; author advanced recurrence/views on-device.

---

*Evidence index:* layouts `activity_tree_task`, `fragment_main_menu`, `featured_header_main_menu`,
`main_menu_header_part`, `item_main_menu_view_list_type_0..3`, `fragment_workspaces`, `item_workspace`,
`item_task_list`, `item_task_group`, `toolbar_tree_task`, `outline_panel`, `outline_navigation_panel`,
`text_filter_panel`, `fragment_edit_task(_total)`, `fragment_preview_task`, `layout_gauge`,
`layout_start_and_due`, `layout_recurrence_*`, `layout_reminder`, `layout_contexts`, `layout_format`,
`layout_review`, `layout_goal`, `layout_project`, `layout_dependencies`, `layout_condition_*`,
`fragment_edit_context`, `activity_context_location`; classes `ComputedScorePriorityType`,
`xb.l`(scoring), `bc.l`(comparator), `TaskSortDescriptor`, `model.d0`(weights), `model.n0`(Task),
`TaskFilter/TaskSort/ViewEntityDescription`, `TaskBuncher`; arrays `SORT_BY`, `GROUP_TASK_BY`,
`ACTIVE_ACTION_FILTER_OPTION`, `RECURRENCE_TYPE`, `DATE/STRING/CONTEXT_FILTER_CONDITION`,
`DEPENDENCIES_TYPE`, `ADDITIONAL_TOOLBAR_MENU_ACTION`, `CONTEXT_MENU_ACTION`, `ADDITIONAL_TASK_PROPERTY`.
