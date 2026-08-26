package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier X1 — a Unified Goal: one objective that spans all three modules at once.
 *
 * A goal binds an optional task list (what to finish), an optional habit (the practice that carries
 * it), and an optional time budget against an activity (the hours to invest). Its health is read from
 * all three together — tasks closed, streak alive, hours banked — and shown as one bar. No single-
 * purpose app can express this object, because none holds tasks, habits and tracked time in one store.
 * Stored as JSON in settings (no DB migration); round-trips losslessly in the backup.
 */
@Serializable
data class Goal(
    val id: String,
    val name: String,
    val emoji: String = "🎯",
    val listId: String = "",          // task list whose completion this goal tracks ("" = no task arm)
    val habitId: String = "",         // supporting habit ("" = no habit arm)
    val activityId: String = "",      // time activity the budget is measured against ("" = no time arm)
    val budgetMinutes: Int = 0,       // total time budget in minutes (0 = no time arm)
    val targetEpochDay: Long = 0L,    // optional deadline as an epoch-day (0 = none)
    val note: String = "",
) {
    /** Which arms are configured — a goal needs at least one to be meaningful. */
    val hasTasks get() = listId.isNotBlank()
    val hasHabit get() = habitId.isNotBlank()
    val hasBudget get() = activityId.isNotBlank() && budgetMinutes > 0
}

/** Y5 — a starter for a Unified Goal: pre-shapes the name, icon and time budget so the three-arm
 *  object has a one-tap on-ramp. The user still binds the actual list / habit / activity. */
data class GoalTemplate(val name: String, val emoji: String, val budgetHours: Int, val note: String)

object Goals {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<Goal> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Goal>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<Goal>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    fun byId(list: List<Goal>, id: String): Goal? = list.firstOrNull { it.id == id }

    /** Y5 — the goal library: common cross-module objectives, ready to instantiate and edit. */
    val TEMPLATES = listOf(
        GoalTemplate("Run a 5K", "🏃", 20, "Build up with a running habit and tracked sessions."),
        GoalTemplate("Write daily", "✍️", 40, "A daily writing practice toward a body of work."),
        GoalTemplate("Ship a side project", "🚀", 60, "A task list, a build habit and a time budget in one."),
        GoalTemplate("Read 12 books", "📚", 100, "A steady reading habit toward a yearly target."),
        GoalTemplate("Get fit", "💪", 50, "Workouts tracked and a movement habit kept."),
        GoalTemplate("Learn a language", "🗣️", 80, "Daily practice plus focused study time."),
    )
}
