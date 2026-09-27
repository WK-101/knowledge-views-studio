package app.parley

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.data.DataContainer
import app.parley.data.DialGuard
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The one-ring ("wangiri") warning in a process started for the call: the call history isn't loaded there, so the
 * guard reads the number's recent calls from the call log instead, and the call gate asks before calling back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DialGuardTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer

    // A UK shared-cost line: costly, so one missed call from it is enough to look like a one-ring scam.
    private val number = "+44 845 464 7000"

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        c = DataContainer(context)
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() = c.scope.cancel()

    private fun missed(at: Long) = CallEntry(1, number, null, CallType.MISSED, at, 0, null, isNew = true, presentationHidden = false)

    private fun guard(history: List<CallEntry>?, callLog: List<CallEntry>): Pair<DialGuard, MutableList<String>> {
        val asked = ArrayList<String>()
        val g = DialGuard(context, c.blocks, c.lists, { history }, c.contacts) { n -> asked += n; callLog }
        return g to asked
    }

    private fun DialGuard.oneRing() = runBlocking { check(number) }.any { it.severe && it.title == "Don't call back?" }

    @Test fun historyNotLoadedFallsBackToTheCallLog() {
        val (g, asked) = guard(history = null, callLog = listOf(missed(System.currentTimeMillis() - 5_000)))
        assertTrue(g.oneRing())
        assertEquals(listOf(number), asked)
    }

    @Test fun loadedHistoryIsUsedWithoutAskingTheCallLog() {
        val (g, asked) = guard(history = emptyList(), callLog = listOf(missed(System.currentTimeMillis() - 5_000)))
        assertFalse(g.oneRing())
        assertTrue(asked.isEmpty())
        val (loaded, _) = guard(history = listOf(missed(System.currentTimeMillis() - 5_000)), callLog = emptyList())
        assertTrue(loaded.oneRing())
    }

    @Test fun anOldMissedCallIsNoWarning() {
        val (g, _) = guard(history = null, callLog = listOf(missed(System.currentTimeMillis() - 30 * 86_400_000L)))
        assertFalse(g.oneRing())
    }
}
