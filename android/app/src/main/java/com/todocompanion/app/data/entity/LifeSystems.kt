package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * R34 — the LIFE-SYSTEMS layer's own tables. Each is small, additive, and lands in the lossless JSON
 * backup. Fully offline; no new permissions.
 */

/** LS5 — a user's core value ("Health", "Craft", "Family"). Habits attach to one; the "living your
 *  values" view shows how the week's actions cash out each value. */
@Serializable
@Entity(tableName = "core_values")
@androidx.compose.runtime.Immutable
data class CoreValueEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val statement: String = "",   // "I am someone who…" — the identity this value expresses
    val orderIndex: Int = 0,
    val createdAt: Long,
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** LS7 — a witness sign-off: a named real-world referee confirmed a milestone on-device. Append-only,
 *  timestamped, with the referee's name — precommitment + third-party verification, no server. */
@Serializable
@Entity(tableName = "witness_events", indices = [Index("habitId")])
data class WitnessEventEntity(
    @PrimaryKey val id: String,
    val habitId: String,
    val refereeName: String,
    val milestoneLabel: String,   // "7-day streak", "Day 30", …
    val atMillis: Long,
    val note: String = "",
    val workspaceId: String = "default",   // R62 — mirrors its habit's workspace
)

/** LS · habit scorecard — one behaviour of a typical day, tagged good (+1), neutral (0) or bad (−1).
 *  "You cannot change what you're not aware of." Negatives seed break-habits; positives seed anchors. */
