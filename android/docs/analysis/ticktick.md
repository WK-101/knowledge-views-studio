# TickTick — Reverse-Engineering Analysis

> Evidence-backed teardown of the TickTick Android APK, produced to guide a **new, fully-offline, account-free, native Kotlin/Jetpack Compose** task app that wants TickTick's UI/UX polish and feature breadth on top of a stronger outliner core.
>
> Sources: apktool-decoded resources (`dec/ticktick/`), `AndroidManifest.xml`, `res/values/strings.xml` (~5,877 lines), `res/xml/*` preference screens, `res/layout/*` (1,360 layouts), `res/values/arrays.xml`, decoded `assets/`, and DEX class descriptors (`tt_classes.txt`, 13,518 unique `com.ticktick.task.*` classes). Where a claim is inferred rather than directly stated, it is flagged `inferred from <evidence>`.

---

## 1. Identity & Build

| Property | Value | Evidence |
|---|---|---|
| Package | `com.ticktick.task` | `aapt dump badging` |
| App label | TickTick | `application-label:'TickTick'` |
| Version | `8.1.2.0` (versionCode `8120`) | `apktool.yml`, badging |
| minSdk / targetSdk / compileSdk | **21 / 36 / 36** (Android 5.0 → 16) | `apktool.yml`, badging |
| APK size | ~41 MB (`ticktick.apk` = 41,008,105 bytes) | filesystem |
| Native ABI | **arm64-v8a only** | `native-code: 'arm64-v8a'` |
| DEX | 6 classes*.dex (multidex, ~42 MB uncompressed) | filesystem |
| Obfuscation | R8/ProGuard partial — package tree `com.ticktick.task.*` retains readable names; leaf helpers renamed to `a`, `b`, `p$c`, etc. Entities/DAOs/Activities are **not** obfuscated. | `tt_classes.txt` |

**Sister app / white-label.** Assets include a full `dida365/` tree and many `..._dida` strings (`invite_you_to_join_dida`, `guide_to_download_dida`). "Dida" (滴答清单) is the Chinese-market twin of the same codebase; the build ships both brand skins. `project_id` = `appest.com:ticktick`.

**Native libraries** (`lib/arm64-v8a/`):
- `libnative_parser.so` (2.1 MB) — the **natural-language date/time parser** for quick-add (see §4). Confirmed by the `com.ticktick.task.ai`/`quickadd` packages and `smart_date_parsing_tips`.
- `libmmkv.so` — Tencent **MMKV** key-value store (fast prefs/config persistence).
- `libbugsnag-ndk.so`, `libbugsnag-plugin-android-anr.so`, `libbugsnag-root-detection.so` — **Bugsnag** crash/ANR/root reporting.
- `libsecuritychecknativelib.so` — anti-tamper / signature check (`com.ticktick.task.securitychecknativelib`).

**Key third-party SDKs** (from `AndroidManifest.xml` meta-data + component names):
- **Google Play Billing** (`com.android.billingclient.*`, `ProxyBillingActivityV2`, `BillingOverrideService`) — subscription paywall.
- **Firebase** full stack: Messaging (push), Crashlytics, Performance, Remote Config, Installations, Analytics connector, **ML Kit** (`CommonComponentRegistrar` — likely for image/text scanning; see "Scan Documents").
- **Google Analytics** (`AnalyticsService`, `CampaignTrackingService`) + **Ad Services** (AD_ID, attribution).
- **Facebook SDK** (`com.facebook.sdk.ApplicationId`) — social login / share.
- **Google Maps v2** (`com.google.android.maps.v2.API_KEY`, `com.ticktick.task.location`) — location reminders / map picker.
- **Lottie** — animation JSONs throughout `assets/` (see §4).
- greenDAO ORM (`com.ticktick.task.greendao.*`, 100+ `*Dao` classes) over SQLite.
- Protobuf (`google/protobuf/*.proto` in `unknownFiles`).

**This is a cloud/account app.** Sign-in is central: `sync_with_ticktick_com` ("Sign in now"), `newbie_show_login_in_toast` ("Data can be permanently stored in Cloud account"), `MANAGE_ACCOUNTS`/`GET_ACCOUNTS` permissions, `sync/` package (973 classes), and — critically — **archived tasks live only in the cloud**: `search_empty_info` ("Tasks archived long ago will be saved in cloud server… Please try to search on the web"), `search_in_cloud` "CLOUD SEARCH". Local-only use is possible but degraded and constantly nudged toward login. This is the single biggest divergence from our offline/no-account goal.

---

## 2. Feature Inventory (comprehensive)

### 2.1 Capture / Quick-add
- **Quick-add bar** with inline **natural-language parsing** (`libnative_parser.so`). Recognizes **date & time**, **`#`tags**, **URLs**, and can optionally strip the parsed text from the title (`preference_smart_date_parse.xml`: `prefkey_enable_date_parsing`, `prefkey_remove_text_in_tasks`, `prefkey_remove_tag_in_tasks`, `prefkey_url_parsing`). `smart_date_parsing_tips`: "…date & time information will be automatically parsed as a reminder."
- **Quick-add defaults** the box pre-fills (from `quickadd/defaults/`): `TitleDefault`, `ContentDefault`, `DueDataDefault`, `PriorityDefault`, `ProjectDefault`, `TagsDefault`, `ColumnDefault`, `AssignDefault`, `ParentDefault`, `PinDefault`, `PositionDefault` (`TopBottomDefault`), `NoteDefault`, `TaskKindDefault`. i.e. quick-add can set list, priority, tags, section, assignee, parent, pin, and where in the list it lands.
- **Full-screen quick-add** and **template insertion** (`quickadd_full_screen`, `quickadd_more_tooltip`: "Template, Full-Screen moved here").
- Off-screen capture surfaces: **Quick Ball** floating bubble (`quick_ball`, premium), **home-screen Quick-Add widget** (`widget_quick_add`), **notification-bar quick add / status bar** (`prefkey_notification_ongoing`, `status_bar_display_type`), **add-from-clipboard** (`prefkey_add_via_clipboard`), **text-selection "Process Text"** action (`ProcessTextActivity`, `prefkey_process_text`), voice input, `QuickAddActivity`.
- **AI capture** (`com.ticktick.task.ai`, 81 classes): "Break Down into Subtask" (`ai_break_into_subtasks`), "Completion Suggestion" (`ai_complete`), daily "Important Tasks" recommendations (`ai_recommend_key_tasks`). Token-metered (`ai_error_token_usage_exceed_limit`).

