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
    WONT_DO("Won't Do"), EDIT("Edit"), CYCLE_PRIORITY("Cycle priority"), SCHEDULE_TOMORROW("Tomorrow")
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
    // A subtle whole-app background tint: none | warm | cool | mint | dusk | rose.
    val appBackground: String = "none",
    // The hour a new "day" begins (0–6). Tasks before this hour still count as the previous day,
    // so late-night work stays under "Today". 0 = midnight (standard).
    val dayStartHour: Int = 0,
    // Reminders
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
    // Sidebar favourites, pinned to the top. Each token is "type:id" (list/folder/tag/context/filter/smart).
    val pinnedRefs: List<String> = emptyList(),
    // Saved view tabs (view + group + sort + outline/hierarchy + zoom), JSON-encoded.
    val viewTabsJson: String = "",
    // Startup: which view opens on launch. resumeLastView wins over defaultViewRef; both empty = Today.
    val resumeLastView: Boolean = false,
    val lastViewRef: String = "",       // ref token of the last-opened view (kept only when resume is on)
    val defaultViewRef: String = "",    // ref token to open on launch (empty = Today)
    // Sidebar section keys currently collapsed (persisted so folds survive an app restart).
    val sidebarCollapsed: Set<String> = emptySet(),
    // Sidebar section keys the user has hidden entirely from the drawer.
    val sidebarHidden: Set<String> = emptySet(),
    // User-defined drawer order for smart lists (SmartKind names) and views (view keys); empty = default.
    val smartOrder: List<String> = emptyList(),
    val viewsOrder: List<String> = emptyList(),
) {
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
        Keys.WEEK_START to weekStart.toString(),
        Keys.TIME_FORMAT to timeFormat.name,
        Keys.TIME_ZONE to timeZone,
        Keys.SWIPE_R to swipeRight.name,
        Keys.SWIPE_L to swipeLeft.name,
        Keys.SWIPE_RF to swipeRightFar.name,
        Keys.SWIPE_LF to swipeLeftFar.name,
        Keys.SMART_VIS to smartListVis.entries.joinToString(",") { "${it.key.name}:${it.value.name}" },
        Keys.BOTTOM_HIDDEN to bottomTabsHidden.joinToString(","),
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
        Keys.APP_BG to appBackground,
        Keys.DAY_START to dayStartHour.toString(),
        Keys.SUMMARY_ON to dailySummaryEnabled.toString(),
        Keys.SUMMARY_H to dailySummaryHour.toString(),
        Keys.SUMMARY_M to dailySummaryMinute.toString(),
        Keys.PINNED to pinnedRefs.joinToString("|"),
        Keys.VIEW_TABS to viewTabsJson,
        Keys.RESUME_LAST to resumeLastView.toString(),
        Keys.LAST_VIEW to lastViewRef,
        Keys.DEFAULT_VIEW to defaultViewRef,
        Keys.SIDEBAR_COLLAPSED to sidebarCollapsed.joinToString(","),
        Keys.SIDEBAR_HIDDEN to sidebarHidden.joinToString(","),
        Keys.SMART_ORDER to smartOrder.joinToString(","),
        Keys.VIEWS_ORDER to viewsOrder.joinToString(","),
    )

    object Keys {
        const val FIRST_VIEW = "first_view"
        const val THEME = "theme"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val ACCENT = "accent"
        const val ADVANCED_PRIORITY = "advanced_priority"
        const val DENSITY = "density"
        const val WEEK_START = "week_start"
        const val TIME_FORMAT = "time_format"
        const val TIME_ZONE = "time_zone"
        const val SWIPE_R = "swipe_r"
        const val SWIPE_L = "swipe_l"
        const val SWIPE_RF = "swipe_rf"
        const val SWIPE_LF = "swipe_lf"
        const val SMART_VIS = "smart_vis"
        const val BOTTOM_HIDDEN = "bottom_hidden"
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
        const val SUMMARY_ON = "summary_on"
        const val APP_BG = "app_bg"
        const val DAY_START = "day_start"
        const val SUMMARY_H = "summary_h"
        const val SUMMARY_M = "summary_m"
        const val PINNED = "pinned_refs"
        const val VIEW_TABS = "view_tabs"
        const val RESUME_LAST = "resume_last"
        const val LAST_VIEW = "last_view"
        const val DEFAULT_VIEW = "default_view"
        const val SIDEBAR_COLLAPSED = "sidebar_collapsed"
        const val SIDEBAR_HIDDEN = "sidebar_hidden"
        const val SMART_ORDER = "smart_order"
        const val VIEWS_ORDER = "views_order"
    }

    companion object {
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
            pinnedRefs = (m[Keys.PINNED] ?: "").split("|").filter { it.isNotBlank() },
            viewTabsJson = m[Keys.VIEW_TABS] ?: "",
            resumeLastView = m[Keys.RESUME_LAST]?.toBooleanStrictOrNull() ?: false,
            lastViewRef = m[Keys.LAST_VIEW] ?: "",
            defaultViewRef = m[Keys.DEFAULT_VIEW] ?: "",
            sidebarCollapsed = (m[Keys.SIDEBAR_COLLAPSED] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            sidebarHidden = (m[Keys.SIDEBAR_HIDDEN] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
            smartOrder = (m[Keys.SMART_ORDER] ?: "").split(",").filter { it.isNotBlank() },
            viewsOrder = (m[Keys.VIEWS_ORDER] ?: "").split(",").filter { it.isNotBlank() },
            dailySummaryEnabled = m[Keys.SUMMARY_ON]?.toBooleanStrictOrNull() ?: false,
            appBackground = m[Keys.APP_BG] ?: "none",
            dayStartHour = m[Keys.DAY_START]?.toIntOrNull()?.coerceIn(0, 6) ?: 0,
            dailySummaryHour = m[Keys.SUMMARY_H]?.toIntOrNull() ?: 8,
            dailySummaryMinute = m[Keys.SUMMARY_M]?.toIntOrNull() ?: 0,
        )
    }
}
