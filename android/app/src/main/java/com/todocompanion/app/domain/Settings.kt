package com.todocompanion.app.domain

enum class FirstView { MATRIX, CALENDAR }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Typed snapshot of app settings, with defaults. */
data class AppSettings(
    val firstView: FirstView = FirstView.MATRIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val advancedPriority: Boolean = false,
) {
    fun toMap(): Map<String, String> = mapOf(
        Keys.FIRST_VIEW to firstView.name,
        Keys.THEME to themeMode.name,
        Keys.DYNAMIC_COLOR to dynamicColor.toString(),
        Keys.ADVANCED_PRIORITY to advancedPriority.toString(),
    )

    object Keys {
        const val FIRST_VIEW = "first_view"
        const val THEME = "theme"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val ADVANCED_PRIORITY = "advanced_priority"
    }

    companion object {
        fun fromMap(m: Map<String, String>): AppSettings = AppSettings(
            firstView = m[Keys.FIRST_VIEW]?.let { runCatching { FirstView.valueOf(it) }.getOrNull() } ?: FirstView.MATRIX,
            themeMode = m[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = m[Keys.DYNAMIC_COLOR]?.toBooleanStrictOrNull() ?: true,
            advancedPriority = m[Keys.ADVANCED_PRIORITY]?.toBooleanStrictOrNull() ?: false,
        )
    }
}
