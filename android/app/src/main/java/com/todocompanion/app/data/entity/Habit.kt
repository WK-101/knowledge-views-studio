package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A habit — to build ("build") or to quit ("break") — checked off per day, with streaks and a
 * computed strength score. Tier I widened the model to specialist depth: flexible frequency,
 * numeric targets with ≥/≤ comparison, per-tap increment, an overachievement goal, a start date,
 * a description, and a whole-habit pause. All fields default so migrations stay additive.
 */
@Serializable
@Entity(tableName = "habits")
@androidx.compose.runtime.Immutable
data class HabitEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val targetPerDay: Int = 1,
    val unit: String? = null,            // e.g. "glasses", "pages", "min" — shown with the count
    val scheduleDays: String = "",       // day-of-week values 1..7 (Mon..Sun), comma-separated; "" = every day
    val reminderTimes: String = "",      // minutes-from-midnight, comma-separated (e.g. "540,1080" = 9:00, 18:00)
    val sortOrder: Double = 0.0,
    val archived: Boolean = false,
    val workspaceId: String = WorkspaceEntity.DEFAULT_ID,
    val createdAt: Long,
    // --- Tier I ---
    // "build" (do it) or "break" (quit it: success = staying at/under the target). See HabitKind.
    val habitType: String = "build",
    // For numeric goals: "atleast" (≥ target) or "atmost" (≤ target). Break habits imply atmost.
    val targetComparison: String = "atleast",
    // Frequency model: "weekly" (specific weekdays via scheduleDays), "times_week" / "times_month"
    // (freqParam completions per rolling 7 / 30 days, any days), or "interval" (every freqParam days).
    val freqType: String = "weekly",
    val freqParam: Int = 0,
    // How much one tap adds toward the target (numeric habits), e.g. +25 pages.
    val clickIncrement: Int = 1,
    // Optional overachievement goal (> targetPerDay): reaching it registers an "extra" day.
    val extraTarget: Int? = null,
    // When the habit began; days before it are not counted as misses. null = createdAt.
    val startDate: Long? = null,
    // A free-text reason / note, shown on the detail page (Markdown-friendly).
    val description: String = "",
    // Vacation: a paused habit is hidden from "due today" and never breaks its streak while paused.
    val paused: Boolean = false,
    // Optional per-unit money figure for break habits ("$0.50 per cigarette") → money-saved stat.
    val moneyPerUnit: Double? = null,
    // Optional grouping label, shown as a section header. "" = ungrouped.
    val category: String = "",
    // --- Tier K ---
    // K3: an identity statement this habit is a vote for, e.g. "I'm a writer". "" = none.
    val identity: String = "",
    // K4: habit stacking — the id of the habit this one is anchored to ("after I <anchor>, I do this").
    val anchorHabitId: String? = null,
    // K2: earned "streak freezes" — spend one to protect a missed day; gained by overachieving.
    val freezeTokens: Int = 0,
    // K5 (light): a self-chosen reward and the streak length that unlocks it (0 = no reward set).
    val rewardText: String = "",
    val rewardAtStreak: Int = 0,
    // K6: an optional place geofence — arriving here can surface/notify this habit. Fully on-device.
    val latitude: Double? = null,
    val longitude: Double? = null,
    val geofenceRadius: Double? = null,
    val placeLabel: String = "",
    // Tier T3: an optional time-activity this habit is tracked under. When set, tracking that activity
    // counts toward the habit, and the two share one goal (the habit's). Nullable — invisible unless the
    // Time module is used.
    val timeActivityId: String? = null,
    // --- Tier V ---
    // V3: how a linked time interval credits this habit — "minutes" (add its minutes, default/legacy),
    // "sessions" (each completed interval adds clickIncrement, so N sessions completes the goal, Streaks-
    // style), or "off" (tracking never auto-logs). Completing a linked task always ticks the habit once.
    val linkMode: String = "minutes",
    // V4: user-written encouragements, one per line; one is shown at random when the habit is checked off.
    val encouragements: String = "",
    // --- R33 · habit BUILDER layer (all additive; a plain tracker leaves them at their defaults) ---
    // F1 implementation intention: "I will [habit] at [cueTime] in/after [cueContext]". Anchor covers the
    // "after <habit>" case via anchorHabitId; these cover a clock time and a place / situation.
    val cueTime: Int? = null,             // minute-of-day the intention names, e.g. 420 = 7:00
    val cueContext: String = "",          // free text: "the kitchen", "after lunch"
    // F10 two-minute rule + auto ramp-up: start tiny, raise targetPerDay toward rampFinalTarget as
    // consistency holds. Null final = no ramp.
    val rampFinalTarget: Int? = null,
    val rampAddPerStep: Int = 1,
    val rampStepDays: Int = 7,
    val rampLastStepDay: Long = 0,
    // F12 quit dashboard (break habits): clean-time anchor + per-slip cost/time for money & time saved,
    // and the last day a daily pledge was tapped.
    val quitSinceMillis: Long? = null,
    val minutesPerUnit: Int = 0,
    val lastPledgeDay: Long = 0,
    // F13/F14: an optional replacement habit to run when an urge hits.
    val replacementHabitId: String? = null,
    // F16 guided journeys: the journey that created this habit (for grouping + progress). "" = standalone.
    val journeyKey: String = "",
    // --- R34 · the LIFE-SYSTEMS layer (all additive; a plain tracker leaves them at their defaults) ---
    // LS1 WOOP: the back half of an implementation intention — the wished outcome, the concrete inner
    // obstacle, and an if-then coping plan bound to it (mental contrasting ~doubles follow-through).
    val woopOutcome: String = "",
    val woopObstacle: String = "",
    val woopCoping: String = "",
    // LS5 values: the core value this habit is a vote for (id into core_values). null = unassigned.
    val valueId: String? = null,
    // LS10 competing response: for a break habit, the pre-chosen incompatible substitute offered on an urge.
    val competingResponse: String = "",
    // LS7 commitment contract + a real-world referee who signs off ("Witness") at milestones.
    val contractText: String = "",
    val refereeName: String = "",
    // LS7 self-forfeit + akrasia horizon: a non-monetary forfeit owed on a derail, an escalation level,
    // and a queued "make it easier" change that only applies after a one-week delay (pendingEaseMillis).
    val forfeitText: String = "",
    val forfeitLevel: Int = 0,
    val pendingEaseMillis: Long = 0,
    val pendingEaseTarget: Int = 0,
    // --- R35 · the THIRD-WAVE layer (all additive) ---
    // TW-A friction & environment: prep/"ready kit" steps to make a good habit easier or an obstacle
    // course to make a bad one harder (one per line), plus a bad habit's antecedent cue + disruption plan.
    val frictionSteps: String = "",
    val cueToDisrupt: String = "",
    val cueDisruptionPlan: String = "",
    // TW-D episodic future thinking: a vivid future-self scene, replayed at urge / decision points.
    val futureScene: String = "",
    // TW-D reward taper: once automatic, a habit "graduates" — celebration & prompting fade to a light check.
    val graduated: Boolean = false,
) {
    /** V4: the encouragement lines, trimmed and non-empty. */
    fun encouragementList(): List<String> = encouragements.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    /** null-safe first day this habit counts from. */
    fun startEpochDay(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        (startDate ?: createdAt).let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() }
}

