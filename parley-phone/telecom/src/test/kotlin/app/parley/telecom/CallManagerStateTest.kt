package app.parley.telecom

import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The parts of [CallManager]'s state that don't need a Telecom `Call` (which apps can't construct). The per-call
 * bookkeeping itself is the pure [app.parley.common.calls.CallBook], tested in core:common.
 */
@RunWith(RobolectricTestRunner::class)
class CallManagerStateTest {
    @After fun tearDown() = CallManager.clear()

    @Test fun anExpectedOutgoingCallShowsUntilItArrivesOrTimesOut() {
        CallManager.expectOutgoing("+15551234567", "Work")
        val pending = CallManager.pendingOutgoing.value
        assertEquals("+15551234567", pending?.number)
        assertEquals("Work", pending?.simLabel)

        // Clearing with no call left doesn't drop the "Calling via Work…" hint: the call may still be on its way.
        CallManager.clear()
        assertEquals(pending, CallManager.pendingOutgoing.value)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertNull(CallManager.pendingOutgoing.value)
    }

    @Test fun aNewerExpectationIsNotClearedByAnOlderTimeout() {
        CallManager.expectOutgoing("+15550000001", null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        CallManager.expectOutgoing("+15550000002", null)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertEquals("+15550000002", CallManager.pendingOutgoing.value?.number)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertNull(CallManager.pendingOutgoing.value)
    }

    @Test fun clearLeavesNoCallsAndTellsObservers() {
        var published: List<CallUi>? = null
        CallManager.onChanged = { published = it }
        try {
            CallManager.clear()
            assertTrue(CallManager.state.value.isEmpty())
            assertEquals(emptyList<CallUi>(), published)
        } finally {
            CallManager.onChanged = null
        }
    }
}
