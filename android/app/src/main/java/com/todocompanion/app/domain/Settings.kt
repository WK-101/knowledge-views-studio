package com.todocompanion.app.domain

import com.todocompanion.app.domain.view.SmartKind

enum class FirstView { MATRIX, CALENDAR }
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class TimeFormat { SYSTEM, H12, H24 }
enum class Density { COMPACT, DEFAULT, RELAXED }

/** How a smart list appears in the sidebar (TickTick-style). */
enum class SmartVis(val label: String) { SHOW("Show"), AUTO("Show if not empty"), HIDE("Hide") }

/** A swipe action that can be bound to a task-row swipe direction. */
enum class SwipeAction(val label: String) {
    NONE("None"), COMPLETE("Complete"), TRASH("Trash"), STAR("Star"),
    WONT_DO("Won't Do"), EDIT("Edit"), CYCLE_PRIORITY("Cycle priority"), SCHEDULE_TOMORROW("Tomorrow"),
    MOVE("Move"), SOMEDAY("Someday")
}

/** The display name for a smart list — the user's custom name if set, else the built-in title. */
fun smartTitle(settings: AppSettings, k: SmartKind): String = settings.smartListNames[k.name]?.takeIf { it.isNotBlank() } ?: k.title

/** Typed snapshot of app settings, with defaults. */
data class AppSettings(
    val firstView: FirstView = FirstView.MATRIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentArgb: Long = 0L,           // 0 = default / dynamic
    val advancedPriority: Boolean = false,
    // Computed-priority (Do-Next) engine weights, MLO-style.
    val priorityMode: String = "both",       // both | importance | urgency
    val priorityDueWeight: Double = 3.0,
    val priorityStartWeight: Double = 2.0,
    val priorityGoalWeight: Double = 5.0,
    val priorityStarBoost: Double = 1.25,     // score multiplier for a starred task (1.0 = off)
    val priorityCurveBase: Double = 1.5,      // steepness of the importance/urgency level curve
    val priorityOverdueBoost: Boolean = true,
    val priorityComputed: Boolean = true,     // false = ignore the computed score, order by importance then manual
    val density: Density = Density.DEFAULT,
    val habitDensity: Int = 1,                // habits matrix cell size: 0 compact · 1 medium · 2 large (persisted)
    val habitMatrixMode: Boolean = false,     // habits tab: list (false) vs all-habits matrix (true), persisted
    val timeGridColumns: Int = 2,             // activity tiles per row in the Time view (2–5)
    val timeActivityParents: Map<String, String> = emptyMap(),  // childId → parentId, for nested activities (KV, no migration)
    val weekStart: Int = 0,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val timeZone: String = "",
    val swipeRight: SwipeAction = SwipeAction.COMPLETE,
    val swipeLeft: SwipeAction = SwipeAction.TRASH,
    val swipeRightFar: SwipeAction = SwipeAction.NONE,   // triggered by a longer right-swipe
    val swipeLeftFar: SwipeAction = SwipeAction.NONE,    // triggered by a longer left-swipe
    // Sidebar: per-smart-list visibility (absent = Show)
    val smartListVis: Map<SmartKind, SmartVis> = emptyMap(),
    // Custom display names for smart lists (key = SmartKind.name). Empty = use the built-in title.
    val smartListNames: Map<String, String> = emptyMap(),
    // Bottom navigation: tab names hidden from the bar (TASKS is always shown)
    val bottomTabsHidden: Set<String> = emptySet(),
    // Active workspace (MLO-style separate space)
    val activeWorkspaceId: String = "default",
    // Matrix
    val matrixImportanceThreshold: Int = 4,
    val matrixUrgencyThreshold: Int = 4,
    val matrixShowCompleted: Boolean = false,
    val matrixHideEmpty: Boolean = false,
    val matrixNames: List<String> = listOf("Urgent & Important", "Not Urgent & Important", "Urgent & Unimportant", "Not Urgent & Unimportant"),
    val matrixListFilter: Set<String> = emptySet(),   // empty = all lists
    val matrixMaxDuration: Int = 0,                   // minutes; 0 = no duration cap
    val matrixOverdueOnly: Boolean = false,           // only tasks past their due date
    val matrixFolderFilter: Set<String> = emptySet(), // folders to include (empty = all); OR-combined with lists
    // Date-range filter: all · today · today_tom · this_week · week_next · this_month · month_next.
    val matrixDateFilter: String = "all",
    // Within-quadrant sort: priority · due · created · alpha · manual.
    val matrixSort: String = "priority",
    // Calendar
    val calendarDefaultMode: String = "month",
    val calendarListFilter: Set<String> = emptySet(),   // empty = all lists
    // M1: draw timed habits as blocks in the day/week calendar. Off by default — opt-in.
    val habitCalendarBlocks: Boolean = false,
    // R27 #6: show completed tasks on the calendar too (off by default so the grid stays a plan of what's left).
    val calendarShowCompleted: Boolean = false,
    // A subtle whole-app background tint: none | warm | cool | mint | dusk | rose.
    val appBackground: String = "none",
    // Planning: hours you can realistically commit per day (workload forecast + auto-schedule).
    // R107 — capacity is now stored in MINUTES so it can be any hour+minute (e.g. 6h 30m). The legacy
    // dailyCapacityHours/capacityByDay fields are kept for back-compat reads; capacityMinutesFor() is the
    // single source consumers read.
    val dailyCapacityHours: Int = 8,
    // Optional per-weekday capacity (Mon..Sun, 7 entries). Empty = use dailyCapacityHours for every day.
    val capacityByDay: List<Int> = emptyList(),
    val dailyCapacityMin: Int = 480,               // R107 — minutes/day (defaults to 8h)
    val capacityByDayMin: List<Int> = emptyList(), // R107 — per-weekday minutes (Mon..Sun); empty = flat
    // Deep-work coach (H4): the daily focused-minutes goal that powers today's progress + streak.
    val deepWorkGoalMin: Int = 60,
    val workStartHour: Int = 9,
    val workEndHour: Int = 18,
    // R55 — the "when am I free" availability model: which weekdays you accept commitments on
    // (1=Mon..7=Sun), the minimum useful gap, and a buffer kept clear around each meeting. The daily
    // window itself is your working hours [workStartHour, workEndHour).
    val availDays: String = "1,2,3,4,5",
    val availMinSlotMin: Int = 30,
    val availBufferMin: Int = 0,
    // R57 — protected openings: recurring self-reserved blocks the availability engine defends (never
    // offered as free). Encoded as "days|startMin|endMin" entries joined by ';', e.g. "1,3,5|540|660".
    val protectedBlocks: String = "",
    // R58 — recent colors, shared across every color picker in the app (most-recent first, CSV of ARGB longs).
    val recentColors: String = "",
    // The time a new "day" begins. Tasks before this still count as the previous day, so late-night work
    // stays under "Today". 0 = midnight (standard). R107 — a minute component was added so it can be any
    // hour+minute (e.g. 3:30 am), not just a whole hour.
    val dayStartHour: Int = 0,
    val dayStartMinute: Int = 0,
    // Reminders
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    // Evening review: an end-of-day nudge to plan tomorrow (opens Plan-your-day).
    val eveningReviewEnabled: Boolean = false,
    val eveningReviewHour: Int = 20,
    // Phase F — adapt the evening reminder to when the user usually closes their day (median of recent
    // close times, clamped to a sane evening window). Off = keep the fixed eveningReviewHour exactly.
    val eveningReviewAdaptive: Boolean = false,
    // R59 (Wave 1) — reminder intensity default (0 Gentle · 1 Persistent · 2 Insistent-alarm), applied to
    // newly created reminders, and the snooze duration (minutes) every notification's Snooze action uses.
    val defaultReminderTier: Int = 0,
    val defaultSnoozeMin: Int = 10,
    // R59 (Wave 2) — quiet hours: reminders that would fire inside [quietStartHour, quietEndHour) are held
    // and delivered together when quiet hours end (a morning digest of what you missed). Off by default.
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    // R59 (Wave 3) — silence notifications (system Do-Not-Disturb) while a Focus session runs. Needs the
    // user to grant DND access; a no-op until they do. Off by default.
    val focusDnd: Boolean = false,
    // R46 Occasions — an ongoing notification pinning the next occasion (refreshed on app open / save), and
    // a daily reflective nudge (a finite-time reflection + today-in-history). Both default off, no new perm.
    val occasionLiveNotif: Boolean = false,
    val occasionNudge: Boolean = false,
    val occasionNudgeHour: Int = 9,
    // Whether the user has dismissed the Android reliability onboarding (battery + exact-alarm).
    val reliabilityOnboarded: Boolean = false,
    // Completion sound on checking a task off.
    val completionSound: Boolean = false,
    // R81 — selectable sound cues. Each is a "sound spec": a built-in preset id ("none","beep","double",
    // "chime","ascending","descending"), "default"/"silent", or a content:// URI the user picked.
    val focusStartSound: String = "none",       // played when a focus / timer session starts
    val focusDoneSound: String = "chime",       // played when a focus / timer countdown finishes, or a stopwatch stops
    val reminderSound: String = "default",      // notification sound for reminders: default | silent | URI
    // Require biometric / device credential to open the app.
    val appLockEnabled: Boolean = false,
    // Frontier F5 — the proof vault: lock just The Record behind biometrics (when the whole app isn't locked).
    val lockRecord: Boolean = false,
    // Security hardening (R18): block screenshots / screen recording / recents-thumbnail capture, and
    // hide task text on the lock screen. Both default off so nothing changes unless the user opts in.
    val secureScreen: Boolean = false,
    val lockscreenPrivacy: Boolean = false,
    val exportRedactNotes: Boolean = false,   // R103 — strip free-text notes from Markdown/CSV/ICS shares
    // Add-button (FAB) horizontal placement: end | center | start.
    val fabPosition: String = "end",
    // Data resilience & account-free sync (Tier D). Folder URIs are SAF tree URIs (persisted grants).
    val autoBackupEnabled: Boolean = false,
    val autoBackupFolder: String = "",
    val autoBackupHour: Int = 2,
    // How often the automatic backup runs, in days: 1 = daily, 7 = weekly, 30 = monthly.
    val autoBackupIntervalDays: Int = 1,
    // When the last successful backup (auto, "Back up now", or a Downloads export) completed, so the
    // Momentum data-safety card can show a real "last backup" age even without folder sync.
    val lastBackupAt: Long = 0L,
    val syncEnabled: Boolean = false,
    val syncFolder: String = "",
    val deviceId: String = "",          // stable per-install id, seeded on first sync
    val lastSyncAt: Long = 0L,
    // Optional passphrase (Tier G1) — AES-encrypts files written to the sync/backup folder at rest.
    // Empty = plaintext. Stored locally only; never travels in a synced/exported file.
    val syncPassphrase: String = "",
    // Human-readable summary of the last sync ("Synced · 3 updated from Tablet") — G2.
    val lastSyncSummary: String = "",
    // First-run onboarding shown (Tier F1).
    val onboarded: Boolean = false,
    // Curated theme-pack id ("" = none / use dynamic-or-accent). See ThemePrefs.
    val themePack: String = "",
    // Sidebar favourites, pinned to the top. Each token is "type:id" (list/folder/tag/context/filter/smart).
    val pinnedRefs: List<String> = emptyList(),
    // Saved view tabs (view + group + sort + outline/hierarchy + zoom), JSON-encoded.
    val viewTabsJson: String = "",
    // Startup: which view opens on launch. resumeLastView wins over defaultViewRef; both empty = Today.
    val resumeLastView: Boolean = false,
    val lastViewRef: String = "",       // ref token of the last-opened view (kept only when resume is on)
    val defaultViewRef: String = "",    // ref token to open on launch (empty = Today)
    // R30 #5 — long-pressing the first bottom-nav tab jumps here (a ref token; default = Inbox).
    val navShortcutRef: String = "smart:INBOX",
    // Tier T0: the modular top-level system. primaryModule ∈ {tasks, habits, time} sets the launch home
    // and the always-shown module. disabledModules holds fully-off modules (from {tasks, habits, time}),
    // hidden from nav, drawer, capture, widgets, Momentum and Today — but never deleted.
    val primaryModule: String = "tasks",
    val disabledModules: Set<String> = emptySet(),
    val onboardedModules: Boolean = false,   // has the first-run "what's your main use" picker been shown
    // Sidebar section keys currently collapsed (persisted so folds survive an app restart).
    val sidebarCollapsed: Set<String> = emptySet(),
    // Sidebar section keys the user has hidden entirely from the drawer.
    val sidebarHidden: Set<String> = emptySet(),
    // User-defined drawer order for smart lists (SmartKind names) and views (view keys); empty = default.
    val smartOrder: List<String> = emptyList(),
    val viewsOrder: List<String> = emptyList(),
    // List ids that open in Board (Kanban) layout instead of the flat list. Remembered per list.
    val boardLists: Set<String> = emptySet(),
    // Task-editor progressive disclosure (#114). Tier per optional field: 0 Always, 1 under "More",
    // 2 Hidden. Empty map = per-field defaults (see EditorField.defaultTier). A field that already
    // holds a value is always shown regardless of tier, so data is never orphaned.
    val editorFieldTiers: Map<String, Int> = emptyMap(),
    // User's arrangement of the optional editor fields (EditorField ids). Empty = canonical order.
    val editorFieldOrder: List<String> = emptyList(),
    // ── Tier U · time-tracking behaviour (all opt-in; the simple defaults are unchanged) ──
    // U5: "account for my whole day" — starting an activity closes any gap since the last one ended,
    // and the day view surfaces untracked gaps as tappable chips. Off = sparse tracking (gaps are fine).
    val timelineFill: Boolean = false,
    // U15: allow more than one timer to run at once (overlapping activities). Off = single-timer.
    val multiTimer: Boolean = false,
    // U2: when a time-blocked task's start time arrives, post a notification to start tracking it.
    val autoTrackPrompt: Boolean = false,
    // U8: forgiving streaks — count a rolling completion rate with grace days instead of brittle chains.
    val forgivingStreaks: Boolean = false,
    // U14: shade the calendar day-column gaps between tracked intervals so uncounted time is visible.
    val untrackedReveal: Boolean = false,
    // U12: lightweight on-device automation rules, JSON-encoded (see domain/AutomationRules.kt).
    val automationRulesJson: String = "",
    // V12: a self-defined rewards store. rewardsJson is a list of {id,name,cost,redeemed}; pointsBalance
    // is the momentum-points wallet (earned by keeping habits / finishing tasks, spent on rewards).
    val rewardsJson: String = "",
    val pointsBalance: Int = 0,
    // W6: routine tags — named bundles (activity + habit group) launched by one NFC/QR tap or shortcut.
    val routinesJson: String = "",
    val routineRunsJson: String = "",   // capped press-play run history (adherence, keystone, on-this-day)
    // R41 calendar: reusable event templates (JSON), a pinned secondary time-zone rail, and remembered
    // per-place travel minutes (JSON map) for auto travel buffers. All local; round-trip in the backup.
    val eventTemplatesJson: String = "",
    val secondaryZoneId: String = "",
    val travelTimesJson: String = "",
    // R42 planner: reusable day routines, protected life-windows, context modes, the lunar overlay, and
    // the set of days whose plan is locked (auto-schedule appends only). All local; round-trip in backup.
    val dayRoutinesJson: String = "",
    val protectedWindowsJson: String = "",
    // R67 — temptation-bundling + implementation-intention plans (kind-tagged, one JSON list). No schema.
    val microPlansJson: String = "",
    val calContextsJson: String = "",
    val activeContextId: String = "",
    val lunarOverlay: Boolean = false,
    val planLockedDaysCsv: String = "",
    // W5/W8: reminders suppressed for these habit ids / list ids (per-item mute; also feeds adaptive skip).
    val mutedHabits: Set<String> = emptySet(),
    val mutedLists: Set<String> = emptySet(),
    // R107 — mute every list inside these folders too (reminders from a muted folder's lists are silenced).
    val mutedFolders: Set<String> = emptySet(),
    // X1: Unified Goals — objectives spanning a task list + a habit + a time budget, one health bar each.
    val goalsJson: String = "",
    // Phase B: goal review log — weekly review sittings that power the scoreboard + integrity chain.
    val goalReviewsJson: String = "",
    // X3: plan against real tracked focus-hours (median) instead of the assumed dailyCapacityHours.
    val honestCapacity: Boolean = false,
    // Z2: dismissed / snoozed assistant insights (per-key user control over nudges).
    val insightPrefsJson: String = "",
    // Z6: a bounded, undoable log of actions the assistant took on the user's behalf.
    val assistantLogJson: String = "",
    // Z5: monthly snapshots of the cross-type meta-metrics, for the "you over time" trend.
    val metricSnapshotsJson: String = "",
    // Z4: the single daily "morning brief" local notification.
    val morningBriefEnabled: Boolean = false,
    val morningBriefHour: Int = 8,
    // Z8: opt-in — let a partially-met (graded) day earn partial credit toward the strength score.
    val gradedStrength: Boolean = false,
    // PC1: honour reduced-motion — mute the app's own animations for people who prefer stillness.
    val reduceMotion: Boolean = false,
    // PC3: the first-completion celebration has been shown (one-time delight).
    val firstWinCelebrated: Boolean = false,
    // PC4: discoverability tips the user has dismissed (per-key, so a hint never nags twice).
    val dismissedTips: Set<String> = emptySet(),
    // Time tab: activity ids the user pinned — they float to the front of the one-tap tile grid.
    val pinnedActivities: Set<String> = emptySet(),
    // Sidebar: show a live entry count on every list / folder / tag / context / filter (smart lists always
    // show theirs). Off by default — a calmer drawer — and turned on from Settings (R19 #13).
    val showEntryCounts: Boolean = false,
    // R29 Phase 7 — the verifiable-timeline seal: "count:headHash:sealedAtMillis", or null when unsealed.
    val integritySeal: String? = null,
    // R34 · life-systems layer. chronotype: 0 neutral · 1 morning lark · 2 night owl — the coach flags
    // habits scheduled against your low-energy window. calmMode hides points/streaks/celebration to
    // protect intrinsic motivation (SDT). rewardMenu is the user's own list of real rewards granted at
    // milestones (the app never invents the reward — avoids overjustification).
    val chronotype: Int = 0,
    val calmMode: Boolean = false,
    val rewardMenu: List<String> = emptyList(),
    // R35 · third-wave toggles. bookends: a daily AM/PM intention-review card on Today. companion: a
    // plant that grows from consistency (a calm-mode-native visual). strengthMeter: show the forgiving
    // strength % as the headline habit metric instead of the streak flame.
    val bookendsEnabled: Boolean = false,
    val companionEnabled: Boolean = false,
    val strengthMeter: Boolean = false,
    // R43 · third-horizon planner. daylightLatitude powers the "daylight rail" (sunrise/sunset bands,
    // computed locally); 999.0 = off / not set, so a real 0.0 (equator) is still usable. northStarTargetsCsv
    // holds target time shares per event-calendar as "calId:0.40,calId2:0.30" — the north-star allocation.
    val daylightLatitude: Double = 999.0,
    val northStarTargetsCsv: String = "",
    // R36 · fourth-wave. habitWipLimit caps how many habits may be "in formation" (not yet graduated)
    // at once — the New-Habit WIP Limiter; 0 = no cap. transitionLabel/transitionStartDay declare a
    // life transition (new job, move, term start) whose fresh-start window the coach uses to prompt a
    // gentle re-plan (Transition Detector + Reset Window). transitionStartDay is an epoch day; 0 = none.
    val habitWipLimit: Int = 0,
    val transitionLabel: String = "",
    val transitionStartDay: Long = 0,
    // R37 · habit-science ports to tasks. taskWipLimit caps how many tasks may be "in progress" (started,
    // not done) at once — personal-kanban WIP. receptivityTiming shifts the daily brief / evening review to
    // the hour you're most likely to act, learned from your own completions. 0 / false = off.
    val taskWipLimit: Int = 0,
    val receptivityTiming: Boolean = false,
    // Phase C — self-scored Daily Questions. A JSON array of {id,text} for the user's active
    // "Did I do my best to…" questions (empty = none). Scores live per-day on the DayLog. See
    // domain/DailyQuestions.kt.
    val dailyQuestionsJson: String = "",
    // Phase F — streak recovery ("never miss twice"). A capped, monthly allowance of "streak repairs":
    // streakRepairTokens is the remaining count, streakRepairPeriod ("YYYY-MM") the month it was granted
    // for (a new month refills to the cap), and repairedDaysCsv the epoch days the user deliberately
    // repaired — a settings-side overlay counted when computing the streak (never fabricated in the DB).
    val streakRepairTokens: Int = ReviewCadence.STREAK_REPAIR_CAP,
    val streakRepairPeriod: String = "",
    val repairedDaysCsv: String = "",
    // Wave 1 — the guided Weekly Review store: a JSON object mapping an ISO-week key ("YYYY-Www") to that
    // week's reflection + next-week focus + life areas. "" = none reviewed yet. See domain/WeeklyReview.kt.
    val weeklyReviewsJson: String = "",
    // Wave 3 (feature C) — the Drucker prediction loop store: a JSON list of predictions ("I expect that…
    // will make me feel…") each with a resurface date + recorded outcome. "" = none. See domain/Predictions.kt.
    val predictionsJson: String = "",
    // Wave 3 (feature D) — keys of judgment-free single-day pattern nudges the user has dismissed, so a
    // once-dismissed observation never returns. CSV of stable insight keys ("" = none). See ReviewInsights.
    val nudgeDismissedCsv: String = "",
    // Track 2.7 — evidence-led cadence corrections. gratitudeWeekly surfaces the gratitude / "three good
    // things" beat WEEKLY (on the week-start day) rather than nagging daily; requireGoodThingWhy adds a
    // short "…and why" line to each good thing (savouring works better with the reason); hideStreaks
    // hides streak counters across Day Review / The Record in favour of the density / consistency view.
    val gratitudeWeekly: Boolean = true,
    val requireGoodThingWhy: Boolean = true,
    val hideStreaks: Boolean = false,
    // Daily-review SHARE config — the modular "what to include in my shared day card" model, kept as ONE
    // settings JSON value (no schema change). "" = the defaults (a card ≈ today's). See domain/DayShareConfig.
    val dayShareConfigJson: String = "",
    // Period SHARE config — the modular "what to include in my shared week / month / year card" model,
    // kept as ONE settings JSON value (no schema change). "" = the defaults. See domain/PeriodShareConfig.
    val periodShareConfigJson: String = "",
) {
    /** Effective tier for an optional editor field: user override, else its built-in default. */
    fun editorTier(f: EditorField): Int = editorFieldTiers[f.id] ?: f.defaultTier

    /** Optional editor fields in the user's saved arrangement (unknown/new ids appended in default order). */
    fun editorFieldsOrdered(): List<EditorField> {
        if (editorFieldOrder.isEmpty()) return EditorField.ALL
        val byId = EditorField.ALL.associateBy { it.id }
        val chosen = editorFieldOrder.mapNotNull { byId[it] }
        val rest = EditorField.ALL.filter { it.id !in editorFieldOrder }
        return chosen + rest
    }

    /** R107 — capacity in MINUTES for a given weekday: per-day override if set, else the flat daily figure.
     *  This is the single source consumers (forecast, auto-schedule, widgets) read. */
    fun capacityMinutesFor(dayOfWeek: java.time.DayOfWeek): Int =
        capacityByDayMin.getOrNull(dayOfWeek.value - 1) ?: dailyCapacityMin
    /** Capacity in whole HOURS (rounded) — legacy accessor kept for any remaining back-compat callers. */
    fun capacityHoursFor(dayOfWeek: java.time.DayOfWeek): Int = (capacityMinutesFor(dayOfWeek) + 30) / 60
    /** R107 — the "day starts at" rollover as a minute-of-day. */
    fun dayStartMinuteOfDay(): Int = dayStartHour * 60 + dayStartMinute

    fun toMap(): Map<String, String> = mapOf(
        Keys.FIRST_VIEW to firstView.name,
        Keys.THEME to themeMode.name,
        Keys.DYNAMIC_COLOR to dynamicColor.toString(),
        Keys.ACCENT to accentArgb.toString(),
        Keys.ADVANCED_PRIORITY to advancedPriority.toString(),
        Keys.PRIO_MODE to priorityMode,
        Keys.PRIO_DUE to priorityDueWeight.toString(),
        Keys.PRIO_START to priorityStartWeight.toString(),
        Keys.PRIO_GOAL to priorityGoalWeight.toString(),
        Keys.PRIO_STAR to priorityStarBoost.toString(),
        Keys.PRIO_CURVE to priorityCurveBase.toString(),
        Keys.PRIO_OVERDUE to priorityOverdueBoost.toString(),
        Keys.PRIO_COMPUTED to priorityComputed.toString(),
        Keys.DENSITY to density.name,
        Keys.HABIT_DENSITY to habitDensity.toString(),
        Keys.HABIT_MATRIX_MODE to habitMatrixMode.toString(),
        Keys.TIME_GRID_COLUMNS to timeGridColumns.toString(),
        Keys.TIME_ACTIVITY_PARENTS to timeActivityParents.entries.joinToString(";") { "${it.key}=${it.value}" },
        Keys.WEEK_START to weekStart.toString(),
        Keys.TIME_FORMAT to timeFormat.name,
        Keys.TIME_ZONE to timeZone,
        Keys.SWIPE_R to swipeRight.name,
        Keys.SWIPE_L to swipeLeft.name,
        Keys.SWIPE_RF to swipeRightFar.name,
        Keys.SWIPE_LF to swipeLeftFar.name,
        Keys.SMART_VIS to smartListVis.entries.joinToString(",") { "${it.key.name}:${it.value.name}" },
        // Control-char separators (U+0001 entries, U+0002 key/value) so a name may contain commas/colons/spaces.
        Keys.SMART_NAMES to smartListNames.entries.joinToString("\u0001") { "${it.key}\u0002${it.value}" },
        Keys.BOTTOM_HIDDEN to bottomTabsHidden.joinToString(","),
        Keys.PRIMARY_MODULE to primaryModule,
        Keys.DISABLED_MODULES to disabledModules.joinToString(","),
        Keys.ONBOARDED_MODULES to onboardedModules.toString(),
        Keys.ACTIVE_WS to activeWorkspaceId,
        Keys.MX_IMP to matrixImportanceThreshold.toString(),
        Keys.MX_URG to matrixUrgencyThreshold.toString(),
        Keys.MX_DONE to matrixShowCompleted.toString(),
        Keys.MX_HIDE_EMPTY to matrixHideEmpty.toString(),
        Keys.MX_NAMES to matrixNames.joinToString("|"),
        Keys.MX_LISTS to matrixListFilter.joinToString(","),
        Keys.MX_MAXDUR to matrixMaxDuration.toString(),
        Keys.MX_OVERDUE to matrixOverdueOnly.toString(),
        Keys.MX_FOLDERS to matrixFolderFilter.joinToString(","),
        Keys.MX_DATE to matrixDateFilter,
        Keys.MX_SORT to matrixSort,
        Keys.CAL_MODE to calendarDefaultMode,
        Keys.CAL_FILTER to calendarListFilter.joinToString(","),
        Keys.HABIT_CAL_BLOCKS to habitCalendarBlocks.toString(),
        Keys.CAL_SHOW_COMPLETED to calendarShowCompleted.toString(),
        Keys.APP_BG to appBackground,
        Keys.CAPACITY to dailyCapacityHours.toString(),
        Keys.CAPACITY_DAYS to capacityByDay.joinToString(","),
        Keys.CAPACITY_MIN to dailyCapacityMin.toString(),
        Keys.CAPACITY_DAYS_MIN to capacityByDayMin.joinToString(","),
        Keys.DEEPWORK_GOAL to deepWorkGoalMin.toString(),
        Keys.WORK_START to workStartHour.toString(),
        Keys.WORK_END to workEndHour.toString(),
        Keys.AVAIL_DAYS to availDays,
        Keys.AVAIL_MIN_SLOT to availMinSlotMin.toString(),
        Keys.AVAIL_BUFFER to availBufferMin.toString(),
        Keys.PROTECTED_BLOCKS to protectedBlocks,
        Keys.RECENT_COLORS to recentColors,
        Keys.DAY_START to dayStartHour.toString(),
        Keys.DAY_START_MIN to dayStartMinute.toString(),
        Keys.SUMMARY_ON to dailySummaryEnabled.toString(),
        Keys.SUMMARY_H to dailySummaryHour.toString(),
        Keys.SUMMARY_M to dailySummaryMinute.toString(),
        Keys.EVENING_ON to eveningReviewEnabled.toString(),
        Keys.EVENING_H to eveningReviewHour.toString(),
        Keys.EVENING_ADAPTIVE to eveningReviewAdaptive.toString(),
        Keys.REMINDER_TIER to defaultReminderTier.toString(),
        Keys.SNOOZE_MIN to defaultSnoozeMin.toString(),
        Keys.QUIET_ON to quietHoursEnabled.toString(),
        Keys.QUIET_START to quietStartHour.toString(),
        Keys.QUIET_END to quietEndHour.toString(),
        Keys.FOCUS_DND to focusDnd.toString(),
        Keys.RELIABILITY to reliabilityOnboarded.toString(),
        Keys.COMPLETION_SOUND to completionSound.toString(),
        Keys.FOCUS_START_SOUND to focusStartSound,
        Keys.FOCUS_DONE_SOUND to focusDoneSound,
        Keys.REMINDER_SOUND to reminderSound,
        Keys.APP_LOCK to appLockEnabled.toString(),
        Keys.LOCK_RECORD to lockRecord.toString(),
        Keys.SECURE_SCREEN to secureScreen.toString(),
        Keys.LOCKSCREEN_PRIVACY to lockscreenPrivacy.toString(),
        Keys.EXPORT_REDACT_NOTES to exportRedactNotes.toString(),
        Keys.FAB_POS to fabPosition,
        Keys.AUTOBK_ON to autoBackupEnabled.toString(),
        Keys.AUTOBK_DIR to autoBackupFolder,
        Keys.AUTOBK_H to autoBackupHour.toString(),
        Keys.AUTOBK_EVERY to autoBackupIntervalDays.toString(),
        Keys.LAST_BACKUP to lastBackupAt.toString(),
        Keys.SYNC_ON to syncEnabled.toString(),
        Keys.SYNC_DIR to syncFolder,
        Keys.DEVICE_ID to deviceId,
        Keys.LAST_SYNC to lastSyncAt.toString(),
        Keys.SYNC_PASS to syncPassphrase,
        Keys.LAST_SYNC_SUMMARY to lastSyncSummary,
        Keys.ONBOARDED to onboarded.toString(),
        Keys.THEME_PACK to themePack,
        Keys.PINNED to pinnedRefs.joinToString("|"),
        Keys.VIEW_TABS to viewTabsJson,
        Keys.RESUME_LAST to resumeLastView.toString(),
        Keys.LAST_VIEW to lastViewRef,
        Keys.DEFAULT_VIEW to defaultViewRef,
        Keys.NAV_SHORTCUT to navShortcutRef,
        Keys.SIDEBAR_COLLAPSED to sidebarCollapsed.joinToString(","),
        Keys.SIDEBAR_HIDDEN to sidebarHidden.joinToString(","),
        Keys.SMART_ORDER to smartOrder.joinToString(","),
        Keys.VIEWS_ORDER to viewsOrder.joinToString(","),
        Keys.BOARD_LISTS to boardLists.joinToString(","),
        Keys.EDITOR_TIERS to editorFieldTiers.entries.joinToString(",") { "${it.key}:${it.value}" },
        Keys.EDITOR_ORDER to editorFieldOrder.joinToString(","),
        Keys.TIMELINE_FILL to timelineFill.toString(),
        Keys.MULTI_TIMER to multiTimer.toString(),
        Keys.AUTO_TRACK_PROMPT to autoTrackPrompt.toString(),
        Keys.FORGIVING_STREAKS to forgivingStreaks.toString(),
        Keys.UNTRACKED_REVEAL to untrackedReveal.toString(),
        Keys.AUTOMATION_RULES to automationRulesJson,
        Keys.REWARDS to rewardsJson,
        Keys.POINTS to pointsBalance.toString(),
        Keys.ROUTINES to routinesJson,
        Keys.ROUTINE_RUNS to routineRunsJson,
        Keys.EVENT_TEMPLATES to eventTemplatesJson,
        Keys.SECONDARY_ZONE to secondaryZoneId,
        Keys.TRAVEL_TIMES to travelTimesJson,
        Keys.DAY_ROUTINES to dayRoutinesJson,
        Keys.PROTECTED_WINDOWS to protectedWindowsJson,
        Keys.MICRO_PLANS to microPlansJson,
        Keys.CAL_CONTEXTS to calContextsJson,
        Keys.ACTIVE_CONTEXT to activeContextId,
        Keys.LUNAR_OVERLAY to lunarOverlay.toString(),
        Keys.PLAN_LOCKED_DAYS to planLockedDaysCsv,
        Keys.MUTED_HABITS to mutedHabits.joinToString(","),
        Keys.MUTED_LISTS to mutedLists.joinToString(","),
        Keys.MUTED_FOLDERS to mutedFolders.joinToString(","),
        Keys.GOALS to goalsJson,
        Keys.GOAL_REVIEWS to goalReviewsJson,
        Keys.HONEST_CAPACITY to honestCapacity.toString(),
        Keys.INSIGHT_PREFS to insightPrefsJson,
        Keys.ASSISTANT_LOG to assistantLogJson,
        Keys.METRIC_SNAPSHOTS to metricSnapshotsJson,
        Keys.MORNING_BRIEF to morningBriefEnabled.toString(),
        Keys.MORNING_BRIEF_HOUR to morningBriefHour.toString(),
        Keys.GRADED_STRENGTH to gradedStrength.toString(),
        Keys.REDUCE_MOTION to reduceMotion.toString(),
        Keys.FIRST_WIN to firstWinCelebrated.toString(),
        Keys.DISMISSED_TIPS to dismissedTips.joinToString(","),
        Keys.PINNED_ACTIVITIES to pinnedActivities.joinToString(","),
        Keys.SHOW_ENTRY_COUNTS to showEntryCounts.toString(),
        Keys.INTEGRITY_SEAL to (integritySeal ?: ""),
        Keys.CHRONOTYPE to chronotype.toString(),
        Keys.CALM_MODE to calmMode.toString(),
        Keys.REWARD_MENU to rewardMenu.joinToString("\n"),
        Keys.BOOKENDS to bookendsEnabled.toString(),
        // R55 — these three were never serialized, so the Settings toggles never stuck (bug fix).
        Keys.OCCASION_LIVE_NOTIF to occasionLiveNotif.toString(),
        Keys.OCCASION_NUDGE to occasionNudge.toString(),
        Keys.OCCASION_NUDGE_HOUR to occasionNudgeHour.toString(),
        Keys.COMPANION to companionEnabled.toString(),
        Keys.STRENGTH_METER to strengthMeter.toString(),
        Keys.DAYLIGHT_LAT to daylightLatitude.toString(),
        Keys.NORTH_STAR to northStarTargetsCsv,
        Keys.HABIT_WIP_LIMIT to habitWipLimit.toString(),
        Keys.TRANSITION_LABEL to transitionLabel,
        Keys.TRANSITION_START to transitionStartDay.toString(),
        Keys.TASK_WIP_LIMIT to taskWipLimit.toString(),
        Keys.RECEPTIVITY_TIMING to receptivityTiming.toString(),
        Keys.DAILY_QUESTIONS to dailyQuestionsJson,
        Keys.STREAK_REPAIR_TOKENS to streakRepairTokens.toString(),
        Keys.STREAK_REPAIR_PERIOD to streakRepairPeriod,
        Keys.REPAIRED_DAYS to repairedDaysCsv,
        Keys.WEEKLY_REVIEWS to weeklyReviewsJson,
        Keys.PREDICTIONS to predictionsJson,
        Keys.NUDGE_DISMISSED to nudgeDismissedCsv,
        Keys.GRATITUDE_WEEKLY to gratitudeWeekly.toString(),
        Keys.REQUIRE_GOOD_WHY to requireGoodThingWhy.toString(),
        Keys.HIDE_STREAKS to hideStreaks.toString(),
        Keys.DAY_SHARE_CONFIG to dayShareConfigJson,
        Keys.PERIOD_SHARE_CONFIG to periodShareConfigJson,
    )

    object Keys {
        const val FIRST_VIEW = "first_view"
        const val THEME = "theme"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val ACCENT = "accent"
        const val ADVANCED_PRIORITY = "advanced_priority"
        const val DENSITY = "density"
        const val HABIT_DENSITY = "habit_density"
        const val HABIT_MATRIX_MODE = "habit_matrix_mode"
        const val TIME_GRID_COLUMNS = "time_grid_columns"
        const val TIME_ACTIVITY_PARENTS = "time_activity_parents"
        const val WEEK_START = "week_start"
        const val TIME_FORMAT = "time_format"
        const val TIME_ZONE = "time_zone"
        const val SWIPE_R = "swipe_r"
        const val SWIPE_L = "swipe_l"
        const val SWIPE_RF = "swipe_rf"
        const val SWIPE_LF = "swipe_lf"
        const val SMART_VIS = "smart_vis"
        const val SMART_NAMES = "smart_names"
        const val BOTTOM_HIDDEN = "bottom_hidden"
        const val PRIMARY_MODULE = "primary_module"
        const val DISABLED_MODULES = "disabled_modules"
        const val ONBOARDED_MODULES = "onboarded_modules"
        const val ACTIVE_WS = "active_ws"
        const val PRIO_MODE = "prio_mode"
        const val PRIO_DUE = "prio_due"
        const val PRIO_START = "prio_start"
        const val PRIO_GOAL = "prio_goal"
        const val PRIO_STAR = "prio_star"
        const val PRIO_CURVE = "prio_curve"
        const val PRIO_OVERDUE = "prio_overdue"
        const val PRIO_COMPUTED = "prio_computed"
        const val MX_IMP = "mx_imp"
        const val MX_URG = "mx_urg"
        const val MX_DONE = "mx_done"
        const val MX_HIDE_EMPTY = "mx_hide_empty"
        const val MX_NAMES = "mx_names"
        const val MX_LISTS = "mx_lists"
        const val MX_MAXDUR = "mx_maxdur"
        const val MX_OVERDUE = "mx_overdue"
        const val MX_FOLDERS = "mx_folders"
        const val MX_DATE = "mx_date"
        const val MX_SORT = "mx_sort"
        const val CAL_MODE = "cal_mode"
        const val CAL_FILTER = "cal_filter"
        const val HABIT_CAL_BLOCKS = "habit_cal_blocks"
        const val CAL_SHOW_COMPLETED = "cal_show_completed"
        const val SUMMARY_ON = "summary_on"
        const val APP_BG = "app_bg"
        const val CAPACITY = "daily_capacity_h"
        const val CAPACITY_DAYS = "capacity_by_day"
        const val CAPACITY_MIN = "daily_capacity_min"
        const val CAPACITY_DAYS_MIN = "capacity_by_day_min"
        const val DEEPWORK_GOAL = "deepwork_goal_min"
        const val WORK_START = "work_start_h"
        const val WORK_END = "work_end_h"
        const val AVAIL_DAYS = "avail_days"
        const val AVAIL_MIN_SLOT = "avail_min_slot"
        const val AVAIL_BUFFER = "avail_buffer"
        const val PROTECTED_BLOCKS = "protected_blocks"
        const val RECENT_COLORS = "recent_colors"
        const val DAY_START = "day_start"
        const val DAY_START_MIN = "day_start_min"
        const val SUMMARY_H = "summary_h"
        const val SUMMARY_M = "summary_m"
        const val EVENING_ON = "evening_on"
        const val EVENING_H = "evening_h"
        const val EVENING_ADAPTIVE = "evening_adaptive"
        const val REMINDER_TIER = "reminder_tier"
        const val SNOOZE_MIN = "snooze_min"
        const val QUIET_ON = "quiet_on"
        const val QUIET_START = "quiet_start"
        const val QUIET_END = "quiet_end"
        const val FOCUS_DND = "focus_dnd"
        const val RELIABILITY = "reliability_onboarded"
        const val COMPLETION_SOUND = "completion_sound"
        const val FOCUS_START_SOUND = "focus_start_sound"
        const val FOCUS_DONE_SOUND = "focus_done_sound"
        const val REMINDER_SOUND = "reminder_sound"
        const val APP_LOCK = "app_lock"
        const val LOCK_RECORD = "lock_record"
        const val SECURE_SCREEN = "secure_screen"
        const val LOCKSCREEN_PRIVACY = "lockscreen_privacy"
        const val EXPORT_REDACT_NOTES = "export_redact_notes"
        const val FAB_POS = "fab_pos"
        const val AUTOBK_ON = "autobackup_on"
        const val AUTOBK_DIR = "autobackup_dir"
        const val AUTOBK_H = "autobackup_h"
        const val AUTOBK_EVERY = "autobackup_every_days"
        const val LAST_BACKUP = "last_backup_at"
        const val SYNC_ON = "sync_on"
        const val SYNC_DIR = "sync_dir"
        const val DEVICE_ID = "device_id"
        const val LAST_SYNC = "last_sync"
        const val SYNC_PASS = "sync_pass"
        const val LAST_SYNC_SUMMARY = "last_sync_summary"
        const val ONBOARDED = "onboarded"
        const val THEME_PACK = "theme_pack"
        const val PINNED = "pinned_refs"
        const val VIEW_TABS = "view_tabs"
        const val RESUME_LAST = "resume_last"
        const val LAST_VIEW = "last_view"
        const val DEFAULT_VIEW = "default_view"
        const val NAV_SHORTCUT = "nav_shortcut"
        const val SIDEBAR_COLLAPSED = "sidebar_collapsed"
        const val SIDEBAR_HIDDEN = "sidebar_hidden"
        const val SMART_ORDER = "smart_order"
        const val VIEWS_ORDER = "views_order"
        const val BOARD_LISTS = "board_lists"
        const val EDITOR_TIERS = "editor_tiers"
        const val EDITOR_ORDER = "editor_order"
        const val TIMELINE_FILL = "timeline_fill"
        const val MULTI_TIMER = "multi_timer"
        const val AUTO_TRACK_PROMPT = "auto_track_prompt"
        const val FORGIVING_STREAKS = "forgiving_streaks"
        const val UNTRACKED_REVEAL = "untracked_reveal"
        const val AUTOMATION_RULES = "automation_rules"
        const val REWARDS = "rewards"
        const val POINTS = "points_balance"
        const val ROUTINES = "routines"
        const val ROUTINE_RUNS = "routine_runs"
        const val EVENT_TEMPLATES = "event_templates"
        const val SECONDARY_ZONE = "secondary_zone"
        const val TRAVEL_TIMES = "travel_times"
        const val DAY_ROUTINES = "day_routines"
        const val PROTECTED_WINDOWS = "protected_windows"
        const val MICRO_PLANS = "micro_plans"
        const val CAL_CONTEXTS = "cal_contexts"
        const val ACTIVE_CONTEXT = "active_context"
        const val LUNAR_OVERLAY = "lunar_overlay"
        const val PLAN_LOCKED_DAYS = "plan_locked_days"
        const val MUTED_HABITS = "muted_habits"
        const val MUTED_LISTS = "muted_lists"
        const val MUTED_FOLDERS = "muted_folders"
        const val GOALS = "goals"
        const val GOAL_REVIEWS = "goal_reviews"
        const val HONEST_CAPACITY = "honest_capacity"
        const val INSIGHT_PREFS = "insight_prefs"
        const val ASSISTANT_LOG = "assistant_log"
        const val METRIC_SNAPSHOTS = "metric_snapshots"
        const val MORNING_BRIEF = "morning_brief"
        const val MORNING_BRIEF_HOUR = "morning_brief_hour"
        const val GRADED_STRENGTH = "graded_strength"
        const val REDUCE_MOTION = "reduce_motion"
        const val FIRST_WIN = "first_win_celebrated"
        const val DISMISSED_TIPS = "dismissed_tips"
        const val PINNED_ACTIVITIES = "pinned_activities"
        const val SHOW_ENTRY_COUNTS = "show_entry_counts"
        const val INTEGRITY_SEAL = "integrity_seal"
        const val CHRONOTYPE = "chronotype"
        const val CALM_MODE = "calm_mode"
        const val REWARD_MENU = "reward_menu"
        const val BOOKENDS = "bookends_enabled"
        const val OCCASION_LIVE_NOTIF = "occasion_live_notif"
        const val OCCASION_NUDGE = "occasion_nudge"
        const val OCCASION_NUDGE_HOUR = "occasion_nudge_hour"
        const val COMPANION = "companion_enabled"
        const val DAYLIGHT_LAT = "daylight_latitude"
        const val NORTH_STAR = "north_star_targets"
        const val STRENGTH_METER = "strength_meter"
        const val HABIT_WIP_LIMIT = "habit_wip_limit"
        const val TRANSITION_LABEL = "transition_label"
        const val TRANSITION_START = "transition_start"
        const val TASK_WIP_LIMIT = "task_wip_limit"
        const val RECEPTIVITY_TIMING = "receptivity_timing"
        const val DAILY_QUESTIONS = "daily_questions"
        const val STREAK_REPAIR_TOKENS = "streak_repair_tokens"
        const val STREAK_REPAIR_PERIOD = "streak_repair_period"
        const val REPAIRED_DAYS = "repaired_days"
        const val WEEKLY_REVIEWS = "weekly_reviews"
        const val PREDICTIONS = "predictions"
        const val NUDGE_DISMISSED = "nudge_dismissed"
        const val GRATITUDE_WEEKLY = "gratitude_weekly"
        const val REQUIRE_GOOD_WHY = "require_good_why"
        const val HIDE_STREAKS = "hide_streaks"
        const val DAY_SHARE_CONFIG = "day_share_config"
        const val PERIOD_SHARE_CONFIG = "period_share_config"
    }

    companion object {
        const val TIER_ALWAYS = 0
        const val TIER_MORE = 1
        const val TIER_HIDDEN = 2

        private inline fun <reified E : Enum<E>> parse(v: String?, def: E): E =
            v?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: def

        fun fromMap(m: Map<String, String>): AppSettings = AppSettings(
            firstView = parse(m[Keys.FIRST_VIEW], FirstView.MATRIX),
            themeMode = parse(m[Keys.THEME], ThemeMode.SYSTEM),
            dynamicColor = m[Keys.DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
            accentArgb = m[Keys.ACCENT]?.toLongOrNull() ?: 0L,
            advancedPriority = m[Keys.ADVANCED_PRIORITY]?.toBooleanStrictOrNull() ?: false,
            priorityMode = m[Keys.PRIO_MODE] ?: "both",
            priorityDueWeight = m[Keys.PRIO_DUE]?.toDoubleOrNull() ?: 3.0,
            priorityStartWeight = m[Keys.PRIO_START]?.toDoubleOrNull() ?: 2.0,
            priorityGoalWeight = m[Keys.PRIO_GOAL]?.toDoubleOrNull() ?: 5.0,
            priorityStarBoost = m[Keys.PRIO_STAR]?.toDoubleOrNull() ?: 1.25,
            priorityCurveBase = m[Keys.PRIO_CURVE]?.toDoubleOrNull() ?: 1.5,
            priorityOverdueBoost = m[Keys.PRIO_OVERDUE]?.toBooleanStrictOrNull() ?: true,
            priorityComputed = m[Keys.PRIO_COMPUTED]?.toBooleanStrictOrNull() ?: true,
            density = parse(m[Keys.DENSITY], Density.DEFAULT),
            habitDensity = m[Keys.HABIT_DENSITY]?.toIntOrNull()?.coerceIn(0, 2) ?: 1,
            habitMatrixMode = m[Keys.HABIT_MATRIX_MODE]?.toBooleanStrictOrNull() ?: false,
            timeGridColumns = m[Keys.TIME_GRID_COLUMNS]?.toIntOrNull()?.coerceIn(2, 5) ?: 2,
            timeActivityParents = (m[Keys.TIME_ACTIVITY_PARENTS] ?: "").split(";").mapNotNull { p ->
                val kv = p.split("="); if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
            }.toMap(),
            weekStart = m[Keys.WEEK_START]?.toIntOrNull() ?: 0,
            timeFormat = parse(m[Keys.TIME_FORMAT], TimeFormat.SYSTEM),
            timeZone = m[Keys.TIME_ZONE] ?: "",
            swipeRight = parse(m[Keys.SWIPE_R], SwipeAction.COMPLETE),
            swipeLeft = parse(m[Keys.SWIPE_L], SwipeAction.TRASH),
            swipeRightFar = parse(m[Keys.SWIPE_RF], SwipeAction.NONE),
            swipeLeftFar = parse(m[Keys.SWIPE_LF], SwipeAction.NONE),
            smartListVis = (m[Keys.SMART_VIS] ?: "").split(",").mapNotNull { pair ->
                val p = pair.split(":")
                if (p.size != 2) return@mapNotNull null
                val k = runCatching { enumValueOf<SmartKind>(p[0]) }.getOrNull()
                val v = runCatching { enumValueOf<SmartVis>(p[1]) }.getOrNull()
                if (k != null && v != null) k to v else null
            }.toMap(),
            smartListNames = (m[Keys.SMART_NAMES] ?: "").split('\u0001').filter { it.isNotBlank() }.mapNotNull { pair ->
                val p = pair.split('\u0002'); if (p.size == 2 && p[1].isNotBlank()) p[0] to p[1] else null
            }.toMap(),
            bottomTabsHidden = (m[Keys.BOTTOM_HIDDEN] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            primaryModule = m[Keys.PRIMARY_MODULE]?.takeIf { it.isNotBlank() } ?: "tasks",
            disabledModules = (m[Keys.DISABLED_MODULES] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            onboardedModules = m[Keys.ONBOARDED_MODULES]?.toBooleanStrictOrNull() ?: false,
            activeWorkspaceId = m[Keys.ACTIVE_WS]?.ifBlank { "default" } ?: "default",
            matrixImportanceThreshold = m[Keys.MX_IMP]?.toIntOrNull() ?: 4,
            matrixUrgencyThreshold = m[Keys.MX_URG]?.toIntOrNull() ?: 4,
            matrixShowCompleted = m[Keys.MX_DONE]?.toBooleanStrictOrNull() ?: false,
            matrixHideEmpty = m[Keys.MX_HIDE_EMPTY]?.toBooleanStrictOrNull() ?: false,
            matrixNames = m[Keys.MX_NAMES]?.split("|")?.takeIf { it.size == 4 } ?: listOf("Urgent & Important", "Not Urgent & Important", "Urgent & Unimportant", "Not Urgent & Unimportant"),
            matrixListFilter = (m[Keys.MX_LISTS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            matrixMaxDuration = m[Keys.MX_MAXDUR]?.toIntOrNull() ?: 0,
            matrixOverdueOnly = m[Keys.MX_OVERDUE]?.toBooleanStrictOrNull() ?: false,
            matrixFolderFilter = (m[Keys.MX_FOLDERS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            matrixDateFilter = m[Keys.MX_DATE] ?: "all",
            matrixSort = m[Keys.MX_SORT] ?: "priority",
            calendarDefaultMode = m[Keys.CAL_MODE] ?: "month",
            calendarListFilter = (m[Keys.CAL_FILTER] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            habitCalendarBlocks = m[Keys.HABIT_CAL_BLOCKS]?.toBooleanStrictOrNull() ?: false,
            calendarShowCompleted = m[Keys.CAL_SHOW_COMPLETED]?.toBooleanStrictOrNull() ?: false,
            pinnedRefs = (m[Keys.PINNED] ?: "").split("|").filter { it.isNotBlank() },
            viewTabsJson = m[Keys.VIEW_TABS] ?: "",
            resumeLastView = m[Keys.RESUME_LAST]?.toBooleanStrictOrNull() ?: false,
            lastViewRef = m[Keys.LAST_VIEW] ?: "",
            defaultViewRef = m[Keys.DEFAULT_VIEW] ?: "",
            navShortcutRef = m[Keys.NAV_SHORTCUT] ?: "smart:INBOX",
            sidebarCollapsed = (m[Keys.SIDEBAR_COLLAPSED] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            sidebarHidden = (m[Keys.SIDEBAR_HIDDEN] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            smartOrder = (m[Keys.SMART_ORDER] ?: "").split(",").filter { it.isNotBlank() },
            viewsOrder = (m[Keys.VIEWS_ORDER] ?: "").split(",").filter { it.isNotBlank() },
            boardLists = (m[Keys.BOARD_LISTS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            editorFieldTiers = (m[Keys.EDITOR_TIERS] ?: "").split(",").mapNotNull { pair ->
                val p = pair.split(":"); if (p.size != 2) return@mapNotNull null
                val t = p[1].toIntOrNull() ?: return@mapNotNull null
                if (p[0].isNotBlank() && t in 0..2) p[0] to t else null
            }.toMap(),
            editorFieldOrder = (m[Keys.EDITOR_ORDER] ?: "").split(",").filter { it.isNotBlank() },
            timelineFill = m[Keys.TIMELINE_FILL]?.toBooleanStrictOrNull() ?: false,
            multiTimer = m[Keys.MULTI_TIMER]?.toBooleanStrictOrNull() ?: false,
            autoTrackPrompt = m[Keys.AUTO_TRACK_PROMPT]?.toBooleanStrictOrNull() ?: false,
            forgivingStreaks = m[Keys.FORGIVING_STREAKS]?.toBooleanStrictOrNull() ?: false,
            untrackedReveal = m[Keys.UNTRACKED_REVEAL]?.toBooleanStrictOrNull() ?: false,
            automationRulesJson = m[Keys.AUTOMATION_RULES] ?: "",
            rewardsJson = m[Keys.REWARDS] ?: "",
            pointsBalance = m[Keys.POINTS]?.toIntOrNull() ?: 0,
            routinesJson = m[Keys.ROUTINES] ?: "",
            routineRunsJson = m[Keys.ROUTINE_RUNS] ?: "",
            eventTemplatesJson = m[Keys.EVENT_TEMPLATES] ?: "",
            secondaryZoneId = m[Keys.SECONDARY_ZONE] ?: "",
            travelTimesJson = m[Keys.TRAVEL_TIMES] ?: "",
            dayRoutinesJson = m[Keys.DAY_ROUTINES] ?: "",
            protectedWindowsJson = m[Keys.PROTECTED_WINDOWS] ?: "",
            microPlansJson = m[Keys.MICRO_PLANS] ?: "",
            calContextsJson = m[Keys.CAL_CONTEXTS] ?: "",
            activeContextId = m[Keys.ACTIVE_CONTEXT] ?: "",
            lunarOverlay = m[Keys.LUNAR_OVERLAY] == "true",
            planLockedDaysCsv = m[Keys.PLAN_LOCKED_DAYS] ?: "",
            mutedHabits = (m[Keys.MUTED_HABITS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            mutedLists = (m[Keys.MUTED_LISTS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            mutedFolders = (m[Keys.MUTED_FOLDERS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            goalsJson = m[Keys.GOALS] ?: "",
            goalReviewsJson = m[Keys.GOAL_REVIEWS] ?: "",
            honestCapacity = m[Keys.HONEST_CAPACITY]?.toBoolean() ?: false,
            insightPrefsJson = m[Keys.INSIGHT_PREFS] ?: "",
            assistantLogJson = m[Keys.ASSISTANT_LOG] ?: "",
            metricSnapshotsJson = m[Keys.METRIC_SNAPSHOTS] ?: "",
            morningBriefEnabled = m[Keys.MORNING_BRIEF]?.toBoolean() ?: false,
            morningBriefHour = m[Keys.MORNING_BRIEF_HOUR]?.toIntOrNull()?.coerceIn(0, 23) ?: 8,
            gradedStrength = m[Keys.GRADED_STRENGTH]?.toBoolean() ?: false,
            reduceMotion = m[Keys.REDUCE_MOTION]?.toBoolean() ?: false,
            firstWinCelebrated = m[Keys.FIRST_WIN]?.toBoolean() ?: false,
            dismissedTips = (m[Keys.DISMISSED_TIPS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            pinnedActivities = (m[Keys.PINNED_ACTIVITIES] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            showEntryCounts = m[Keys.SHOW_ENTRY_COUNTS]?.toBooleanStrictOrNull() ?: false,
            integritySeal = m[Keys.INTEGRITY_SEAL]?.takeIf { it.isNotBlank() },
            dailySummaryEnabled = m[Keys.SUMMARY_ON]?.toBooleanStrictOrNull() ?: false,
            appBackground = m[Keys.APP_BG] ?: "none",
            dailyCapacityHours = m[Keys.CAPACITY]?.toIntOrNull()?.coerceIn(1, 16) ?: 8,
            capacityByDay = (m[Keys.CAPACITY_DAYS] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 7 } ?: emptyList(),
            dailyCapacityMin = m[Keys.CAPACITY_MIN]?.toIntOrNull()?.coerceIn(30, 24 * 60)
                ?: ((m[Keys.CAPACITY]?.toIntOrNull()?.coerceIn(1, 16) ?: 8) * 60),
            capacityByDayMin = (m[Keys.CAPACITY_DAYS_MIN] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 7 }
                ?: (m[Keys.CAPACITY_DAYS] ?: "").split(",").mapNotNull { it.trim().toIntOrNull()?.times(60) }.takeIf { it.size == 7 } ?: emptyList(),
            deepWorkGoalMin = m[Keys.DEEPWORK_GOAL]?.toIntOrNull()?.coerceIn(15, 600) ?: 60,
            workStartHour = m[Keys.WORK_START]?.toIntOrNull()?.coerceIn(0, 23) ?: 9,
            workEndHour = m[Keys.WORK_END]?.toIntOrNull()?.coerceIn(1, 24) ?: 18,
            availDays = m[Keys.AVAIL_DAYS] ?: "1,2,3,4,5",
            availMinSlotMin = m[Keys.AVAIL_MIN_SLOT]?.toIntOrNull()?.coerceIn(5, 480) ?: 30,
            availBufferMin = m[Keys.AVAIL_BUFFER]?.toIntOrNull()?.coerceIn(0, 120) ?: 0,
            protectedBlocks = m[Keys.PROTECTED_BLOCKS] ?: "",
            recentColors = m[Keys.RECENT_COLORS] ?: "",
            dayStartHour = m[Keys.DAY_START]?.toIntOrNull()?.coerceIn(0, 11) ?: 0,
            dayStartMinute = m[Keys.DAY_START_MIN]?.toIntOrNull()?.coerceIn(0, 59) ?: 0,
            dailySummaryHour = m[Keys.SUMMARY_H]?.toIntOrNull() ?: 8,
            dailySummaryMinute = m[Keys.SUMMARY_M]?.toIntOrNull() ?: 0,
            eveningReviewEnabled = m[Keys.EVENING_ON]?.toBooleanStrictOrNull() ?: false,
            eveningReviewHour = m[Keys.EVENING_H]?.toIntOrNull()?.coerceIn(0, 23) ?: 20,
            eveningReviewAdaptive = m[Keys.EVENING_ADAPTIVE]?.toBooleanStrictOrNull() ?: false,
            defaultReminderTier = m[Keys.REMINDER_TIER]?.toIntOrNull()?.coerceIn(0, 2) ?: 0,
            defaultSnoozeMin = m[Keys.SNOOZE_MIN]?.toIntOrNull()?.coerceIn(1, 720) ?: 10,
            quietHoursEnabled = m[Keys.QUIET_ON]?.toBooleanStrictOrNull() ?: false,
            quietStartHour = m[Keys.QUIET_START]?.toIntOrNull()?.coerceIn(0, 23) ?: 22,
            quietEndHour = m[Keys.QUIET_END]?.toIntOrNull()?.coerceIn(0, 23) ?: 7,
            focusDnd = m[Keys.FOCUS_DND]?.toBooleanStrictOrNull() ?: false,
            reliabilityOnboarded = m[Keys.RELIABILITY]?.toBooleanStrictOrNull() ?: false,
            completionSound = m[Keys.COMPLETION_SOUND]?.toBooleanStrictOrNull() ?: false,
            focusStartSound = m[Keys.FOCUS_START_SOUND] ?: "none",
            focusDoneSound = m[Keys.FOCUS_DONE_SOUND] ?: "chime",
            reminderSound = m[Keys.REMINDER_SOUND] ?: "default",
            appLockEnabled = m[Keys.APP_LOCK]?.toBooleanStrictOrNull() ?: false,
            lockRecord = m[Keys.LOCK_RECORD]?.toBooleanStrictOrNull() ?: false,
            secureScreen = m[Keys.SECURE_SCREEN]?.toBooleanStrictOrNull() ?: false,
            lockscreenPrivacy = m[Keys.LOCKSCREEN_PRIVACY]?.toBooleanStrictOrNull() ?: false,
            exportRedactNotes = m[Keys.EXPORT_REDACT_NOTES]?.toBooleanStrictOrNull() ?: false,
            fabPosition = m[Keys.FAB_POS] ?: "end",
            autoBackupEnabled = m[Keys.AUTOBK_ON]?.toBooleanStrictOrNull() ?: false,
            autoBackupFolder = m[Keys.AUTOBK_DIR] ?: "",
            autoBackupHour = m[Keys.AUTOBK_H]?.toIntOrNull()?.coerceIn(0, 23) ?: 2,
            autoBackupIntervalDays = m[Keys.AUTOBK_EVERY]?.toIntOrNull()?.coerceIn(1, 30) ?: 1,
            lastBackupAt = m[Keys.LAST_BACKUP]?.toLongOrNull() ?: 0L,
            syncEnabled = m[Keys.SYNC_ON]?.toBooleanStrictOrNull() ?: false,
            syncFolder = m[Keys.SYNC_DIR] ?: "",
            deviceId = m[Keys.DEVICE_ID] ?: "",
            lastSyncAt = m[Keys.LAST_SYNC]?.toLongOrNull() ?: 0L,
            syncPassphrase = m[Keys.SYNC_PASS] ?: "",
            lastSyncSummary = m[Keys.LAST_SYNC_SUMMARY] ?: "",
            onboarded = m[Keys.ONBOARDED]?.toBooleanStrictOrNull() ?: false,
            themePack = m[Keys.THEME_PACK] ?: "",
            chronotype = m[Keys.CHRONOTYPE]?.toIntOrNull()?.coerceIn(0, 2) ?: 0,
            calmMode = m[Keys.CALM_MODE]?.toBooleanStrictOrNull() ?: false,
            rewardMenu = (m[Keys.REWARD_MENU] ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            bookendsEnabled = m[Keys.BOOKENDS]?.toBooleanStrictOrNull() ?: false,
            occasionLiveNotif = m[Keys.OCCASION_LIVE_NOTIF]?.toBooleanStrictOrNull() ?: false,
            occasionNudge = m[Keys.OCCASION_NUDGE]?.toBooleanStrictOrNull() ?: false,
            occasionNudgeHour = m[Keys.OCCASION_NUDGE_HOUR]?.toIntOrNull()?.coerceIn(0, 23) ?: 9,
            companionEnabled = m[Keys.COMPANION]?.toBooleanStrictOrNull() ?: false,
            strengthMeter = m[Keys.STRENGTH_METER]?.toBooleanStrictOrNull() ?: false,
            daylightLatitude = m[Keys.DAYLIGHT_LAT]?.toDoubleOrNull() ?: 999.0,
            northStarTargetsCsv = m[Keys.NORTH_STAR] ?: "",
            habitWipLimit = m[Keys.HABIT_WIP_LIMIT]?.toIntOrNull()?.coerceIn(0, 20) ?: 0,
            transitionLabel = m[Keys.TRANSITION_LABEL] ?: "",
            transitionStartDay = m[Keys.TRANSITION_START]?.toLongOrNull() ?: 0,
            taskWipLimit = m[Keys.TASK_WIP_LIMIT]?.toIntOrNull()?.coerceIn(0, 20) ?: 0,
            receptivityTiming = m[Keys.RECEPTIVITY_TIMING]?.toBooleanStrictOrNull() ?: false,
            dailyQuestionsJson = m[Keys.DAILY_QUESTIONS] ?: "",
            streakRepairTokens = m[Keys.STREAK_REPAIR_TOKENS]?.toIntOrNull()?.coerceIn(0, ReviewCadence.STREAK_REPAIR_CAP) ?: ReviewCadence.STREAK_REPAIR_CAP,
            streakRepairPeriod = m[Keys.STREAK_REPAIR_PERIOD] ?: "",
            repairedDaysCsv = m[Keys.REPAIRED_DAYS] ?: "",
            weeklyReviewsJson = m[Keys.WEEKLY_REVIEWS] ?: "",
            predictionsJson = m[Keys.PREDICTIONS] ?: "",
            nudgeDismissedCsv = m[Keys.NUDGE_DISMISSED] ?: "",
            gratitudeWeekly = m[Keys.GRATITUDE_WEEKLY]?.toBooleanStrictOrNull() ?: true,
            requireGoodThingWhy = m[Keys.REQUIRE_GOOD_WHY]?.toBooleanStrictOrNull() ?: true,
            hideStreaks = m[Keys.HIDE_STREAKS]?.toBooleanStrictOrNull() ?: false,
            dayShareConfigJson = m[Keys.DAY_SHARE_CONFIG] ?: "",
            periodShareConfigJson = m[Keys.PERIOD_SHARE_CONFIG] ?: "",
        )
    }
}

/**
 * The optional fields/sections of the task editor, in canonical order, each with a default
 * disclosure tier (#114). Core fields — title, notes, date, priority, list — are never optional
 * and are not listed here. Defaults keep a first-timer's editor lean: only the everyday fields
 * sit at [AppSettings.TIER_ALWAYS]; power features default to "More" and reveal on demand.
 */
enum class EditorField(val id: String, val label: String, val defaultTier: Int) {
    REPEAT("repeat", "Repeat", AppSettings.TIER_ALWAYS),
    REMINDERS("reminders", "Reminders", AppSettings.TIER_ALWAYS),
    CHECKLIST("checklist", "Checklist / subtasks", AppSettings.TIER_ALWAYS),
    DEADLINE("deadline", "Deadline", AppSettings.TIER_MORE),
    ENERGY("energy", "Energy", AppSettings.TIER_MORE),
    FLAG("flag", "Flag", AppSettings.TIER_MORE),
    ATTACHMENTS("attachments", "Attachments", AppSettings.TIER_MORE),
    TAGS("tags", "Tags & contexts", AppSettings.TIER_MORE),
    BLOCKED("blocked", "Blocked by", AppSettings.TIER_MORE),
    ACTIVITY("activity", "Activity log", AppSettings.TIER_MORE),
    ADVANCED("advanced", "Estimate, goal, project, review", AppSettings.TIER_MORE),
    REFLECTION("reflection", "Reflection (win, mood, notes)", AppSettings.TIER_MORE);

    companion object {
        val ALL: List<EditorField> = entries.toList()
    }
}
