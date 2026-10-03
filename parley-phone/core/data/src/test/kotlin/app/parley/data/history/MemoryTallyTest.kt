package app.parley.data.history

import android.app.Application
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.backup.CallHistoryLine
import app.parley.common.backup.CallLogRecord
import app.parley.common.memory.CallTally
import app.parley.data.DataContainer
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

/** Number memory reads only the calls archived since its last rebuild, and everything again when calls left. */
@RunWith(RobolectricTestRunner::class)
class MemoryTallyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private val gb = "GB"

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

    private fun line(n: String, at: Long, name: String? = null) = CallHistoryLine(call = CallLogRecord(n, at, 30, Calls.INCOMING_TYPE, name = name))

    @Test fun onlyNewCallsAreReadUntilOneLeaves() = runBlocking {
        val t0 = 1_600_000_000_000L
        c.history.restoreLines(listOf(line("+44 20 7946 0002", t0), line("+44 20 7946 0003", t0 + 3_600_000, "Ana")))
        val first = c.history.tallyForMemory(null, gb)!!
        assertEquals(2, first.lines.size)

        // A marker line the archive doesn't have: kept only if the tally is added to, not read again.
        val marked = first.copy(lines = first.lines + ("+999" to CallTally.Line("+999", 1, 1, 1)))
        c.history.restoreLines(listOf(line("+44 20 7946 0002", t0 + 7_200_000)))
        val next = c.history.tallyForMemory(marked, gb)!!
        assertTrue("added to, not read again", "+999" in next.lines)
        assertEquals(2, next.lines.getValue(first.lines.keys.first { k -> first.lines.getValue(k).number == "+44 20 7946 0002" }).count)

        // Nothing new: the same tally with the same mark.
        assertEquals(next, c.history.tallyForMemory(next, gb))

        // A call left the archive: counts can't be patched, so everything is read again.
        c.history.applyRetention(1)
        val again = c.history.tallyForMemory(next, gb)!!
        assertFalse("+999" in again.lines)
        assertTrue(again.lines.isEmpty())

        // Another region: read again too.
        assertFalse("+999" in c.history.tallyForMemory(marked, "FR")!!.lines)
    }
}
