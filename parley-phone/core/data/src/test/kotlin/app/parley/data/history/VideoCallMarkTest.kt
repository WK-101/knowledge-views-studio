package app.parley.data.history

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
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A video call keeps its camera mark in Parley's archive and in a private contact's call history. */
@RunWith(RobolectricTestRunner::class)
class VideoCallMarkTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking {
            c.settings.update { AppSettings() }
            c.history.setArchiveEnabled(true)
        }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    @Test fun anArchivedVideoCallStaysAVideoCall() = runBlocking {
        val old = 1_600_000_000_000L
        val lines = listOf(
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0002", old, 60, Calls.INCOMING_TYPE, features = Calls.FEATURES_VIDEO)),
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0003", old + 1_000, 30, Calls.OUTGOING_TYPE)),
        )
        assertEquals(2, c.history.restoreLines(lines))
        val byNumber = c.history.archive.value.orEmpty().associate { it.record.number to it.record.features }
        assertEquals(Calls.FEATURES_VIDEO, byNumber["+44 20 7946 0002"])
        assertEquals(0, byNumber["+44 20 7946 0003"])
    }

    @Test fun aPrivateVideoCallStaysAVideoCall() = runBlocking {
        val id = c.vault.save(null, ContactDetails(given = "Private", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE))))
        c.vault.storePrivateCall(id, "+44 20 7946 0001", "Private", 5_000L, 60L, Calls.INCOMING_TYPE, video = true)
        c.vault.storePrivateCall(id, "+44 20 7946 0001", "Private", 9_000L, 60L, Calls.INCOMING_TYPE)
        val calls = c.vault.privateCallsOf(id).sortedBy { it.date }
        assertEquals(listOf(true, false), calls.map { it.video })
        assertTrue(CallHistory.privateEntry(calls[0]).video)
        assertFalse(CallHistory.privateEntry(calls[1]).video)
    }
}
