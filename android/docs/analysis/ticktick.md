# TickTick (Android) — Build-Grade UX Teardown

Reverse-engineered from a decoded APK (apktool resources + `classes*.dex` descriptors, selected jadx single-class decompiles). The app is R8-obfuscated: **data classes, resource names, and string keys retain real names** and are the primary evidence; obfuscated helper classes are cited only where their names survived. Package: `com.ticktick.task`. Main screen activity: `com.ticktick.task.activity.MeTaskActivity`.

Evidence conventions: `layout/foo.xml` = `res/layout/foo.xml`; `@string/foo` = a `res/values/strings.xml` key; `Class` = a `com.ticktick.task.*` class; `res/xml/foo.xml` = a preference/appwidget descriptor.

---

## 1. Organizational Hierarchy (container model) — CRITICAL

### 1.1 The container graph

```
Team (shared workspace, optional)
  └─ ProjectGroup   ("Folder")           class com.ticktick.task.data.ProjectGroup
        └─ Project  ("List")             class com.ticktick.task.data.Project
              └─ Column ("Kanban column")class com.ticktick.task.data.Column   (only when viewMode = kanban)
              └─ Task2  ("Task")         class com.ticktick.task.data.Task2
                    ├─ subtasks          (Task2.parentSid / Task2.childIds — real child Task2 rows)
                    └─ ChecklistItem[]   (Task2.checklistItems — lightweight in-task checklist)
Tag                                       class com.ticktick.task.tags.Tag       (cross-cutting, hierarchical)
Filter ("Custom Smart List")              class com.ticktick.task.data.Filter    (saved rule query)
```

All confirmed field-by-field from the decompiled classes below.

### 1.2 Project (a "List") — `com.ticktick.task.data.Project`

Key fields (from jadx decompile):
- Identity/display: `sid`, `name`, `color`, `sortOrder`, `displayOrder`, `kind`, `muted`, `closed` (archived), `defaultProject`, `showInAll`, `showType`.
- **Folder membership: `String projectGroupSid`** — the ONLY link from a list up to its folder. A null/empty `projectGroupSid` = ungrouped (top-level) list.
- Sharing: `team`, `teamId`, `permission`, `teamMemberPermission`, `openToTeam`, `userCount`.
- Background/theme: `backgroundInfo` (drives per-list `ColorProjectBackground` / `GradientProjectBackground` / `ImageProjectBackground`, all `data/*ProjectBackground`).
- **View + sort config (per list):** `String viewMode` (`Constants.ViewMode`: `"list"`, `"kanban"`, `"timeline"`), `Constants.SortType sortType`, `Constants.SortType groupBy`, `Constants.SortType orderBy`, and a parallel timeline triplet `timelineSortType` / `timelineGroupBy` / `timelineOrderBy`. `String defaultColumn` = default kanban column sid.
- Sync: `etag`, `modifiedTime`, `createdTime`, `deleted`, `needPullTasks`.

### 1.3 ProjectGroup (a "Folder") — `com.ticktick.task.data.ProjectGroup`

Fields: `sid`, `name`, `sortOrder`, `boolean isFolded` (collapsed state), `boolean showAll` (whether a synthetic "All tasks in folder" row shows), `int taskCount`, `viewMode`, `sortType`/`groupBy`/`orderBy` (+ timeline triplet), `team`/`teamId`, `deleted`, `etag`. A folder is a flat, one-level container — **folders do not nest** (a Project has exactly one `projectGroupSid`; ProjectGroup has no parent pointer). The synthetic "all tasks in this folder" pseudo-list uses id `SpecialListUtils.SPECIAL_LIST_PROJECT_GROUP_ALL_TASKS_ID = -10009`.

### 1.4 Task2 (a "Task") — `com.ticktick.task.data.Task2`

The workhorse row (`ParcelableTask2` is its parcelable transport). Fields grouped:
- Content: `sid`, `String title`, `String content` (note/description body, markdown), `String desc`, `Constants.Kind kind` (`TEXT(1)`, `CHECKLIST(2)`, `NOTE(3)`), `progress`, `Integer priority`, `status`/`taskStatus`, `Integer deleted`.
- **Hierarchy:** `String parentSid`, `List<String> childIds` (real subtasks), `List<ChecklistItem> checklistItems` (in-task checklist), `collapsed`.
- **List membership:** `Project project` / `Long projectId` / `String projectSid`.
- **Kanban:** `Column column` / `String columnId` / `Long columnUid`.
- **Tags:** `Set<String> tags`.
- Scheduling: `Date startDate`, `Date dueDate`, `boolean isAllDay`, `boolean isFloating` (floating time zone), `serverStartDate`/`serverDueDate`, `Date completedTime`, `Date pinnedTime`, `localUnpinned`.
- Reminders/recurrence: `String reminder`, `List<TaskReminder> reminders`, `Integer annoyingAlert`, `Date snoozeRemindTime`, `String repeatFlag` (RRULE), `String repeatFrom` (recur from due vs completion), `Date repeatFirstDate`, `repeatTaskId`, `Set<String> exDate` (skipped instances), `repeatReminderTime`.
- Rich content: `List<Attachment> originalAttachments`, `hasAttachment`, `List<PomodoroSummary> pomodoroSummaries`, `commentCount`, `List<Location> locationList` + `Location displayLocation`, `notionBlock` (Notion sync), `imgMode`.
- Assignment (shared lists): `long assignee`, `assigneeName`, `long creator`, `isOwner`.

Task type constants: `Task2.TASK = 1`, `Task2.CALENDAR = 2` (a `CalendarEvent` masquerading as a task in list/calendar views).