### 2.2 Organize
- **Lists** (called "Lists" in UI, `Project` internally). Special/system lists: **Inbox** (`project_name_inbox`), **Today**, **Next 7 Days** (`project_name_week`), **Assigned to Me** (`assigned_to_me_list_label`), **Tags**, **Completed**, **Won't Do** / "Abandoned" (`project_name_abandoned`), **Trash**. `SpecialProject` entity.
- **Folders** (a.k.a. "list groups" — `ProjectGroup`, `list_group` = "Folder", `list_group_add_new_fold` "New Folder"). One-level grouping of lists.
- **Tags** (`Tag`, hierarchical — `TagDao` + `move_to_personal_tags`; tags can have sort order `TagSortType`). `#`-prefixed, colored, and support nesting (parent tags — inferred from `move_to_personal_tags` and tag tree UI).
- **Smart Lists / Filters** (`Filter` entity, `filter/` = 238 classes). Custom saved filters combining conditions: keywords, **Lists**, **Tags**, **Assignee**, **Priority**, **Date**, **Task type**, **Repeat**, with **AND/OR logic** (`filter_conditions`, `logic`, `filter_include`, `filter_task_type`). Custom Smart Lists are **premium** (`pro_custom_smart_list`).
- **List customization**: per-list color (`list_color`), emoji icon (`emoji_title_add` "Select Icon"), and **List Background** — none / color / gradient / image (`list_background_*`, entities `ColorProjectBackground`, `GradientProjectBackground`, `ImageProjectBackground`).
- **Archive list** (`project_close_warn_dialog` → "Archived Lists" folder) vs **Delete**.

### 2.3 Views (per-list, switchable)
`list_view_type` / `view_mode`. Available modes:
- **List View** (`list_mode`, default) — grouped, sortable.
- **Kanban / Board** (`kanban_mode`, `kanban_view`, `Column` entity, `ColumnDao`) — sections as columns, drag cards between them (`drag_here_to_change_column`, `move_to_column`).
- **Timeline** (`timeline_view`, `TimelineTipView`, `timeline/` = 54 classes) — Gantt-like horizontal schedule; not available for note lists (`note_list_not_support_timeline`). **Premium**.
- **Calendar / Agenda** (`CalendarViewActivity`, `calendar/` package): Day, **3-Day**, Week, Month, **Year**, and **Agenda** list styles. Drag tasks onto the calendar to schedule (`drag_schedule_calendar_tips`, `drag_onto the calendar`). Multi-day / Month / Daily-timeline calendar views are **premium** (`pro_monthly_calendar_view`, `pro_daily_calendar_view`, `pro_three_day_calendar_view`).
- **Eisenhower Matrix** (`eisenhower_matrix`, `matrix/` package, `MatrixActivity`) — four quadrants (Urgent&Important … Not-urgent&Not-important, `important_urgent`, `matrix_empty_tip_0..3`); rule-driven auto-categorization; multiple saved matrix "Views" (`matrix_manage_views`). **Premium** (`pro_feature_description_matrix`).

### 2.4 Scheduling
- **Dates**: start date, **due date**, and **task duration / time span** (`DueData` entity, `pro_title_task_duration`, `pro_desc_task_duration` "Meeting from 8 to 9 o'clock…"). All-day vs timed (`task_default_reminder_mode` = Due Time / All Day).
- **Recurrence** (RRULE-style; `RepeatInstance`, `RepeatInstanceDao`): None, Daily, Every Weekday (Mon-Fri), Weekly, Monthly (by date **or** by nth-weekday), Yearly, **Yearly Lunar** (`g_repeats` array). "Repeat by": Each / On the… / Workday (`repeat_by`). **Repeat-from**: Due Date / Completion Date / Optional Date (`repeat_from_name`). Ends: never / after N times / on date (`repeat_end_count`, `repeat_end_date`). **Lunar repeat** supported (`lunar_repeat`, `monthly_on_lunar`). Skip/complete-all across occurrences (`repeat_skip_all`, `repeat_complete_all`, `repeat_all_instance`, `repeat_from_now_instance`).
- **Reminders** (`Reminder`, `TaskReminder`, `ChecklistReminder`, `HabitReminder`, `CountdownReminder`, `LocationReminder`): multiple per task (**up to 5 — premium**, `pro_more_reminders`); relative offsets ("%1$s early"), at-due, at-end-time (`reminder_at_the_end`). Per-checklist-item reminders (**premium**, `pro_desc_check_item_reminder`). Reminders **for subtasks** = premium (`pro_reminder_for_sub_tasks`).
- **"Annoying Alert"** (`annoying_alert`) — persistent alarm that re-rings: "the alarm will continue to play for a minute and will ring again after two minutes if not handled." **Premium-gated** (`annoying_alert_close_no_pro_user_ensure_msg`).
- **Location reminders** (`LocationReminder`, `FavLocation`): remind on **arrive** / **leave** a place (`location_arrive_remind`, `location_leave_remind`), geofence-based, Google Maps picker.

