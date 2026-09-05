package com.todocompanion.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Tier X1 / Phase B — a Unified Goal: one objective that spans all three modules at once, expressed
 * through the frameworks executives and coaches actually use.
 *
 * A goal binds an optional task list (what to finish), an optional habit (the practice that carries
 * it), and an optional time budget against an activity (the hours to invest). Its health is read from
 * all three together — tasks closed, streak alive, hours banked — the *revealed-behaviour* view no
 * single-purpose app can express, because none holds tasks, habits and tracked time in one store.
 *
 * Phase B layers the expert scaffolding on top of that object:
 *   • milestones — intermediate checkpoints with their own dates (0.3),
 *   • key results — 2–5 measurable OKR outcomes, each 0–100% (2.2),
 *   • an area of focus — GTD's horizon-2, to group goals by life area (1.2),
 *   • a 12-week execution cycle — a start + a window, so the deadline is a sprint not a someday (1.3),
 *   • a review cadence — how often you sit with it (0.4),
 *   • an identity line — "the kind of person who…" (Atomic Habits), and a why.
 *
 * Lead vs lag: the habit and time arms are *lead* measures (inputs you control day to day); the task
 * list and key results are *lag* measures (the outcomes they produce). The screen frames them so.
 *
 * Everything is stored as JSON in settings (no DB migration); round-trips losslessly in the backup.
 */
@Serializable
data class GoalMilestone(
    val id: String,
    val title: String,
    val targetEpochDay: Long = 0L,   // optional checkpoint date (0 = none)
    val done: Boolean = false,
    val doneEpochDay: Long = 0L,
)

/** 2.2 — an OKR Key Result: a measurable outcome that moves from a start value to a target. */
@Serializable
data class KeyResult(
    val id: String,
    val title: String,
    val start: Double = 0.0,
    val target: Double = 100.0,
    val current: Double = 0.0,
    val unit: String = "",
) {
    /** Fraction complete (0..1); guards a zero-width range. */
    val fraction: Double
        get() {
            val span = target - start
            if (span == 0.0) return if (current >= target) 1.0 else 0.0
            return ((current - start) / span).coerceIn(0.0, 1.0)
        }
}

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
    val note: String = "",            // the "why" — the motivating reason
    // ── Phase B additions (all default so old goals decode unchanged) ────────────────────────────
    val area: String = "",            // 1.2 GTD area of focus ("" = unfiled)
    val identity: String = "",        // Atomic-Habits identity line ("the kind of person who…")
    val milestones: List<GoalMilestone> = emptyList(),   // 0.3
    val keyResults: List<KeyResult> = emptyList(),       // 2.2
    val cycleStartEpochDay: Long = 0L, // 1.3 12-week year start (0 = no cycle)
    val cycleWeeks: Int = 0,           // 1.3 window length in weeks (0 = none; 12 = a 12-week year)
    val reviewCadenceDays: Int = 7,    // 0.4 how often to review (7 = weekly)
    val archived: Boolean = false,
) {
    /** Which arms are configured — a goal needs at least one to be meaningful. */
    val hasTasks get() = listId.isNotBlank()
    val hasHabit get() = habitId.isNotBlank()
    val hasBudget get() = activityId.isNotBlank() && budgetMinutes > 0
    val hasKeyResults get() = keyResults.isNotEmpty()
    val hasCycle get() = cycleStartEpochDay > 0 && cycleWeeks > 0

    val milestonesDone get() = milestones.count { it.done }
    /** Average completion across the key results (0..1), or null when there are none. */
    val keyResultFraction: Double?
        get() = if (keyResults.isEmpty()) null else keyResults.map { it.fraction }.average()
}

/** Y5 — a starter for a Unified Goal: pre-shapes the name, icon and time budget so the three-arm
 *  object has a one-tap on-ramp. The user still binds the actual list / habit / activity. */
