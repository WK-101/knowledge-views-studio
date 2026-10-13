package app.parley.data.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.PhoneIdentity
import app.parley.data.PrefsRepository
import app.parley.data.StatePrefs
import app.parley.data.backup.TimeMachine
import app.parley.data.records.ContactRecordStore
import app.parley.data.db.AppDatabase
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.NumberSimEntity
import app.parley.data.db.SpeedDialEntity
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Call notes, remembered SIMs and speed dial keep no number readable in the database: notes and SIM choices are stored
 * under the line's keyed fingerprint with the line key sealed beside it, speed dial numbers are sealed, and everything
 * above reads plain line keys and numbers, found by any key form. Rows stored plain before are keyed once, and rows
 * keyed with a key since replaced are keyed again from their sealed copy.
 */
@RunWith(RobolectricTestRunner::class)
class LineKeysTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private val crypto by lazy { RecordCrypto.get(context) }
    private val lines by lazy { LineKeys.get(context) }
    private val meta by lazy { SealedMetaDao(db.metaDao(), crypto, lines) }
    private val prefs by lazy { PrefsRepository(db, crypto, lines) { "GB" } }

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        StatePrefs.open(context, "record_sealing").edit().clear().commit()
    }

    @After fun tearDown() = db.close()

    private val number = "+44 20 7946 0123"
    private val key get() = PhoneIdentity.key(number, "GB")
    private val digits = "79460123"

    private fun sealing() = RecordSealing(context, db, { TimeMachine(context, ContactRecordStore(context)) }) { listOf(prefs) }

    /** Whether any stored text of [table] holds the number's digits. */
    private fun plainIn(table: String): Boolean = db.openHelper.readableDatabase.query("SELECT * FROM $table").use { c ->
        var found = false
        while (c.moveToNext()) for (i in 0 until c.columnCount) {
            if (c.getType(i) == android.database.Cursor.FIELD_TYPE_STRING && c.getString(i).contains(digits)) found = true
        }
        found
    }

    @Test fun a_call_note_is_stored_under_a_fingerprint_and_found_by_every_key_form() = runBlocking {
        meta.addCallNote(CallNoteEntity(numberKey = key, callDate = 5, text = "Owes me a call"))
        val row = db.metaDao().allCallNotesNow().single()
        assertTrue(LineKeys.isKeyed(row.numberKey))
        assertTrue(crypto.isSealed(row.sealedKey))
        assertFalse("no digits of the number in the table", plainIn("call_notes"))
        // Stable for the install: the same line gives the same fingerprint.
        assertEquals(row.numberKey, lines.keyed(key))

        val forms = PhoneIdentity.lookupKeys(number, "GB")
        assertEquals(key, meta.callNotesNow(forms).single().numberKey)
        assertEquals("Owes me a call", meta.callNotesAny(forms).first().single().text)
        assertEquals(key, meta.allCallNotesNow().single().numberKey)
        assertEquals(1, meta.countCallNote(key, 5, "Owes me a call"))
        assertTrue(meta.callNotesNow(PhoneIdentity.lookupKeys("+44 20 7946 0999", "GB")).isEmpty())
    }

    @Test fun plain_notes_from_before_are_keyed_once_and_still_found() = runBlocking {
        val raw = db.metaDao()
        raw.addCallNote(CallNoteEntity(numberKey = key, callDate = 1, text = "Plain"))
        // Stored under the old last-digits form by a much older version.
        raw.addCallNote(CallNoteEntity(numberKey = PhoneIdentity.legacyKey(number), callDate = 2, text = "Older"))
        assertTrue(plainIn("call_notes"))
        assertEquals(2, meta.callNotesNow(PhoneIdentity.lookupKeys(number, "GB")).size)

        sealing().runIfNeeded()
        assertFalse(plainIn("call_notes"))
        assertTrue(raw.allCallNotesNow().all { LineKeys.isKeyed(it.numberKey) })
        val found = meta.callNotesNow(PhoneIdentity.lookupKeys(number, "GB"))
        assertEquals(listOf("Older", "Plain"), found.map { it.text })
        assertEquals(setOf(key, PhoneIdentity.legacyKey(number)), found.map { it.numberKey }.toSet())
    }

    @Test fun a_note_keyed_with_a_replaced_key_is_keyed_again_from_its_sealed_copy() = runBlocking {
        // What a row keyed before the Keystore lost the key looks like: a fingerprint this key never made.
        db.metaDao().addCallNote(CallNoteEntity(numberKey = "#0123456789abcdef0123456789abcdef", callDate = 3, text = "Kept", sealedKey = crypto.sealText(key)))
        assertTrue(meta.callNotesNow(listOf(key)).isEmpty())
        sealing().runIfNeeded()
        assertEquals("Kept", meta.callNotesNow(listOf(key)).single().text)
    }

    @Test fun a_sim_choice_is_keyed_and_speed_dial_sealed() = runBlocking {
        prefs.setSimFor(number, "sim-2")
        prefs.setSpeedDial(3, number, "Ada")
        assertFalse(plainIn("number_sim"))
        assertFalse(plainIn("speed_dial"))
        assertEquals("sim-2", prefs.simFor("020 7946 0123"))
        assertNull(prefs.simFor("+44 20 7946 0999"))
        assertEquals(key, prefs.numberSims.first().single().matchKey)
        assertEquals(number, prefs.speedDial(3)?.number)
        assertEquals(number, prefs.speedDials.first().single().number)

        prefs.setSimFor(number, "sim-1")
        assertEquals("one row per line", 1, db.prefsDao().allSimsNow().size)
        assertEquals("sim-1", prefs.simFor(number))
        prefs.setSimFor(number, null)
        assertTrue(db.prefsDao().allSimsNow().isEmpty())
    }

    @Test fun plain_sims_and_speed_dials_from_before_are_sealed_once() = runBlocking {
        val raw = db.prefsDao()
        raw.setSim(NumberSimEntity(PhoneIdentity.legacyKey(number), "sim-1"))
        raw.setSpeedDial(SpeedDialEntity(2, number, "Ada"))
        assertEquals("sim-1", prefs.simFor(number))

        sealing().runIfNeeded()
        assertTrue(sealing().done)
        assertFalse(plainIn("number_sim"))
        assertFalse(plainIn("speed_dial"))
        assertEquals("sim-1", prefs.simFor(number))
        assertEquals(PhoneIdentity.legacyKey(number), prefs.numberSims.first().single().matchKey)
        assertEquals(number, prefs.speedDial(2)?.number)
    }

    @Test fun a_restored_sim_is_keyed_with_this_phones_key() = runBlocking {
        prefs.restoreSim(key, "sim-9")
        assertFalse(plainIn("number_sim"))
        assertEquals("sim-9", prefs.simFor(number))
        // Another phone's fingerprint means nothing here: it is not stored.
        prefs.restoreSim("#feedfacefeedfacefeedfacefeedface", "sim-1")
        assertEquals(1, db.prefsDao().allSimsNow().size)
    }
}
