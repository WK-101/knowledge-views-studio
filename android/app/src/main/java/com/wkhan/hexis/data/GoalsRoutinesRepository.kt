package com.wkhan.hexis.data

import androidx.room.withTransaction
import com.wkhan.hexis.data.entity.GoalEntity
import com.wkhan.hexis.data.entity.GoalReviewEntity
import com.wkhan.hexis.data.entity.RoutineEntity
import com.wkhan.hexis.data.entity.RoutineRunEntity
import com.wkhan.hexis.data.entity.WorkspaceEntity
import com.wkhan.hexis.data.entity.toDomain
import com.wkhan.hexis.data.entity.toEntity
import com.wkhan.hexis.domain.Goal
import com.wkhan.hexis.domain.GoalReview
import com.wkhan.hexis.domain.GoalReviews
import com.wkhan.hexis.domain.Goals
import com.wkhan.hexis.domain.Routine
import com.wkhan.hexis.domain.RoutineRun
import com.wkhan.hexis.domain.RoutineRuns
import com.wkhan.hexis.domain.Routines
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Safe partial decomposition of the AppRepository god-object — the **Goals + Routines data module**, lifted
 * whole out of the coordinator into its own repository.
 *
 * This cluster is genuinely self-contained (unlike the tasks / notes / time surfaces, which reach across
 * features by design): every method touches only the goal and routine tables via `db.goalDao()` /
 * `db.routineDao()` and the pure domain codecs — no FTS, no cross-feature writes, no shared `uid`/`now`/
 * `activeWs`. So it moves with zero behaviour change. AppRepository keeps one-line forwarding shims, so every
 * one of the ~19 external call sites (`repo.observeGoals()`, `repo.deleteRoutine()`, …) is unchanged.
 *
 * W3 (cross-module unification) note preserved from the origin: Goals & Routines and their logs live in Room;
 * the settings `goals`/`goal_reviews`/`routines`/`routine_runs` k/v entries are kept only as the backward-
 * compatible BACKUP transport (regenerated from the table at export, consumed into the table at import — that
 * export/import path stays on AppRepository, reading the same tables directly).
 */
internal class GoalsRoutinesRepository(private val db: AppDatabase) {
    private val goals = db.goalDao()
    private val routines = db.routineDao()

    fun observeGoals(): Flow<List<Goal>> = goals.observeAll().map { it.map { e -> e.toDomain() } }
    fun observeGoalReviews(): Flow<List<GoalReview>> = goals.observeReviews().map { it.map { e -> e.toDomain() } }
    suspend fun goalsFromTableOnce(): List<GoalEntity> = goals.getAll()
    suspend fun goalReviewsFromTableOnce(): List<GoalReviewEntity> = goals.getAllReviews()

    fun observeRoutines(): Flow<List<Routine>> = routines.observeAll().map { it.map { e -> e.toDomain() } }
    fun observeRoutineRuns(): Flow<List<RoutineRun>> = routines.observeRuns().map { it.map { e -> e.toDomain() } }
    suspend fun routinesOnce(): List<Routine> = routines.getAll().map { it.toDomain() }
    suspend fun routineRunsOnce(): List<RoutineRun> = routines.getAllRuns().map { it.toDomain() }
    suspend fun routinesFromTableOnce(): List<RoutineEntity> = routines.getAll()
    suspend fun routineRunsFromTableOnce(): List<RoutineRunEntity> = routines.getAllRuns()

    private fun routineWsOf(ws: String) = ws.ifBlank { WorkspaceEntity.DEFAULT_ID }

    /** Replace the ACTIVE workspace's routines with [list] (leaving other workspaces' intact) — mirrors the old
     *  settings-JSON saveRoutines semantics exactly, against the table, in one transaction. */
    suspend fun replaceWorkspaceRoutines(ws: String, list: List<Routine>) {
        db.withTransaction {
            val keepIds = list.map { it.id }.toSet()
            routines.getAll().filter { routineWsOf(it.workspaceId) == ws && it.id !in keepIds }.forEach { routines.deleteById(it.id) }
            routines.upsertAll(list.map { it.toEntity() })
        }
    }
    suspend fun deleteRoutine(id: String) = routines.deleteById(id)
    /** Append a press-play run, keeping the newest 400 (matches the old JSON cap + the backup transport cap). */
    suspend fun appendRoutineRun(run: RoutineRun) {
        db.withTransaction { routines.upsertRuns(listOf(run.toEntity())); routines.trimRunsTo(400) }
    }
    /** One-time, idempotent safety net for the JSON→table flip: adopt into the tables any routine (by id) or run
     *  (by routineId+startedAtMillis) that still exists only in the legacy settings-JSON. Additive; never deletes. */
    suspend fun reconcileRoutinesFromLegacyJson(routinesJson: String, runsJson: String) {
        val haveRoutineIds = routines.getAll().map { it.id }.toSet()
        val missingRoutines = Routines.parse(routinesJson).filter { it.id !in haveRoutineIds }
        if (missingRoutines.isNotEmpty()) routines.upsertAll(missingRoutines.map { it.toEntity() })
        val haveRunKeys = routines.getAllRuns().map { it.routineId to it.startedAtMillis }.toSet()
        val missingRuns = RoutineRuns.parse(runsJson).filter { (it.routineId to it.startedAtMillis) !in haveRunKeys }
        if (missingRuns.isNotEmpty()) { routines.upsertRuns(missingRuns.map { it.toEntity() }); routines.trimRunsTo(400) }
    }

    private fun goalWsOf(ws: String) = ws.ifBlank { WorkspaceEntity.DEFAULT_ID }

    /** Replace the ACTIVE workspace's goals with [list] (leaving other workspaces' goals intact) — mirrors the
     *  old settings-JSON saveGoals semantics exactly, but against the table, in one transaction. */
    suspend fun replaceWorkspaceGoals(ws: String, list: List<Goal>) {
        db.withTransaction {
            val keepIds = list.map { it.id }.toSet()
            goals.getAll().filter { goalWsOf(it.workspaceId) == ws && it.id !in keepIds }.forEach { goals.deleteById(it.id) }
            goals.upsertAll(list.map { it.toEntity() })
        }
    }
    suspend fun deleteGoal(id: String) = goals.deleteById(id)
    /** Replace the whole review log (reviews are global, not workspace-scoped — as in the old blob). */
    suspend fun replaceGoalReviews(list: List<GoalReview>) {
        db.withTransaction { goals.clearReviews(); goals.upsertReviews(list.takeLast(500).map { it.toEntity() }) }
    }
    /** One-time, idempotent safety net for the JSON→table flip: adopt into the table any goal/review that
     *  still exists only in the legacy settings-JSON (e.g. one created on an Increment-1 build before the flip).
     *  Additive — never deletes — so it can run every startup harmlessly. */
    suspend fun reconcileGoalsFromLegacyJson(goalsJson: String, reviewsJson: String) {
        val haveGoalIds = goals.getAll().map { it.id }.toSet()
        val missingGoals = Goals.parse(goalsJson).filter { it.id !in haveGoalIds }
        if (missingGoals.isNotEmpty()) goals.upsertAll(missingGoals.map { it.toEntity() })
        val haveRevIds = goals.getAllReviews().map { it.id }.toSet()
        val missingRev = GoalReviews.parse(reviewsJson).filter { it.id !in haveRevIds }
        if (missingRev.isNotEmpty()) goals.upsertReviews(missingRev.map { it.toEntity() })
    }
}
