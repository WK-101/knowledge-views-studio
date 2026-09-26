package com.wkhan.hexis

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import com.wkhan.hexis.domain.Goal
import com.wkhan.hexis.domain.GoalReview
import com.wkhan.hexis.domain.GoalReviews
import com.wkhan.hexis.domain.Goals
import com.wkhan.hexis.domain.Routine
import com.wkhan.hexis.domain.RoutineRun
import com.wkhan.hexis.domain.RoutineRuns
import com.wkhan.hexis.domain.Routines
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ceiling 3 — a HEADLESS (JVM/Robolectric) replay of the migration SQL *bodies* against a real SQLite engine.
 *
 * The existing [MigrationChainGuardTest] / [MigrationChainTest] only assert the migration array's integer
 * shape (contiguous, lands on the declared version); they build a fresh DB directly at the current version, so
 * they never execute a single migration statement. The one test that actually replays the SQL, the
 * instrumented [MigrationTest], is device-only and excluded from `./gradlew test`. That left the sharpest
 * data-loss exposure — a fault in the hand-written JSON→row copies of MIGRATION_85_86 / MIGRATION_86_87 (the
 * Goals and Routines promotions) — passing the entire headless CI gate.
 *
 * This closes that gap without a device: it stands up a real SQLite DB at v85 with a seeded `settings` k/v
 * table (the pre-migration source of truth), runs the two migrations' `migrate()` bodies in order, and proves
 * the parse → `toEntity()` → INSERT copy actually produces the right rows. A wrong column, a mis-ordered
 * INSERT, or a broken codec now fails on the JVM instead of only on a user's device. The migrations are pulled
 * from [AppDatabase.ALL_MIGRATIONS] (they are private on the companion) so the test drives the exact objects
 * that ship.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationReplayTest {

    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    private val m8586 = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 85 && it.endVersion == 86 }
    private val m8687 = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 86 && it.endVersion == 87 }

    @Before fun setup() {
        // A real (Robolectric-backed) SQLite DB at v85 whose only prerequisite is the settings k/v table the
        // two migrations read from. The migrations CREATE their own target tables, so nothing else is needed.
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(85) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build(),
        )
        db = helper.writableDatabase
    }

    @After fun teardown() = helper.close()

    private fun seed(key: String, value: String) =
        db.execSQL("INSERT OR REPLACE INTO `settings` VALUES (?, ?)", arrayOf<Any?>(key, value))

    private fun count(table: String): Int =
        db.query("SELECT COUNT(*) FROM `$table`").use { c -> c.moveToFirst(); c.getInt(0) }

    private fun scalar(sql: String): String? =
        db.query(sql).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    @Test fun replaysGoalsAndRoutinesJsonIntoRows() {
        val goals = listOf(
            Goal(id = "g1", name = "Ship v1", listId = "l1", workspaceId = "w1"),
            Goal(id = "g2", name = "Read 12 books", emoji = "📚", area = "Growth", workspaceId = "w2"),
        )
        val reviews = listOf(GoalReview(id = "rv1", goalId = "g1", epochDay = 20_000L, executionPct = 80))
        val routines = listOf(
            Routine(id = "ro1", name = "Morning", createdAt = 111L, workspaceId = "w1"),
            Routine(id = "ro2", name = "Shutdown", days = listOf(1, 2, 3, 4, 5), workspaceId = "w2"),
        )
        val runs = listOf(RoutineRun(routineId = "ro1", epochDay = 20_000L, startedAtMillis = 999L, totalSec = 300))

        seed("goals", Goals.encode(goals))
        seed("goal_reviews", GoalReviews.encode(reviews))
        seed("routines", Routines.encode(routines))
        seed("routine_runs", RoutineRuns.encode(runs))

        // Run the real migration bodies, in chain order.
        m8586.migrate(db)
        m8687.migrate(db)

        // Every seeded JSON row was copied into its new table…
        assertEquals("goals copied", 2, count("goals"))
        assertEquals("goal_reviews copied", 1, count("goal_reviews"))
        assertEquals("routines copied", 2, count("routines"))
        assertEquals("routine_runs copied", 1, count("routine_runs"))

        // …and the fields land in the right columns (a mis-ordered positional INSERT would fail these).
        assertEquals("Read 12 books", scalar("SELECT `name` FROM `goals` WHERE `id`='g2'"))
        assertEquals("w2", scalar("SELECT `workspaceId` FROM `goals` WHERE `id`='g2'"))
        assertEquals("g1", scalar("SELECT `goalId` FROM `goal_reviews` WHERE `id`='rv1'"))
        assertEquals("Shutdown", scalar("SELECT `name` FROM `routines` WHERE `id`='ro2'"))
        assertEquals("ro1", scalar("SELECT `routineId` FROM `routine_runs` LIMIT 1"))
    }

    @Test fun replayIsIdempotentOnRerun() {
        seed("goals", Goals.encode(listOf(Goal(id = "g1", name = "Only", workspaceId = "w1"))))

        // Re-running the additive promotion must not duplicate rows (INSERT OR REPLACE on the PK).
        m8586.migrate(db)
        m8586.migrate(db)

        assertEquals("re-run must not duplicate goals", 1, count("goals"))
        assertEquals("Only", scalar("SELECT `name` FROM `goals` WHERE `id`='g1'"))
    }

    @Test fun replayWithNoSeededJsonCreatesEmptyTables() {
        // No settings rows for goals/routines: the migrations must still create the tables and leave them empty
        // (the JSON read is guarded), not crash. count() throwing would mean a table was never created.
        m8586.migrate(db)
        m8687.migrate(db)

        assertEquals(0, count("goals"))
        assertEquals(0, count("goal_reviews"))
        assertEquals(0, count("routines"))
        assertEquals(0, count("routine_runs"))
    }
}
