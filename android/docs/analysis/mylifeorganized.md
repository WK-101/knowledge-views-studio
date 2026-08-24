# MyLifeOrganized (MLO) for Android — Reverse-Engineering Analysis

Evidence-backed teardown of the decompiled MLO Android APK, written to inform a new native
Kotlin/Compose task manager that replicates MLO's task-management core (offline, private, lossless
export/import).

**Source of evidence:** decoded resources (`AndroidManifest.xml`, `res/values/strings.xml` ≈ 3517
lines, `res/values/arrays.xml`, `res/layout/*`, `assets/*`), the class/package descriptor list
(`mlo_classes.txt`, 1376 classes, largely **unobfuscated**), and string literals extracted from
`classes.dex` (greenDAO `CREATE TABLE` statements, sync log strings). Every claim below cites a real
identifier. Where a behavior is inferred rather than directly stated, it is marked
"inferred from `<evidence>`".

---

## 1. Identity & build

| Property | Value | Evidence |
|---|---|---|
| Package | `net.mylifeorganized.mlo` | manifest `package=` |
| App name | MyLifeOrganized (MLO) | `@string/APP_NAME` |
| Version | **5.0.0** (versionCode **5030**) | `apktool.yml` `versionInfo` |
| minSdk / targetSdk / compileSdk | **21 / 35 / 35** | `apktool.yml sdkInfo`, manifest `compileSdkVersion="35"` |
| APK size | ~14.7 MB (`classes.dex` ~9.4 MB, single dex) | `apks/mlo.apk`, `dec/mlo/classes.dex` |
| Native libs | Only `libdatastore_shared_counter.so` (AndroidX DataStore) for arm64-v8a, armeabi-v7a, x86, x86_64 | `dec/mlo/lib/*` |
| Obfuscation | **Very low.** Model/activity/controller classes fully named (`TaskEntityDescription`, `MainActivity`, `ComputedScorePriorityType`, `sync/conflict/TaskMergePolicy`); only some helpers are single-letter (`model/a`…`model/z`). Third-party libs shrunk. | `mlo_classes.txt` |
| Persistence / ORM | **greenDAO** (`de.greenrobot.dao`) over SQLite. Each entity has a generated `*EntityDescription` + inner `$Properties`; merge policies live under `de/greenrobot/dao/merge/*`. | dex strings `Lde/greenrobot/dao/merge/*`, `*EntityDescription` classes |
| Date/time | **Joda-Time** (`net.danlew.android.joda` init in manifest; `org/joda/time/format/messages_*.properties`) | manifest `JodaTimeInitializer`, `apktool.yml unknownFiles` |
| Logging | log4j (`org/apache/log4j/*`) | `apktool.yml unknownFiles` |
| Archives | Apache Commons Compress (ZIP) for backup/sync payloads | dex `org/apache/commons/compress/archivers/zip/*` |
| Cloud / push | Firebase Cloud Messaging + Crashlytics + Analytics; GCM permission `com.google.android.c2dm.permission.RECEIVE`. Push is used to trigger sync, not to move data. | manifest `MLOMessagingService`, `SyncListenerService`, `FirebaseInitProvider` |
| Maps | Google Maps + Places SDK (context locations / nearby) | manifest `com.google.android.geo.API_KEY`, `places.widget.AutocompleteActivity` |
| Sync mechanisms | (a) **MLO Cloud** REST (`sync/rest/CloudApi`), (b) **Wi-Fi sync with MLO Desktop** (`WiFiSyncSettingsActivity`), (c) legacy HTTP autosync endpoint `autosync.mylifeorganized.net/mlo/MLOInetSyncPost.aspx`. Transport payload is a **versioned multi-section CSV** (see §9). | manifest, `AUTO_SYNC_URL`, dex `SYNC send to server CSV` |

**Architecture note.** MLO keeps two table-naming conventions in one greenDAO database:
- **Core MLO tables** use MLO-Desktop-compatible CamelCase names with `Ver` + `UID` columns for
  delta sync: `TodoItems`, `Places` (= contexts), `Notes`, `Reminders`, `Views`, `TaskFilter`,
  `TaskSort`, `ViewIndexes`, `DeletedItems`, `TodoPredecessors`, `TodoItemPlaces`, `PlaceRelations`,
  `Attachments`, `Bookmarks`, `NearbyReminders`, `Preferences`.
- **Newer Android-only entities** use greenDAO's default `UPPER_SNAKE` names: `COLOR_CODING`,
  `CONFLICT_ENTITY`, `CONFLICT_PROPERTY`, `CONFLICT_SYNC_SESSION`, `ZOOM` (and, from the
  `*EntityDescription` set, the view/workspace entities).

This dual schema is the reason MLO round-trips losslessly with the desktop `.ml` format: the core
task graph is stored in the exact desktop layout.

---

## 2. Permissions → feature implications

| Permission | Enables |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Cloud sync, Wi-Fi sync with desktop, connectivity-triggered auto-sync (`ConnectionReceiver`) |
| `com.google.android.c2dm.permission.RECEIVE` | FCM push that signals "data changed, sync now" (`SyncListenerService`, `MLOMessagingService`) |
| `SCHEDULE_EXACT_ALARM` + `AlarmPermissionStateChangedReceiver` | Exact-time reminders/alarms |
| `POST_NOTIFICATIONS` | Reminder notifications, persistent notification, sync status |
| `WAKE_LOCK`, `VIBRATE` | Wake device for reminder, vibrate on alert / swipe feedback |
| `RECEIVE_BOOT_COMPLETED` (`ReceiverStarter`) | Re-arm reminders/geofences after reboot / app update |
| `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` + `hardware.location*` | **Location contexts / geofenced "nearby" reminders** (`NearbyService`, `LocationReminderReceiver`, `FetchAddressIntentService`) |
| `READ_CALENDAR` | "Today"/"My events" overlay of device calendar next to tasks (`CALENDARS_TOP_LABEL_TITLE`, `fragment_my_events`) |
| `READ_CONTACTS` (sdk-23) | `@`-mention contact insertion into notes (`AUTOCOMPLETE_IN_NOTES_EXPLANATION`) |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | Biometric profile unlock (`BIOMETRIC_PROMPT_*`, `FINGERPRINT_*`) |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `requestLegacyExternalStorage` | Backup/restore files, import views/templates, custom reminder audio files |
| `REQUEST_INSTALL_PACKAGES` + `UpdateDownloadReceiver` | Self-update of the site-purchased (non-Play) build |
| `<queries>` speech / TTS / `com.google.android.apps.maps` | Voice task capture, speak-back, "open in maps" for a context location |

