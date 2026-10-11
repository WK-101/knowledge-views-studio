package app.parley.telecom

import android.content.Context
import android.telecom.Call
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * RTT on the call screen, against Telecom's own Call objects: offered only where the call supports it (never in a
 * conference), requests go to Telecom and are tracked both ways, and nothing is kept for a call that ends without text.
 */
@RunWith(RobolectricTestRunner::class)
class CallRttTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val telecom = FakeTelecom()

    @After fun tearDown() = CallRtt.release()

    private fun active(id: String, properties: Int) =
        telecom.call(FakeTelecom.Spec(id, Call.STATE_ACTIVE, properties = properties, connectTimeMillis = 1_000)).also {
            CallRtt.attach(context, it, id)
            FakeTelecom.idle()
        }

    @Test fun offered_only_where_the_call_supports_it_and_never_in_a_conference() {
        active("r1", Call.Details.PROPERTY_RTT)
        active("v1", 0)
        active("c1", Call.Details.PROPERTY_RTT or Call.Details.PROPERTY_CONFERENCE)
        val s = CallRtt.state.value
        assertTrue(s.getValue("r1").supported)
        assertFalse(s.getValue("v1").supported)
        assertFalse(s.getValue("c1").supported)
    }

    @Test fun a_request_goes_to_telecom_and_waits_for_its_answer() {
        active("r1", Call.Details.PROPERTY_RTT)
        CallRtt.request("r1")
        assertTrue(CallRtt.state.value.getValue("r1").requesting)
        assertTrue(telecom.sent.any { it.startsWith("sendRttRequest") })
        // A call nobody watches: nothing is asked.
        val before = telecom.sent.size
        CallRtt.request("unknown")
        assertEquals(before, telecom.sent.size)
    }

    @Test fun their_request_is_answered_once() {
        val call = active("r1", Call.Details.PROPERTY_RTT)
        Call::class.java.getDeclaredMethod("internalOnRttUpgradeRequest", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(call, 7)
        FakeTelecom.idle()
        assertEquals(7, CallRtt.state.value.getValue("r1").incomingRequest)
        CallRtt.respond("r1", accept = false)
        assertNull(CallRtt.state.value.getValue("r1").incomingRequest)
        assertEquals(1, telecom.sent.count { it.startsWith("respondToRttRequest") })
        CallRtt.respond("r1", accept = true)
        assertEquals(1, telecom.sent.count { it.startsWith("respondToRttRequest") })
    }

    @Test fun a_call_ending_without_text_leaves_nothing_behind() {
        val call = active("r1", Call.Details.PROPERTY_RTT)
        CallRtt.detach(call, "r1")
        assertFalse("r1" in CallRtt.state.value)
        assertEquals(RttMode.FULL, RttMode.of(-1))
    }
}
