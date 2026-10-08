package app.parley.data.calls

import android.app.Application
import android.provider.CallLog.Calls
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.data.ContactDetails
import app.parley.data.DataContainer
import app.parley.data.DataItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The network's names live as long as the number is an unknown caller's: a number that becomes a private contact's
 * loses them, and deleting calls takes them only with the number's last call (an undone delete brings them back).
 */
@RunWith(RobolectricTestRunner::class)
class NetworkNameLifecycleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        FakeCallLogProvider.install()
        c = DataContainer(app)
        c.startFull()
        runBlocking {
            c.settings.update { AppSettings() }
            c.history.setArchiveEnabled(true)
        }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    /** [read] once it is null (the private contacts are listed off the main thread), or what it last was. */
    private suspend fun <T> soon(read: () -> T?): T? {
        var last = read()
        withTimeoutOrNull(10_000) {
            while (last != null) {
                delay(20)
                last = read()
            }
        }
        return last
    }

    @Test fun becoming_a_private_contact_forgets_the_names() = runBlocking {
        c.networkNames.record("+442079460001", "Ravi Kumar", now, null, "GB")
        c.networkNames.record("+442079460002", "Sita Devi", now, null, "GB")
        // Saved privately (the post-call card, a new private contact or one moved to private: one save).
        c.vault.save(null, ContactDetails(given = "Plumber", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE))))
        assertNull(soon { c.networkNames.latest("+442079460001") })
        assertEquals("Sita Devi", c.networkNames.latest("+442079460002")?.name)
    }

    @Test fun a_private_contact_gaining_the_number_forgets_it_too() = runBlocking {
        val id = c.vault.save(null, ContactDetails(given = "Plumber", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE))))
        c.networkNames.record("+442079460003", "Ravi Kumar", now, null, "GB")
        c.vault.save(
            id,
            ContactDetails(
                given = "Plumber",
                phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE), DataItem(null, "+44 20 7946 0003", Phone.TYPE_WORK)),
            ),
        )
        assertNull(soon { c.networkNames.latest("+442079460003") })
    }

    @Test fun names_go_with_the_last_call_and_come_back_with_undo() = runBlocking {
        val number = "+442079460004"
        c.history.restoreLines(
            listOf(
                CallHistoryLine(call = CallLogRecord(number, now - 60_000, 30, Calls.INCOMING_TYPE)),
                CallHistoryLine(call = CallLogRecord(number, now - 30_000, 30, Calls.MISSED_TYPE)),
            ),
        )
        c.networkNames.record(number, "Ravi Kumar", now - 30_000, "sim1", "GB")
        val calls = c.history.callsFor(number)
        assertEquals(2, calls.size)

        // One stray call deleted (and undone): the name stays.
        val first = c.history.delete(listOf(calls.first()))
        assertNotNull(first)
        assertEquals("Ravi Kumar", c.networkNames.latest(number)?.name)
        c.history.undoDelete(first!!)
        assertEquals("Ravi Kumar", c.networkNames.latest(number)?.name)

        // Every call deleted: the name goes; Undo brings it back with its history.
        val all = c.history.delete(c.history.callsFor(number))
        assertNotNull(all)
        assertNull(c.networkNames.latest(number))
        c.history.undoDelete(all!!)
        val back = c.networkNames.latest(number)
        assertEquals("Ravi Kumar", back?.name)
        assertEquals("sim1", back?.accountId)
    }
}
