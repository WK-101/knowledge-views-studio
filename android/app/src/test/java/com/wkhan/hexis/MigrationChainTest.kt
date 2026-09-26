package com.wkhan.hexis

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wkhan.hexis.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R109 audit — the headless guard for Room's migration chain.
 *
 * The real safety net (a replay of every migration against real SQLite) lives in the instrumented
 * [MigrationTest], but instrumented tests do NOT run in the plain `:app:testDebugUnitTest` suite — so on a
 * headless CI (and this repo's) the single most valuable Room invariant went unchecked, and its assertion
 * had silently gone stale (pinned to version 82 while the DB advanced to 87). Forgetting to add a migration
 * when bumping `@Database(version = …)` is the single most common — and most destructive, it wipes the user's
 * data on update — Room mistake, and nothing that actually ran was catching it.
 *
 * This is that check, moved to a Robolectric unit test that runs in the normal suite, and made **dynamic** so
 * it can never go stale: the "current version" is read back from a freshly-built database (its `PRAGMA
 * user_version`, which Room stamps to the `@Database` schema version), not hard-coded. Bump the version
 * without adding the matching migration and `chain must end at the current schema version` fails here, in a
 * test that runs on every build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationChainTest {

    /** The schema version Room stamps onto a freshly-created database — the single source of truth. */
    private fun currentSchemaVersion(): Int {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        return try {
            db.openHelper.readableDatabase.version
        } finally {
            db.close()
        }
    }

    @Test fun migrationChainIsContiguousAndEndsAtCurrentVersion() {
        val steps = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        assertTrue("there should be migrations registered", steps.isNotEmpty())
        // Every migration advances exactly one version — no multi-version jumps to reason about.
        steps.forEach { (from, to) -> assertEquals("migration $from→$to must span exactly one version", from + 1, to) }
        // No two migrations start at the same version.
        assertEquals("duplicate migration start versions", steps.size, steps.map { it.first }.toSet().size)
        // Contiguous: each migration's end is the next one's start (no gap in the path).
        for (i in 0 until steps.size - 1) {
            assertEquals("gap between ${steps[i]} and ${steps[i + 1]}", steps[i].second, steps[i + 1].first)
        }
        // The chain must land exactly on the DB's real current version. Bumping @Database(version) without
        // adding the matching migration — or the reverse — trips this. Read dynamically so it never goes stale.
        val current = currentSchemaVersion()
        assertEquals("migration chain must end at the current schema version", current, steps.last().second)
    }
}
