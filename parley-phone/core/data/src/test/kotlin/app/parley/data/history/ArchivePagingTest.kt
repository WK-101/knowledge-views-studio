package app.parley.data.history

import android.app.Application
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.common.history.RetentionDefaults
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Whole-archive reads page by (date, id) and see every row exactly once, also when many calls share a date across a
 * page boundary; one person's calls come from the person index alone.
 */
@RunWith(RobolectricTestRunner::class)
class ArchivePagingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        c = DataContainer(app)
        runBlocking { c.history.setArchiveEnabled(true) }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private val numbers = listOf("+44 20 7946 0101", "+44 20 7946 0102", "+44 20 7946 0103")

    /** [n] calls; the three numbers share each date, so pages of 500 end in the middle of a run of equal dates. */
    private fun lines(n: Int) = (0 until n).map { i ->
        CallHistoryLine(call = CallLogRecord(numbers[i % numbers.size], 1_700_000_000_000L + (i / 3) * 1_000L, i.toLong(), Calls.INCOMING_TYPE))
    }

    @Test fun everyRowIsReadOnceAcrossPages() = runBlocking {
        assertEquals(1_203, c.history.restoreLines(lines(1_203)))
        val read = c.history.backupLines().mapNotNull { it.call }
        assertEquals(1_203, read.size)
        // The duration is unique per call here: no row twice, none missed.
        assertEquals((0L until 1_203L).toSet(), read.map { it.duration }.toSet())
    }

    @Test fun aRestoredCallIsArchivedOnce() = runBlocking {
        assertEquals(40, c.history.restoreLines(lines(40)))
        // The same lines again: the unique dedupe key turns every one away.
        assertEquals(0, c.history.restoreLines(lines(40)))
        assertEquals(40, c.history.archiveCount())
    }

    @Test fun onePersonsCallsComeFromTheIndex() = runBlocking {
        c.history.restoreLines(lines(900))
        val mine = c.history.callsFor("+442079460102")
        assertEquals(300, mine.size)
        assertTrue(mine.all { it.number.replace(" ", "") == "+442079460102" })
        assertEquals(mine.sortedByDescending { it.date }, mine)
        assertEquals(300, c.history.purgeNumber("+44 20 7946 0102"))
        assertEquals(600, c.history.archiveCount())
        assertEquals(0, c.history.callsFor("+442079460102").size)
    }

    @Test fun aNewInstallKeepsFiveYearsOfHistory() = runBlocking {
        assertEquals(RetentionDefaults.NEW_INSTALL_DAYS, c.settings.current().callLogRetentionDays)
        // A choice made later is kept as made.
        c.settings.update { it.copy(callLogRetentionDays = 0) }
        assertEquals(0, c.settings.current().callLogRetentionDays)
        assertEquals(0, AppSettings().callLogRetentionDays)
    }
}
