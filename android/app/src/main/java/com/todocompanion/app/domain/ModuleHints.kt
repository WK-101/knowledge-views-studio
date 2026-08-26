package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity

/**
 * Ω3 — adaptive module hints. The unified store can see when a user would benefit from a module they
 * haven't turned on, and gently offer it: lots of estimated tasks but no tracking → "see where the
 * time goes?"; several repeating tasks but no habits → "make these habits?". A guided path into depth
 * no single-purpose app can offer. Pure; the UI decides when to show and remembers dismissals.
 */
object ModuleHints {
    data class Hint(val key: String, val text: String, val actionLabel: String, val enableModule: String)

    fun compute(settings: AppSettings, tasks: List<TaskEntity>, habits: List<HabitEntity>): List<Hint> {
        val out = mutableListOf<Hint>()
        val timeOn = Modules.isEnabled(settings, Modules.TIME)
        val habitsOn = Modules.isEnabled(settings, Modules.HABITS)
        val tasksOn = Modules.isEnabled(settings, Modules.TASKS)

        // Estimating a lot of work but not tracking any of it → offer the Time module.
        if (!timeOn) {
            val estimated = tasks.count { !it.trashed && (it.estimateMin ?: 0) > 0 }
            if (estimated >= 5) out += Hint(
                key = "hint_enable_time",
                text = "You've estimated $estimated tasks but aren't tracking time. Want to see where the hours actually go?",
                actionLabel = "Turn on Time", enableModule = Modules.TIME,
            )
        }

        // Repeating the same work as recurring tasks → offer the Habits module.
        if (!habitsOn) {
            val recurring = tasks.count { !it.trashed && !it.rrule.isNullOrBlank() }
            if (recurring >= 3) out += Hint(
                key = "hint_enable_habits",
                text = "You repeat $recurring tasks on a schedule. Some of those might be better as habits — track streaks and strength.",
                actionLabel = "Turn on Habits", enableModule = Modules.HABITS,
            )
        }

        // Keeping habits but with nowhere to put one-off to-dos → offer Tasks.
        if (!tasksOn && habitsOn) {
            val activeHabits = habits.count { !it.archived }
            if (activeHabits >= 2) out += Hint(
                key = "hint_enable_tasks",
                text = "You're keeping $activeHabits habits. Add a to-do list alongside them for the one-off things?",
                actionLabel = "Turn on Tasks", enableModule = Modules.TASKS,
            )
        }
        return out
    }
}
