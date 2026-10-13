package app.parley.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.parley.common.PhoneIdentity
import app.parley.data.PrefsRepository
import app.parley.data.StatePrefs
import app.parley.data.backup.TimeMachine
import app.parley.data.history.HistoryDatabase
import app.parley.data.records.ContactRecordStore
import app.parley.data.security.LineKeys
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import app.parley.data.security.SealedMetaDao
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        for (version in 2..LATEST) helper.runMigrationsAndValidate(NAME, version, true, *Migrations.ALL).close()
    }

    @Test fun rowsFromVersionOneSurviveToTheLatest() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO block_rules (id, pattern, type, action, enabled, note, createdAt) VALUES (1, '+1555*', 'PREFIX', 'REJECT', 1, 'spam', 1000)",
            )
            db.execSQL("INSERT INTO blocked_calls (id, number, reason, action, time) VALUES (1, '+15551234567', 'rule', 'REJECT', 2000)")
            db.execSQL("INSERT INTO speed_dial (`key`, number, label) VALUES (2, '+15550000002', 'Mum')")
            db.execSQL("INSERT INTO number_sim (matchKey, phoneAccountId) VALUES ('5550000002', 'sim-1')")
        }
        helper.runMigrationsAndValidate(NAME, LATEST, true, *Migrations.ALL).use { db ->
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

    /**
     * The two latest steps in order: 8 → 9 adds contact_meta.parleyRelations (relations kept in Parley only), 9 → 10
     * adds the indexes for private calls, the journal and call rings. Rows written at 8 survive both.
     */
    @Test fun eightToNineToTenKeepsRowsAndAddsBothChanges() {
        helper.createDatabase(NAME, 8).use { db ->
            db.execSQL("INSERT INTO contact_meta (lookupKey, pinnedNote) VALUES ('ana', 'note')")
            db.execSQL("INSERT INTO private_calls (id, vaultId, blob, date, durationSec, type, dedupeKey) VALUES (1, 7, x'00', 3000, 12, 1, 'k1')")
            db.execSQL("INSERT INTO journal (id, contactKey, displayName, action, time, payload, restored) VALUES (1, 'ana', 'Ana', 'EDIT', 4000, x'00', 0)")
        }
        helper.runMigrationsAndValidate(NAME, 9, true).use { db ->
            db.query("SELECT pinnedNote, parleyRelations FROM contact_meta WHERE lookupKey = 'ana'").use { c ->
                c.moveToFirst()
                assertEquals("note", c.getString(0))
                assertNull(c.getString(1))
            }
            db.execSQL("UPDATE contact_meta SET parleyRelations = '[]' WHERE lookupKey = 'ana'")
        }
        helper.runMigrationsAndValidate(NAME, 10, true).use { db ->
            db.query("SELECT parleyRelations FROM contact_meta WHERE lookupKey = 'ana'").use { c -> c.moveToFirst(); assertEquals("[]", c.getString(0)) }
            db.query("SELECT vaultId, date FROM private_calls WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals(7L, c.getLong(0))
                assertEquals(3000L, c.getLong(1))
            }
            db.query("SELECT COUNT(*) FROM journal").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            val indexes = buildSet {
                db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { c -> while (c.moveToNext()) add(c.getString(0)) }
            }
            for (name in listOf(
                "index_private_calls_vaultId_date_type", "index_private_calls_date", "index_journal_time", "index_journal_contactKey",
                "index_call_rings_startedAt",
            )) {
                assertTrue("missing $name", name in indexes)
            }
        }
    }

    /**
     * 11 → 12 → 13: a temporary contact saved before keeps its date across both steps and stays a fixed one (no
     * last-call days, not waiting for a case); at 13 the new columns take a rule.
     */
    @Test fun elevenToTwelveToThirteenKeepsTemporaryContactsFixed() {
        helper.createDatabase(NAME, 11).use { db ->
            db.execSQL(
                "INSERT INTO temporary_contacts (lookupKey, contactId, expiresAt, purgeHistory, rawIds, name, keepAsked) " +
                    "VALUES ('plumber', 4, 9000, 1, '12', 'Plumber', 0)",
            )
        }
        helper.runMigrationsAndValidate(NAME, 12, true, *Migrations.ALL).use { db ->
            db.query("SELECT expiresAt, rawIds FROM temporary_contacts WHERE lookupKey = 'plumber'").use { c ->
                c.moveToFirst()
                assertEquals(9000L, c.getLong(0))
                assertEquals("12", c.getString(1))
            }
        }
        twelveToThirteen()
    }

    /** 12 → 13 adds the temporary contacts' rule columns: none set, so every existing one stays a fixed date. */
    @Test fun twelveToThirteenKeepsTemporaryContactsFixed() {
        helper.createDatabase(NAME, 12).use { db ->
            db.execSQL(
                "INSERT INTO temporary_contacts (lookupKey, contactId, expiresAt, purgeHistory, rawIds, name, keepAsked) " +
                    "VALUES ('plumber', 4, 9000, 1, '12', 'Plumber', 0)",
            )
        }
        twelveToThirteen()
    }

    private fun twelveToThirteen() {
        helper.runMigrationsAndValidate(NAME, 13, true, *Migrations.ALL).use { db ->
            db.query("SELECT expiresAt, rawIds, slideDays, untilCase FROM temporary_contacts WHERE lookupKey = 'plumber'").use { c ->
                c.moveToFirst()
                assertEquals(9000L, c.getLong(0))
                assertEquals("12", c.getString(1))
                assertTrue(c.isNull(2))
                assertEquals(0, c.getInt(3))
            }
            db.execSQL("UPDATE temporary_contacts SET slideDays = 7, untilCase = 1 WHERE lookupKey = 'plumber'")
        }
    }

    /** The app opens the migrated file through Room itself (the generated auto-migrations, no fallback). */
    @Test fun roomOpensAVersionOneFileWithTheShippedMigrations() = runBlocking {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO speed_dial (`key`, number, label) VALUES (3, '+15550000003', NULL)")
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        try {
            assertEquals("+15550000003", room.prefsDao().speedDial(3)?.number)
            assertEquals(0, room.interactionDao().all().size)
        } finally {
            room.close()
        }
    }

    /**
     * 10 → 11 rebuilds the journal and vault_contacts with the blob last: every row survives with every byte (a payload
     * and a sealed detail larger than a page included), the indexes come back, and the new photo table is empty.
     */
    @Test fun tenToElevenKeepsEveryRowAndPhotoWithTheBlobsLast() {
        val bigPayload = ByteArray(300_000) { (it % 251).toByte() }
        val bigDetail = ByteArray(120_000) { (it % 13).toByte() }
        helper.createDatabase(NAME, 10).use { db ->
            db.execSQL(
                "INSERT INTO journal (id, contactKey, displayName, action, time, payload, restored) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(5, "ana", "Ana", "DELETE", 4000, bigPayload, 0),
            )
            db.execSQL(
                "INSERT INTO journal (id, contactKey, displayName, action, time, payload, restored) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(9, "bo", "Bo", "EDIT", 5000, byteArrayOf(1, 2), 1),
            )
            db.execSQL(
                "INSERT INTO vault_contacts (id, callerIdBlob, detailBlob, expiresAt, createdAt) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(3, byteArrayOf(7, 7), bigDetail, 9000L, 1000L),
            )
            db.execSQL("INSERT INTO vault_numbers (vaultId, hmac) VALUES (3, 'h')")
            // The newest of each was deleted before the upgrade: its id must not be handed out again.
            db.execSQL("INSERT INTO journal (id, contactKey, displayName, action, time, payload, restored) VALUES (12, 'dy', 'Dy', 'EDIT', 5500, x'00', 0)")
            db.execSQL("DELETE FROM journal WHERE id = 12")
            db.execSQL("INSERT INTO vault_contacts (id, callerIdBlob, detailBlob, createdAt) VALUES (8, x'01', x'02', 1)")
            db.execSQL("DELETE FROM vault_contacts WHERE id = 8")
        }
        helper.runMigrationsAndValidate(NAME, 11, true, *Migrations.ALL).use { db ->
            db.query("SELECT id, contactKey, displayName, action, time, restored, photoHashes, payload FROM journal ORDER BY id").use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals(5L, c.getLong(0))
                assertEquals("Ana", c.getString(2))
                assertEquals("DELETE", c.getString(3))
                assertEquals(4000L, c.getLong(4))
                assertEquals(0, c.getInt(5))
                assertTrue(c.isNull(6))
                assertTrue(bigPayload.contentEquals(c.getBlob(7)))
                c.moveToNext()
                assertEquals(9L, c.getLong(0))
                assertEquals(1, c.getInt(5))
            }
            db.query("SELECT id, callerIdBlob, expiresAt, createdAt, detailBlob FROM vault_contacts").use { c ->
                c.moveToFirst()
                assertEquals(3L, c.getLong(0))
                assertTrue(byteArrayOf(7, 7).contentEquals(c.getBlob(1)))
                assertEquals(9000L, c.getLong(2))
                assertEquals(1000L, c.getLong(3))
                assertTrue(bigDetail.contentEquals(c.getBlob(4)))
            }
            db.query("SELECT vaultId FROM vault_numbers WHERE hmac = 'h'").use { c -> c.moveToFirst(); assertEquals(3L, c.getLong(0)) }
            // The blob is the last column of both tables.
            for (table in listOf("journal" to "payload", "vault_contacts" to "detailBlob")) {
                val columns = buildList { db.query("PRAGMA table_info(${table.first})").use { c -> while (c.moveToNext()) add(c.getString(1)) } }
                assertEquals(table.second, columns.last())
            }
            db.query("SELECT COUNT(*) FROM journal_photos").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            // New rows continue after every id ever handed out, deleted ones included.
            db.execSQL("INSERT INTO journal (contactKey, displayName, action, time, restored, payload) VALUES ('cy', 'Cy', 'EDIT', 6000, 0, x'00')")
            db.query("SELECT MAX(id) FROM journal").use { c -> c.moveToFirst(); assertEquals(13L, c.getLong(0)) }
            db.execSQL("INSERT INTO vault_contacts (callerIdBlob, detailBlob, createdAt) VALUES (x'01', x'02', 2)")
            db.query("SELECT MAX(id) FROM vault_contacts").use { c -> c.moveToFirst(); assertEquals(9L, c.getLong(0)) }
        }
    }

    /** A new install creates both tables with the blob last too, and a listing reads no detail blob. */
    @Test fun aNewDatabaseHasTheBlobsLast() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val db = room.openHelper.writableDatabase
            for (table in listOf("journal" to "payload", "vault_contacts" to "detailBlob")) {
                val columns = buildList { db.query("PRAGMA table_info(${table.first})").use { c -> while (c.moveToNext()) add(c.getString(1)) } }
                assertEquals(table.second, columns.last())
            }
            val id = room.vaultDao().upsert(VaultContactEntity(callerIdBlob = byteArrayOf(1), expiresAt = 5L, detailBlob = ByteArray(200_000)))
            assertEquals(listOf(id), room.vaultDao().expired(10L).map { it.id })
            assertEquals(200_000, room.vaultDao().detailBlob(id)?.size)
        } finally {
            room.close()
        }
    }

    /**
     * 11 → 12 adds the sealed line key beside call notes and remembered SIMs: every row survives as it was (still
     * plain, keyed later by the app), and once Room opens the file the app keys them and finds them by their number.
     */
    @Test fun elevenToTwelveKeepsNotesAndSimsAndTheAppKeysThem() = runBlocking {
        val key = PhoneIdentity.key("+44 20 7946 0123", "GB")
        val legacy = PhoneIdentity.legacyKey("+44 20 7946 0456")
        helper.createDatabase(NAME, 11).use { db ->
            db.execSQL("INSERT INTO call_notes (id, numberKey, callDate, text, createdAt) VALUES (4, ?, 1000, 'Owes me a call', 900)", arrayOf(key))
            db.execSQL("INSERT INTO call_notes (id, numberKey, callDate, text, createdAt) VALUES (5, ?, 2000, 'Older key', 1900)", arrayOf(legacy))
            db.execSQL("INSERT INTO number_sim (matchKey, phoneAccountId) VALUES (?, 'sim-2')", arrayOf(key))
            db.execSQL("INSERT INTO speed_dial (`key`, number, label) VALUES (4, '+442079460123', 'Ada')")
        }
        helper.runMigrationsAndValidate(NAME, 12, true, *Migrations.ALL).use { db ->
            db.query("SELECT id, numberKey, callDate, text, createdAt, sealedKey FROM call_notes ORDER BY id").use { c ->
                assertEquals(2, c.count)
                c.moveToFirst()
                assertEquals(4L, c.getLong(0))
                assertEquals(key, c.getString(1))
                assertEquals(1000L, c.getLong(2))
                assertEquals("Owes me a call", c.getString(3))
                assertEquals(900L, c.getLong(4))
                assertTrue(c.isNull(5))
                c.moveToNext()
                assertEquals(legacy, c.getString(1))
            }
            db.query("SELECT matchKey, phoneAccountId, sealedKey FROM number_sim").use { c ->
                c.moveToFirst()
                assertEquals(key, c.getString(0))
                assertEquals("sim-2", c.getString(1))
                assertTrue(c.isNull(2))
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        FakeAndroidKeyStore.install()
        StatePrefs.open(context, "record_sealing").edit().clear().commit()
        val room = Room.databaseBuilder(context, AppDatabase::class.java, NAME).addMigrations(*Migrations.ALL).allowMainThreadQueries().build()
        try {
            val crypto = RecordCrypto.get(context)
            val lines = LineKeys.get(context)
            val prefs = PrefsRepository(room, crypto, lines) { "GB" }
            RecordSealing(context, room, { TimeMachine(context, ContactRecordStore(context)) }) { listOf(prefs) }.runIfNeeded()
            val raw = room.openHelper.readableDatabase
            for (table in listOf("call_notes", "number_sim", "speed_dial")) {
                raw.query("SELECT * FROM $table").use { c ->
                    while (c.moveToNext()) for (i in 0 until c.columnCount) {
                        if (c.getType(i) != Cursor.FIELD_TYPE_STRING) continue
                        val text = c.getString(i)
                        assertFalse("$table keeps a number", text.contains("79460123") || text.contains("79460456"))
                    }
                }
            }
            val meta = SealedMetaDao(room.metaDao(), crypto, lines)
            assertEquals("Owes me a call", meta.callNotesNow(PhoneIdentity.lookupKeys("020 7946 0123", "GB")).single().text)
            assertEquals("Older key", meta.callNotesNow(PhoneIdentity.lookupKeys("+44 20 7946 0456", "GB")).single().text)
            assertEquals("sim-2", prefs.simFor("+44 20 7946 0123"))
            assertEquals("+442079460123", prefs.speedDial(4)?.number)
        } finally {
            room.close()
        }
    }

    /** Deleted rows are overwritten, not left in the file's free pages: secure_delete is on for every opened database. */
    @Test fun secureDeleteIsOn() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("parley.db")
        val room = AppDatabase.create(context)
        try {
            val db = room.openHelper.writableDatabase
            assertTrue(SecureDelete.isOn(db))
            val marker = "Forget-me-note-31415926"
            val id = room.metaDao().addCallNote(CallNoteEntity(numberKey = "#k", callDate = 1, text = marker.repeat(40)))
            room.metaDao().deleteCallNote(id)
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        } finally {
            room.close()
        }
        val bytes = String(context.getDatabasePath("parley.db").readBytes(), Charsets.ISO_8859_1)
        assertFalse("the deleted note is gone from the file", bytes.contains("Forget-me-note"))
    }

    private companion object {
        const val NAME = "migration-test.db"
        const val LATEST = 13
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

    /** Version 2 adds the call facts table; archived calls are untouched. */
    @Test fun versionTwoAddsCallFactsAndKeepsTheArchive() {
        helper.createDatabase("history-step.db", 1).use { db ->
            db.execSQL(
                "INSERT INTO archived_calls (id, dedupeKey, personKey, date, durationSec, type, blob, archivedAt) " +
                    "VALUES (1, 'd', 'p', 1000, 5, 1, x'01', 2000)",
            )
        }
        helper.runMigrationsAndValidate("history-step.db", 2, true).use { db ->
            db.query("SELECT personKey FROM archived_calls").use { c -> c.moveToFirst(); assertEquals("p", c.getString(0)) }
            db.execSQL("INSERT INTO call_facts (id, kind, lineKey, startedAt, blob) VALUES ('a', 'ring_facts', 'm:x', 1, x'07')")
            db.query("SELECT COUNT(*) FROM call_facts").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }
}