@Serializable
@Entity(tableName = "scorecard_items")
@androidx.compose.runtime.Immutable
data class ScorecardItemEntity(
    @PrimaryKey val id: String,
    val text: String,
    val sign: Int = 0,            // +1 good · 0 neutral · −1 bad
    val orderIndex: Int = 0,
    val createdAt: Long,
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** LS · a buddy's imported progress digest — SDT relatedness / social proof, peer-to-peer, nothing on a
 *  network. Just the compact payload the buddy chose to export, held locally to show their streaks. */
@Serializable
@Entity(tableName = "buddy_snapshots")
data class BuddySnapshotEntity(
    @PrimaryKey val id: String,
    val name: String,
    val importedAtMillis: Long,
    val payloadJson: String,      // the buddy's exported BuddyDigest, verbatim
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** LS · a saved weekly / annual integrity-review reflection, anchored to a period + fresh-start date.
 *  Reflection consolidates identity and re-commits at temporal landmarks; compounds each year kept. */
@Serializable
@Entity(tableName = "integrity_reviews")
data class IntegrityReviewEntity(
    @PrimaryKey val id: String,
    val kind: String,             // "weekly" | "annual"
    val periodKey: String,        // e.g. "2026-W35" or "2026"
    val createdAt: Long,
    val note: String = "",        // the user's written reflection
    val statsJson: String = "",   // a snapshot of the period's computed figures, for the record
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** R35 · TW-C — a personal n-of-1 experiment: toggle one habit ON/OFF in alternating blocks and measure
 *  an outcome, turning a correlation into a within-person causal test. The causal upgrade to correlation. */
@Serializable
@Entity(tableName = "experiments")
data class ExperimentEntity(
    @PrimaryKey val id: String,
    val habitId: String,          // the habit being manipulated (the independent variable)
    val outcome: String,          // the dependent variable: "mood" | "energy" | "tasks" | "focus"
    val startDay: Long,           // epoch day the experiment began
    val blockLenDays: Int = 3,    // length of each ON/OFF block
    val blocks: Int = 4,          // total blocks (alternating ON/OFF)
    val active: Boolean = true,
    val note: String = "",
    val createdAt: Long,
    val workspaceId: String = "default",   // R62 — mirrors its habit's workspace
) {
    /** The plan: block index → whether the habit should be ON that block (odd blocks OFF). */
    fun onForDay(day: Long): Boolean {
        val idx = ((day - startDay) / blockLenDays.coerceAtLeast(1)).toInt()
        return idx % 2 == 0
    }
    fun endDay() = startDay + blockLenDays.coerceAtLeast(1) * blocks - 1
}

/** R35 · TW-D — a behavioral-activation item: a small, values-linked activity scheduled and rated for
 *  Pleasure & Mastery. Act before motivation returns; the ratings are the therapeutic loop. */
@Serializable
@Entity(tableName = "activation_items")
data class ActivationItemEntity(
    @PrimaryKey val id: String,
    val text: String,
    val valueId: String? = null,
    val plannedDay: Long,         // epoch day it's scheduled for
    val done: Boolean = false,
    val pleasure: Int = 0,        // 0 unset, 1–5 after doing
    val mastery: Int = 0,         // 0 unset, 1–5 after doing
    val createdAt: Long,
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** R35 · TW-E — one day's morning-intention / evening-review bookend, plus the day's mood (a clean daily
 *  signal for the correlation engine). R62 — one row per (day, workspace): the bookend is per-workspace like
 *  every other feature, so the natural key is the pair. */
@Serializable
@Entity(tableName = "day_logs", primaryKeys = ["epochDay", "workspaceId"])
data class DayLogEntity(
    val epochDay: Long,
    val amIntention: String = "",
    val pmReflection: String = "",
    val amMood: Int = 0,          // 0 unset, 1–5
    val pmMood: Int = 0,
    // R106 — richer daily-review reflection. All optional; 0/"" = unset.
    val dayRating: Int = 0,       // 0 unset, 1–5 (how the day went overall)
    val energy: Int = 0,          // 0 unset, 1–5 (energy level)
    val highlight: String = "",   // the best moment / high point
    val gratitude: String = "",   // one thing you're grateful for
    val lesson: String = "",      // one lesson / what you'd change
    val tomorrowFocus: String = "", // the one thing that matters tomorrow (MIT)
    // Phase B — reflection depth. All optional; 0/"" = unset (so old backups round-trip).
    val good1: String = "",       // three good things (savouring / after-action credit)
    val good2: String = "",
    val good3: String = "",
    val intentionOutcome: Int = 0, // did the morning intention land? 0 unset, 1 no, 2 partly, 3 yes
    val promptAnswer: String = "", // the answer to the day's rotating reflective prompt
    // Phase C — self-scored Daily Questions: a JSON object mapping questionId -> score 1..5 for this day.
    // "" = nothing scored yet (so old backups round-trip). See domain/DailyQuestions.kt.
    val dailyScoresJson: String = "",
    val updatedAt: Long = 0,
    val workspaceId: String = "default",
)

/** R36 · FW-F — a self-escrow contingency reward/stake: pre-committed now, released only at a verified
 *  milestone (a streak / clean-days / automaticity target). Bank-or-redeem on release. Fully offline. */
@Serializable
@Entity(tableName = "escrows")
data class EscrowEntity(
    @PrimaryKey val id: String,
    val habitId: String? = null,
    val description: String,       // the reward or the stake ("new headphones", "donate ₹500")
    val kind: String,              // "reward" | "stake"
    val milestoneKind: String,     // "streak" | "cleandays" | "automaticity" | "taskdone" (R37)
    val milestoneValue: Int,
    val released: Boolean = false, // milestone reached and acted on
    val redeemed: Boolean = false, // for a reward: taken (vs banked); for a stake: paid
    val createdAt: Long,
    // R37 — an escrow may instead ride on shipping a specific task/project (milestoneKind = "taskdone").
    val taskId: String? = null,
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)

/** R36 · FW-M2 — Personal Nudge MRT: each time the app shows an opportunity nudge it micro-randomizes the
 *  message variant and logs it; a daily reconcile marks whether the habit was then done, so per-variant
 *  effectiveness can be read out. Single-case science, offline. One row per (habit, day) impression. */
@Serializable
@Entity(tableName = "nudge_events")
data class NudgeEventEntity(
    @PrimaryKey val id: String,
    val habitId: String,          // the target id — a habit id, or a task id when targetKind = "task"
    val variant: Int,             // which message variant was shown (0..N-1)
    val epochDay: Long,
    val acted: Boolean = false,   // was the habit/task completed that day after the nudge
    val createdAt: Long,
    // R37 — the nudge MRT also covers task reminders; "habit" (default) or "task".
    val targetKind: String = "habit",
    val workspaceId: String = "default",   // R62 — fully isolated per workspace
)
