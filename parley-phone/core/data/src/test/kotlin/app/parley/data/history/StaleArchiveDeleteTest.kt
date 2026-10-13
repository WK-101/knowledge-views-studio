package app.parley.data.history

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.provider.CallLog.Calls
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
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

/**
 * A call Recents shows from the archive while the system log still has it (the list merged a log read a moment before
 * the call reached it) is deleted from the log too, and Undo puts it back: before, only its archived copy went and the
 * next sync brought it straight back (the flaky RecentsViewModelTest delete case).
 */
@RunWith(RobolectricTestRunner::class)
class StaleArchiveDeleteTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var log: FakeCallLogProvider
    private val number = "+44 20 7946 0001"
    private val other = "+44 20 7946 0002"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        log = FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.READ_CONTACTS)
        c = DataContainer(app)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() {
        c.scope.cancel()
        c.db.close()
    }

    private fun row(n: String, date: Long) = ContentValues().apply {
        put(Calls.NUMBER, n)
        put(Calls.DATE, date)
        put(Calls.TYPE, Calls.MISSED_TYPE)
    }

    @Test fun anArchivedRowStillInTheLogIsDeletedFromItToo() = runBlocking {
        val at = 1_700_000_000_000L
        app.contentResolver.insert(Calls.CONTENT_URI, row(number, at))
        app.contentResolver.insert(Calls.CONTENT_URI, row(other, at - 60_000))
        c.history.setArchiveEnabled(true)
        // Only the archive has it now, so it is listed with an archive id...
        app.contentResolver.delete(Calls.CONTENT_URI, "${Calls.NUMBER} = ?", arrayOf(number))
        val shown = c.history.callsFor(number).single()
        assertTrue(CallHistory.isArchived(shown))
        // ...and the log has it again, as when Recents merged a log read just before the call reached it.
        app.contentResolver.insert(Calls.CONTENT_URI, row(number, at))

        val batch = c.history.delete(listOf(shown))
        assertEquals(listOf(other), log.rows().map { it["number"] })
        c.history.sync(full = true)
        assertTrue("the call doesn't come back with the next sync", c.history.callsFor(number).isEmpty())

        c.history.undoDelete(batch!!)
        assertEquals(setOf(number, other), log.rows().map { it["number"] }.toSet())
    }
}
