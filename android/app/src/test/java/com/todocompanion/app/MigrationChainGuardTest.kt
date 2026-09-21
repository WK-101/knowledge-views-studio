package com.todocompanion.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.todocompanion.app.data.AppDatabase
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
 * Re-audit #20 — a HEADLESS (JVM/Robolectric) guard on the Room migration chain.
 *
 * [MigrationTest] replays every step against a real SQLite engine, but it is instrumented
 * (MigrationTestHelper needs a device), so it does not run in the JVM `testDebugUnitTest` gate that CI and
 * every local `./gradlew test` use. That leaves the single most common Room mistake — bumping the
 * `@Database(version = …)` but forgetting to add the matching migration to [AppDatabase.ALL_MIGRATIONS] —
 * unguarded on the JVM. This test closes that gap without a device: it reads the chain's shape and the
 * declared version and proves they agree, and it opens the current schema to prove the @Entity graph is
 * internally consistent.
 *
 * Note on indices (also #20): the two columns the audit flagged were checked — `events.calendarId` is
 * already indexed (index_events_calendarId, and the composite index_events_calendarId_startMillis), and
 * `time_entries` is only ever read whole-table (observeAll + in-Kotlin workspace filter; there is no
 * `WHERE workspaceId` query), so a workspaceId index would never be used. No index change was made:
 * adding an unused one is a schema-hash/migration cost for zero query benefit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationChainGuardTest {

    private lateinit var db: AppDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).addMigrations(*AppDatabase.ALL_MIGRATIONS).allowMainThreadQueries().build()
    }

    @After fun teardown() = db.close()

    @Test fun migrationChainIsContiguousAndEndsAtTheDeclaredVersion() {
        // @Database has BINARY retention, so it isn't readable via reflection at runtime; the DB's SQLite
        // user_version (which Room sets from the @Database version) is the reliable source.
        val declaredVersion = db.openHelper.readableDatabase.version
        val steps = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertTrue("there must be migrations", steps.isNotEmpty())
        // Every migration advances exactly one version.
        steps.forEach { (from, to) -> assertEquals("migration $from→$to must span exactly one version", from + 1, to) }
        // No two migrations start at the same version.
        assertEquals("duplicate migration start versions", steps.size, steps.map { it.first }.toSet().size)
        // Contiguous: each migration's end is the next one's start.
        for (i in 0 until steps.size - 1) {
            assertEquals("gap between ${steps[i]} and ${steps[i + 1]}", steps[i].second, steps[i + 1].first)
        }
        // The chain must land exactly on the @Database version — the invariant a forgotten migration breaks.
        assertEquals("migration chain must reach the declared DB version", declaredVersion, steps.last().second)
    }

    @Test fun currentSchemaOpensAndReadsCleanly() = runBlocking {
        // A fresh in-memory build materializes the CURRENT @Entity graph directly; a trivial read across a few
        // representative tables proves Room accepts the declared schema (a broken @Entity / @Index fails here).
        assertTrue("tasks table reads empty on a fresh DB", db.taskDao().getAll().isEmpty())
        assertTrue("events table reads empty on a fresh DB", db.eventDao().getAll().isEmpty())
        assertTrue("time entries table reads empty on a fresh DB", db.timeTrackingDao().getEntries().isEmpty())
    }
}