### 2.5 Prioritization, sort & manual order
- **Priority**: High / Medium / Low / None (`pick_priority_name`, `priority_label_ticktick`).
- **Sort by**: Custom (manual), Time/Date, Priority, Title, Tag, List, Assignee, Created Time, Modified Time (`sort_by_*`), with **ascending/descending** direction (`sort_direction_title`, `sort_rule_*`).
- **Group by** (`group_by`, `group_sort`): e.g. by date, priority, list, tag, or **custom Sections** (`section`, `Column`).
- **Manual order** persisted per context via dedicated tables: `TaskSortOrderInDate`, `TaskSortOrderInList`, `TaskSortOrderInPriority`, `TaskSortOrderInTag`, `TaskSortOrderInPinned`, `SortOrderInSection`. Drag-to-reorder (see §4). **Pin** to top (`PinDefault`, `TaskSortOrderInPinned`).

### 2.6 Focus / Pomodoro (`focus/` = 178 classes)
- Two modes: **Pomodoro timer** and **Stopwatch** (`pomo_timer`, `stopwatch`). `Pomodoro`, `PomodoroConfig`, `PomodoroSummary`, `Timer` entities; `PomodoroActivity`, `StopwatchFinishActivity`.
- Configurable Pomo duration, **Short/Long break**, pomos-per-long-break, **auto-start of break / next pomo** (`preference_pomodoro`).
- **Estimate**: estimated Pomo count and estimated duration per task (`estimated_pomo`, `estimated_duration`; **premium** `pro_estimate_duration`). Compares actual vs estimate.
- **White noise / focus ambience** — 17 sounds: Rain, Forest, Campfire, Drizzle, Storm, Stream, Wave, Seagull, Spring, Chirp, Clock, Windbell, Wooden fish, Biscuit, Lava, Timer, None (`sound_*`). **Premium** (`pro_desc_premium_exclusives_2`).
- **Strict Focus Mode + Allowlist** (`pomo_focus_mode`, `pomo_white_list_*`): leaving the app (to a non-allowlisted app) abandons the pomo; uses **Usage Access** permission.
- **Flip-to-start** (`flip_start`), **Floating window** timer (`focus_floating_window`, premium), **Auto full-screen**, **cross-device focus sync** (`focus_auto_sync`), **anti-burn-in**.
- **Focus notes** per session (`focus_note`), manual record entry, **Focus Records** timeline, and **Study Room** — a shared/social focus room (`study_room`, `invite_friends_to_join_study_room`).

### 2.7 Habits (`habit/` icons + `Habit`,`HabitCheckIn`,`HabitRecord`,`HabitConfig`,`HabitSection`)
- **Boolean** ("Achieve it all") or **quantitative** goals ("Reach a certain amount", `goal_value_unit` "%1$s %2$s daily") with custom units.
- **Streaks** (`habit_best_streak` "Best Streak", `statistics_best_streak`), **goal cycles** (`goal_days_description` — e.g. 7-day cycles yielding achievements), **skip** (`habit_checkin_skip`), archive.
- **Annual heatmap** of check-ins (`pro_annual_heatmap`, **premium**), habit sections, sort-by-check-in-status, show-in-Today/Next-7-days, app-badge count. ~40 preset habits with dedicated Lottie animations (`assets/habit_animations/*.json.zip`: drink_water, meditation, exercise, reading, …).

### 2.8 Statistics & Gamification
- **Achievement Score** + **Levels** (`achievement_score`, `achievement_level` "LV.%1$d"), "More productive than %1$s of users" (`achievement_more_diligent`), shareable awards (`achievement_check_awards`).
- **Trends** over time: weekly/monthly completion rate (`statistics_month_completion`, `statistics_weekly`), historical statistics (**premium** `pro_history_statistics`).
- **Yearly Report** (`assets/yearly_report/`, `com.ticktick.task.annualreport`) — animated year-in-review.
- `HistoricalStatisticsData`, `RecentStatisticsData`, `RankInfo` entities.

### 2.9 Notes & Attachments
- **Task ↔ Note duality**: any list can be a **Note list**; tasks convert to notes and back (`convert_to_note`/`convert_to_task`, `TaskKindDefault`). Notes are prioritization-exempt (`note_move_fail`).
- **Rich text / Markdown** editing, **checklist items** inside a task, `note_content_hint`.
- **Note templates** built in: Meeting Note, Reading Note, Weekly Review (`note_template_*`).
- **Attachments** (`Attachment`, `AttachmentDao`): **Photo**, **Records** (voice, `soundrecorder/`), **Scan Documents** (ML Kit doc scan), and other files (`attach_choice_*`). Daily upload cap; **99/day is premium** (`pro_more_attachments`).

### 2.10 Widgets (26 configurable types, `res/xml/ticktick_appwidget_info_*`)
Standard task list, Compact, 4x4, Grid/Month, Week, 3-Day/Timeline, Today-Calendar, **Quick-Add**, **Pomo timer** (start focus from home screen), **Eisenhower Matrix**, **Habit** (today/week/month/progress/single), **Countdown** (single + list), **Focus distribution** (2x2/4x2/4x4), **Task-completion**, **Course/timetable**, **Undone count**. Calendar & Pomo widgets are **premium**.

### 2.11 Collaboration / Sharing (`share/` = 140 classes)
- **Shared lists** with members (`Team`, `TeamMember`, `Assignment`, `Attendee` entities). Invite by email / phone / link / contacts / WeChat / team (`invite_*`).
- **Assignees** (`assign_to`, `Assignment`), **per-task/list Comments** (`Comment`, `TaskCommentActivity`) with **@mentions** and replies (`notification_comment_mention`).
- **Permissions**: Can Edit / Can Comment (`permission_can_edit`, `permission_can_comment`), owner approval to join.
- **Agendas** (`agenda_*`) — shared meeting entries with attendees.
- Member counts are **premium-scaled** (`pro_more_sharing_members`).

