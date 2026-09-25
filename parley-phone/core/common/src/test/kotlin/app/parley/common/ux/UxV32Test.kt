package app.parley.common.ux

import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class UxV32Test {
    // ---------------------------------------------------------------- U3 call colours

    @Test fun every_call_type_has_a_fixed_hue() {
        assertEquals(CallHue.INCOMING, CallHue.of(CallType.INCOMING))
        assertEquals(CallHue.INCOMING, CallHue.of(CallType.ANSWERED_EXTERNALLY))
        assertEquals(CallHue.OUTGOING, CallHue.of(CallType.OUTGOING))
        assertEquals(CallHue.MISSED, CallHue.of(CallType.MISSED))
        assertEquals(CallHue.MISSED, CallHue.of(CallType.REJECTED))
        assertEquals(CallHue.BLOCKED, CallHue.of(CallType.BLOCKED))
        // No type falls through to an unexpected family.
        CallType.entries.forEach { CallHue.of(it) }
    }
}
