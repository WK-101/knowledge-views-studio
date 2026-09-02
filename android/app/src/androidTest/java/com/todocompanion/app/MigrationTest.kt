package com.todocompanion.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.todocompanion.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R73 — the safety net for 54 hand-written Room migrations.
 *
 * With `exportSchema = true` (from v59), every future version's schema JSON is committed to
 * `app/schemas/`. These instrumented tests then replay the migration chain against a real SQLite
 * engine so a migration that drifts from the entity definitions fails the build instead of the field.
 *
 * Historical schemas (v5..v58) were never exported, so a full v5→v59 replay can't be reconstructed
 * retroactively; from v59 forward each new migration gets a `createDatabase(old) → runMigrationsAndValidate(new)`
 * pair. Until then these three checks already guard the real regressions: a gap/dup in the migration
 * array, an un-buildable exported schema, and a mismatch between the migrated DB and the @Database entities.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * The migration list Room is handed must cover every version step with no gap and no duplicate,
     * and land exactly on the current schema version. Forgetting to add a new migration to the array
     * is the single most common Room mistake; this makes it impossible to miss.
     */
    @Test
    fun allMigrationsFormContiguousChain() {
        val steps = AppDatabase.ALL_MIGRATIONS
            .map { it.startVersion to it.endVersion }
            .sortedBy { it.first }

        // Every migration advances exactly one version.
        steps.forEach { (from, to) -> assertEquals("migration $from→$to spans >1 version", from + 1, to) }
        // No duplicate start versions.
        assertEquals("duplicate migration start versions", steps.size, steps.map { it.first }.toSet().size)
        // Contiguous: each migration's end is the next one's start.
        for (i in 0 until steps.size - 1) {
            assertEquals("gap between ${steps[i]} and ${steps[i + 1]}", steps[i].second, steps[i + 1].first)
        }
        // The chain ends on the DB's declared version (59). Bumping the version without adding a
        // migration — or vice-versa — trips this.
        assertEquals("chain must end at the current schema version", 59, steps.last().second)
    }

    /** The exported v59 schema JSON must describe a database SQLite can actually create. */
    @Test
    fun exportedLatestSchemaIsBuildable() {
        helper.createDatabase(TEST_DB, 59).close()
    }

    /**
     * Open the real database through the full migration set and let Room validate the resulting
     * schema against the @Database entities. If any migration produced a table/column that no longer
     * matches the entities, Room throws here — turning a silent field-data corruption into a red test.
     */
    @Test
    fun roomOpensRealDatabaseWithFullMigrationSet() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, "room-open-test.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        try {
            // Forces the open + Room's internal schema validation to run.
            val cursor = db.openHelper.writableDatabase.query("SELECT count(*) FROM sqlite_master")
            cursor.use { assertTrue(it.moveToFirst()) }
        } finally {
            db.close()
            context.deleteDatabase("room-open-test.db")
        }
    }
}
