package com.todocompanion.app.domain

enum class FirstView { MATRIX, CALENDAR }
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class TimeFormat { SYSTEM, H12, H24 }

/** Typed snapshot of app settings, with defaults. */
data class AppSettings(
    val firstView: FirstView = FirstView.MATRIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val advancedPriority: Boolean = false,
    val weekStart: Int = 0,            // 0 = system; 1..7 = Mon..Sun
    val timeFormat: TimeFormat = TimeFormat.SYSTEM,
    val timeZone: String = "",         // "" = device zone
) {
    fun toMap(): Map<String, String> = mapOf(
        Keys.FIRST_VIEW to firstView.name,
        Keys.THEME to themeMode.name,
        Keys.DYNAMIC_COLOR to dynamicColor.toString(),
        Keys.ADVANCED_PRIORITY to advancedPriority.toString(),
        Keys.WEEK_START to weekStart.toString(),
        Keys.TIME_FORMAT to timeFormat.name,
        Keys.TIME_ZONE to timeZone,
    )

    object Keys {
        const val FIRST_VIEW = "first_view"
        const val THEME = "theme"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val ADVANCED_PRIORITY = "advanced_priority"
        const val WEEK_START = "week_start"
        const val TIME_FORMAT = "time_format"
        const val TIME_ZONE = "time_zone"
    }

    companion object {
        fun fromMap(m: Map<String, String>): AppSettings = AppSettings(
            firstView = m[Keys.FIRST_VIEW]?.let { runCatching { FirstView.valueOf(it) }.getOrNull() } ?: FirstView.MATRIX,
            themeMode = m[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = m[Keys.DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
            advancedPriority = m[Keys.ADVANCED_PRIORITY]?.toBooleanStrictOrNull() ?: false,
            weekStart = m[Keys.WEEK_START]?.toIntOrNull() ?: 0,
            timeFormat = m[Keys.TIME_FORMAT]?.let { runCatching { TimeFormat.valueOf(it) }.getOrNull() } ?: TimeFormat.SYSTEM,
            timeZone = m[Keys.TIME_ZONE] ?: "",
        )
    }
}