/**
 * One day's progress for a habit. [count] is completions/value that day; [status] is
 * "done" (normal) or "skip" (a neutral rest/skip day that neither breaks the streak nor dents the
 * score). A [reason] is an optional free-text note on a skip.
 */
@Serializable
@Entity(tableName = "habit_checkins", primaryKeys = ["habitId", "epochDay"], indices = [Index("habitId")])
data class HabitCheckinEntity(
    val habitId: String,
    val epochDay: Long,
    val count: Int = 1,
    val status: String = "done",   // "done" | "skip"
    val reason: String = "",
    // K5: an optional photo attached to this day (a content/file uri copied into app storage).
    val photoUri: String? = null,
    // O2: minute-of-day (0–1439) this day was first marked done, for real "you usually do this at…"
    // timing insight and a time-of-day view. Null for days logged before O2 or backfilled.
    val doneAtMinute: Int? = null,
    // V5: an optional free-text journal note for the day ("what helped / what got in the way").
    val note: String = "",
    // R34 · LS2 context tags captured at check-in — the substrate the correlation engine reads later.
    // 0 = unset; energy & mood are 1–5 scales; place is free text ("home", "gym").
    val ctxEnergy: Int = 0,
    val ctxMood: Int = 0,
    val ctxPlace: String = "",
)

/** A completed focus (Pomodoro / stopwatch) session, for the focus tab + statistics. */
@Serializable
@Entity(tableName = "focus_sessions")
data class FocusSessionEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    val startMillis: Long,
    val minutes: Int,
    val kind: String = "pomo",
    val taskId: String? = null,   // the task this focus session was spent on, if any
)
