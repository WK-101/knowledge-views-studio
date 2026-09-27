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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Calls with private (vault) contacts never get into the archive, also when a backup puts old calls back. */
@RunWith(RobolectricTestRunner::class)
class ArchivePrivacyTest {
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

    @Test fun restoringABackupLeavesPrivateCallsOut() = runBlocking {
        c.vault.save(null, ContactDetails(given = "Private", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE))))
        val old = 1_600_000_000_000L
        val lines = listOf(
            CallHistoryLine(call = CallLogRecord("+442079460001", old, 60, Calls.INCOMING_TYPE)),
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0002", old + 1_000, 30, Calls.OUTGOING_TYPE)),
        )
        assertEquals(1, c.history.restoreLines(lines))
        assertEquals(listOf("+44 20 7946 0002"), c.history.archive.value.orEmpty().map { it.record.number })
    }
}
