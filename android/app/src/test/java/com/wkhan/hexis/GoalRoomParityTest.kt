package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.data.AppRepository
import com.wkhan.hexis.data.entity.toDomain
import com.wkhan.hexis.data.entity.toEntity
import com.wkhan.hexis.domain.Goal
import com.wkhan.hexis.domain.GoalMilestone
import com.wkhan.hexis.domain.GoalReview
import com.wkhan.hexis.domain.KeyResult
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
 * W3 (cross-module unification) — proves the goals→Room promotion is lossless before the read/write path is
 * flipped to the table (Increment 2). Two guarantees:
 *  1. The [GoalEntity]/[GoalReviewEntity] ↔ domain mappers round-trip EVERY field, including the two bounded
 *     sub-lists ([GoalMilestone]s, [KeyResult]s) that ride as JSON columns — so a goal stored as a row and read
 *     back is byte-for-byte the goal the settings-JSON held.
 *  2. The DAO stores and reads goals/reviews correctly, and the tables carry the covering indices the
 *     workspace-scoped and per-goal queries rely on.
 * The one-time JSON→row copy that MIGRATION_85_86 performs on real data is proven separately by the device
 * Diag `[goals]` probe (JSON count/ids == table count/ids) and the instrumented MigrationTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GoalRoomParityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = AppRepository(db)
    }

    @After fun teardown() = db.close()

    // A fully-populated goal: every arm set, both sub-lists non-empty, a Phase-B cycle + identity + area.
    private val richGoal = Goal(
        id = "g1", name = "Run a 5K", emoji = "🏃", listId = "L1", habitId = "H1", activityId = "A1",
        budgetMinutes = 1200, targetEpochDay = 20000L, note = "why it matters", area = "Health",
        identity = "a runner",
        milestones = listOf(
            GoalMilestone(id = "m1", title = "Run 1 km", targetEpochDay = 19900L, done = true, doneEpochDay = 19890L),
            GoalMilestone(id = "m2", title = "Run 3 km"),
        ),
        keyResults = listOf(
            KeyResult(id = "k1", title = "Longest run", start = 0.0, target = 5.0, current = 2.5, unit = "km"),
            KeyResult(id = "k2", title = "Runs completed", target = 30.0, current = 8.0),
        ),
        cycleStartEpochDay = 19800L, cycleWeeks = 12, reviewCadenceDays = 7, archived = false, workspaceId = "ws1",
    )
    // A minimal goal: no arms, no sub-lists — the empty-list ("") JSON-column path.
    private val bareGoal = Goal(id = "g2", name = "Someday", workspaceId = "ws2")

    @Test fun goalEntityMapper_roundTripsEveryField_includingSubLists() {
        for (g in listOf(richGoal, bareGoal)) {
            assertEquals("Goal must survive toEntity()->toDomain() unchanged", g, g.toEntity().toDomain())
        }
    }

    @Test fun goalReviewMapper_roundTripsEveryField() {
        val r = GoalReview(id = "r1", goalId = "g1", epochDay = 19950L, executionPct = 80, commitmentsKept = 4,
            commitmentsTotal = 5, note = "solid week", createdAt = 1234L)
        assertEquals(r, r.toEntity().toDomain())
    }

    @Test fun dao_storesAndReadsGoalsAndReviews() = runBlocking {
        db.goalDao().upsertAll(listOf(richGoal.toEntity(), bareGoal.toEntity()))
        db.goalDao().upsertReview(
            GoalReview(id = "r1", goalId = "g1", epochDay = 19950L, executionPct = 80).toEntity(),
        )
        val readBack = repo.goalsFromTableOnce().map { it.toDomain() }.sortedBy { it.id }
        assertEquals(listOf(bareGoal, richGoal).sortedBy { it.id }, readBack)
        // Workspace-scoped query returns only ws1's goal.
        val ws1 = db.goalDao().observeByWorkspace("ws1").first()
        assertEquals(setOf("g1"), ws1.map { it.id }.toSet())
        assertEquals(1, repo.goalReviewsFromTableOnce().size)
    }

    @Test fun replaceWorkspaceGoals_replacesOnlyTheGivenWorkspace_leavingOthersIntact() = runBlocking {
        // Mirrors the old saveGoals semantics: saving ws1's goals must not touch ws2's.
        db.goalDao().upsertAll(listOf(
            Goal(id = "a", name = "ws1-A", workspaceId = "ws1").toEntity(),
            Goal(id = "b", name = "ws1-B", workspaceId = "ws1").toEntity(),
            Goal(id = "z", name = "ws2-Z", workspaceId = "ws2").toEntity(),
        ))
        // Replace ws1 with a set that drops "b" and adds "c"; ws2's "z" must remain.
        repo.replaceWorkspaceGoals("ws1", listOf(
            Goal(id = "a", name = "ws1-A2", workspaceId = "ws1"),
            Goal(id = "c", name = "ws1-C", workspaceId = "ws1"),
        ))
        val all = repo.goalsFromTableOnce().associate { it.id to it.name }
        assertEquals(setOf("a", "c", "z"), all.keys)
        assertEquals("ws1-A2", all["a"])   // updated
        assertEquals("ws2-Z", all["z"])    // untouched
        assertEquals(null, all["b"])       // dropped
    }

    @Test fun goalsTables_haveTheCoveringIndices() {
        fun indices(table: String): Set<String> {
            val c = db.openHelper.writableDatabase.query("PRAGMA index_list(`$table`)")
            return buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }.also { c.close() }
        }
        assertTrue("goals(workspaceId) index exists", indices("goals").contains("index_goals_workspaceId"))
        assertTrue("goal_reviews(goalId) index exists", indices("goal_reviews").contains("index_goal_reviews_goalId"))
    }
}