### 1.5 Tag — `com.ticktick.task.tags.Tag`

Obfuscated field names but structurally: a tag has a name/label, a **`parent` pointer (hierarchical/nested tags)**, a `color`, its own `Constants.SortType` group (five SortType fields → tag has independent sort/group/order + timeline config just like a Project), and a sort order. Sync model twin: `com.ticktick.task.network.sync.model.Tag`. Tag sort mode enum: `data/TagSortType`. Tags are stored on tasks as `Task2.tags: Set<String>` and joined via `data/TaskId2Tag`.

### 1.6 Filter ("Custom Smart List") — `com.ticktick.task.data.Filter`

A saved, rule-based query (Premium). Fields: `sid`, `name`, `rule` (serialized), `FilterModel filterModel`, and typed rule lists: `duedateRules`, `priorityRules`, `assigneeRules`, `keywordsRules`, `taskTypeRules` (each `List<FilterRule>`), plus `List<String> tags`, `projectIds`, `groupSids`, `teamSids`, `boolean filterHiddenTasks`. Also carries its own `viewMode` + `sortType`/`groupBy`/`orderBy` (+ timeline). Edited via `com.ticktick.task.filter.FilterEditActivity` / previewed via `FilterPreviewActivity`; layouts `normal_filter_edit_layout.xml`, `fragment_advance_filter_edit.xml`.

### 1.7 Built-in Smart Lists (evidence: `SpecialListUtils`, `Constants.SmartProjectNameKey`)

Each has a stable synthetic SID (`_special_id_*`) and a negative long id. `SmartProjectNameKey.getNameKey()` maps ids → name keys:

| Smart List | SID (`SpecialListUtils`) | long id | display `@string` | name key |
|---|---|---|---|---|
| Inbox | (real Project — `getInbox(userId)`) | user inbox id | `@string/project_name_inbox` "Inbox" | `inbox` |
| Today | `_special_id_today` | `SPECIAL_LIST_TODAY_ID` | `@string/project_name_today` "Today" | `today` |
| Tomorrow | `_special_id_tomorrow` | `SPECIAL_LIST_TOMORROW_ID` | `@string/tomorrow` "Tomorrow" | `tomorrow` |
| Next 7 Days | `_special_id_week` | `SPECIAL_LIST_WEEK_ID` | `@string/project_name_week` "Next 7 Days" | `n7ds` |
| All | `_special_id_all` | `SPECIAL_LIST_ALL_ID = -1` | `@string/widget_tasklist_all_label` "All" | `all` |
| Assigned to Me | `_special_id_assignToMe` | `SPECIAL_LIST_ASSIGNED_LIST_ID = -10011` | `@string/assigned_to_me_list_label` "Assigned to Me" | `assignee` |
| Completed | `_special_id_completed` | `SPECIAL_LIST_COMPLETED_ID = -10003` | `@string/project_name_completed` "Completed" | `completed` |
| Won't Do | `_special_id_abandoned` | `SPECIAL_LIST_ABANDONED_ID` | `@string/project_name_abandoned` "Won't Do" | `abandoned` |
| Trash | `_special_id_trash` | `SPECIAL_LIST_TRASH_ID = -10006` | `@string/project_name_trash` "Trash" | `trash` |
| Tags (group) | `_special_id_tags` | `SPECIAL_LIST_TAGS_ID = -10004` | `@string/project_name_tags` "Tags" | `tag` |
| Scheduled | `_special_id_scheduled` | — | — | `scheduled` |
| Calendar group | `_special_id_calendar_group` | `SPECIAL_LIST_CALENDAR_GROUP_ID` | — | `calendar` |
| Closed/Archived group | `_special_id_closed` | `SPECIAL_LIST_CLOSED_GROUP_ID = -10010` | — | `closed` |

Calendar/date pseudo-lists also exist: `_special_id_grid` (month grid), `_special_id_one_day_calendar`, `_special_id_three_day_calendar`, `_special_id_seven_day_calendar`, `_special_id_all_calendar_event`, `_special_id_system_calendar_event`. **Summary** (daily review) is `@string/summary` "Summary". The visibility of each smart list is user-controlled: `Constants.SmartProjectVisibility { AUTO, SHOW, HIDE }` (strings `@string/smart_list_always_show`, `@string/smart_list_not_show`, `@string/smart_list_on_the_day`).

`SpecialProject` (`data/SpecialProject`) is the runtime object rendered for these in the sidebar (via `SpecialProjectViewBinder`).

---

## 2. Side Navigation Panel / Drawer — CRITICAL

Fragment: `com.ticktick.task.activity.fragment.slidemenu.TickTickSlideMenuFragment` (drawer styling `ISlideMenuStyle` → `AppThemeSlideMenuStyle` / `TaskListThemeSlideMenuStyle`; overlay `com.ticktick.task.view.DrawerLayoutWhiteMaskView`). Root layout **`layout/menu_fragment_layout.xml`**. Data assembled by `SlideProjectDataProvider` (+ `SlideProjectDataProvider.SlideMenuFoldStateProvider` for per-folder collapse state, persisted via `data/SectionFoldedStatus` and `data/SlideMenuPinned`).

### 2.1 Structure (top → bottom)

