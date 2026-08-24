package com.todocompanion.app.domain

enum class FirstView { MATRIX, CALENDAR }
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class TimeFormat { SYSTEM, H12, H24 }
enum class Density { COMPACT, DEFAULT, RELAXED }

/** A swipe action that can be bound to a task-row swipe direction. */
enum class SwipeAction(val label: String) {
    NONE("None"), COMPLETE("Complete"), TRASH("Trash"), STAR("Star"),
    WONT_DO("Won't Do"), EDIT("Edit"), CYCLE_PRIORITY("Cycle priority")
}

/** Typed snapshot of app settings, with defaults. */
data class AppSettings(
    val firstView: FirstView = FirstView.MATRIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentArgb: Long = 0L,           // 0 = default / dynamic
    val advancedPriority: Boolean = false,
    val density: Density = Density.DEFAULT,
    val weekStart: Int = 0,
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val timeZone: String = "",
    val swipeRight: SwipeAction = SwipeAction.COMPLETE,
    val swipeLeft: SwipeAction = SwipeAction.TRASH,
    // Matrix
    val matrixImportanceThreshold: Int = 4,
    val matrixUrgencyThreshold: Int = 4,
    val matrixShowCompleted: Boolean = false,
    val matrixHideEmpty: Boolean = false,
    // Calendar
    val calendarDefaultMode: String = "month",
    // Reminders
    val dailySummaryEnabled: Boolean = false,
    val dailySummaryHour: Int = 8,
    val dailySummaryMinute: Int = 0,
) {
    fun toMap(): Map<String, String> = mapOf(
        Keys.FIRST_VIEW to firstView.name,
        Keys.THEME to themeMode.name,
        Keys.DYNAMIC_COLOR to dynamicColor.toString(),
        Keys.ACCENT to accentArgb.toString(),
        Keys.ADVANCED_PRIORITY to advancedPriority.toString(),
        Keys.DENSITY to density.name,
        Keys.WEEK_START to weekStart.toString(),
        Keys.TIME_FORMAT to timeFormat.name,
        Keys.TIME_ZONE to timeZone,
        Keys.SWIPE_R to swipeRight.name,
        Keys.SWIPE_L to swipeLeft.name,
        Keys.MX_IMP to matrixImportanceThreshold.toString(),
        Keys.MX_URG to matrixUrgencyThreshold.toString(),
        Keys.MX_DONE to matrixShowCompleted.toString(),
        Keys.MX_HIDE_EMPTY to matrixHideEmpty.toString(),
        Keys.CAL_MODE to calendarDefaultMode,
        Keys.SUMMARY_ON to dailySummaryEnabled.toString(),
        Keys.SUMMARY_H to dailySummaryHour.toString(),
        Keys.SUMMARY_M to dailySummaryMinute.toString(),
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
        const val MX_IMP = "mx_imp"
        const val MX_URG = "mx_urg"
        const val MX_DONE = "mx_done"
        const val MX_HIDE_EMPTY = "mx_hide_empty"
        const val CAL_MODE = "cal_mode"
        const val SUMMARY_ON = "summary_on"
        const val SUMMARY_H = "summary_h"
        const val SUMMARY_M = "summary_m"
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
            density = parse(m[Keys.DENSITY], Density.DEFAULT),
            weekStart = m[Keys.WEEK_START]?.toIntOrNull() ?: 0,
            timeFormat = parse(m[Keys.TIME_FORMAT], TimeFormat.SYSTEM),
            timeZone = m[Keys.TIME_ZONE] ?: "",
            swipeRight = parse(m[Keys.SWIPE_R], SwipeAction.COMPLETE),
            swipeLeft = parse(m[Keys.SWIPE_L], SwipeAction.TRASH),
            matrixImportanceThreshold = m[Keys.MX_IMP]?.toIntOrNull() ?: 4,
            matrixUrgencyThreshold = m[Keys.MX_URG]?.toIntOrNull() ?: 4,
            matrixShowCompleted = m[Keys.MX_DONE]?.toBooleanStrictOrNull() ?: false,
            matrixHideEmpty = m[Keys.MX_HIDE_EMPTY]?.toBooleanStrictOrNull() ?: false,
            calendarDefaultMode = m[Keys.CAL_MODE] ?: "month",
            dailySummaryEnabled = m[Keys.SUMMARY_ON]?.toBooleanStrictOrNull() ?: false,
            dailySummaryHour = m[Keys.SUMMARY_H]?.toIntOrNull() ?: 8,
            dailySummaryMinute = m[Keys.SUMMARY_M]?.toIntOrNull() ?: 0,
        )
    }
}
