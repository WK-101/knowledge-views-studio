package app.parley.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.parley.data.history.HistoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every schema step of Parley's database, from the exported schemas in core/data/schemas: each auto-migration must
 * produce exactly the next exported schema, and rows written by the first version must survive to the current one.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun everyStepValidatesAgainstTheExportedSchema() {
        helper.createDatabase(NAME, 1).close()
        for (version in 2..LATEST) helper.runMigrationsAndValidate(NAME, version, true).close()
    }

    @Test fun rowsFromVersionOneSurviveToTheLatest() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO block_rules (id, pattern, type, action, enabled, note, createdAt) VALUES (1, '+1555*', 'PREFIX', 'REJECT', 1, 'spam', 1000)")
            db.execSQL("INSERT INTO blocked_calls (id, number, reason, action, time) VALUES (1, '+15551234567', 'rule', 'REJECT', 2000)")
            db.execSQL("INSERT INTO speed_dial (`key`, number, label) VALUES (2, '+15550000002', 'Mum')")
            db.execSQL("INSERT INTO number_sim (matchKey, phoneAccountId) VALUES ('5550000002', 'sim-1')")
        }
        helper.runMigrationsAndValidate(NAME, LATEST, true).use { db ->
            db.query("SELECT pattern, kind, notify, hitCount, label FROM block_rules WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("+1555*", c.getString(0))
                // Columns added later take their declared defaults.
                assertEquals("BLOCK", c.getString(1))
                assertEquals("DEFAULT", c.getString(2))
                assertEquals(0, c.getInt(3))
                assertNull(c.getString(4))
            }
            db.query("SELECT number, allowed, failedOpen FROM blocked_calls").use { c ->
                c.moveToFirst()
                assertEquals("+15551234567", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getInt(2))
            }
            db.query("SELECT label FROM speed_dial WHERE `key` = 2").use { c -> c.moveToFirst(); assertEquals("Mum", c.getString(0)) }
            db.query("SELECT phoneAccountId FROM number_sim").use { c -> c.moveToFirst(); assertEquals("sim-1", c.getString(0)) }
        }
    }

    /** The app opens the migrated file through Room itself (the generated auto-migrations, no fallback). */
    @Test fun roomOpensAVersionOneFileWithTheShippedMigrations() = runBlocking {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO speed_dial (`key`, number, label) VALUES (3, '+15550000003', NULL)")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME).allowMainThreadQueries().build()
        try {
            assertEquals("+15550000003", room.prefsDao().speedDial(3)?.number)
            assertEquals(0, room.interactionDao().all().size)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val LATEST = 8
    }
}

/** The call-history archive has one version so far; its exported schema must match the entities. */
@RunWith(RobolectricTestRunner::class)
class HistoryDatabaseSchemaTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HistoryDatabase::class.java)

    @Test fun versionOneMatchesTheExportedSchema() {
        helper.createDatabase("history-test.db", 1).close()
        helper.runMigrationsAndValidate("history-test.db", 1, true).close()
    }
}
