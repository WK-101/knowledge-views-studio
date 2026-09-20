package com.todocompanion.app.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.todocompanion.app.domain.Routine
import com.todocompanion.app.domain.RoutineRun
import com.todocompanion.app.domain.RoutineStep
import com.todocompanion.app.util.AppJson
import kotlinx.serialization.encodeToString

/**
 * W3 (cross-module unification) — Routines (the press-play rituals) and their run history promoted out of the
 * settings-JSON blobs (`routines` / `routine_runs`) into their own Room tables — the second half of retiring
 * the "settings-JSON as a persistence substrate" the audit flags (Goals were the first half). A routine is now
 * a queryable, workspace-indexed row; a run is an append-only row keyed by a synthetic id. The one transient
 * value — the single in-progress `active_routine_run` — deliberately stays in settings: it is ephemeral,
 * cleared on finish, and never queried, so a table would be dead weight.
 *
 * A routine's two variable-length sub-lists — its ordered [RoutineStep]s and its ISO-weekday cadence — ride as
 * JSON columns: they're edited only in the routine editor and never queried across routines, so a column is the
 * right altitude; every other field is a real column.
 *
 * Rollout is staged for safety (a JSON→Room promotion is the audit's highest-risk item, and routines also drive
 * the alarm scheduler + the runner): Increment 1 (these tables + MIGRATION_86_87) is ADDITIVE — it copies the
 * parsed JSON into rows while the app still reads/writes the JSON, so behaviour is unchanged and the copy is
 * proven on-device (Diag `[routines]` probe) before Increment 2 flips the read/write path and the scheduler.
 */
@Entity(tableName = "routines", indices = [Index("workspaceId")])
@androidx.compose.runtime.Immutable
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val emoji: String = "🔗",
    val activityId: String = "",
    val habitCategory: String = "",
    val note: String = "",
    val stepsJson: String = "[]",     // JSON List<RoutineStep>
    val whenReminderMin: Int? = null,
    val daysJson: String = "[]",      // JSON List<Int> (ISO weekdays; [] = every day)
    val createdAt: Long = 0L,
    val workspaceId: String = "",
)

/** One completed/partial press-play run — append-only history (adherence, keystone, on-this-day). The domain
 *  [RoutineRun] has no natural key, so the row carries a synthetic auto-id; it never leaves the DB (the backup
 *  transport is the id-less domain shape). */
@Entity(tableName = "routine_runs", indices = [Index("routineId")])
@androidx.compose.runtime.Immutable
data class RoutineRunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val routineId: String,
    val epochDay: Long,
    val startedAtMillis: Long,
    val completedStepIdsJson: String = "[]",
    val skippedStepIdsJson: String = "[]",
    val totalSec: Int = 0,
    val lite: Boolean = false,
    val finished: Boolean = true,
)

// ── Lossless mappers between the Room row and the domain model ────────────────────────────────────
private fun decodeSteps(s: String): List<RoutineStep> =
    if (s.isBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<RoutineStep>>(s) }.getOrDefault(emptyList())

private fun decodeIntList(s: String): List<Int> =
    if (s.isBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<Int>>(s) }.getOrDefault(emptyList())

private fun decodeStrList(s: String): List<String> =
    if (s.isBlank()) emptyList() else runCatching { AppJson.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())

fun RoutineEntity.toDomain(): Routine = Routine(
    id = id, name = name, emoji = emoji, activityId = activityId, habitCategory = habitCategory, note = note,
    steps = decodeSteps(stepsJson), whenReminderMin = whenReminderMin, days = decodeIntList(daysJson),
    createdAt = createdAt, workspaceId = workspaceId,
)

fun Routine.toEntity(): RoutineEntity = RoutineEntity(
    id = id, name = name, emoji = emoji, activityId = activityId, habitCategory = habitCategory, note = note,
    stepsJson = runCatching { AppJson.encodeToString(steps) }.getOrDefault("[]"),
    whenReminderMin = whenReminderMin,
    daysJson = runCatching { AppJson.encodeToString(days) }.getOrDefault("[]"),
    createdAt = createdAt, workspaceId = workspaceId,
)

fun RoutineRunEntity.toDomain(): RoutineRun = RoutineRun(
    routineId = routineId, epochDay = epochDay, startedAtMillis = startedAtMillis,
    completedStepIds = decodeStrList(completedStepIdsJson), skippedStepIds = decodeStrList(skippedStepIdsJson),
    totalSec = totalSec, lite = lite, finished = finished,
)

fun RoutineRun.toEntity(): RoutineRunEntity = RoutineRunEntity(
    routineId = routineId, epochDay = epochDay, startedAtMillis = startedAtMillis,
    completedStepIdsJson = runCatching { AppJson.encodeToString(completedStepIds) }.getOrDefault("[]"),
    skippedStepIdsJson = runCatching { AppJson.encodeToString(skippedStepIds) }.getOrDefault("[]"),
    totalSec = totalSec, lite = lite, finished = finished,
)
