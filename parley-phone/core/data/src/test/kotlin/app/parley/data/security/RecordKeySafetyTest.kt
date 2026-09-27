package app.parley.data.security

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.data.db.AppDatabase
import app.parley.data.db.CallNoteEntity
import app.parley.data.db.ContactMetaEntity
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.ProviderException
import java.security.UnrecoverableKeyException

/**
 * A sealed note that can't be opened right now is never overwritten by ordinary writes, an ambiguous Keystore error
 * never replaces the records key, and a value stored plain as a fallback is sealed on a later run.
 */
@RunWith(RobolectricTestRunner::class)
class RecordKeySafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        File(context.noBackupFilesDir, "records.keys").delete()
        context.getSharedPreferences("record_crypto", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("record_sealing", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        FakeAndroidKeyStore.failure = null
        db.close()
    }

    private fun recordAliases() = FakeAndroidKeyStore.keys.keys.filter { it.startsWith("parley_records_wrap") }.toSet()

    @Test fun a_note_that_cant_be_opened_now_survives_whole_row_writes() = runBlocking {
        SealedMetaDao(db.metaDao(), RecordCrypto.fresh(context)).setMeta(ContactMetaEntity("k1", pinnedNote = "Allergic to peanuts"))
        db.metaDao().addCallNote(CallNoteEntity(numberKey = "n1", callDate = 1, text = RecordCrypto.fresh(context).sealText("Owes me a call")!!))
        val stored = db.metaDao().meta("k1")!!.pinnedNote

        // After a restart the first unwrap hits a Keystore hiccup.
        FakeAndroidKeyStore.failure = { ProviderException("keystore busy") }
        val crypto = RecordCrypto.fresh(context)
        val meta = SealedMetaDao(db.metaDao(), crypto)
        val row = meta.meta("k1")!!
        assertNull("shown as no note while it can't be opened", row.pinnedNote)
        assertThrows(RecordCrypto.UnreadableException::class.java) { crypto.openTextOrThrow(stored) }
        // A snooze, a Circle change or a restore writes the whole row back with that "no note".
        meta.setMeta(row.copy(reachOutDays = 30))
        meta.setPersonalMeta("k1", null, "sms", null, null)
        assertEquals(stored, db.metaDao().meta("k1")!!.pinnedNote)
        assertEquals(30, db.metaDao().meta("k1")!!.reachOutDays)
        assertEquals("", meta.callNotesNow(listOf("n1")).single().text)

        // Once the Keystore answers again, everything is there.
        FakeAndroidKeyStore.failure = null
        assertEquals("Allergic to peanuts", meta.meta("k1")!!.pinnedNote)
        assertEquals("Owes me a call", meta.callNotesNow(listOf("n1")).single().text)
        // Clearing the note explicitly still works.
        meta.setPinnedNote("k1", 0, null)
        assertNull(db.metaDao().meta("k1")!!.pinnedNote)
    }

    @Test fun an_unrecoverable_key_report_keeps_the_key_and_stores_plain_for_later() = runBlocking {
        val first = RecordCrypto.fresh(context)
        val sealed = first.sealText("Call after six")!!
        assertTrue(first.isSealed(sealed))
        val aliases = recordAliases()

        // AndroidKeyStore wraps some passing keystore2 errors in UnrecoverableKeyException.
        FakeAndroidKeyStore.failure = { UnrecoverableKeyException("keystore2 system error") }
        val crypto = RecordCrypto.fresh(context)
        val context2 = context.getSharedPreferences("record_sealing", Context.MODE_PRIVATE)
        context2.edit().putBoolean("done_v1", true).commit()
        assertEquals("kept plain rather than lost", "New note", crypto.sealText("New note"))
        assertFalse("the next sealing run picks it up", context2.getBoolean("done_v1", true))
        assertNull(crypto.openText(sealed))
        // Nothing was replaced or deleted, and there is nothing to tell the user.
        assertEquals(aliases, recordAliases())
        assertEquals(0L, crypto.resetAt.value)
        assertFalse(File(context.noBackupFilesDir, "records.keys").parentFile!!.listFiles()!!.any { it.name.startsWith("records.keys.lost") })

        FakeAndroidKeyStore.failure = null
        assertEquals("Call after six", RecordCrypto.fresh(context).openText(sealed))
    }

    @Test fun a_key_that_is_provably_gone_is_replaced_without_deleting_anything() = runBlocking {
        val meta = SealedMetaDao(db.metaDao(), RecordCrypto.fresh(context))
        meta.setMeta(ContactMetaEntity("k2", pinnedNote = "Old note"))
        val old = db.metaDao().meta("k2")!!.pinnedNote
        FakeAndroidKeyStore.delete("parley_records_wrap_v1")

        val crypto = RecordCrypto.fresh(context)
        val fresh = SealedMetaDao(db.metaDao(), crypto)
        fresh.setMeta(ContactMetaEntity("k3", pinnedNote = "New note"))
        // A new key under a new alias; the old wrapped key is set aside and the user is told.
        assertTrue(crypto.isSealed(db.metaDao().meta("k3")!!.pinnedNote))
        assertEquals("New note", fresh.meta("k3")!!.pinnedNote)
        assertTrue(recordAliases().any { it != "parley_records_wrap_v1" })
        assertTrue(File(context.noBackupFilesDir, "records.keys").parentFile!!.listFiles()!!.any { it.name.startsWith("records.keys.lost") })
        assertNotEquals(0L, crypto.resetAt.value)
        crypto.acknowledgeReset()
        assertEquals(0L, crypto.resetAt.value)

        // The old note stays as it was: unreadable, but never overwritten by a whole-row write.
        val row = fresh.meta("k2")!!
        assertNull(row.pinnedNote)
        fresh.setMeta(row.copy(reachOutDays = 14))
        assertEquals(old, db.metaDao().meta("k2")!!.pinnedNote)
    }
}
