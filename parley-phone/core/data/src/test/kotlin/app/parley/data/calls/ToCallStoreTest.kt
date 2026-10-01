package app.parley.data.calls

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.parley.common.calls.ToCall
import app.parley.common.calls.ToCallState
import app.parley.data.backup.TimeMachine
import app.parley.data.db.AppDatabase
import app.parley.data.records.ContactRecordStore
import app.parley.data.security.RecordCrypto
import app.parley.data.security.RecordSealing
import app.parley.data.testing.FakeAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The To call list is never lost to a sealed value that can't be opened for a moment, and never stored as plain
 * text, even when sealing fails.
 */
@RunWith(RobolectricTestRunner::class)
class ToCallStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy { context.getSharedPreferences("to_call", Context.MODE_PRIVATE) }
    private val crypto by lazy { RecordCrypto.get(context) }
    private val now = 1_700_000_000_000L

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        prefs.edit().clear().commit()
    }

    private fun stored(): String? = prefs.getString("state_v1", null)

    private fun add(number: String) = { s: ToCallState -> ToCall.remind(s, "k$number", number, now + 3_600_000, now) }

    @Test fun a_list_that_cant_be_opened_now_is_kept_and_never_overwritten() = runBlocking {
        // Sealed, but not openable right now (a Keystore hiccup looks the same to the store).
        val unreadable = RecordCrypto.TEXT_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        prefs.edit().putString("state_v1", unreadable).commit()
        val store = ToCallStore(context) { false }

        store.load()
        assertFalse(store.available)
        // A change (Decline & remind from the call screen) is refused rather than written over the stored list.
        val w = store.write(add("+12025550100"))
        assertFalse(w.changed)
        assertFalse(w.saved)
        assertEquals(unreadable, stored())
        // The store wasn't marked loaded: once the value is readable again, the list is read, not taken as empty.
        val readable = crypto.sealText(ToCall.encode(add("+12025550199")(ToCallState())))!!
        prefs.edit().putString("state_v1", readable).commit()
        store.load()
        assertTrue(store.available)
        assertEquals(listOf("+12025550199"), store.state.value.items.map { it.number })
    }

    @Test fun when_sealing_fails_nothing_plain_is_stored_and_the_change_is_written_later() = runBlocking {
        var sealing = false
        val store = ToCallStore(context, { false }) { text -> if (sealing) crypto.sealText(text) else null }

        val w = store.write(add("+12025550100"))
        assertTrue(w.changed)
        assertFalse(w.saved)
        assertNull("Never plain on disk", stored())
        assertTrue(store.pendingWrite)
        // Still on the list in memory, so the reminder works meanwhile.
        assertEquals(1, store.state.value.items.size)

        sealing = true
        assertTrue(store.resealPlain())
        assertFalse(store.pendingWrite)
        assertTrue(crypto.isSealed(stored()))
        assertEquals(listOf("+12025550100"), ToCall.decode(crypto.openText(stored())).items.map { it.number })
    }

    @Test fun a_list_left_plain_by_an_older_version_is_resealed() = runBlocking {
        val plain = ToCall.encode(add("+12025550100")(ToCallState()))
        prefs.edit().putString("state_v1", plain).commit()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val store = ToCallStore(context) { false }
            context.getSharedPreferences("record_sealing", Context.MODE_PRIVATE).edit().clear().commit()
            RecordSealing(context, db, { TimeMachine(context, ContactRecordStore(context)) }) { listOf(store) }.runIfNeeded()
            assertTrue(crypto.isSealed(stored()))
            assertEquals(listOf("+12025550100"), store.state.value.items.map { it.number })
        } finally {
            db.close()
        }
    }
}
