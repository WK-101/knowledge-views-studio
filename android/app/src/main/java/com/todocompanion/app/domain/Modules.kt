package com.todocompanion.app.domain

/**
 * Tier T0 — the modular top-level system. The app is three first-class modules; any one can be the
 * user's primary, and any can be switched fully off. This object is the single source of truth for
 * "is module X on?" so every surface (nav, drawer, capture, widgets, Momentum, Today) gates the same way.
 *
 * Invariant I3: disabling a module hides it everywhere but never deletes its data.
 */
object Modules {
    const val TASKS = "tasks"
    const val HABITS = "habits"
    const val TIME = "time"
    val ALL = listOf(TASKS, HABITS, TIME)

    fun label(module: String): String = when (module) {
        TASKS -> "Tasks"; HABITS -> "Habits"; TIME -> "Time"; else -> module
    }

    /** A module is enabled unless the user turned it off. The primary module is always enabled. */
    fun isEnabled(s: AppSettings, module: String): Boolean =
        module == primary(s) || module !in s.disabledModules

    fun primary(s: AppSettings): String = s.primaryModule.takeIf { it in ALL } ?: TASKS

    /** The set of enabled modules, primary first. */
    fun enabled(s: AppSettings): List<String> {
        val p = primary(s)
        return listOf(p) + ALL.filter { it != p && it !in s.disabledModules }
    }

    /**
     * Which module a bottom-nav tab belongs to, or null for cross-cutting tabs that are always available
     * (Search, Focus, Settings). Tab names match the Tab enum in AppRoot.
     */
    fun moduleOfTab(tabName: String): String? = when (tabName) {
        "TASKS", "CALENDAR", "TIMELINE", "MATRIX" -> TASKS
        "HABITS" -> HABITS
        "TIME" -> TIME
        else -> null
    }
}