`android:allowBackup="false"` — MLO deliberately opts out of Android auto-backup (its own
backup/sync owns the data). Good privacy signal for our offline-first goal.

---

## 3. Data model (CRITICAL)

Entities are the greenDAO `*EntityDescription` classes; exact columns come from the dex `CREATE
TABLE` literals. The task table (`TodoItems`) is the spine.

### 3.1 Task — `TaskEntityDescription` → table `TodoItems`

Full column list (dex literal):

```
TodoItemID (PK), ParentItemID, ItemIndex, TaskCaption,
IsComplete, IsStarred, StarredDateTime,
Importance, Urgency,
HideInToDo, HideInToDoThisTask,
GoalFor, ScheduleType,
CompletionDateTime, DueDateTime, StartDateTime, LeadTime,
EstimateMin, EstimateMax,
RecType, RecStartDate, RecEndDate, RecOccurrences, RecInterval, RecInstance,
RecDOWMask, RecDayOfMonth, RecMonthOfYear, RecUseCompletionDate,
RecUncompleteSubtasks, RecGeneratedCount, RecHourDelta, RecDNCCCopy,
RecRecurWSC, RecUncomplIfCompl,
CompleteInOrder, Effort, Satisfaction,
IsProject, ProjectStatus, ProjectCompletion,
TaskNoteID, FlagID,
CreatedDate, LastModified, IsExpanded,
NextReviewDate, LastReviewed, ReviewEvery, ReviewRecurrenceType,
DependOper, DependPostpone,
TaskUID (sync id), Ver (sync version)
```

Field meaning (cross-checked against `FILTER_FIELD_*` and `LABEL_*` strings):
- **Hierarchy:** `ParentItemID` (self-referential tree; index `idx_TodoItems_ParentID`) +
  `ItemIndex` (sibling order → this is what makes it an **outline**, not a flat list).
  `IsExpanded` stores per-task collapse state. `IsFolder`/`IsProject` classify branch types
  (`FILTER_FIELD_IsFolder`, `FILTER_FIELD_IsProject`).
- **Completion:** `IsComplete`, `CompletionDateTime`. Project rollup: `ProjectStatus`
  (Not started / In progress / Suspended / Completed / None — `arrays.xml PROJECT_STATUS`),
  `ProjectCompletion` (percent; `FILTER_FIELD_ProjectCompletionPercent`).
- **Prioritization inputs:** `Importance` and `Urgency` (each 1–5, editable via natural-language
  `-i1..-i5` / `-u1..-u5` and the "Gauge" dial `fragment_property_gauge`), `Effort`,
  `EstimateMin`/`EstimateMax` (time-required min/max → `TimeRequiredMin/Max`), `Satisfaction`.
