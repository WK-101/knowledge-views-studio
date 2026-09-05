package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A Routine — a named, ordered, press-play ritual (morning primer, evening shutdown, deep-work start…).
 *
 * Evolved from the Tier-W6 "routine tag": the original one-tap fields ([activityId] to start tracking,
 * [habitCategory] to surface, run by the on-start automation) are kept as an OPTIONAL on-start action, so
 * old data round-trips untouched. What's new is [steps] — the actual guided sequence you press play on —
 * plus an optional [whenReminderMin] daily reminder. All fields default, so existing `routinesJson` blobs
 * deserialize cleanly (ignoreUnknownKeys + defaults). Fully local; rides the JSON backup via settings.
 */
@Serializable
data class Routine(
    val id: String,
    val name: String,
    val emoji: String = "🔗",
    val activityId: String = "",     // on-start: start tracking this activity (blank = none)
    val habitCategory: String = "",  // on-start: surface habits in this group (blank = none)
    val note: String = "",
    // The guided sequence. Empty = a bare tag (old behaviour); non-empty = a runnable routine.
    val steps: List<RoutineStep> = emptyList(),
    // Optional daily reminder, minutes-past-midnight (null = no reminder).
    val whenReminderMin: Int? = null,
    val createdAt: Long = 0L,
) {
    /** Total planned seconds across timed steps (untimed check-off steps contribute 0). */
    val plannedSec: Int get() = steps.sumOf { it.durationSec ?: 0 }
    val isRunnable: Boolean get() = steps.isNotEmpty()
}

enum class StepKind { TIMER, CHECKOFF }

/**
 * One step of a routine. A [durationSec] gives it a countdown; null makes it an untimed check-off. On
 * finishing the step the runner ticks any [linkedHabitId] / [linkedTaskId] (the cross-module magic —
 * a single press-play flows across habits, tasks and the time-tracker).
 */
@Serializable
data class RoutineStep(
    val id: String,
    val title: String,
    val emoji: String = "",
    val durationSec: Int? = null,        // null = untimed check-off
    val kind: StepKind = StepKind.TIMER,
    val linkedHabitId: String? = null,   // finishing ticks this habit today
    val linkedTaskId: String? = null,    // finishing completes this task
    val startActivityId: String? = null, // finishing/entering starts the time-tracker for this activity
    val note: String = "",               // a cue / instruction shown during the step
    // Tier 2.1 — a step the felt-state-gated "lite" run keeps even on a low-energy day (the 2-minute core).
    val essential: Boolean = false,
)

/**
 * One completed (or partial) run of a routine — the history that powers adherence analytics, keystone
 * detection and "on this day". Capped list stored under settings so it rides the backup with no migration.
 */
@Serializable
data class RoutineRun(
    val routineId: String,
    val epochDay: Long,
    val startedAtMillis: Long,
    val completedStepIds: List<String> = emptyList(),
    val skippedStepIds: List<String> = emptyList(),
    val totalSec: Int = 0,
    val lite: Boolean = false,           // ran the low-energy 2-minute version
    val finished: Boolean = true,        // reached the end (vs ended early)
)

object Routines {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<Routine> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Routine>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<Routine>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    fun byName(list: List<Routine>, name: String): Routine? = list.firstOrNull { it.name.equals(name.trim(), true) }

    /** The felt-state "lite" projection: on a low-energy day, keep only essential steps and cap timers at
     *  2 minutes, so never-miss-twice becomes a kind recovery instead of a shame event. */
    fun lite(r: Routine): Routine {
        val kept = r.steps.filter { it.essential || it.durationSec == null }
            .ifEmpty { r.steps.take(1) }
            .map { if ((it.durationSec ?: 0) > 120) it.copy(durationSec = 120) else it }
        return r.copy(steps = kept)
    }
}

object RoutineRuns {
    private const val CAP = 400
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<RoutineRun> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<RoutineRun>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<RoutineRun>): String = runCatching { json.encodeToString(list.takeLast(CAP)) }.getOrDefault("")

    /** Append a run, newest last, capped. */
    fun append(list: List<RoutineRun>, run: RoutineRun): List<RoutineRun> = (list + run).takeLast(CAP)
}

/**
 * Shipped starter catalog — data only, mirroring HabitJourneys. Each is a named, ordered, timed ritual the
 * user can add in one tap. Durations are seconds. Verbal-cue steps (Newport's "shutdown complete") are
 * plain check-off steps. IDs are assigned fresh when a catalog routine is added to the user's own list.
 */
object RoutineCatalog {
    data class Template(val name: String, val emoji: String, val blurb: String, val steps: List<RoutineStep>)

    private fun step(title: String, emoji: String, sec: Int?, essential: Boolean = false, note: String = "") =
        RoutineStep(id = "", title = title, emoji = emoji, durationSec = sec,
            kind = if (sec == null) StepKind.CHECKOFF else StepKind.TIMER, essential = essential, note = note)

    val templates: List<Template> = listOf(
        Template("Morning primer", "☀️", "A 10-minute launch for the day.", listOf(
            step("Drink a glass of water", "💧", 60, essential = true),
            step("Make the bed", "🛏️", 120),
            step("Stretch", "🧘", 120),
            step("Plan your top 3", "🎯", 180, essential = true, note = "What would make today a win?"),
            step("Step into sunlight", "🌤️", 120),
        )),
        Template("Evening shutdown", "🌙", "Cal Newport's ritual — close the day's open loops.", listOf(
            step("Review open loops", "🔄", 180, note = "Anything still spinning? Capture it."),
            step("Plan tomorrow's one thing", "✍️", 180, essential = true),
            step("Say “Shutdown complete”", "🔒", null, essential = true, note = "The verbal cue that ends work in your head."),
            step("One thing you're grateful for", "🙏", 60),
        )),
        Template("Deep-work start", "🎧", "Cross the starting line into focus.", listOf(
            step("Phone away / notifications off", "📵", 60, essential = true),
            step("Define the one task", "🎯", 60, essential = true, note = "One sentence: done looks like…"),
            step("Focus sprint", "⏱️", 50 * 60, essential = true, note = "One deep block. The tracker is running."),
            step("Renewal break", "🌿", 5 * 60),
        )),
        Template("Wind-down", "😴", "Signal your body it's time to sleep.", listOf(
            step("Screens off, lights dim", "🔅", null, essential = true),
            step("Read", "📖", 10 * 60),
            step("Same bedtime", "🛌", null, essential = true),
        )),
        Template("Calm reset", "🧘", "Five minutes back to baseline.", listOf(
            step("Box breathing", "🌬️", 120, essential = true, note = "In 4 · hold 4 · out 4 · hold 4"),
            step("5-4-3-2-1 grounding", "👀", 120, essential = true),
            step("Name one good thing", "✨", 60),
        )),
        Template("Focus sprint (90/20)", "🚀", "One ultradian cycle — Schwartz's work/renewal rhythm.", listOf(
            step("Set the intention", "🎯", 60, essential = true),
            step("Deep sprint", "⏱️", 90 * 60, essential = true, note = "90 minutes, one thing. Tracker on."),
            step("Full renewal", "🌿", 20 * 60, note = "Move, hydrate, look far away."),
        )),
    )
}