### 2.12 Integrations, Import & Calendar Sync
- **Two-way calendar**: **Google Calendar** (`calendar_connect_integration_with_google_calendar`), **Outlook** (`sync_with_outlook_calendar`), **CalDAV** (`caldav_*`, `BindCalDavAccountsActivity`). Subscribe to external calendars incl. **iCal .ics URL subscription** (`ics_tip`).
- **Notion** two-way DB sync (`notion_integrate`, `detail_list_item_notion*` layouts). **Premium** (`pro_connect_to_notion_upgrade_summary`).
- **Import** on-device from: **Google Tasks / GTasks**, **Any.DO**, **Wunderlist**, **Todoist** (`import_gtasks_title`, `pref_title_import_todoist`, `import_from_wunderlist_hint`, `dialog_title_import_anydo`). Also **holidays** and **contact birthdays** import (`import_holiday`, `import_birthday`).
- **Course timetable** for students (`course_*`, `TimetableDao`, `CourseDetailDao`): import a class schedule from **image OCR** or **school portal** (`import_timetable_by_image_orc`, `import_timetable_by_school_website_parse`), overlaid on the calendar.
- **Export**: **no first-class local export** on Android — only **Print** (`print`) and **Save-as-image** share (`save_to_gallery`). Full CSV/backup export is **web-only** (see §6).

### 2.13 Countdown (`Countdown`, `CountdownDao`)
Anniversary/countdown tracker (birthdays, exams, deadlines) with day/week/month/year display units, backgrounds, recommendations, and dedicated widgets.

### 2.14 Wearables & Reach
- **Wear OS / Android Wear** (`wear/` = 205 classes, `res/drawable-watch/`, `wear_select_list`) and **Huawei Watch** (`send_data_to_huawei_wear`), Apple Watch on iOS.
- Home-screen **shortcuts** (`shortcut_config_preferences.xml`, `shortcut/`).

### 2.15 Customization
- **Themes**: Light, Dark (multiple dark variants — Dark Cyan/Green/Pink/Purple/Yellow), **Material You** (`theme_variety`), plus premium theme packs — Color Series, **City Series**, **Seasons Series**, Photograph Series, Ink, Matcha, Lilac, Peach, Navy, Pearl, Pebble… (`theme_*`, `pro_premium_themes`).
- **Sidebar styles**: Classic / Modern / Minimal (`sidebar_*`).
- **Alternate app icons** — ~54 `activity-alias` entries in the manifest (subset are launcher-icon aliases; `app_icon` "App icons").
- **Custom fonts** shipped (`assets/DIN_Numbers.ttf`, `roboto_numbers_regular.ttf` for the timer; `gulzar.ttf`, `sans_light.ttf`).
- **Custom swipe actions**, **configurable tab bar**, **configurable task-detail page** (see §4).

### 2.16 Security
Pattern lock + **fingerprint** (`lock_preferences.xml`: `patternlock_enabled`, `prekey_fingerprint`, `lock_widget`, lock start-time). App-level privacy gate independent of account.

---

## 3. Data Model (inferred from greenDAO DAOs + `data/` entities)

The DB is SQLite via greenDAO; **~100 tables** (`*Dao`). Core graph:

