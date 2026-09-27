package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallFailureTest {
    private fun facts(
        code: EndCode? = EndCode.ERROR, outgoing: Boolean = true, connected: Boolean = false, picker: Boolean = false,
        user: Boolean = false, airplane: Boolean = false, emergency: Boolean = false,
    ) = EndFacts(outgoing, connected, code, picker, user, airplane, emergency, hasNumber = true)

    @Test fun failure_only_for_outgoing_calls_that_never_connected() {
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts()))
        assertNull(CallFailure.classify(facts(outgoing = false)))
        assertNull(CallFailure.classify(facts(connected = true)))
        assertNull(CallFailure.classify(facts(emergency = true)))
        // The user hung up or cancelled: no banner.
        assertNull(CallFailure.classify(facts(code = EndCode.LOCAL, user = true)))
        // The other side declined.
        assertNull(CallFailure.classify(facts(code = EndCode.REMOTE)))
        assertNull(CallFailure.classify(facts(code = EndCode.REJECTED)))
    }

    @Test fun failure_reasons() {
        assertEquals(FailureKind.AIRPLANE_MODE, CallFailure.classify(facts(airplane = true)))
        assertEquals(FailureKind.NO_SIM_SELECTED, CallFailure.classify(facts(code = EndCode.ERROR, picker = true)))
        assertEquals(FailureKind.BUSY, CallFailure.classify(facts(code = EndCode.BUSY)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.RESTRICTED)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.OTHER)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.UNKNOWN)))
    }

    @Test fun a_hang_up_from_outside_parley_is_never_a_failure() {
        // Power button, Bluetooth headset or car kit, a watch: Telecom reports LOCAL (or CANCELED) without Parley knowing.
        listOf(EndCode.LOCAL, EndCode.CANCELED).forEach { code ->
            assertNull(CallFailure.classify(facts(code = code)))
            assertNull(CallFailure.classify(facts(code = code, airplane = true)))
            assertNull(CallFailure.classify(facts(code = code, picker = true)))
        }
        assertNull(CallFailure.classify(facts(code = EndCode.MISSED)))
        assertNull(CallFailure.classify(facts(code = null)))
    }
}
