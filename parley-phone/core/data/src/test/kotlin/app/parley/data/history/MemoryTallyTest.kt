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

    /** Retention takes the oldest calls every day: the tally is counted again from the index, no call opened. */
    @Test fun callsLeftByDateAreCountedAgainWithoutReadingTheArchive() = runBlocking {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        val mum = "+44 20 7946 0002"
        c.history.restoreLines(listOf(line(mum, now - 400 * day), line(mum, now - 2 * day), line(mum, now - day), line("+44 20 7946 0009", now - 500 * day)))
        val first = c.history.tallyForMemory(null, gb)!!
        val key = first.lines.keys.first { first.lines.getValue(it).number == mum }
        assertEquals(3, first.lines.getValue(key).count)
        // A name only this tally has (as if a call had shown it): kept only if the tally is counted again, not read again.
        val marked = first.copy(lines = first.lines + (key to first.lines.getValue(key).copy(name = "Marker", namedAt = now - day)))

        c.history.applyRetention(365)
        val trimmed = c.history.tallyForMemory(marked, gb)!!
        assertEquals(1, trimmed.lines.size)
        val l = trimmed.lines.getValue(key)
        assertEquals(2, l.count)
        assertEquals(now - 2 * day, l.since)
        assertEquals(now - day, l.at)
        assertEquals("counted again, not read again", "Marker", l.name)

        // Calls deleted by hand: read again whole, so nothing is kept from them.
        c.history.delete(c.history.callsFor(mum).take(1))
        val read = c.history.tallyForMemory(trimmed, gb)!!
        assertEquals(1, read.lines.getValue(key).count)
        assertFalse(read.lines.getValue(key).name == "Marker")
    }
}
