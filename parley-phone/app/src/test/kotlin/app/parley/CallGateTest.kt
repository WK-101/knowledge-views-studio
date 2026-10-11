package app.parley

import android.Manifest
import android.app.Application
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import app.parley.common.AppSettings
import app.parley.common.SimAccount
import app.parley.data.DataContainer
import app.parley.data.PlaceResult
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.telecom.CallManager
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The one gate every outgoing call passes: emergency calls are never held up; confirmation and the SIM question. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CallGateTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var gate: CallGate

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
        c = DataContainer(context)
        gate = CallGate(c)
        // Start from defaults, whatever an earlier test stored.
        runBlocking { c.settings.update { AppSettings() } }
    }

    @After fun tearDown() = c.scope.cancel()

    private fun confirmCalls() = runBlocking { c.settings.update { it.copy(confirmBeforeCall = true) } }

    private fun check(number: String, sims: Int = 1, simId: String? = null, skipConfirm: Boolean = false) =
        runBlocking { gate.check(number, "Someone", sims, simId, skipConfirm) }

    @Test fun anOrdinaryCallGoesStraightThrough() {
        assertNull(check("+1 202 555 0143"))
    }

    @Test fun confirmBeforeCallingAsksUnlessTheUserAlreadyConfirmed() {
        confirmCalls()
        val p = check("+1 202 555 0143")
        assertNotNull(p)
        assertTrue(p!!.needConfirm)
        assertFalse(p.chooseSim)
        assertEquals("Someone", p.name)
        assertNull(check("+1 202 555 0143", skipConfirm = true))
    }

    @Test fun twoSimsWithoutADefaultAskWhichSim() {
        val p = check("+1 202 555 0143", sims = 2)
        assertTrue(p!!.chooseSim)
        assertFalse(p.needConfirm)
        assertNull("a SIM picked for this call answers the question", check("+1 202 555 0143", sims = 2, simId = "sim-2"))
        assertNull("service codes go to the default SIM", check("*#06#", sims = 2))
    }

    @Test fun aRememberedSimAnswersTheQuestion() = runBlocking {
        c.prefs.setSimFor("+1 202 555 0143", "sim-2")
        assertNull(gate.check("+1 202 555 0143", null, 2))
    }

    @Test fun emergencyNumbersAreNeverHeldUp() {
        confirmCalls()
        for (n in listOf("911", "112", "١١٢")) {
            assertNull(n, check(n, sims = 2))
            assertTrue(n, runBlocking { gate.isEmergency(n) })
        }
        assertFalse(runBlocking { gate.isEmergency("+1 202 555 0143") })
    }

    @Test fun anEmergencyCallIsPlacedInAsciiDigitsWithoutARememberedSim() = runBlocking {
        c.prefs.setSimFor("112", "sim-2")
        val sims = listOf(SimAccount("sim-1", "Home", null, 0, 0), SimAccount("sim-2", "Work", null, 0, 1))
        val placed = gate.place("١١٢", null, null, sims)
        assertEquals(CallGate.Placed.Done(PlaceResult.Placed), placed)
        val tm = shadowOf(context.getSystemService(TelecomManager::class.java))
        val call = tm.allOutgoingCalls.single()
        assertEquals("112", call.address.schemeSpecificPart)
        assertNull(
            "no SIM forced: the platform picks a network that can carry it",
            call.extras.getParcelable<PhoneAccountHandle>(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE),
        )
        assertNull(CallManager.pendingOutgoing.value?.simLabel)
    }

    @Test fun anOrdinaryCallIsPlacedAndAnnounced() = runBlocking {
        val placed = gate.place("+1 202 555 0143", null, "Someone", emptyList(), confirmed = true)
        assertEquals(CallGate.Placed.Done(PlaceResult.Placed), placed)
        assertEquals("+1 202 555 0143", CallManager.pendingOutgoing.value?.number)
        assertEquals(1, shadowOf(context.getSystemService(TelecomManager::class.java)).allOutgoingCalls.size)
    }
}
