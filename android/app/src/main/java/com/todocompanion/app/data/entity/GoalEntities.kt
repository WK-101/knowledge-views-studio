package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.todocompanion.app.domain.Goal
import com.todocompanion.app.domain.GoalMilestone
import com.todocompanion.app.domain.GoalReview
import com.todocompanion.app.domain.KeyResult
import com.todocompanion.app.util.AppJson
import kotlinx.serialization.encodeToString

/**
 * W3 (cross-module unification) — Goals promoted out of the settings-JSON blob (`goalsJson`) into their
 * own Room table, so a goal is a queryable, workspace-indexed row instead of one opaque string inside the
 * key/value settings table (the "second persistence substrate" the audit flags). The two bounded sub-lists
 * a goal carries — [GoalMilestone]s and [KeyResult]s — stay as JSON columns: they are edited only inside
 * the goal editor and never queried across goals, so a column is the right altitude; every other field is
 * a real column.
 *
 * Rollout is staged for safety (the audit calls a JSON→Room promotion the highest-risk item in the plan):
 *  • Increment 1 (this table + MIGRATION_85_86) is ADDITIVE — the migration copies the parsed `goalsJson`
 *    into these rows, and the app still reads/writes the JSON, so behaviour is unchanged and the copy is
 *    proven on-device (via the Diag `[goals]` probe) before the read/write path is flipped to the table.
 *  • Increment 2 (next) flips `AppViewModel.goals()`/`saveGoals()` and the backup contract onto the table.
 */
@Entity(tableName = "goals", indices = [Index("workspaceId")])
@androidx.compose.runtime.Immutable
data class GoalEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String = "🎯",
    val listId: String = "",
    val habitId: String = "",
    val activityId: String = "",
    val budgetMinutes: Int = 0,
    val targetEpochDay: Long = 0L,
    val note: String = "",
    val area: String = "",
    val identity: String = "",
    val milestonesJson: String = "",   // JSON List<GoalMilestone> ("" = none)
    val keyResultsJson: String = "",   // JSON List<KeyResult> ("" = none)
    val cycleStartEpochDay: Long = 0L,
    val cycleWeeks: Int = 0,
    val reviewCadenceDays: Int = 7,
    val archived: Boolean = false,
    val workspaceId: String = "",
)

/** The review log (0.4 / moat #5) — one row per review sitting; promoted out of `goalReviewsJson`. */
@Entity(tableName = "goal_reviews", indices = [Index("goalId")])
@androidx.compose.runtime.Immutable
data class GoalReviewEntity(
    @PrimaryKey val id: String,
    val goalId: String = "",
    val epochDay: Long,
    val executionPct: Int = 0,
    val commitmentsKept: Int = 0,
    val commitmentsTotal: Int = 0,
    val note: String = "",
    val createdAt: Long = 0L,
)

// ── Lossless mappers between the Room row and the domain model ────────────────────────────────────
private fun decodeMilestones(s: String): List<GoalMilestone> =
    if (s.isBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<GoalMilestone>>(s) }.getOrDefault(emptyList())

private fun decodeKeyResults(s: String): List<KeyResult> =
    if (s.isBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<KeyResult>>(s) }.getOrDefault(emptyList())

fun GoalEntity.toDomain(): Goal = Goal(
    id = id, name = name, emoji = emoji, listId = listId, habitId = habitId, activityId = activityId,
    budgetMinutes = budgetMinutes, targetEpochDay = targetEpochDay, note = note, area = area, identity = identity,
    milestones = decodeMilestones(milestonesJson), keyResults = decodeKeyResults(keyResultsJson),
    cycleStartEpochDay = cycleStartEpochDay, cycleWeeks = cycleWeeks, reviewCadenceDays = reviewCadenceDays,
    archived = archived, workspaceId = workspaceId,
)

fun Goal.toEntity(): GoalEntity = GoalEntity(
    id = id, name = name, emoji = emoji, listId = listId, habitId = habitId, activityId = activityId,
    budgetMinutes = budgetMinutes, targetEpochDay = targetEpochDay, note = note, area = area, identity = identity,
    milestonesJson = if (milestones.isEmpty()) "" else runCatching { AppJson.encodeToString(milestones) }.getOrDefault(""),
    keyResultsJson = if (keyResults.isEmpty()) "" else runCatching { AppJson.encodeToString(keyResults) }.getOrDefault(""),
    cycleStartEpochDay = cycleStartEpochDay, cycleWeeks = cycleWeeks, reviewCadenceDays = reviewCadenceDays,
    archived = archived, workspaceId = workspaceId,
)

fun GoalReviewEntity.toDomain(): GoalReview = GoalReview(
    id = id, goalId = goalId, epochDay = epochDay, executionPct = executionPct,
    commitmentsKept = commitmentsKept, commitmentsTotal = commitmentsTotal, note = note, createdAt = createdAt,
)

fun GoalReview.toEntity(): GoalReviewEntity = GoalReviewEntity(
    id = id, goalId = goalId, epochDay = epochDay, executionPct = executionPct,
    commitmentsKept = commitmentsKept, commitmentsTotal = commitmentsTotal, note = note, createdAt = createdAt,
)
