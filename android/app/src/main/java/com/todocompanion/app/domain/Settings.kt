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
    MOVE("Move")
}

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
    // Calendar
    val calendarDefaultMode: String = "month",
    val calendarListFilter: Set<String> = emptySet(),   // empty = all lists
    // M1: draw timed habits as blocks in the day/week calendar. Off by default — opt-in.
    val habitCalendarBlocks: Boolean = false,
    // A subtle whole-app background tint: none | warm | cool | mint | dusk | rose.
    val appBackground: String = "none",
    // Planning: hours you can realistically commit per day (workload forecast + auto-schedule).
    val dailyCapacityHours: Int = 8,
    // Optional per-weekday capacity (Mon..Sun, 7 entries). Empty = use dailyCapacityHours for every day.
    val capacityByDay: List<Int> = emptyList(),
    // Deep-work coach (H4): the daily focused-minutes goal that powers today's progress + streak.
    val deepWorkGoalMin: Int = 60,
    val workStartHour: Int = 9,
    val workEndHour: Int = 18,
    // The hour a new "day" begins (0–6). Tasks before this hour still count as the previous day,
    // so late-night work stays under "Today". 0 = midnight (standard).
    val dayStartHour: Int = 0,
    // Reminders
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    // Evening review: an end-of-day nudge to plan tomorrow (opens Plan-your-day).
    val eveningReviewEnabled: Boolean = false,
    val eveningReviewHour: Int = 20,
    // Whether the user has dismissed the Android reliability onboarding (battery + exact-alarm).
    val reliabilityOnboarded: Boolean = false,
    // Completion sound on checking a task off.
    val completionSound: Boolean = false,
    // Require biometric / device credential to open the app.
    val appLockEnabled: Boolean = false,
    // Security hardening (R18): block screenshots / screen recording / recents-thumbnail capture, and
    // hide task text on the lock screen. Both default off so nothing changes unless the user opts in.
    val secureScreen: Boolean = false,
    val lockscreenPrivacy: Boolean = false,
    // Add-button (FAB) horizontal placement: end | center | start.
    val fabPosition: String = "end",
    // Data resilience & account-free sync (Tier D). Folder URIs are SAF tree URIs (persisted grants).
    val autoBackupEnabled: Boolean = false,
    val autoBackupFolder: String = "",
    val autoBackupHour: Int = 2,
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
    // W5/W8: reminders suppressed for these habit ids / list ids (per-item mute; also feeds adaptive skip).
    val mutedHabits: Set<String> = emptySet(),
    val mutedLists: Set<String> = emptySet(),
    // X1: Unified Goals — objectives spanning a task list + a habit + a time budget, one health bar each.
    val goalsJson: String = "",
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

    /** Capacity (hours) for a given weekday — per-day override if set, else the flat daily figure. */
    fun capacityHoursFor(dayOfWeek: java.time.DayOfWeek): Int =
        capacityByDay.getOrNull(dayOfWeek.value - 1) ?: dailyCapacityHours

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
        Keys.CAL_MODE to calendarDefaultMode,
        Keys.CAL_FILTER to calendarListFilter.joinToString(","),
        Keys.HABIT_CAL_BLOCKS to habitCalendarBlocks.toString(),
        Keys.APP_BG to appBackground,
        Keys.CAPACITY to dailyCapacityHours.toString(),
        Keys.CAPACITY_DAYS to capacityByDay.joinToString(","),
        Keys.DEEPWORK_GOAL to deepWorkGoalMin.toString(),
        Keys.WORK_START to workStartHour.toString(),
        Keys.WORK_END to workEndHour.toString(),
        Keys.DAY_START to dayStartHour.toString(),
        Keys.SUMMARY_ON to dailySummaryEnabled.toString(),
        Keys.SUMMARY_H to dailySummaryHour.toString(),
        Keys.SUMMARY_M to dailySummaryMinute.toString(),
        Keys.EVENING_ON to eveningReviewEnabled.toString(),
        Keys.EVENING_H to eveningReviewHour.toString(),
        Keys.RELIABILITY to reliabilityOnboarded.toString(),
        Keys.COMPLETION_SOUND to completionSound.toString(),
        Keys.APP_LOCK to appLockEnabled.toString(),
        Keys.SECURE_SCREEN to secureScreen.toString(),
        Keys.LOCKSCREEN_PRIVACY to lockscreenPrivacy.toString(),
        Keys.FAB_POS to fabPosition,
        Keys.AUTOBK_ON to autoBackupEnabled.toString(),
        Keys.AUTOBK_DIR to autoBackupFolder,
        Keys.AUTOBK_H to autoBackupHour.toString(),
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
        Keys.MUTED_HABITS to mutedHabits.joinToString(","),
        Keys.MUTED_LISTS to mutedLists.joinToString(","),
        Keys.GOALS to goalsJson,
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
        const val CAL_MODE = "cal_mode"
        const val CAL_FILTER = "cal_filter"
        const val HABIT_CAL_BLOCKS = "habit_cal_blocks"
        const val SUMMARY_ON = "summary_on"
        const val APP_BG = "app_bg"
        const val CAPACITY = "daily_capacity_h"
        const val CAPACITY_DAYS = "capacity_by_day"
        const val DEEPWORK_GOAL = "deepwork_goal_min"
        const val WORK_START = "work_start_h"
        const val WORK_END = "work_end_h"
        const val DAY_START = "day_start"
        const val SUMMARY_H = "summary_h"
        const val SUMMARY_M = "summary_m"
        const val EVENING_ON = "evening_on"
        const val EVENING_H = "evening_h"
        const val RELIABILITY = "reliability_onboarded"
        const val COMPLETION_SOUND = "completion_sound"
        const val APP_LOCK = "app_lock"
        const val SECURE_SCREEN = "secure_screen"
        const val LOCKSCREEN_PRIVACY = "lockscreen_privacy"
        const val FAB_POS = "fab_pos"
        const val AUTOBK_ON = "autobackup_on"
        const val AUTOBK_DIR = "autobackup_dir"
        const val AUTOBK_H = "autobackup_h"
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
        const val MUTED_HABITS = "muted_habits"
        const val MUTED_LISTS = "muted_lists"
        const val GOALS = "goals"
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
            calendarDefaultMode = m[Keys.CAL_MODE] ?: "month",
            calendarListFilter = (m[Keys.CAL_FILTER] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            habitCalendarBlocks = m[Keys.HABIT_CAL_BLOCKS]?.toBooleanStrictOrNull() ?: false,
            pinnedRefs = (m[Keys.PINNED] ?: "").split("|").filter { it.isNotBlank() },
            viewTabsJson = m[Keys.VIEW_TABS] ?: "",
            resumeLastView = m[Keys.RESUME_LAST]?.toBooleanStrictOrNull() ?: false,
            lastViewRef = m[Keys.LAST_VIEW] ?: "",
            defaultViewRef = m[Keys.DEFAULT_VIEW] ?: "",
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
            mutedHabits = (m[Keys.MUTED_HABITS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            mutedLists = (m[Keys.MUTED_LISTS] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            goalsJson = m[Keys.GOALS] ?: "",
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
            dailySummaryEnabled = m[Keys.SUMMARY_ON]?.toBooleanStrictOrNull() ?: false,
            appBackground = m[Keys.APP_BG] ?: "none",
            dailyCapacityHours = m[Keys.CAPACITY]?.toIntOrNull()?.coerceIn(1, 16) ?: 8,
            capacityByDay = (m[Keys.CAPACITY_DAYS] ?: "").split(",").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 7 } ?: emptyList(),
            deepWorkGoalMin = m[Keys.DEEPWORK_GOAL]?.toIntOrNull()?.coerceIn(15, 600) ?: 60,
            workStartHour = m[Keys.WORK_START]?.toIntOrNull()?.coerceIn(0, 23) ?: 9,
            workEndHour = m[Keys.WORK_END]?.toIntOrNull()?.coerceIn(1, 24) ?: 18,
            dayStartHour = m[Keys.DAY_START]?.toIntOrNull()?.coerceIn(0, 6) ?: 0,
            dailySummaryHour = m[Keys.SUMMARY_H]?.toIntOrNull() ?: 8,
            dailySummaryMinute = m[Keys.SUMMARY_M]?.toIntOrNull() ?: 0,
            eveningReviewEnabled = m[Keys.EVENING_ON]?.toBooleanStrictOrNull() ?: false,
            eveningReviewHour = m[Keys.EVENING_H]?.toIntOrNull()?.coerceIn(0, 23) ?: 20,
            reliabilityOnboarded = m[Keys.RELIABILITY]?.toBooleanStrictOrNull() ?: false,
            completionSound = m[Keys.COMPLETION_SOUND]?.toBooleanStrictOrNull() ?: false,
            appLockEnabled = m[Keys.APP_LOCK]?.toBooleanStrictOrNull() ?: false,
            secureScreen = m[Keys.SECURE_SCREEN]?.toBooleanStrictOrNull() ?: false,
            lockscreenPrivacy = m[Keys.LOCKSCREEN_PRIVACY]?.toBooleanStrictOrNull() ?: false,
            fabPosition = m[Keys.FAB_POS] ?: "end",
            autoBackupEnabled = m[Keys.AUTOBK_ON]?.toBooleanStrictOrNull() ?: false,
            autoBackupFolder = m[Keys.AUTOBK_DIR] ?: "",
            autoBackupHour = m[Keys.AUTOBK_H]?.toIntOrNull()?.coerceIn(0, 23) ?: 2,
            syncEnabled = m[Keys.SYNC_ON]?.toBooleanStrictOrNull() ?: false,
            syncFolder = m[Keys.SYNC_DIR] ?: "",
            deviceId = m[Keys.DEVICE_ID] ?: "",
            lastSyncAt = m[Keys.LAST_SYNC]?.toLongOrNull() ?: 0L,
            syncPassphrase = m[Keys.SYNC_PASS] ?: "",
            lastSyncSummary = m[Keys.LAST_SYNC_SUMMARY] ?: "",
            onboarded = m[Keys.ONBOARDED]?.toBooleanStrictOrNull() ?: false,
            themePack = m[Keys.THEME_PACK] ?: "",
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
    ADVANCED("advanced", "Estimate, goal, project, review", AppSettings.TIER_MORE);

    companion object {
        val ALL: List<EditorField> = entries.toList()
    }
}
