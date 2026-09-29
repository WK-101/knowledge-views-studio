package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallDropTest {
    private fun facts(
        code: EndCode? = EndCode.ERROR,
        reason: String? = null,
        connected: Boolean = true,
        user: Boolean = false,
        emergency: Boolean = false,
        limit: Boolean = false,
    ) =
        DropFacts(connected, user, emergency, code, reason, limit)

    @Test fun an_error_after_connecting_is_a_drop() {
        assertEquals(DropKind.NETWORK, CallDrop.classify(facts()))
        assertEquals(DropKind.LOST_SIGNAL, CallDrop.classify(facts(reason = "LOST_SIGNAL")))
        assertEquals(DropKind.LOST_SIGNAL, CallDrop.classify(facts(reason = "IMS call ended, CDMA_DROP")))
        assertEquals(DropKind.WIFI_LOST, CallDrop.classify(facts(reason = "ims, WIFI_LOST")))
        assertEquals(DropKind.NO_SERVICE, CallDrop.classify(facts(reason = "OUT_OF_SERVICE")))
    }

    @Test fun hanging_up_is_never_a_drop() {
        assertNull(CallDrop.classify(facts(code = EndCode.LOCAL, reason = "LOST_SIGNAL")))
        assertNull(CallDrop.classify(facts(code = EndCode.REMOTE)))
        assertNull(CallDrop.classify(facts(code = EndCode.BUSY)))
        assertNull(CallDrop.classify(facts(user = true)))
        assertNull(CallDrop.classify(facts(limit = true)))
    }

    @Test fun only_connected_non_emergency_calls() {
        assertNull(CallDrop.classify(facts(connected = false)))
        assertNull(CallDrop.classify(facts(emergency = true)))
        assertNull(CallDrop.classify(facts(code = null)))
    }

    @Test fun other_and_unknown_count_only_with_telephonys_drop_cause() {
        assertNull(CallDrop.classify(facts(code = EndCode.OTHER)))
        assertNull(CallDrop.classify(facts(code = EndCode.UNKNOWN, reason = "NORMAL")))
        assertEquals(DropKind.LOST_SIGNAL, CallDrop.classify(facts(code = EndCode.OTHER, reason = "LOST_SIGNAL")))
        // A word inside another token doesn't count.
        assertNull(CallDrop.classify(facts(code = EndCode.OTHER, reason = "NOT_LOST_SIGNALLING")))
    }
}