data class GoalTemplate(
    val name: String,
    val emoji: String,
    val budgetHours: Int,
    val note: String,
    val identity: String = "",
    val keyResults: List<String> = emptyList(),   // suggested KR titles
    val milestones: List<String> = emptyList(),   // suggested milestone titles
)

object Goals {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<Goal> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Goal>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<Goal>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    fun byId(list: List<Goal>, id: String): Goal? = list.firstOrNull { it.id == id }

    /** The distinct, non-blank areas across a goal set, in first-seen order — for the GTD grouping. */
    fun areasOf(list: List<Goal>): List<String> =
        list.mapNotNull { it.area.trim().ifBlank { null } }.distinct()

    /** Y5 — the goal library: common cross-module objectives, ready to instantiate and edit. Each
     *  carries a suggested identity, key results and milestones so a tap yields a real, shaped goal. */
    val TEMPLATES = listOf(
        GoalTemplate(
            "Run a 5K", "🏃", 20, "Build up with a running habit and tracked sessions.",
            identity = "a runner",
            keyResults = listOf("Longest run (km)", "Runs completed"),
            milestones = listOf("Run 1 km without stopping", "Run 3 km", "Finish a 5K"),
        ),
        GoalTemplate(
            "Write daily", "✍️", 40, "A daily writing practice toward a body of work.",
            identity = "a writer",
            keyResults = listOf("Words written", "Days written"),
            milestones = listOf("Write 7 days straight", "10,000 words", "First draft done"),
        ),
        GoalTemplate(
            "Ship a side project", "🚀", 60, "A task list, a build habit and a time budget in one.",
            identity = "a builder who ships",
            keyResults = listOf("Features shipped", "Users"),
            milestones = listOf("MVP scoped", "First working build", "Public launch"),
        ),
        GoalTemplate(
            "Read 12 books", "📚", 100, "A steady reading habit toward a yearly target.",
            identity = "a reader",
            keyResults = listOf("Books finished", "Pages read"),
            milestones = listOf("Finish book 3", "Finish book 6", "Finish book 12"),
        ),
        GoalTemplate(
            "Get fit", "💪", 50, "Workouts tracked and a movement habit kept.",
            identity = "someone who trains",
            keyResults = listOf("Workouts done", "Weekly active minutes"),
            milestones = listOf("Train 3×/week for a month", "Hit a strength PR"),
        ),
        GoalTemplate(
            "Learn a language", "🗣️", 80, "Daily practice plus focused study time.",
            identity = "a language learner",
            keyResults = listOf("Study hours", "New words learned"),
            milestones = listOf("Hold a 2-minute conversation", "Finish beginner course"),
        ),
    )
}

/**
 * 0.4 / moat #5 — the review + accountability layer. Each entry is one review sitting (weekly by
 * default): a self-scored execution percentage plus commitments-kept, which together power the
 * scoreboard history and the *integrity chain* — the run of consecutive review periods you actually
 * showed up for. "goalId" is blank for a whole-portfolio review, or the id of a single goal.
 */
@Serializable
data class GoalReview(
    val id: String,
    val goalId: String = "",          // "" = whole-portfolio review
    val epochDay: Long,
    val executionPct: Int = 0,        // 0..100 — how much of the plan you executed this period
    val commitmentsKept: Int = 0,
    val commitmentsTotal: Int = 0,
    val note: String = "",
    val createdAt: Long = 0L,
)

object GoalReviews {
    private const val CAP = 500
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(s: String): List<GoalReview> =
        if (s.isBlank()) emptyList() else runCatching { json.decodeFromString<List<GoalReview>>(s) }.getOrDefault(emptyList())

    fun encode(list: List<GoalReview>): String = runCatching { json.encodeToString(list.takeLast(CAP)) }.getOrDefault("")

    /** Append a review, keeping the list bounded. */
    fun append(existing: List<GoalReview>, r: GoalReview): List<GoalReview> = (existing + r).takeLast(CAP)
}
