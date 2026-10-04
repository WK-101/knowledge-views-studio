package app.parley.data.history

import android.app.Application
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import android.telephony.TelephonyManager
import java.time.Duration

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

    @Test fun aRestoreInChunksWritesEachCallOnceAndShowsThemAtTheEnd() = runBlocking {
        val restore = c.history.beginRestore()
        // The same call in two chunks counts once; the window shows the calls once the restore has finished.
        val all = lines(1_000)
        val n = all.chunked(300).sumOf { restore.add(it) } + restore.add(all.take(10))
        restore.finish()
        assertEquals(1_000, n)
        assertEquals(1_000, c.history.archiveCount())
        assertEquals(1_000, c.history.backupLines().count { it.call != null })
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

    /**
     * Calls archived in Germany, some written nationally, deleted later while roaming in France (or after a SIM swap):
     * "Delete all calls" with either form of the number leaves none of them, and nobody else's.
     */
    @Test fun deletingEverythingWithANumberFindsCallsArchivedInAnotherRegion() = runBlocking<Unit> {
        fun inCountry(iso: String) {
            shadowOf(app.getSystemService(TelephonyManager::class.java)).setSimCountryIso(iso)
            ShadowSystemClock.advanceBy(Duration.ofMinutes(1)) // past the cached region
        }
        var round = 0
        fun call(number: String, i: Int) =
            CallHistoryLine(call = CallLogRecord(number, 1_700_000_000_000L + round * 3_600_000L + i * 60_000L, i.toLong(), Calls.INCOMING_TYPE))
        val berlin = listOf("030 1234567", "030 1234567", "+49 30 1234567", "+4930 1234567")
        val other = "+33 1 23 45 67 89"
        for (form in listOf("+49 30 1234567", "030 1234567")) {
            round++
            inCountry("de")
            c.history.restoreLines(berlin.mapIndexed { i, n -> call(n, i) } + call(other, 10))
            assertEquals(5, c.history.archiveCount())
            inCountry("fr")
            c.history.deleteForNumber(form)
            assertEquals("deleted with $form", 1, c.history.archiveCount())
            assertEquals(other, c.history.backupLines().single().call?.number)
            c.history.purgeNumber(other)
        }
        // The automatic purge (an expired temporary contact) reaches them too.
        round++
        inCountry("de")
        c.history.restoreLines(berlin.mapIndexed { i, n -> call(n, i) })
        inCountry("fr")
        c.history.purgeNumber("030 1234567")
        assertEquals(0, c.history.archiveCount())
        // The region is cached process-wide: leave it as the next test expects (no SIM, the locale's country).
        inCountry("")
        PhoneEnv.countryIso(app)
    }
}