1. **Background layers** — `@id/slide_menu_image` (custom background image) + `@id/slide_menu_mask` (theme mask). Supports per-drawer custom background (`@string/custom_background`).
2. **Account header** — `<include layout="@layout/menu_head_item">` (id `user_info_menu_head_layout`). Contents (`layout/menu_head_item.xml`):
   - `@id/avatar` — `UserAvatarView` with Pro badge (`app:proIcon="@drawable/ic_pro_small"`) and streak/count-day badge overlay.
   - `@id/account_username` + email row, or `@id/sign_in_up_btn` ("Sign in / Sign up", `@string/dailog_title_cal_sub_remind_ticktick`) when logged out; `@id/need_verify_email_ll` verify-email nag.
   - Right icon cluster (`@id/iconlayout`): `@id/search_btn` (`ic_svg_actionbar_search_v8`), `@id/notification_button` (`ic_svg_actionbar_notification_v8` + `@id/notification_button_text` unread badge → `NotificationCenterActivity`), `@id/settings_btn` (`ic_svg_actionbar_settings_v8` + `@id/red_point` update dot → Settings).
3. **Scrolling body** — `@id/recyclerView` (`ScrollBarFixRecyclerView`). A heterogeneous list built from `adapter/viewbinder/slidemenu/*` view binders, in this logical order:
   - **`SpecialProjectViewBinder`** — the Smart Lists block (Today, Tomorrow, Next 7 Days, Inbox, All, Assigned to Me, Summary, Tags, Completed, Won't Do, Trash — subject to per-item visibility from §1.7). `menu_project_item.xml`.
   - **`TitleViewBinder`** — collapsible section headers (e.g. "Lists").
   - **`ProjectGroupViewBinder`** — a Folder row: name + aggregate `@id/task_count` + expand/collapse chevron (`@id/right` = `ic_svg_common_arrow_right`); folds/unfolds its child `Project` rows (`ProjectGroup.isFolded`). `ProjectGroupDividerViewBinder` draws drag dividers.
   - **`ProjectViewBinder` / `BaseProjectViewBinder`** — a List row (`menu_project_item.xml`): `@id/view_project_color` (color dot), `@id/left` (icon/emoji), `@id/name`, `@id/tv_desc`, `@id/task_count` (undone count), `@id/icon_error_info` (sync warning), `@id/item_bg_selected` (selection highlight). Indented when inside a folder.
   - **`FilterViewBinder`** — Custom Smart List (Filter) rows.
   - **`TagListViewBinder`** — Tags rows (expandable, nested via Tag.parent).
   - **`CalendarListViewBinder` / `CalendarInfoViewBinder`** — subscribed calendars group (Google/CalDAV/URL).
   - **`TeamViewBinder` / `EmptyTeamViewBinder`** — team workspaces (shared).
   - **`PinEntityViewBinder`** — pinned lists/filters/tags at the very top (`data/SlideMenuPinned`).
   - **`GroupViewBinder` / `ActionViewBinder`** — group affordances / inline "add" actions.
4. **Bottom bar** — `@id/layout_bottom` (fixed, 56dp): **`@id/layout_add_project`** = "+ Add" (`@id/iv_add_project` = `ic_svg_common_slide_add_v8`, `@id/tv_add_list` = `@string/add`) opening an add sheet for **List / Folder / Filter / Tag** (`@string/add_folder` "Add Folder", `@string/add_smart_list` "Add Smart List"); and **`@id/iv_manage_project`** (`ic_svg_slidemenu_manage_list_v8`) → `ProjectManageActivity` ("Manage Lists", reorder/drag lists into folders via `ProjectItemTouchHelperCallback.SlideMenuEditModeCallback`).

### 2.2 Density / style (3 options)

Sidebar visual density is a setting with three named styles (`@string/sidebar_xx` "Sidebar: %s"):
- `@string/sidebar_classic_simple` — **Classic**
- `@string/sidebar_minimal_simple` — **Minimal**
- `@string/sidebar_modern_simple` — **Modern**

Task-count display style separately: `@string/sidemenu_task_count_style` "Sidebar Count".

### 2.3 Drag-to-reorder & folders

Lists reorder and drop **into folders** via `ProjectItemTouchHelperCallback` (edit mode `SlideMenuEditModeCallback`). Folders created/edited with `ProjectEditActivity` / `edit_folder_layout.xml` (`@string/add_folder`, `@string/edit_folder`). Folder collapse persists in `ProjectGroup.isFolded` + `SlideMenuFoldStateProvider`.

### 2.4 Configurable bottom tab bar

Separate from the drawer, `MeTaskActivity` hosts a bottom tab bar (`layout/layout_slide_tabbar.xml`, item `item_slide_tabbar.xml` / `item_slide_focus_tabbar.xml`; overflow `fragment_tab_bar_more_item.xml`). Configured in `com.ticktick.task.tabbars.TabBarConfigActivity` (`activity_tab_bar_config.xml`; `@string/preference_navigation_bar`, `@string/navigation_preference_tips` "You can choose to show/hide the following options from the tab bar"). Selectable tabs — `TabBarKey` enum: **`TASK`, `CALENDAR`, `POMO`(Focus), `HABIT`, `SEARCH`, `SETTING`** (labels `@string/navigation_calendar`, `@string/navigation_pomo` "Focus", `@string/navigation_habit`, `@string/navigation_search`, `@string/navigation_settings`; plus `@string/tab_bar_pomodoro`, `@string/tab_bar_habit_tracker`, `@string/tab_bar_countdown` and Matrix `@string/matrix_tab_bar_desc` available as tab targets). Tab model `data/TabBarItem`; pad layout via `tabbars/PadNavigationController`.

---

## 3. Quick-Add Input Bar — CRITICAL

Primary UI: fragment `com.ticktick.task.quickadd.QuickAddActivity` / `layout/fragment_quick_add.xml`. Config/model: `model/quickAdd/QuickAddConfig(+Builder)`, `TaskInitData`, `QuickAddHelper`, `QuickAddResultData`, and variant configs `TaskListAddConfig`, `MatrixAddConfig`, `CalendarConfig`, `DetailAddConfig`. Task defaults applied from `data/TaskDefaultParam`.

### 3.1 Layout (`fragment_quick_add.xml`)

- `@id/layout_quick_add_background` — scrim/background.
- `@id/input_view` → `@id/edit_input_layout`:
  - `@id/et_title` — `com.ticktick.task.view.OnSectionChangedEditText`, hint `@string/editor_hint_note`. The **`OnSectionChangedEditText` is the natural-language field**: it live-highlights recognized "sections" (date phrases, `#tags`, `!priority`, `~list`) as colored chips while you type (parse driven by the Smart Date/Tag recognition engine, §6/§8).
  - `@id/et_content` — second line, description/note.
  - `@id/list_attachment` — thumbnail row for attached images/files.
- **`@id/list_buttons`** — horizontal RecyclerView = the **option toolbar** (the tappable option buttons; see §3.2). Its rows are produced by `adapter/viewbinder/quickadd/*ViewBinder`.
- `@id/iv_voice` — microphone (`ic_svg_microphone_v8`) → voice add (`fragment_voice_add_task.xml`, `voice_input_dialog_layout.xml`).
- `@id/iv_save` — send (`ic_save_button`).

### 3.2 Option buttons in `list_buttons` (each → its picker)

Enumerated from the quick-add view binders and item layouts:

| Button | ViewBinder / item layout | Icon | Opens |
|---|---|---|---|
| **Date / time** (smart date) | `DateButtonViewBinder` / `item_quick_add_date_button.xml` (`@id/iv_date` = `ic_svg_quickdate_pick_date_v8`, `@id/tv_date` shows parsed text, `@id/iv_date_subicon`) | calendar | The **Quick Date picker** (`fragment_quick_date_normal_config.xml`) — a smart-date suggestion grid: Today, Tomorrow, Next Monday, Next Week, custom "Pick Date", Clear (`@string/pick_date_*`), All-day toggle (`@string/quick_date_all_day`), plus **Switch to Advanced** (`@string/quick_date_advanced`) → sub-tabs for **Time**, **Reminder**, **Repeat**, **Duration** (`dialog_fragment_quick_date_advanced_pick.xml`: `@id/tv_time_info`, `@id/tv_repeat_info`; delta/duration `dialog_fragment_quick_date_delta_picker.xml`). If the title already contains a parsed date, this button shows it and stays editable — **natural-language parse and the button coexist** (typing "tomorrow 3pm" fills the button; tapping the button overrides it). |
| **Priority** (flag) | `IconButtonViewBinder` + `PriorityLabelItemViewBinder` / `item_quick_add_icon_button.xml` | priority flag | Priority popup — levels `Constants.PriorityLevel.PRIORITIES = {5=High, 3=Medium, 1=Low, 0=None}`. |
| **Tag** (`#`) | `IconButtonViewBinder` + `PopupTagItemViewBinder` (`data/PopupTagItem`) | `ic_svg_detail_md_tags_v8` | Tag chooser popup (search + create); typing `#` inline does the same. |
| **List / project** | `ProjectButtonViewBinder` / `item_quick_add_project_button.xml` (`@id/iv_project_icon` = `ProjectIconView`, `@id/tv_project_name`) | list icon/emoji | Project picker (choose destination list; Inbox default). |
| **Reminder / bell** | reminder branch of the Date advanced picker (`item_item_reminder`; icon `ic_svg_om_reminder_v8`) | bell | Reminder time list (on time / 5 min / …/ custom) + multiple reminders (§6). |
| **Repeat** | repeat branch of Date advanced (`ic_svg_quickdate_repeat_v8`) | loop | Recurrence picker (§6). |
| **Eisenhower quadrant** (when adding from Matrix) | `MatrixButtonViewBinder` / `item_quick_add_matrix_button.xml` (`@id/tv_matrix_emoji`, `@id/iv_matrix_icon` = `ic_matrix_1..4`, `@id/tv_matrix_title`) | quadrant | Quadrant picker Q1–Q4 (`MatrixLabelItemViewBinder`). |
| **Assignee** (shared lists) | `PopupTeamMemberItemViewBinder` (`model/quickAdd/AssignValues`) | avatar | Assign-to-member popup. |
| **Template** | via `iv_task_template` (in detail input) | template | Insert from `data/TaskTemplate`. |
| **Edit/configure toolbar** | `EditQuickAddButtonViewBinder` → `com.ticktick.task.quickadd.controller.AddTaskButtonSettingsActivity` (`activity_add_task_button_settings.xml`, previews `@id/list_buttons` + editable `@id/list`) | ⋯ | Reorder / show-hide which option buttons appear. |

Widget quick-add twin: `AppWidgetProviderQuickAdd` / `ticktick_appwidget_quick_add.xml`, config persisted by `QuickAddPreferencesHelper` (keys `quick_add_preferences_helper_date/priority/tag/projectId/templateId`). Floating **Quick Ball** overlay: `QuickBallService` / `quick_ball_layout.xml` (Premium — `@string/feature_quick_ball_title`). Paste-to-tasks: `PasteQuickAddTasksHelper` (`@string/clipborad`). Voice widget: `voice_input_widget_layout.xml`, `layout_widget_confirm_voice_input.xml`.

---

## 4. Task Detail Screen

Activity `com.ticktick.task.activity.TaskActivity` (detail fragment); the body is a RecyclerView of `detail_list_item_*` rows, the bottom is an inline editor toolbar `layout_task_detail_input.xml`, and the ⋯ menu is `fragment_task_detail_menu.xml` (items `TaskDetailMenuItems` / `TaskDetailMenuHeader`, edited in `TaskDetailMenuEditActivity` / `activity_task_detail_menu_edit.xml`).

### 4.1 Content rows (RecyclerView)

- `detail_list_item_title.xml` — title (with checkbox / status circle).
- `detail_list_item_text.xml` — **description / note, Markdown** (styles toolbar `ic_svg_detail_md_style_v8`; insert time `ic_svg_detail_md_insert_time_v8`; link task `ic_svg_detail_md_link_task_v8`).
- `detail_list_checklist_item.xml` + `detail_subtask_list_item.xml` — **checklist / subtasks, reorderable** (`ic_svg_detail_arrow_updown_v8`, delete `ic_svg_detail_checklist_delete_v8`); task-kind toggle text↔checklist `ic_svg_detail_checklist_v8`.
- `detail_list_date_info.xml` — date / duration / reminder / repeat summary (`ic_svg_detail_time_v8`, `ic_svg_detail_reminder_v8`).
- `detail_list_item_tags.xml` — tag chips.
- `detail_list_item_attachment_image.xml` / `_attachment_other.xml` — image / voice / file attachments (`ic_svg_menu_attachment_v8`; `data/Attachment`, image picker `view.customview.imagepicker.*`).
- `detail_list_item_agenda.xml` — agenda / attendees (shared).
- `detail_list_item_notion*.xml` — Notion-synced property blocks.
- `detail_list_item_preset_gif.xml` / `_preset_video.xml` — preset/rich media (onboarding samples).

### 4.2 Bottom input toolbar (`layout_task_detail_input.xml`)

Buttons: `@id/layout_project` (move list, `ic_svg_om_move_project_v8`), `@id/iv_tag`/`@id/et_tag` (`ic_svg_detail_md_tags_v8`), `@id/iv_task_kind` (checklist toggle), `@id/iv_item_reminder` (`ic_svg_om_reminder_v8`), `@id/iv_note_date`, `@id/iv_summary` (**AI summary** `ic_svg_menu_md_summary_v8`), `@id/iv_task_template` (**save as template** `ic_svg_menu_save_template_v8`), `@id/iv_attachment`, `@id/iv_show_md_styles`, `@id/iv_undo`/`@id/iv_redo`, `@id/iv_close_keyboard`. Markdown mode strip: `@id/list_markdown` + `@id/iv_close_markdown` (`ic_svg_menu_md_normal_v8`).

### 4.3 ⋯ / actions

Pin, Priority, Move to list, Copy/**Duplicate**, Tags, **Won't Do** (`@string/project_name_abandoned`), Delete, Focus (Pomo `ic_svg_detail_pomodoro_v8` / Stopwatch `ic_svg_detail_stopwatch_v8`), add to Calendar, Print/Share (`TaskShareActivity`, `ic_svg_detail_share_x`), Comments/Activities (`TaskCommentActivity`, `data/Comment`; Premium `@string/feature_task_activities_title`), convert task↔note. Location reminder `ic_svg_detail_location_star_v8` (`data/LocationReminder`, `TaskMapActivity`).

---

## 5. Views & how each works

Rendered by `MeTaskActivity` + `MeTaskViewModel` (`onTaskListViewCreated` / `onCalendarViewCreated`). `Project.viewMode` picks per-list mode.

- **List** (`Constants.ViewMode.LIST`) — standard grouped list; row `menu_project_item`/list item viewbinders; checkbox anim §10.
- **Kanban** (`Constants.ViewMode.KANBAN`) — horizontal **Columns** from `Task2.columnId`; columns are first-class `data/Column` (name, sortOrder, `isDefaultColumn`, `isPin`, `taskCount`). Managed via `activity.kanban.ColumnManageActivity` / `ColumnEditActivity`. Adding a task in a column sets `columnId`.
- **Timeline** (`Constants.ViewMode.TIMELINE`, `@string/timeline`/`@string/timeline_view`) — Gantt-like; separate `timelineSortType/GroupBy/OrderBy`; options `TimelineViewOptionsActivity` / `res/xml/timeline_view_options.xml`, `TimelineTimeZoneActivity`. **Premium** (`@string/feature_time_line_title`).
- **Calendar** — `CalendarViewActivity`; sub-modes: **Day** (`@string/day_view`), **3-Day** (`@string/three_day_view`), **Week** (`@string/week_view`), **Month/grid** (`@string/month_view`, `_special_id_grid`; **Premium** `@string/feature_grid_view_title`), **Schedule/Agenda**, plus **Year** (Premium `@string/feature_guide_calendar_year_title`). Options `res/xml/preference_calendar_view_options.xml`: color source, item style, show checkbox/details/completed/subtask/repeat-task/habit/focus-records/countdown/course, extra time zone. `CalendarWeekViewMode { DEFAULT, GRID }`.
- **Eisenhower Matrix** — `matrix.ui.MatrixDetailListActivity` / `MatrixConditionActivity` / `MatrixEditActivity`; 4 quadrants (`ic_matrix_1..4`, guide `assets/matrix_guide_{cn,en}.json`), each quadrant is a saved condition (urgent×important). **Premium** (`@string/pro_feature_subtitle_matrix`).

**Sort / group options** — `Constants.SortType`: `DUE_DATE("dueDate")`, `USER_ORDER` (manual/drag), `LEXICOGRAPHICAL("title")`, `PRIORITY`, `ASSIGNEE`, `TAG`, `PROJECT`, `CREATED_TIME`, `MODIFIED_TIME`, `COMPLETED_TIME`, `PROGRESS`, `TIMELINE`, `TASK_DATE`, `QUICK_SORT`, `NONE`; direction `ASC`/`DESC`. Persisted per container in `sortType`/`groupBy`/`orderBy`. Manual order stored in `data/TaskSortOrderInList` / `...InDate` / `...InPriority` / `...InTag` / `...InPinned` and `SortOrderInSection`. **Collapse rule:** section headers are collapsible only when sort = `USER_ORDER` (manual); other sort types render flat groups (fold state `data/SectionFoldedStatus`).

---

## 6. Scheduling, Reminders, Recurrence

- **Date/time/duration** — `DueData` model; `startDate`/`dueDate`, `isAllDay`, `isFloating`. Duration/estimate is **Premium** (`@string/feature_time_duration_title`, `@string/feature_estimate_duration`); picker `dialog_fragment_quick_date_delta_picker.xml`.
- **Reminders (multiple)** — `List<TaskReminder>` + `data/Reminder`, offsets set in advanced picker; multiple reminders **Premium** (`@string/feature_multiple_reminders_title` "More Reminders"). Advance defaults `res/xml/advance_reminder_preferences.xml`. Checklist-item reminders `data/ChecklistReminder` (Premium `@string/feature_sub_task_reminder_title`).
- **Annoying Alert** — `data/AnnoyingAlert` / `IAnnoyingAlertItem`; `Task2.annoyingAlert`; `@string/annoying_alert` "Reminder Annoying Alert" — repeats the alarm for a minute and re-rings after two minutes (`@string/annoying_alert_hint`); toggling off is Premium-gated.
- **Full-screen alarm & snooze** — `ReminderPopupActivity` / `SnoozePopupActivity`; snooze grid `@string/snooze`, `@string/snooze_tomorrow`, `snooze until %s`; `data/DelayReminder`, `data/RecentReminder`.
- **Recurrence** — RRULE in `Task2.repeatFlag`; `repeatFrom` = **By Due Dates** (`@string/repeat_due_date`) vs **By Completion Date** (`@string/repeat_completion_date`); ends: never / **count** (`@string/repeat_end_count`) / **date** (`@string/repeat_end_date`); **Lunar Repeat** (`@string/lunar_repeat`, constraint `@string/lunar_unsupport_repeat_hint`); **Skip**: Skip Weekends (`@string/skip_weekend`), Skip Official Holidays (`@string/skip_public_holidays`), **Skip the Recurrence** (`@string/skip_current_recurrence`, `exDate` set); edit-scope prompts: This / All / All Future / All Unfinished (`@string/repeat_this_instance`, `_all_instance`, `_from_now_instance`, `_all_uncompleted_instance`). `data/RepeatInstance`.
- **Location reminders** — `data/LocationReminder` / `data/Location` / `data/FavLocation`, `TaskMapActivity` (arrive/leave geofence).
- **Daily summary** — `DailyTaskDisplayActivity` + `DailyReminderTimeActivity`; `@string/summary` "Summary" smart list.
- **Smart date parsing** — `res/xml/preference_smart_date_parse.xml`: `@string/enable_date_parsing`, `@string/remove_text_in_tasks`, tag recognition `@string/remove_tags_in_task_name` (feeds the `OnSectionChangedEditText` in §3).

---

## 7. Focus/Pomodoro, Habits, Statistics, Countdown, Templates

- **Focus / Pomodoro** — `PomodoroActivity`, `focus.ui.timer.*` (Stopwatch `AddTimerActivity`/`TimerDetailActivity`/`ArchiveTimersActivity`; full-screen `fullscreen.FullScreenTimerActivity`; exit `FocusExitConfirmActivity`). Pomo vs Stopwatch; **Focus Mode** (`@string/pomo_focus_mode` — leaving app not in allowlist ends focus), Focus Note (`@string/focus_note`), white-noise sounds (`ChoosePomoSoundActivity`), link focus to a task (`PomodoroTaskBrief`), summaries `data/PomodoroSummary`/`FocusSummaryHelper`. Prefs `res/xml/preference_pomodoro*.xml`. Overlay `PomoPopupActivity`, widget `AppWidgetProviderDailyFocused`.
- **Habits** — `habit.HabitAddActivity`/`HabitEditActivity`/`HabitDetailActivity`/`HabitRecordActivity`/`AllHabitListActivity`; sections `HabitSectionManageActivity`. Models `data/Habit`, `HabitCheckIn`, `HabitConfig`, `HabitRecord`, `HabitReminder`, `SkippedHabit`, `FrozenHabitData`. Goal/step/cycle (`HabitCycleActivity`, `HabitCompleteCycleActivity`). Check-in Lottie: `assets/habit_animations/habit_animation_*.json.zip` (drink_water, exercise, jogging, early_to_rise, eat_breakfast, …). **Unlimited habits = Premium** (`@string/feature_unlimited_habit_numbers_title`). Prefs `preference_habit_settings.xml`.
- **Statistics / Achievement** — `RankInfo`, `data/HistoricalStatisticsData` / `RecentStatisticsData`, `assets/statistics`; Achievement grades/levels `Constants.AchievementGrade`/`AchievementLevel`; medals `MedalShareActivity`/`MedalWebActivity`, share `AchievementSharePreviewActivity`; **Annual/Yearly Report** (`AnnualYearReportWebViewActivity`, `assets/yearly_report`, `YearlyReportBannerPreference`). **Historical statistics = Premium** (`@string/feature_history_statistics_title`).
- **Countdown** — `countdown.CountdownDetailActivity` / `edit.CountdownEditActivity` / `ArchivedCountdownFragment`; models `data/Countdown`(+`Builder`/`Background`/`Reminder`/`Section`/`ListConfig`), `PinnedCountdown`. Widgets `single_countdown` / `countdown_list`; birthday import `CountdownBirthdayImportFragment`.
- **Templates** — `data/TaskTemplate` / `ProjectTemplate` / `PresetTask*`; "Manage Templates" (`@string/manage_template`), save-as-template button §4.2. **Course/Timetable** module: `course.*`, `TimetableCreateActivity`/`Edit`/`Manage`/`Share`, prefs `TimetableSettingsActivity`.

---

## 8. Settings Tree

Root `res/xml/preferences.xml` (host `SettingsPreferencesHelper`/`FragmentWrapActivity`). Top-level entries (key → `@string`):
- `prefkey_current_account` (AccountInfoPreference) · `prefkey_yearly_report` · Pro banner (`Account7ProPreference`) → `GetProActivity`/`ProFeaturesActivity`.
- `prefkey_navigation_setup` → `@string/preference_navigation_bar` (tab bar config, §2.4).
- `prefkey_appearance` → **Appearance** (`ChooseAppearanceActivity` / `CustomThemeActivity`): themes `@string/theme_*` (Light, Dark, Blue, Navy, Lilac, Matcha, Ink, Dark Cyan/Green/Pink/Purple/Yellow…), Premium themes (`@string/feature_theme_title`), per-list color/background, list row style, fonts.
- `prefkey_date_and_time` → `DateAndTimePreference` (`date_and_time_preference.xml`): week start, time zone, time format, smart date parse (`preference_smart_date_parse.xml`).
- `prefkey_reminder` → **Sounds & Notifications** (`sound_reminder_and_notification_preferences.xml`, `NotificationSettingActivity`): ringtone (`@string/ringtone`), annoying alert, ongoing status bar, alert mode, per-Android-6 tips.
- `prefkey_widgets` → widget settings (`widget_*_preference.xml`).
- `prefkey_ai_features` → `preferences_ai_feature.xml` (AI summary/complete; `AiCompleteActivity`).
- `prefkey_settings` → **General** (`more_settings_preferences.xml`): Shortcuts, Task Detail Page, Smart Recognition, **Task Quick Add** (`task_quick_add_preference.xml`: quick-add notification, clipboard add, status bar, **Quick Ball**, text-selection action), **Task Defaults**, Upload/Download attachments, Share list, **Manage Templates**, Wear, **Pattern Lock** (`lock_preferences.xml`, `ChooseLockPattern`), **Swipe Actions** (`CustomSwipePreference`, §10), Advanced (`more_advance_settings.xml`).
- `provider_data_import` → Import & Integration (`DataImportPreferences`; Notion, calendars `CalendarManagerActivity`).
- `services`, `prefkey_share_app`, `prefkey_guide` (newbie), `prefkey_help` (`help_preferences.xml`, `FeedbackPreferences`, `TicketActivity`), `prefkey_follow_us`, `prefkey_about` (`about_preferences.xml`), `prefkey_logout` (`@string/rank_sign_out`).

---

## 9. Widgets

App-widget providers + `res/xml/ticktick_appwidget_info_*.xml`. Full set:
- Task lists: `standard` (`ticktick_appwidget_standard.xml`), `4x4`, `compact`, `undone`, `grid` (month), `grid_week`, `three_day`, `week`, `today_calendar`.
- Focus: `pomo`, `daily_focused`, `single_timer`, `focus_distribution2x2` / `4x2` / `4x4`, `task_completion`.
- Habit: `habit`, `habit_week`, `habit_month`, `single_habit`, `habit_progress2x2` / `4x2`.
- Other: `matrix` (Eisenhower), `quick_add` (§3), `countdown_list`, `single_countdown`, `course` (timetable).

Each has a config preference fragment (`widget_*_config_preference_fragment.xml`) covering theme, alpha, list source, page-turn, completed visibility. Config host `WidgetConfiguration` / `SimpleWidgetConfig` / `WidgetExtensibleConfig`.

---

## 10. Interaction & Polish

- **Swipe actions** (`CustomSwipePreference`, `@string/preference_custom_swipe_title` "Swipe Actions") — three positions **Left / Middle / Right** (`@string/swipe_left/right_option`, `swipe_middle_option`) with short vs long swipe (`@string/short_swipe_left`, `@string/long_swipe_right`). Assignable actions: Complete, Delete, Due Date, Estimated Duration, Move to, Priority, Start Focus, Add Tag, None (`@string/preference_custom_swipe_entries_*`). Customizing them is **Premium** (`@string/feature_custom_swipe_title`). `Constants.SwipeOption`.
- **Drag reorder** — tasks (`data/TaskDragBackup`, sort-order tables §5) and lists-into-folders (`ProjectItemTouchHelperCallback`).
- **Lottie micro-interactions** — checkbox complete: `assets/animation_checkbox_click.json`; swipe hint `assets/animation_swipe.json`; **pull-to-refresh stages**: `assets/refresh/start.json` → `assets/refresh/progress.json` → `assets/refresh/done.json` (+ `refresh/animation_swipe.json`); onboarding `assets/guide_circle.json`, `assets/matrix_guide_*.json`, `assets/back_and_arrow_down_{light,dark}.json`, `assets/screen_rotate`, `assets/login/banner_lottie_{light,dark}.json`.
- **Bottom sheets / popups** — quick-add sheet, date/priority/tag pickers, `WidgetTaskListDialog`, `ReminderPopupActivity`, `SnoozePopupActivity`, `TokenTimeoutPopupActivity`.
- **Theming** — many built-in themes (`@string/theme_*`), Premium themes; **per-list color & background** (`Project.backgroundInfo` → color/gradient/image `data/*ProjectBackground`, `@string/custom_background`); custom theme builder `CustomThemeActivity`; fonts bundled `assets/*.ttf` (DIN_Numbers, roboto_numbers_regular, sans_light, gulzar, icomoon). App icons/alt-icons via manifest activity-aliases.

---

## 11. Activities & Fragments (selected map)

| Class | Purpose |
|---|---|
| `MeTaskActivity` | Main shell: drawer + task/calendar/kanban/timeline views + bottom tab bar |
| `activity.fragment.slidemenu.TickTickSlideMenuFragment` | Side navigation drawer (§2) |
| `quickadd.QuickAddActivity` | Quick-add task bar (§3) |
| `quickadd.controller.AddTaskButtonSettingsActivity` | Configure quick-add option buttons |
| `TaskActivity` / `TaskDetailMenuEditActivity` | Task detail + editable ⋯ menu (§4) |
| `TaskCommentActivity` / `TaskActivitiesWebViewActivity` | Comments / activity log |
| `CalendarViewActivity` / `CalendarViewOptionsActivity` | Calendar views + options (§5) |
| `matrix.ui.MatrixDetailListActivity` / `MatrixConditionActivity` / `MatrixEditActivity` | Eisenhower Matrix |
| `kanban.ColumnManageActivity` / `ColumnEditActivity` | Kanban columns |
| `ProjectEditActivity` / `ProjectManageActivity` | Create/edit list & folder; manage/reorder |
| `TagEditActivity` | Create/edit/nest tags |
| `filter.FilterEditActivity` / `FilterPreviewActivity` / `search.SearchFilterActivity` | Custom Smart Lists (Filter) |
| `QuickDateConfigActivity` | Customize quick-date suggestions |
| `PomodoroActivity` / `focus.ui.timer.*` / `PomoPopupActivity` | Focus / Pomodoro / Stopwatch |
| `habit.HabitAddActivity` / `HabitDetailActivity` / `AllHabitListActivity` | Habits |
| `countdown.CountdownDetailActivity` / `edit.CountdownEditActivity` | Countdown |
| `course.Timetable*Activity` | Timetable / course schedule |
| `SearchActivity` | Global search (`ticktick_searchable.xml`) |
| `NotificationCenterActivity` | In-app notifications |
| `ReminderPopupActivity` / `SnoozePopupActivity` | Reminder alarm / snooze |
| `calendarmanage.CalendarManagerActivity` / `LinkGoogleCalendarActivity` / `SubscribeCalendarActivity` | Calendar subscriptions |
| `tabbars.TabBarConfigActivity` | Bottom tab bar config |
| `preference.*` (Appearance, DateAndTime, CustomSwipe, Lock, DataImport, About…) | Settings screens (§8) |
| `upgrade.ProFeaturesActivity` / `account.GetProActivity` / `payfor.PayUserInfoActivityV6` | Premium upsell / purchase |
| `share.TaskShareActivity` / `TaskListShareActivity` / `AchievementSharePreviewActivity` | Sharing |
| `account.LoginMainActivity` / `userguide.FirstLaunchGuideActivityV2` / `NewUserConfigActivity` | Login / onboarding |
| `DispatchActivity` / `dispatch.InnerDispatch*Activity` | Deep-link / intent routing |
| Services: `QuickBallService`, `AutoSyncJobService`, `NotificationOngoing` | Floating ball, sync, ongoing notif |

---

## 12. Premium (Pro) Gating

Paywalled features (evidence: `feature_*_title` / `pro_*` strings; enforced at `ProFeaturesActivity` / `GetProActivity`):

- **Custom Smart Lists / Filters** (`@string/feature_custom_smart_list_title`, `@string/pro_filter_title`).
- **Custom Swipe Options** (`@string/feature_custom_swipe_title`).
- **Calendar views**: Month/grid (`feature_grid_view_title`), **Timeline** (`feature_time_line_title`), Year (`feature_guide_calendar_year_title`), calendar item style (`feature_guide_calendar_style_title`), **Calendar subscription** (`feature_subscribe_calendar_title`), calendar widgets/grid widget (`feature_grid_widget_title`).
- **Duration / Estimated Duration** (`pro_title_task_duration`, `feature_time_duration_title`, `feature_estimate_duration`).
- **More reminders** (`feature_multiple_reminders_title`) & **checklist-item reminders** (`feature_sub_task_reminder_title`); Annoying Alert toggle-off (`annoying_alert_close_no_pro_user_ensure_msg`).
- **Larger capacity** (`pro_title_larger_capacity`): more lists/tasks/checklist items (`feature_over_project_or_task_title`), more sharing members (`feature_over_share_user_title`), more/larger attachments (`feature_over_upload_count_title`).
- **Task/List Activities & comments history** (`feature_task_activities_title`, `feature_list_activities_title`).
- **Historical Statistics** (`feature_history_statistics_title`), **Premium themes** (`feature_theme_title`), **Quick Ball** (`feature_quick_ball_title`), **Unlimited Habits** (`feature_unlimited_habit_numbers_title`), **Eisenhower Matrix** (`pro_feature_subtitle_matrix`), **Pomo widget** (`feature_pomo_widget`), Daily reminder / "Unlimited Plan" (`feature_daily_reminder_title`).

Grouping on the paywall: `pro_title_premium_exclusives`, `pro_title_various_calendar_views`, `pro_title_calendar_extras`, `pro_title_larger_capacity`, `pro_title_list_task_activities`, `pro_title_task_duration`.