### Task (`Task2` / `Task2Dao`)
The primary entity (named `Task2` after a schema migration). Fields inferred from entities/strings: `title`, `content`/notes (Markdown), `priority` (0/1/3/5 → None/Low/Medium/High, inferred), `status` (open / completed / **won't-do/abandoned**), `dueData` (`DueData`: start, due, isAllDay, duration/timezone), `projectId`, `columnId` (kanban section), `parentId` (**nested subtask link**), `sortOrder` (multiple, per context), `pinned`, `kind` (TEXT/CHECKLIST/NOTE), `tags` (via `TaskId2Tag` join), `assignee`, `repeatFlag` (RRULE), `reminders`.
- **`TaskExtraData`** / `TaskExtraDataService` — side-car (e.g. Notion props, extra metadata).
- **`TaskContentBackup`**, **`TaskDragBackup`** — undo/redo & drag safety (`undo/` package).
- **`TaskSyncedJson`** — raw server JSON cache per task (sync).
- **`TaskTemplate`** / `ProjectTemplate` — reusable templates.
- **`TaskDefaultParam`** — per-list default date/priority/reminder for new tasks.

### The two "sub-item" concepts (critical for our outliner)
1. **Checklist items** — `ChecklistItem` / `ChecklistItemDao`, a *flat* list of sub-steps inside one task, each with its own completion + optional `ChecklistReminder`. **Cannot be dragged/reordered freely** (`checklist_item_long_click_toast` "Can't drag checklist item.") and **cannot be nested**. This is the *free* sub-item.
2. **Subtasks (nested tasks)** — real `Task2` rows with `parentId` pointing at a parent task (`parent_task`, `parent_task_added`). This is **"Task Nesting"**, gated as **premium** (`nested_task_upgrade_title` "New Feature: Task Nesting"). Nested tasks can't be converted to notes (`nested_tasks_cant_be_converted`).
   → **Net effect: TickTick's hierarchy is shallow** — one checklist level (free) or a limited nesting level (premium). It is *not* a true infinite outliner. **This is our opening.**

### Containers
- **`Project`** (= List) → `ProjectDao`. Fields: name, color, `groupId` (folder), `viewMode` (list/kanban/timeline), sortType, `permission`, `closed`(archived), background (`ProjectBackground`), `ProjectSyncedJson`, `ProjectPermissionItem`.
- **`ProjectGroup`** (= Folder) → `ProjectGroupDao`. One level of list grouping.
- **`Column`** (kanban Section) → `ColumnDao`; ordered; `SectionFoldedStatus`.
- **`Filter`** (Smart List) → `FilterDao`; stores serialized rule (`FilterSyncedJson`, `FilterDataProvider`, `logic` AND/OR).
- **`Tag`** → `TagDao` (+ `TagSortType`, `TaskId2Tag` join, `TagSyncedJson`). Supports hierarchy & per-tag sort.

### Scheduling / reminder entities
`DueData`, `Reminder` + `ReminderKey` (dedup), `TaskReminder`, `ChecklistReminder`, `LocationReminder` (+ `Location`, `FavLocation`), `DelayReminder`/`RecentReminder` (snooze), `AnnoyingAlert`, `RepeatInstance` (+ `RepeatInstanceFetchPoint` — materialized occurrences of recurring tasks).

### Feature entities
- Focus: `Pomodoro`, `PomodoroConfig`, `PomodoroSummary`, `PomodoroTaskBrief`, `Timer`, `RecentFocusEntity`, `FocusOptionModel`.
- Habit: `Habit`, `HabitCheckIn`, `HabitRecord`, `HabitConfig`, `HabitSection`, `SkippedHabit`, `FrozenHabitData`, `HabitSyncCheckInStamp`.
- Collab: `Team`, `TeamMember`, `Assignment`, `Attendee`, `EventAttendee`, `Comment`, `CommentAttach`, `Conference`.
- Calendar: `CalendarEvent`, `Calendars`, `BindCalendarAccount`, `CalendarSubscribeProfile`, `CalendarInfo`, `CalendarRefProject`, `TaskCalendarEventMap`, `Holiday`/`JapanHoliday`/`PresetHoliday`.
- Countdown: `Countdown`, `CountdownReminder`, `CountdownSection`, `PinnedCountdown`.
- Course: `Timetable`, `CourseDetail`, `CourseReminder`.
- Misc: `Attachment`, `User`/`UserProfile`/`UserPublicProfile`, `RankInfo`, `SearchHistory`, `WidgetConfiguration`/`WidgetExtensibleConfig`, `Promotion`, `Limits` (server-driven free/premium quotas), `SyncStatus` (per-entity dirty tracking).

**Sync architecture (inferred):** every syncable entity has a paired `*SyncedJson` table (raw server payload) + a `SyncStatus`/dirty flag; `sync/` (973 classes) does delta push/pull. All ordering, filters, and configs are server-synced JSON — meaning **the canonical model assumes a server**. For our offline app this whole `*SyncedJson`/`SyncStatus` layer is unnecessary; we keep just the clean local entities.

---

## 4. UI/UX & Interaction Design *(emphasis)*

### 4.1 Navigation architecture
- **Configurable bottom Tab Bar** (`tabbars/`, `TabBarConfigActivity`, `layout_slide_tabbar.xml`, `section_title_tab_bar`). The user picks up to ~4 visible tabs + a **"More"** overflow (`section_tab_bar_toast` "You can add up to 4 tabs to the tab bar"; `section_title_more_desc` "%s+ tabs will show in More tab"). Candidate tabs: **Tasks, Calendar, Focus/Pomo, Habit, Matrix, Countdown, Search, Settings** (`navigation_calendar`, `navigation_pomo`, `navigation_habit`, `tab_bar_*`).
- **Left drawer / sidebar** for the list tree (Inbox, smart lists, folders, lists, tags, filters), with **three visual densities**: Classic / Modern / Minimal (`sidebar_*`). `SlideMenuPinned` lets users pin favorite lists to the top.
- **Tablet/foldable**: dedicated `PadNavigationController` (two-pane).
- Main host: `MeTaskActivity` (the "Me/Tasks" home), `TaskListFragment` renders a list; tab fragments swap the content pane.
- **Takeaway for us:** Compose `NavigationBar` (Material3) with a user-editable set of destinations backed by a settings list; a `ModalNavigationDrawer` for the list/tag tree; `ListDetailPaneScaffold` for tablets.

### 4.2 The quick-add box & NLP chips
The signature interaction. A single text field parses as you type and surfaces **tappable chips**: date button, project button, priority, tag, matrix quadrant (`item_quick_add_date_button.xml`, `item_quick_add_project_button.xml`, `item_quick_add_matrix_button.xml`, `item_quick_add_icon_button.xml`). Parsed date/`#tag`/URL are highlighted inline and can be auto-removed from the title. A `QuickDateConfigActivity` lets users define what the "date" chip pre-selects; default date options are No-date / Today / Tomorrow / Day-after / Next-week (`default_duedate_option_value_name`).
- **Takeaway:** Compose `TextField` + a span-annotated `VisualTransformation` to highlight recognized tokens, a `Row` of `AssistChip`/`InputChip` below it (date, list, priority, tags), a bottom-anchored input that rises with the IME. This is the highest-ROI pattern to copy.

### 4.3 Swipe gestures (`custom_swipe_layout.xml`, `SwipeRelativeLayout`)
Each row supports **left, middle, right** swipe slots (`swipe_left_option`/`swipe_middle_option`/`swipe_right_option`), each bindable to: **None, Complete Task, Date, Priority, Move to, Delete Task, Start focus, Estimated duration, Add Tag** (`preference_custom_swipe_entries_with_pomo`). Defaults are free; **fully customizing them is premium** (`pro_custom_swipe_options`). Short swipe reveals an action button; long swipe commits. Lottie `assets/animation_swipe.json` teaches the gesture on first run (`newbie_try_swiping_left_and_right`).
- **Takeaway:** Compose `SwipeToDismissBox` with custom start/end backgrounds; make the action set user-configurable (offer it free — it's cheap and TickTick paywalls it).

### 4.4 Drag-and-drop reorder
Extensive: reorder within a list, drag between kanban sections (`drag_here_to_change_column`), drag a task **onto the calendar** to schedule (`drag_schedule_calendar_tips`), drag chips in task detail to reorder the action menu (`drag_task_details_menu_tip`), drag to reorder tab bar. Guardrails: dragging is disabled under non-custom sort/group and the app tells the user why (`dragging_not_supported_in_sorting_hint`, `dragging_not_supported_in_grouping_hint`). Backed by `TaskDragBackup` for safe undo. Custom views `DragView`, `DragChipOverlay`, `CancelDragTargetView`.
- **Takeaway:** `androidx.compose` reorderable list (LazyColumn + `detectDragGesturesAfterLongPress` or the reorderable lib); disable + explain when sort ≠ manual, exactly as TickTick does.

### 4.5 Micro-interactions & "polish" (Lottie/asset evidence)
- **Checkbox completion** — `assets/animation_checkbox_click.json` (Lottie tick draw-on) + completion **sounds** (`res/raw/completion_sound_{drip,jingle,knock,spiral}.aac`, `ticktick_pop.ogg`, `dida_bells.mp3`). Choosable completion sound (`prefkey_completion_task_sound`).
- **Pull-to-refresh** — bespoke multi-stage Lottie (`assets/refresh/{start,progress,done}.json`) instead of the stock spinner.
- **Loading** — `assets/loading/{enter,exit,indeterminate}.json`.
- **Screen-rotate / calendar mode switch** — `assets/screen_rotate/*.json` (light+dark variants).
- **Number rendering** — dedicated DIN/Roboto number fonts for the focus timer (crisp, monospaced digits).
- **Onboarding** — full-motion `res/raw/introduce_{task,calendar,countdown,focus,habit,matrix}.mp4` + `tick_onboarding.mp4`; guided Lottie for Matrix (`matrix_guide_en.json`) and the v7 feature tour (`assets/v7guide/`).
- **Custom checkbox states per priority** (colored ring), habit rings/heatmaps (`SectorProgressView`, `LineProgress`, `MonthLineProgressChartView`, `TaskProgressBar`).
- **Takeaway:** these small, consistent motions are *what makes it feel premium.* Compose equivalents: `airbnb/lottie-compose` for check/refresh; `animateFloatAsState`/`Animatable` for ring fills; `SoundPool` for completion chimes; theme-aware asset pairs (light/dark) as we already do with tokens.

### 4.6 Task-detail screen
- **Two presentation modes** the user chooses: full **Page** or lightweight **Dialog/bottom-sheet** (`task_detail_mode_config_tip`, `task_detail_page_mode`). Layouts `layout_task_detail_input.xml`, `detail_list_*` (title, text, tags, date-info, checklist item, subtask item, attachment image/other, agenda, Notion property rows).
- Rendered as a **RecyclerView of typed rows** (`detail_task_list_item`, `detail_subtask_list_item`, `detail_list_checklist_item`, `detail_list_item_tags`, `detail_list_date_info`) — i.e. title, description, checklist/subtasks, tags, date, attachments each a reorderable block.
- A **configurable action menu** (`TaskDetailMenuEditActivity`, `fragment_task_detail_menu.xml`, `item_task_detail_edit_menu.xml`): users drag their most-used actions to the top row (`drag_task_details_menu_tip`). Actions include complete, priority, date, move, duplicate, pin, start focus, add subtask, convert to note/event, copy link, won't-do, delete.
- **Takeaway:** Compose `LazyColumn` of typed detail blocks; offer both a `ModalBottomSheet` quick-edit and a full screen; let users reorder the action row (store order in prefs).

### 4.7 Date-picker UX
Custom calendar stack (`CalendarSetLayout`, `CalendarViewPager`, `SimpleCalendarView`, `MultiCalendarViewPager`) — not the stock dialog. Supports quick chips (Today/Tomorrow/Next week), time, duration, reminder offset, repeat, **lunar** overlay, and skip-to-adjacent by long-press (`newbie_tips_skip_date`). "Set Reminder" and "Repeat" are inline sub-sheets.
- **Takeaway:** build a single scheduling bottom sheet combining quick chips + month grid + time/repeat/reminder, rather than chaining Material date+time dialogs.

### 4.8 Empty states, onboarding, theming
- **Empty states** everywhere with tailored copy (`matrix_empty_tip_*`, `focus_timeline_no_record`, `notification_empty_text`, `EmptyPlaceholder`/`emptyimage` package).
- **Onboarding** flow: choose features → choose lists → choose theme (`newbie_choose_feature/list/theme`), backed by intro videos.
- **Theming** is token-driven with **light + many dark variants**, **Material You** dynamic color, and per-list background images/gradients. Uses `res/drawable-night/` for dark assets.
- **Bottom sheets** are the dominant modal (`design_bottom_sheet_dialog*`), including full-screen and match-height variants — echo Material's `ModalBottomSheet`.

---

## 5. Notifications & Reminders UX

- **Rich reminder pop-up** (`ReminderPopupActivity`, `reminder_popup_visibility`: No pop-ups / Always / Except-fullscreen) — a full-screen-intent alarm-style dialog (`USE_FULL_SCREEN_INTENT`) with actions: **Complete**, **Snooze**, "Remind Now", "Completed '%s'?" (`reminder_if_completed`).
- **Snooze** presets 15 / 60 / 180 / 1440 min (`snooze_minutes`) + custom "Remind me later" (`SnoozePopupActivity`, `DelayReminder`).
- **"Annoying Alert"** persistent re-ringing alarm (premium) — for reminders you must not miss.
- **Per-priority ringtones**: distinct High/Medium/Low reminder tones (`advance_reminder_preferences.xml`).
- **Override Do-Not-Disturb** for reminders (`prefkey_override_not_disturb_priority`).
- **Daily Notification / Summary** at a fixed time (`preferences_daily_summary`, `daily_reminder_*`): morning/afternoon/evening/night slots (up to 3/day), lists today + overdue + all-day tasks, can **skip weekends/holidays** (`daily_reminder_skip_holidays`).
- **Ongoing/persistent bar**: status-bar quick-add + running **Pomo timer notification** (`pomo_status_bar`, `notification_in_pomo`), recording notification, `FOREGROUND_SERVICE_*` (media playback, microphone, special-use).
- **Notification grouping** (`group_notification`), **turn-on-screen** (`notifications_turn_on_screen`), **completion sound**, **short vibrate**.
- **Alternate delivery channels**: **Email** and **WeChat** reminders (`pref_email_reminder`, `pref_wechat_reminder`), plus mirror to the **system calendar** (`prefkey_notification_by_system_calendar`).
- **Reliability plumbing** — the reason for the huge OEM permission list. A whole "reminders not working" help flow (`reminders_not_working`, `reminder_banner_tips`, `reminder_improve_stability`) walks users through **battery-optimization exemption** (`ignore_battery_optimization_preference.xml`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` via `pure_background`) and OEM auto-start whitelists: **Oppo/ColorOS** (`com.coloros.permission.*`, `oppo.permission.OPPO_COMPONENT_SAFE`), **Vivo** (`com.vivo.aiengine.*`, `com.vivo.devicerpc.notify`), **Huawei/Honor** (`com.huawei.permission.external_app_settings.*`, `hihonor.healthservice`), Samsung survey. Uses **`USE_EXACT_ALARM`/`SCHEDULE_EXACT_ALARM`** + `RECEIVE_BOOT_COMPLETED` to reschedule after reboot.
- **Takeaway:** for an offline app we still need exact alarms + boot reschedule + battery-exemption prompt + full-screen-intent reminder UI; the per-priority tone, snooze grid, and daily-summary patterns are all worth copying. Skip email/WeChat/system-calendar mirroring.

---

## 6. Import / Export / Sync

**Sync** (the app's backbone, but *against TickTick's own cloud*):
- Proprietary account sync via `sync/` (973 classes) + `*SyncedJson`/`SyncStatus` tables.
- **Two-way calendar sync**: Google Calendar, **Outlook**, **CalDAV** (generic servers). Subscribe to external **iCal `.ics`** URLs (read-only).
- **Notion** two-way database sync (premium).

**Import (on-device, into a TickTick account):** Google Tasks, **Any.DO**, **Wunderlist**, **Todoist** (each requires login), plus holidays and contact birthdays, and course timetables (image OCR / school portal).

**Export (Android): effectively none locally.** The only in-app "output" is **Print** (`print`) and **Save-as-Image** for shares (`save_to_gallery`). There is **no CSV/JSON/OPML/backup export string in the Android resources.** TickTick's real backup/CSV export lives on the **web app** only, and old data is offloaded to the cloud (`search_empty_info`, `search_in_cloud`).
- **Implication for our offline/no-loss goal:** TickTick is effectively **cloud-locked** — a user cannot get a complete local file of their data from the phone. Our app should treat **local, open export/import (JSON + Markdown/OPML + `.ics`) as a first-class, free feature**, since it is precisely where TickTick is weakest. We can offer a **TickTick importer** by consuming the CSV a user exports from TickTick's web app.

---

## 7. Strengths & Weaknesses

### Strengths (what TickTick nails)
- **Best-in-class capture**: NLP quick-add with inline chips + off-app entry points (quick ball, widget, notification, text-selection, clipboard, voice). Fast, low-friction.
- **View breadth per list**: List / Kanban / Timeline / Calendar (Day/3-Day/Week/Month/Year/Agenda) / Eisenhower Matrix — same data, many lenses.
- **Deep, reliable reminders**: multiple reminders, location, recurrence incl. **lunar**, per-priority tones, annoying-alert, daily summary, and heroic OEM-background-reliability work.
- **Integrated productivity suite**: Pomodoro+Stopwatch with white noise & strict mode, Habits with heatmaps, Countdown, Statistics/achievements, yearly report, student course timetable — all in one app.
- **Polish**: consistent Lottie micro-interactions (checkbox, pull-to-refresh, loading), completion sounds, custom date pickers, rich theming (Material You + premium packs, per-list backgrounds), configurable tab bar / swipe / detail-menu.
- **Collaboration**: shared lists, assignees, comments, mentions, permissions.
- **Highly configurable** without feeling cluttered (progressive disclosure via "More" tab, bottom sheets, per-list settings).

### Weaknesses (our openings)
- **Shallow hierarchy.** Only flat **checklist items** (free, non-draggable) or one level of **nested subtasks** (premium). No true outliner (infinite indent, zoom-in, fold, mirror). *This is the core differentiator for our app.*
- **Cloud/account lock-in.** Central login, cloud-only archived data, **no complete local export on Android**. Fails the "own your data / offline / private" bar.
- **Aggressive premium paywall on arguably-core features** (list below).
- Heavy third-party/telemetry footprint (Firebase, GA, Facebook, Bugsnag, Ad Services) — antithetical to a privacy-first app.
- arm64-only, ~41 MB, and a very large surface (245 activities) → high complexity to match feature-for-feature.

### What is premium-gated (from `pro_*` strings)
Eisenhower **Matrix**; **Calendar views** (month / daily-timeline / 3-day / various); **Timeline** view; **Custom Smart Lists / advanced filters** (`pro_custom_smart_list`, `pro_filter_title`); **Custom swipe actions**; **Task duration** & **estimated Pomo/duration**; **≥ premium counts** of lists / tasks / **checklist items** / **subtasks (nesting)** / **reminders (5/task)** / **shared members** / **attachments (99/day, larger files)** (`pro_more_*`); **subtask & checklist-item reminders**; **Annoying Alert**; **calendar subscriptions & CalDAV/Notion**; **premium themes**, **per-list background** (partly); **Quick Ball**; **Pomo & Calendar widgets**; **Annual habit heatmap**; **historical statistics**; **list/task activity history**. Free tier is a usable but deliberately capped core.

---

## 8. Takeaways for OUR App (offline, private, outliner-core)

### Replicate (high value, offline-friendly) — with Compose mapping
| TickTick pattern | Our Compose implementation |
|---|---|
| NLP quick-add with inline chips | `TextField` + span `VisualTransformation` to highlight date/`#tag`/`!priority`/`~list`; `Row` of `InputChip`s; IME-anchored bar. Ship an on-device parser (rules or a small model) — *make it free*. |
| Configurable bottom tab bar + drawer list tree | Material3 `NavigationBar` (user-editable destinations) + `ModalNavigationDrawer`; `ListDetailPaneScaffold` on tablets. |
| Swipe actions (L/M/R, user-mapped) | `SwipeToDismissBox` with custom backgrounds; expose the full action map free. |
| Drag reorder + "disabled under non-manual sort" guard | Reorderable `LazyColumn`; show the same explanatory toast. |
| Checkbox Lottie + completion sound | `lottie-compose` tick; `SoundPool` chime; per-priority colored checkbox ring via `Canvas`. |
| Custom pull-to-refresh / loading motion | `lottie-compose` staged animation (or `PullToRefreshBox` for a lighter take). |
| Combined scheduling bottom sheet (date+time+repeat+reminder, quick chips) | One `ModalBottomSheet` with a month grid, quick chips, RRULE builder, reminder-offset picker. |
| Task detail as typed blocks + reorderable action row | `LazyColumn` of block composables; both a bottom-sheet quick-edit and a full screen. |
| Rich reminders: multiple, snooze grid, per-priority tone, daily summary, full-screen-intent | `AlarmManager` exact alarms + boot reschedule + battery-exemption onboarding; full-screen reminder activity; `snooze` presets. |
| Token-driven theming, Material You, light/dark asset pairs, per-list color/icon | Extend our existing token system; `dynamicColorScheme`; emoji/icon per list. |
| Empty states, onboarding "choose features/lists/theme" | Tailored empty composables; a short first-run flow. |
| Pomodoro + white noise + stats; Habits + heatmap; Countdown | Optional modules, each a tab the user can enable — mirror TickTick's opt-in tab bar. |

### Adopt but improve (our differentiators)
- **True outliner core**: infinite nesting, fold/expand, zoom-into-node ("hoist"), node mirroring/links, drag to re-indent. Where TickTick stops at checklist-vs-1-level-subtask, we make hierarchy the spine. Keep TickTick's checklist *ergonomics* (Enter = new sibling item, Tab/Shift-Tab = indent) but remove the depth cap and the paywall.
- **Local-first data & open export/import as free, first-class**: JSON backup, Markdown/OPML outline export, `.ics` for dated items, and a TickTick-CSV importer. Directly attacks TickTick's cloud-lock weakness.
- **Views over the outline**: reuse the outline as the source for List/Board(Kanban via a "section" field)/Calendar/Matrix lenses — but never gate them behind a subscription.

### Skip (for an offline personal app)
- Account/sync backend, shared lists, assignees, comments, teams, Study Room (all inherently multi-user/cloud).
- Email/WeChat/system-calendar reminder mirroring; Notion/CalDAV two-way sync (optional later, not core).
- Telemetry stack (Firebase/GA/Facebook/Bugsnag/Ad Services) — omit entirely for privacy.
- WeChat/Dida market-specific plumbing; achievement "more productive than X% of users" social ranking.
- Consider deferring: student course timetable, countdown, wearable apps — nice-to-have, not core.

### Concrete free-vs-premium inversion
Everything TickTick paywalls that is *client-side and offline-computable* — Matrix, all calendar/timeline views, custom smart-list filters, custom swipe, task duration/estimates, unlimited nesting, multiple reminders, themes, widgets, habit heatmap, historical stats — costs us nothing to run locally and should simply be **free**. Our monetization (if any) should not gate the productivity surface.

---

### Appendix — Evidence pointers
- Class list: `tt_classes.txt` (regenerate: `strings -n6 dec/ticktick/classes*.dex | grep -oE 'Lcom/ticktick/task/[A-Za-z0-9/_$]+;' | sort -u`).
- Feature strings: `dec/ticktick/res/values/strings.xml`; option arrays: `res/values/arrays.xml`.
- Settings tree: `res/xml/preferences.xml`, `more_settings_preferences.xml`, `preference_smart_date_parse.xml`, `preference_pomodoro.xml`, `advance_reminder_preferences.xml`, `sound_reminder_and_notification_preferences.xml`, `lock_preferences.xml`, `task_quick_add_preference.xml`, `date_and_time_preference.xml`.
- Widgets: `res/xml/ticktick_appwidget_info_*` (26). UI layouts: `res/layout/{activity_quick_add,fragment_quick_add,custom_swipe_layout,layout_task_detail_input,detail_list_*,layout_slide_tabbar}.xml`.
- Motion/assets: `assets/{animation_checkbox_click.json,animation_swipe.json,refresh/*,loading/*,screen_rotate/*,matrix_guide_*,habit_animations/*,yearly_report/*}`, `res/raw/{introduce_*.mp4,completion_sound_*.aac,pomo_end.aac}`.
- DB schema: `com.ticktick.task.greendao.*Dao` (~100 tables); entities `com.ticktick.task.data.*`.
