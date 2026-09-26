package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.toDomain
import com.wkhan.hexis.data.entity.toEntity
import com.wkhan.hexis.domain.Routine
import com.wkhan.hexis.domain.RoutineRun
import com.wkhan.hexis.domain.RoutineStep
import com.wkhan.hexis.domain.StepKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W3 (cross-module unification) — proves the routines→Room promotion is lossless before the read/write path is
 * flipped to the tables (Increment 2). Guarantees:
 *  1. The [RoutineEntity]/[RoutineRunEntity] ↔ domain mappers round-trip EVERY field, including the ordered
 *     [RoutineStep] list and the ISO-weekday cadence (JSON columns) and the string-id lists on a run.
 *  2. The DAO stores/reads/workspace-scopes routines and appends runs; the tables carry the covering indices.
 * The one-time JSON→row copy MIGRATION_86_87 performs on real data is proven separately by the device Diag
 * `[routines]` probe (JSON count/ids == table) and the instrumented MigrationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RoutineRoomParityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() = db.close()

    // A fully-populated routine: on-start action, multiple steps (timed + checkoff, linked ids, essential),
    // a reminder, a weekday cadence, a workspace.
    private val richRoutine = Routine(
        id = "r1", name = "Morning primer", emoji = "☀️", activityId = "act1", habitCategory = "health",
        note = "launch the day",
        steps = listOf(
            RoutineStep(id = "s1", title = "Water", emoji = "💧", durationSec = 60, kind = StepKind.TIMER,
                linkedHabitId = "h1", essential = true, note = "one glass"),
            RoutineStep(id = "s2", title = "Shutdown complete", durationSec = null, kind = StepKind.CHECKOFF,
                linkedTaskId = "t1", startActivityId = "act2"),
        ),
        whenReminderMin = 7 * 60, days = listOf(1, 2, 3, 4, 5),
        createdAt = 1234L, workspaceId = "ws1",
    )
    // A bare tag routine: no steps, no reminder, empty cadence (the empty-list JSON path).
    private val bareRoutine = Routine(id = "r2", name = "Deep work", workspaceId = "ws2")

    @Test fun routineEntityMapper_roundTripsEveryField_includingStepsAndCadence() {
        for (r in listOf(richRoutine, bareRoutine)) {
            assertEquals("Routine must survive toEntity()->toDomain() unchanged", r, r.toEntity().toDomain())
        }
    }

    @Test fun routineRunMapper_roundTripsEveryField() {
        val run = RoutineRun(routineId = "r1", epochDay = 19_000L, startedAtMillis = 9_000L,
            completedStepIds = listOf("s1", "s2"), skippedStepIds = listOf("s3"), totalSec = 300, lite = true, finished = false)
        // rowId is DB-internal; the domain shape must survive a table round-trip.
        assertEquals(run, run.toEntity().toDomain())
    }

    @Test fun dao_storesRoutinesWorkspaceScoped_andAppendsRuns() = runBlocking {
        db.routineDao().upsertAll(listOf(richRoutine.toEntity(), bareRoutine.toEntity()))
        db.routineDao().upsertRuns(listOf(
            RoutineRun(routineId = "r1", epochDay = 19_000L, startedAtMillis = 1L).toEntity(),
            RoutineRun(routineId = "r1", epochDay = 19_001L, startedAtMillis = 2L).toEntity(),
        ))
        val readBack = repo.routinesFromTableOnce().map { it.toDomain() }.sortedBy { it.id }
        assertEquals(listOf(bareRoutine, richRoutine).sortedBy { it.id }, readBack)
        assertEquals(setOf("r1"), db.routineDao().observeByWorkspace("ws1").first().map { it.id }.toSet())
        assertEquals("both runs appended as separate rows", 2, repo.routineRunsFromTableOnce().size)
    }

    @Test fun routineTables_haveTheCoveringIndices() {
        fun indices(table: String): Set<String> {
            val c = db.openHelper.writableDatabase.query("PRAGMA index_list(`$table`)")
            return buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }.also { c.close() }
        }
        assertTrue("routines(workspaceId) index exists", indices("routines").contains("index_routines_workspaceId"))
        assertTrue("routine_runs(routineId) index exists", indices("routine_runs").contains("index_routine_runs_routineId"))
    }
}
