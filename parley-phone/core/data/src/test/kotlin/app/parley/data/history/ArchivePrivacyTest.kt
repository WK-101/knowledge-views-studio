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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Calls with private (vault) contacts never get into the archive, also when a backup puts old calls back, and never
 * come out of it (Recall's older calls); what the archive can vouch for.
 */
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

    @Test fun recallsOlderCallsLeaveOutANumberMadePrivateSince() = runBlocking {
        val old = 1_600_000_000_000L
        val lines = listOf(
            CallHistoryLine(call = CallLogRecord("+442079460001", old, 60, Calls.INCOMING_TYPE)),
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0002", old + 1_000, 30, Calls.OUTGOING_TYPE)),
        )
        assertEquals(2, c.history.restoreLines(lines))
        // Made private after it was archived; the next sync would purge it, but Recall may read the range first.
        c.vault.save(null, ContactDetails(given = "Private", phones = listOf(DataItem(null, "+44 20 7946 0001", Phone.TYPE_MOBILE))))
        val seen = ArrayList<String>()
        c.history.archivedBetween(old - 1, old + 10_000) { seen += it.number; true }
        assertEquals(listOf("+44 20 7946 0002"), seen)
    }

    @Test fun theCopyVouchesForCallsSinceItsOldestWithinRetention() = runBlocking {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        val lines = listOf(
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0003", now - 400 * day, 60, Calls.OUTGOING_TYPE)),
            CallHistoryLine(call = CallLogRecord("+44 20 7946 0004", now - 10 * day, 30, Calls.OUTGOING_TYPE)),
        )
        assertEquals(2, c.history.restoreLines(lines))
        assertEquals(now - 400 * day, c.history.keptSince("+44 20 7946 0004", retentionDays = 0, now = now))
        // Trimmed after 90 days: older calls may be gone, so it vouches only for the last 90.
        assertEquals(now - 90 * day, c.history.keptSince("+44 20 7946 0004", retentionDays = 90, now = now))
        // Kept forever, a line's calls are never trimmed.
        c.history.setKeepForever(listOf("+44 20 7946 0004"), true)
        assertEquals(now - 400 * day, c.history.keptSince("+44 20 7946 0004", retentionDays = 90, now = now))
        // Off: nothing to vouch with.
        c.history.setArchiveEnabled(false)
        assertNull(c.history.keptSince("+44 20 7946 0004", retentionDays = 0, now = now))
    }
}