- **Scheduling:** `StartDateTime`, `DueDateTime`, `LeadTime` (how far ahead a task becomes "near
  due"/active), `ScheduleType`.
- **Star / flag / tag:** `IsStarred` + `StarredDateTime` (starred-date is filterable/sortable — see
  `StarToggleDateTime`), `FlagID` (→ Flag entity), plus a free-text **tag** (`-tag`,
  `FILTERED_FIELD_TEXT_TAG`, `fragment_property_text_tag`).
- **To-Do visibility:** `HideInToDo` (hide whole branch from To-Do) vs `HideInToDoThisTask` (hide
  only this task) — two distinct flags (`FILTER_FIELD_HideInToDo`, `GROUP_SORT_HIDE_IN_TODO`).
- **Goal:** `GoalFor` (None / Weekly / Monthly / Yearly — `arrays LABEL_GOAL_*`); goals get their
  own views (`ACTIVE_GOALS_VIEW_TITLE`, `FILTER_FIELD_Goal`, `FILTER_FIELD_GoalMaster`).
- **Review (GTD):** `NextReviewDate`, `LastReviewed`, `ReviewEvery`, `ReviewRecurrenceType`
  (days/weeks/months/years) — a per-task review cadence (`fragment_property_review`,
  `BUTTON_MARK_REVIEWED`, `LABEL_NEVER_REVIEWED`).
- **Dependencies:** `DependOper` (AND/OR combine predecessors), `DependPostpone` (auto-postpone
  start/due while blocked; a.k.a. "Delay" `DEPENDENCY_DELAY`). Links stored in `TodoPredecessors`.
- **Ordering flag:** `CompleteInOrder` (`-o`) — force sequential completion of subtasks; only the
  first incomplete subtask becomes the "next action".
- **Recurrence:** the `Rec*` columns (detail in §3.6 / §6).
- **Sync:** `TaskUID` (stable GUID) + `Ver` (monotonic version for delta sync/merge).

### 3.2 Context (GTD) — `ContextEntityDescription` → table `Places`

```
ContextID (PK), ContextCaption, ContextNoteID, HideFromTodo, HideFromItemProps,
OpenHours (BLOB), ContextUID, Latitude, Longitude, Radius,
NotifyEntering, NotifyExiting, Ver
```

- A **context is a place**: it carries geo coordinates + `Radius` and geofence flags
  `NotifyEntering`/`NotifyExiting` (enter/leave alerts). `OpenHours` BLOB stores open/closed-hours
  schedule (Pro; `ContextOpenHoursActivity`), so a filter can consider whether a context is
  currently "open".
- **Task ↔ Context is many-to-many** via `TodoItemPlaces (TaskID, PlaceID)` (greenDAO join entity
  `ContextToAssignedTaskEntityDescription`). One task can have several contexts; contexts filter the
  To-Do list.
- **Context ↔ Context hierarchy** via `PlaceRelations (PlaceDID, ParentPlaceID)` (greenDAO
  `DependentContextToMasterContextEntityDescription`) — contexts can nest / depend on other
  contexts. Default set: Home, Home Calls, Home Agenda, Office Calls, Office Agenda, Outdoors,
  Errands… (`arrays DEFAULT_CONTEXT`).

### 3.3 Flag — `FlagEntityDescription`

Named, colored, icon-bearing marker assigned 1:1 to a task via `TodoItems.FlagID`. Icons ship in
`assets/flags/` (`flag_red/orange/yellow/green/blue/purple/pink.ico`). Editable
(`FlagEditActivity`, `FlagIconSelectActivity`); default set creatable (`BUTTON_CREATE_DEFAULT_FLAGS`).
Filterable/sortable (`FILTER_FIELD_Flag`, `FlagsTaskFilter`).

### 3.4 Note — `NoteEntityDescription` → table `Notes (NoteID, Note TEXT)`

One note row per task, referenced by `TodoItems.TaskNoteID` (and one per context via
`ContextNoteID`). Notes support **Markdown** (`MarkdownSyntaxActivity`, `BUTTON_PREVIEW_MARKDOWN`),
an in-note action toolbar (insert date/time, clipboard, search, jump), and `@`-contact insertion.

### 3.5 Reminder — `ReminderEntityDescription` → table `Reminders`

```
ReminderID (PK), TodoItemId, Reminder (time), NextAlert,
AutoAlert, AutoAlertDelta, LimitAutoAlertCount, MaxAutoAlertCount, AutoAlertIndex,
ReminderState, AlertAction, Email, AudioFile
```

- Time-based reminder tied to a task. **Auto-repeat**: `AutoAlert` on/off, `AutoAlertDelta`
  (re-alert interval), `MaxAutoAlertCount`/`LimitAutoAlertCount`/`AutoAlertIndex` (how many repeats,
  progress). `AlertAction` selects behavior; `AudioFile` = custom sound; `Email` = email-me action.
  `ReminderState` tracks fired/snoozed/dismissed.
- Reminder can be anchored to None / Start date / Due date / Start&Due (`arrays
  REMINDER_DATE_TIME_TO`).
- **Location reminders** are separate: geofence enter/leave on a context (`Places.NotifyEntering/
  Exiting`, `LocationReminderReceiver`, `NearbyReminders` table, `LocationReminderAlertsActivity`).
- Global default alert profile: sounds, vibration, LED, repeat (`DEFAULT_REMINDERS_ACTION_SUMMARY`,
  `ReminderAlertSettings`). Snooze: fixed "Snooze 5 min" and "Snooze [last period]"
  (`BUTTON_REMINDER_SNOOZE*`).

### 3.6 Recurrence — `RecurrenceEntityDescription` / `RecurrencePatternSettings`

Recurrence is stored **inline on the task** (the `Rec*` columns), not a separate row; the
`RecurrenceEntityDescription` wraps those. Types: None / Hourly / Daily / Weekly / Monthly / Yearly
(`arrays RECURRENCE_TYPE`, `RECURRENCE_TYPE_*`). Pattern fields:
- `RecInterval` ("every N"), `RecDOWMask` (day-of-week bitmask for weekly), `RecInstance` +
  `RecDayOfMonth` + `RecMonthOfYear` (e.g. "second Tuesday of every 3 months" / "day 15 of every K
  months" / "every January 15" — see `RS_RECUR_MSG_*`), `RecHourDelta` (hourly), `RecStartDate`/
  `RecEndDate`/`RecOccurrences` (bounds; `OccurrencesLeft` filterable).
- Regeneration semantics: `RecUseCompletionDate` (next due measured from completion vs schedule),
  `RecUncompleteSubtasks` / `RecUncomplIfCompl` / `RecRecurWSC` (reset subtasks when the branch
  recurs — "Automatic Recurring: When All / When Any Subtask Completed", `LABEL_AUTOMATIC_RECURRING_*`,
  `LABEL_SUB_TASK_RESETTING_*`), `RecDNCCCopy`, `RecGeneratedCount`. Skip controls:
  `BUTTON_RECURRENCE_SKIP`, `…SKIP_ALL_UP_TO_TODAY`, `…SKIP_ONLY_CURRENT`.
- **Advanced patterns are desktop-authored** on the free tier (`ADVANCED_RECURRENCE_EXPLANATION`);
  Pro unlocks full editing on device (`fragment_property_recurrence_advanced/full`).

### 3.7 ColorCoding — `ColorCodingEntityDescription` → table `COLOR_CODING`

Per-task formatting, FK `COLOR_CODING._id → TASK.COLOR_CODING_ID`. Columns: `USE_CUSTOM_COLOR_CODING`,
`FONT`, `SIZE`, `BOLD`, `ITALIC`, `UNDERLINE`, `STRIKETHROUGH`, `FONT_COLOR`, `HIGHLIGHT_COLOR`,
`CHILDREN_INHERIT_COLOR_CODING`, `UNDERLINE_COLOR`, `SIDE_BAR_COLOR`, `BACKGROUND_COLOR1_1/1_2/2_1/2_2`
(gradient stops), `UNDERLINE_ENTIRE_ROW_COLOR/THICKNESS`, `UNDERLINE_DOTTED`,
`BACKGROUND_GRADIENT_TO_CENTER`, `INDENT_ROW_LINE_AND_BACKGROUND`. There are also **auto-format
rules** (rule engine that formats tasks by property; `AUTO_FORMAT_*`, `TaskCellTheme`) layered above
per-task formatting.

### 3.8 Preference — `PreferenceEntityDescription` → table `Preferences (Key TEXT, Value INTEGER)`

Per-profile settings key/value store (distinct from Android SharedPreferences; travels with the
profile and can be included in backup).

### 3.9 GroupStatus — `GroupStatusEntityDescription`

Persists expand/collapse state of grouping headers within a view (inferred from name + `TaskBuncher`
grouping engine and `collapse view groups` Pro feature `ADVANCED_TRIAL_NEW_PACK_FEATURES`). Has a
sync merge policy (`GroupStatusMergePolicy`).

### 3.10 Views & workspaces (subsystem `model/view/*`)

- `ViewEntityDescription` → `Views (ViewId, ViewName, FilterId, SortingId, Grouping,
  Hierarchy, IncludeParents, IncludeChildren, ProcessBranch)` — a saved view = filter + sort +
  grouping + hierarchy behavior.
- `TaskFilter (FilterId, ParentFilterGroupId, FilterCriterion, FilterParams BLOB, isInverse)` —
  recursive AND/OR filter tree (see §5). `TaskSort (SortId, ViewId, Collation1..4, Direction)` —
  up to **4 sort keys**.
- `GroupViewEntityDescription` — folders that group views in the drawer.
- `WorkspaceEntityDescription` — **workspaces**: independent sets of views/UI state
  (`WorkspacesActivity`, `DEFAULT_WORKSPACE_TITLE = "MLO"`).
- `ViewTaskIndexEntityDescription` → `ViewIndexes (ViewID, TodoItemID, ItemIndex, UID, Ver)` — cached
  membership + per-view ordering (so a view's manual order and "don't vanish until refreshed"
  behavior persist). `ManualTaskIndexSetEntityDescription` — manual drag-order sets.
- `ViewUISettingsEntityDescription` — per-view UI (counters, columns).
- `ZoomEntityDescription` → `ZOOM (_id, LAST_USED, TASK_ID, WORKSPACE_ID, OLD_ZOOM_ORDINAL)` — zoom
  (hoist) history so you can re-zoom to a branch (`FEATURE_OLD_ZOOMS_*`, `ZoomListActivity`).

### 3.11 Sync tombstones & conflict model

- `DeletedItemEntityDescription` → `DeletedItems (DeletedUID, ID, ObjectType, DeleteDate, Ver)` —
  **tombstones** so deletions propagate over sync (composite PK `DeletedUID,ObjectType`).
- `ConflictSyncSessionEntityDescription` → `CONFLICT_SYNC_SESSION (_id, SYNC_DATE,
  CONFLICT_RESOLUTION)` — one row per sync that produced conflicts.
- `ConflictEntityEntityDescription` → `CONFLICT_ENTITY (_id, CONFLICT_ENTITY_TYPE, ENTITY_ID,
  CONFLICT_SYNC_SESSION_ID)`.
- `ConflictPropertyEntityDescription` → `CONFLICT_PROPERTY (_id, CONFLICT_ENTITY_TYPE, ENTITY_ID,
  LOCAL_VALUE BLOB, REMOTE_VALUE BLOB, PROPERTY_NAME, CONFLICT_ENTITY_ID)` — **field-level** conflict
  records: keeps both local and remote values so the user can pick per property
  (`CONFLICT_RESOLUTION_USE_LOCAL` / `USE_REMOTE`, `ConflictPropertiesActivity`).
- Merge is per-entity-type: `sync/conflict/{Task,Context,Flag,Recurrence,Reminder,GroupStatus,
  ViewTaskIndex}MergePolicy` on top of greenDAO's `Merge{ByProperties,ByAnnotation,WholeEntity}Policy`.

### 3.12 Auxiliary tables

`Attachments (AttachmentId, TaskId, Uri)` — file/URI attachments per task. `Bookmarks (TodoItemID)` —
bookmarked tasks. `NearbyReminders (ContextID)` — active geofence set. `Preferences`,
`PlaceRelations`, `TodoItemPlaces`, `TodoPredecessors` as above.

### 3.13 Relationship summary

```
Task (TodoItems) ──self ParentItemID──▶ Task            (outline tree, ordered by ItemIndex)
Task ──1:1 TaskNoteID──▶ Note
Task ──1:1 FlagID──▶ Flag
Task ──1:1 COLOR_CODING_ID──▶ ColorCoding
Task ──1:N ReminderID──▶ Reminder
Task ──M:N via TodoItemPlaces──▶ Context (Places)
Task ──M:N via TodoPredecessors (DependOper AND/OR, DependPostpone)──▶ Task   (dependencies)
Context ──M:N via PlaceRelations──▶ Context                                   (context hierarchy)
View ──▶ TaskFilter(tree) + TaskSort(4 keys) + Grouping ; belongs to Workspace ; GroupView folders
View ──cached──▶ ViewIndexes (membership + order)
DeletedItems / CONFLICT_* = sync bookkeeping
```

---

## 4. The computed-priority engine

Class: `net.mylifeorganized.android.model.ComputedScorePriorityType` (+ inner `$a`). Sort option
`SORT_BY_COMPUTED_SCORE_PRIORITY = "Computed-Score"` (index 8 in `arrays SORT_BY`). This is MLO's
signature feature: it collapses the outline into a single automatically ranked **To-Do list**.

**Inputs** (all present as task columns / filter fields): `Importance` (1–5), `Urgency` (1–5),
`DueDateTime` + `LeadTime` (urgency ramps as due approaches), position/depth in the tree, and
parent inheritance. MLO's documented algorithm (consistent with these fields): each task's computed
score blends its own importance with an urgency term that grows as `DueDateTime - now` shrinks
(becoming dominant when overdue), and a task **inherits importance from ancestors** so subtasks of an
important project float up. Higher score = higher in the To-Do list. (Exact weights live in
`ComputedScorePriorityType`, not in resources — inferred from the field set + `LABEL_IMPORTANCE_AND_URGENCY`
gauge + `SORT_BY_COMPUTED_SCORE_PRIORITY`.)

**To-Do list vs Outline — the core distinction:**
- **Outline** = the raw hierarchical tree (all tasks, folders, projects, expandable/collapsible,
  manually ordered by `ItemIndex`). This is the *editing/organizing* surface.
- **To-Do (Active Actions)** = an auto-generated *flat* list of only the tasks that need attention
  right now, ranked by Computed-Score. `INTRO_2_TODO_TEXT`: "MLO automatically prepares a simple
  list of actions ("Active Actions" view) which require your immediate attention."
  `INTRO_3_NEXTACTIONS_TEXT`: "The generated To-Do list is updated automatically once you complete a
  task, change your location or assign new context."

**What makes a task "active" vs merely "available":** filter fields
`FILTERED_FIELD_ACTIVE_ACTION` (ActiveAction), `FILTERED_FIELD_AVAILABLE_ACTION` (AvailableAction),
`FILTERED_FIELD_NEXT_ACTION` (NextAction), and the Active-filter options
`Active / Available / Next Actions / Completed / All` (`ACTIVE_FILTER_OPTION_*`). Rules driving the
To-Do engine:
- A task is excluded if `IsComplete`, or `HideInToDo`/`HideInToDoThisTask`, or it is a folder
  (`IsFolder`), or start date is in the future, or it has incomplete **dependencies**
  (`HasIncompleteDependency` — "hide tasks in the To-Do list until other tasks are completed",
  `MESSAGE_BUY_ACCESS_TO_DEPENDENCY`), or its assigned **context is filtered out / closed**.
- Under `CompleteInOrder`, only the first incomplete leaf of a branch is the "next action".
- `AvailableAction` = leaf actionable now; `ActiveAction` = available AND passes context/next-action
  gating; `ActiveAction (Overdue)` flagged separately (`ACTIVE_ACTION_STATUS_OVERDUE`).

Grouping/branch rollups feed the engine: `HasIncompleteSubtasks`, `HasOverdueSubtasks`,
`HasNeardueSubtasks`, `ProjectCompletionPercent`, `NextAlertTime`.

---

## 5. Views & filtering

**Built-in views** (from `*_VIEW_TITLE` strings): Active Actions, To-Do/Next Actions, Active by
Context, Active by Project, Active by Flag, Active Goals, Active Starred, Goals, Due Next 7 days, Due
Next 30 days, By Next Alert, Completed in Outline, Completed by Context/Project/Flag.

**Custom views** are fully modeled (`Views` + `TaskFilter` + `TaskSort` + grouping):
- **Filter tree** (`AdvancedFilterActivity`, `ConditionActivity`, `ConditionGroupActivity`,
  `TaskFilter.ParentFilterGroupId`, `isInverse`): nestable AND/OR condition groups, each condition
  = field + operator + params. Filterable fields (`FILTER_FIELD_*`): Caption, Complete,
  CompletedDateTime, Contexts / ContextsText, CreatedDateTime, ModifiedDateTime, DueDateTime,
  StartDateTime, Effort, Flag, FolderName, Goal / GoalMaster, HasDependency /
  HasIncompleteDependency, DependencyCounter, HasSubtasks / HasIncompleteSubtasks, HideInToDo,
  Importance / Urgency, IsFolder / IsProject, LastReviewed / NextReview, Notes, OccurrencesLeft,
  ParentName, ProjectCompletionPercent / ProjectName / ProjectStatus, Recurrence, Reminder,
  Starred / StarToggleDateTime, TimeRequiredMin / TimeRequiredMax, TopLevelFolderName /
  TopLevelParentName / TopLevelProjectName, ActiveAction. Operators include text (contains / is
  exactly / is empty…), boolean (is true/false), and rich **date operators** (today, this week/month,
  in next/last X weeks/months, before/after/on/on-or-after, does not exist — `DATE_FILTER_*`).
- **Context filter** with open/closed-hours awareness (`CONTEXT_FILTER_CONDITION_CONTAINS_CONSIDER_
  OPEN_CLOSED`), **Flag filter** (`FlagsFilterActivity`).
- **Sort**: up to 4 keys × direction (`TaskSort.Collation1..4`), from `arrays SORT_BY`: Completed
  Date, Importance, Due Date, Caption, Urgency, Modified, Created, **Computed-Score**, Start,
  Next Alert, Goal, Starred / Starred Date, Next Review, Effort, Time Required Min/Max, Project
  Status, Top-Level Parent, Project, Recurrence, Flag, Text Tag, Path, Folder, Project Completion %.
- **Group by** (`TaskBuncher`, `arrays GROUP_TASK_BY`): Completed Date, Context, Due Date, Hide in
  To-Do, Is Folder, Modified Date, Starred, Starred Date, Start Date, Next Review Date, Time
  Required Min/Max.
- **Hierarchy behavior per view** (`Views.Hierarchy / IncludeParents / IncludeChildren /
  ProcessBranch`): show as flat list or keep ancestors; include/exclude children; process whole
  branch.
- **UI filters** (`UI_FILTER_*`, `Completed Recently / Flags / Effort / References / Start Date /
  Time`) — note `UI_FILTER_EXPLANATION`: these are **desktop-only configurable** and merely honored
  on mobile.

**Navigation of the outline:** Zoom-In/Out (hoist to a branch), collapse/expand all, zoom history
(`ZOOM` table), star, hoist-by-project. Search: dedicated searchable activity
(`SearchResultsActivity`, `res/xml/searchable.xml`, `SearchTaskFilter`, `TextFilterPanel`).

---

## 6. Scheduling

- **Dates:** `StartDateTime`, `DueDateTime`, `LeadTime` (lead/near-due window). Date pickers offer
  scrollable-list or calendar mode (`LABEL_SELECT_DATE_WITH_CALENDAR`, Samsung calendar workaround
  `MESSAGE_ABOUT_POSSIBLE_RECOVERY_CALENDAR`). Quick-pick offsets (+1 day/week, +N hours…) via
  `arrays QUICK_PICK_*`. "Add to calendar…" pushes a task to the device calendar
  (`CREATE_CALENDAR_EVENT`).
- **Recurrence:** see §3.6 — None/Hourly/Daily/Weekly/Monthly/Yearly with interval, day-of-week
  mask, nth-weekday-of-month, day-of-month, month-of-year, completion-date-based regeneration,
  subtask reset options, occurrence limits, skip-occurrence controls. Advanced patterns behind Pro.
- **Reminders:** multiple per task, exact-alarm scheduled; auto-repeat with count/interval; custom
  sound/vibration/LED; email action; snooze (5 min or last period); anchor to start/due
  (`REMINDER_DATE_TIME_TO`). Persistent-notification and quick-settings tiles for add-reminder
  (`QSSecondTileService`).
- **Location reminders:** geofence enter/leave on a context's lat/long/radius
  (`Places.NotifyEntering/Exiting`, `NearbyService`, `LocationReminderReceiver`, `NearbyReminders`,
  `NEARBY_SETTINGS_TITLE`, `ENTER_INTO_CONTEXT_NOTIFICATION_MESSAGE = "Arrive %..."`). "Tasks nearby
  on map" is a Pro feature.
- **Review scheduling:** independent review cadence per task (`ReviewEvery` + `ReviewRecurrenceType`).

---

## 7. Feature inventory

**Capture**
- Natural-language **Input Parser** (`REGISTRATION_PARSER_FEATURE_*`): free text →
  structured task. Keywords/keys: `-i`/importance, `-u`/urgency, `-d`/`-due`, `-s`/`-start`,
  `-e`/effort, `-t`/`-tm` time-required, `-every`/`-rec` recurrence, `reminder;remind me;rmd`,
  `-fl` flag, `-go` goal, `-star`, `-tag`, `-h` hide, `-f` folder, `-p` project, `-o` complete-in-order,
  `-to`/`-toprj`/`-tofld` parent placement, `context;cont`, weekday/today/tomorrow, N-day/week/month
  suffixes (`PARSER_KEYWORD_*`, `PARSER_KEY_*`).
- Add to **Inbox** quick-capture; voice capture (`TASK_RECOGNIZE_SPEECH[_WITH_PARSING]`); share-to-MLO
  from other apps (`SharedReceiverActivity`, `ExternalActionActivity` handles `SEND` / `CREATE_NOTE`);
  home-screen shortcuts, quick-settings tiles, and widget add-task.
- Templates: create task from template (`CREATE_TASK_FROM_TEMPLATE`, `assets/templates/*.mlt`:
  GTD, GTD+FranklinCovey Roles, FranklinCovey, ControlJournal, GTD Zoom4 Focused Action, GTD
  Beginners Action, Do It Tomorrow — in en/de/ru).

**Organize**
- Unlimited **outline** tree, folders & projects, drag/indent, collapse/expand, zoom (hoist) with
  history, cut/copy/paste branches, multiselect, undo/redo (Pro).
- GTD **contexts** (places, M:N, hierarchy, open/closed hours), **flags** (colored/iconed),
  free-text **tags**, per-task **notes** (Markdown, contact `@`, in-note toolbar), **attachments**
  (URIs), **bookmarks**.
- **Workspaces** (independent view sets), custom views in view-folders.

**Prioritize**
- Importance + Urgency dials, **Computed-Score** ranked To-Do, dependencies (AND/OR, postpone),
  complete-in-order, effort/estimate, goals (weekly/monthly/yearly), starred.

**Schedule**
- Start/due/lead, rich recurrence, multiple reminders (repeat, sound, LED, email, snooze),
  location/geofence reminders, calendar overlay + push to calendar.

**Review & track**
- GTD **Review** cadence per task, **Statistics** (created/completed/modified/deleted counts,
  assigned contexts/flags, folders — `STATISTICS_*`), **Today** view (tasks + calendar events +
  weekly workload chart), project completion %.

**Customize**
- Per-task color coding + auto-format rule engine, themes/dark mode (`SwitchThemeSettingsActivity`),
  configurable swipe gestures, configurable toolbars/menus (main menu, context menu, task-property
  menu, multiselect, promoted action, persistent-notification actions, app shortcuts), counters/badges
  on views, emoji replacement, language switch, quick-date selection, **triggers & actions**
  (event→action automation, e.g. play sound when a goal completes — `ACTION_*`, Pro).
- **Widgets** (dynamic list widget with Today chart, flags-in-widget, per-view), **quick-settings
  tiles**, **app-lock** (passcode + biometric per profile), multiple **profiles**.

---

## 8. UI/UX & screens

- **Entry:** `StartActivity` (launcher) → `MainActivity` (phone) / `MainActivityTablet`
  (two-pane; tablet UI mode toggle `TabletUIModeSettingsActivity`). Multi-window/Samsung multiwindow
  aware.
- **Main screen:** left drawer of workspaces → view-folders → views (`fragment_main_menu`,
  `item_main_menu_view_list_type_0..3`, counters `fragment_counters`); center = outline/task list;
  action bar with dates/sync/search/workspace custom views. Bottom toolbar is user-configurable.
- **Task editor** (`fragment_edit_task` / `fragment_edit_task_total`; preview
  `fragment_preview_task`) is organized as property panels rather than tabs:
  `fragment_property_start_and_due`, `_reminder`, `_recurrence_{hourly,daily,weekly,monthly,yearly,
  advanced,full}`, `_review`, `_contexts`, `_dependencies` / `_add_dependencies`, `_goal`, `_project`,
  `_time_required`, `_gauge` (importance/urgency), `_format` (color coding), `_notes`, `_text_tag`.
  Which properties appear and their order are configurable
  (`EditTaskPropertiesMenuSettingsActivity`, "Pin More properties" `CONTEXT_MENU_PIN_MORE_BUTTON`).
- **Gestures:** configurable left/right swipe actions with priority action + vibration feedback
  (`SwipeActionSettings`, `SWIPE_*`); long-press context menu (configurable); physical-keyboard
  shortcuts (`REFERENCE_KEYBOARD_*`, F1 help).
- **Dialogs/activities of note:** `ReminderDialog`, `AddToInboxActivity`, `SelectTaskActivity`
  (dependency/parent picker), `ConflictPropertiesActivity`, `WorkspacesActivity`, `ZoomListActivity`,
  `CloudFilesListActivity`/`CloudFileSharingActivity` (cloud file collaboration), `ProductTour*`.
- **Themes/app-lock:** light/dark/theme switch, appearance settings (fonts, cell theme, note length
  mode), profile passcode + biometric unlock, "Lock Profile".
- Free vs **Pro** gating is pervasive (`MESSAGE_BUY_ACCESS_TO_*`): dependencies, WiFi sync,
  undo/redo, workspaces, review, complex recurrence, location alerts, widgets/shortcuts,
  sharing, custom reminders, import views are Pro.

---

## 9. Import / Export / Backup

**Supported formats (exhaustive — grepped for opml/csv/xml/ml/mlo/webdav/dropbox/wifi):**

| Artifact | Extension | Direction | Notes |
|---|---|---|---|
| Profile backup | **`.mlobak`** | export + restore | ZIP (Apache Commons Compress) of the profile's CSV entity sections + optional settings; can be password-encrypted (`BACKUP_IMPORT_BAD_PASSWORD_OR_ARCHIVE`). Restore via `RestoreProfileActivity` (registered file/content intent for `*.mlobak`, mime `application/zip`). Backup scope options: Tasks / Tasks+Contexts / Tasks+Contexts+Flags, plus Views, Triggers&Actions, Auto-format rules, Settings (`BACKUP_*_OPTION`). Can email the backup. |
| Views | **`.mfv`** (`_Views.mfv`) | import (+export views) | `ImportViewsActivity` handles `*.mfv` / `application/xml`. Import custom views from desktop (Pro). `BUTTON_EXPORT_VIEWS`. |
| Template | **`.mlt`** (`_template.mlt`) | import (bundled) + create | `CreateTemplateFromProfileSettingsActivity` creates a template from a profile; bundled templates in `assets/templates`. |
| Sync payload | internal `.ml` / CSV | sync only | **Not a user file.** The cloud/wifi/autosync transport is a **versioned multi-section CSV** (`SYNC send to server CSV for version greater than %d`, `TaskCSVSection WRITE Entity Recurrence property …`). Each entity type is a CSV section; `Ver` columns drive delta sync; `DeletedItems` carry tombstones. |
| Single task | — | share out/in | Share a task as text/link to Email, Calendar, other apps and back (`SHARING_OPTIONS`, `LiNK_TO_TASK_*`, Pro). "Add to calendar…" creates a calendar event. |
| Settings / Triggers / Auto-formats | export files | import/export | Separate export/import of settings, triggers&actions, auto-format rules (`EXPORT_*`). |

**Explicitly NOT supported:** OPML, user-facing CSV, plain-text outline export, WebDAV, Dropbox.
(The only CSV is the internal sync/backup encoding; "tasks.csv" is a sync section name, gated behind
"SYNC Doesn't send CSV because it's empty".) Interop is **within the MLO ecosystem only** (desktop
`.ml`, cloud, wifi).

**Round-trip fidelity implications for our lossless goal:**
- MLO itself is lossless only across MLO surfaces because it moves the *entire* entity graph with
  stable `TaskUID`/`ContextUID` + `Ver` + tombstones + field-level conflict capture. To be "lossless"
  our app must persist and export **every** field in §3 (not just title/dates) — importance, urgency,
  effort, estimate min/max, both hide-in-todo flags, all `Rec*` recurrence fields, dependency
  operator + postpone, review cadence, goal, project status/completion, star + star-date, flag, tag,
  color-coding, contexts (with geo + open-hours), notes, reminders (with auto-alert params),
  attachments, per-view membership/order.
- There is **no open interchange** to reuse. To import from MLO we must parse either the `.mlobak`
  ZIP (multi-section CSV) or the desktop `.ml` SQLite — the table/column layout in §3 is the map.
  Recommendation: adopt an **open** export (JSON or SQLite mirroring these tables) plus a `.mlobak`
  importer.

---

## 10. Strengths & weaknesses

**Strengths (what MLO does better than almost anyone):**
1. **Computed-Score To-Do engine** — turns a big outline into one honest, self-updating "do this
   next" list from importance × urgency × due proximity × hierarchy inheritance. Very few apps do
   real automatic prioritization.
2. **Outline depth + To-Do duality** — unlimited nesting with folders/projects, yet a flat ranked
   action list; zoom/hoist with history.
3. **True GTD contexts** as first-class, many-to-many, hierarchical, location-aware places with
   open/closed hours, feeding availability.
4. **Dependencies** that actually suppress blocked tasks from the To-Do list (AND/OR + auto-postpone).
5. **Deep, composable views** — nested AND/OR filters over ~35 fields, 4-key sort, grouping, per-view
   hierarchy, saved into workspaces; lossless multi-device sync with field-level conflict resolution.
6. **Natural-language capture parser** and rich recurrence/reminder (including geofenced) model.

**Weaknesses / annoyances:**
1. **Dated, dense UI** — action-bar/drawer paradigm, many nested settings activities
   (`AppTheme.Popup` everywhere), lots of modal dialogs; steep learning curve. Power is buried in
   long configurable menus.
2. **Desktop-tethered power features** — advanced recurrence, UI filters, open-hours authoring, and
   view creation are "do it on desktop then sync" on the free/mobile tier
   (`ADVANCED_RECURRENCE_EXPLANATION`, `UI_FILTER_EXPLANATION`, `MESSAGE_BUY_ACCESS_TO_IMPORT_VIEWS`).
3. **Closed, no-open-export ecosystem** — no OPML/CSV/Markdown/JSON out; you're locked into `.mlobak`
   / cloud / wifi. Painful for backup-portability and migration.
4. **Pro paywall fragmentation** — core-feeling features (dependencies, undo/redo, workspaces,
   widgets, review, wifi sync) are Pro; multiple "new Pro pack" tiers add confusion.
5. **Sync operational friction** — autosync can self-disable after a crash
   (`AUTOSYNC_WAS_DISABLED_DUE_TO_CRASH`), "too many tasks" warnings push manual archiving, conflicts
   surface as a manual property-by-property resolution list.
6. **Firebase/Crashlytics/Analytics present** — counter to a strictly private/offline promise (our
   app should drop these).

---

## 11. Takeaways for OUR app

**Replicate exactly (the core that makes MLO MLO):**
- The **Task** entity with the full field set in §3.1. Do not trim importance/urgency/effort/
  estimate-min-max/lead-time/both hide-in-todo flags/goal/project-status+completion/review fields/
  dependency-operator+postpone/star+star-date/tag/complete-in-order.
- **Outline tree** (`parentId` + explicit `sortIndex` for sibling order; `isExpanded`;
  isFolder/isProject) AND a derived **To-Do (Active Actions)** projection.
- **Computed-Score prioritization**: score = f(importance, inherited-ancestor-importance, urgency,
  due-proximity via lead-time, overdue). Recompute reactively on complete / context change /
  location change. Expose it as the default To-Do sort.
- **Contexts** as M:N, hierarchical, optionally geo (lat/long/radius, enter/leave) with open-hours;
  availability gating of the To-Do list.
- **Dependencies** (task→task, AND/OR, postpone) that hide blocked tasks.
- **Views** = filter-tree (nestable AND/OR over the §5 field list, invertible conditions) + up to
  4 sort keys + grouping + per-view hierarchy flags; grouped into **workspaces**; persisted
  membership/manual order.
- **Recurrence** covering all `Rec*` semantics (interval, DOW mask, nth-weekday, day-of-month,
  month-of-year, completion-date regeneration, subtask reset, occurrence limits, skip).
- **Reminders**: multiple/task, auto-repeat (count+interval), custom sound/vibration, snooze,
  start/due anchoring, plus geofenced location reminders.
- **Flags, tags, Markdown notes, attachments, color-coding + auto-format rules, GTD review cadence,
  goals, statistics, Today view, templates, natural-language capture parser.**

**Improve on MLO:**
- **Open, lossless export/import**: ship JSON and/or a documented SQLite schema mirroring these
  entities, plus OPML/Markdown for interop and a `.mlobak` importer. Make full-fidelity export a
  first-class, non-paywalled feature.
- **No paywall on core** and **no cloud/analytics by default** — fully offline, no account; sync
  optional and end-to-end. Drop Firebase/Crashlytics.
- **Modern Compose UI**: single adaptive layout, bottom-sheet task editor with the same property
  panels but progressive disclosure; author advanced recurrence/views **on device** (remove the
  desktop tether).
- **Robust sync design from day one**: stable UUIDs + monotonic per-entity version + tombstones +
  field-level conflict capture (mirror `CONFLICT_PROPERTY`'s local/remote-value model) — but
  auto-resolve most conflicts and only surface genuine ones.

**Schema/field checklist (minimum for lossless parity):** Task(id/uuid, parentId, sortIndex, title,
isComplete, completedAt, isStarred, starredAt, importance, urgency, effort, estimateMin, estimateMax,
satisfaction, hideInToDo, hideInToDoThisTask, completeInOrder, startAt, dueAt, leadTime, scheduleType,
goalFor, isFolder, isProject, projectStatus, projectCompletion, flagId, tag, noteId, colorCodingId,
nextReviewAt, lastReviewedAt, reviewEvery, reviewRecurrenceType, dependOperator, dependPostpone,
recType, recInterval, recDowMask, recDayOfMonth, recMonthOfYear, recInstance, recHourDelta,
recStartDate, recEndDate, recOccurrences, recUseCompletionDate, recUncompleteSubtasks, recResetFlags,
createdAt, modifiedAt, isExpanded, ver) · Context(id/uuid, caption, noteId, hideFromTodo,
hideFromItemProps, openHours, lat, long, radius, notifyEntering, notifyExiting, ver) ·
Flag · Note · Reminder(taskId, time, nextAlert, autoAlert, autoAlertDelta, maxCount, state,
alertAction, email, audioFile) · ColorCoding(full attribute set) · joins: TaskContext(M:N),
TaskDependency(predecessor, operator, delay), ContextRelation(parent) · Attachment(taskId, uri) ·
View + Filter(tree) + Sort(4 keys) + Grouping + Workspace + ViewIndex · DeletedItem(uuid, type,
deletedAt, ver) · Conflict(entity/property, local, remote).
