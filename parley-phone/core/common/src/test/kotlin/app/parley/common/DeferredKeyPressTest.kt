package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeferredKeyPressTest {
    private fun tracker() = app.parley.common.calls.KeyPressTracker(deferPress = true)
    private val press = app.parley.common.calls.KeyAction.Press

    @Test fun a_scroll_that_starts_on_a_key_sends_nothing() {
        val k = tracker()
        assertTrue(k.down(0).isEmpty())
        assertTrue(k.move(inside = true, now = 30, scrolled = true).isEmpty())
        assertTrue(k.settle(100).isEmpty())
        assertTrue(k.up(400).isEmpty())
        assertFalse(k.typedThisTouch)
    }

    @Test fun a_scroll_taking_over_cancels_without_pressing() {
        val k = tracker()
        k.down(0)
        assertTrue(k.cancel(50).isEmpty())
        assertFalse(k.typedThisTouch)
    }

    @Test fun a_quick_tap_presses_on_release() {
        val k = tracker()
        k.down(0)
        assertEquals(listOf(press, app.parley.common.calls.KeyAction.StopTone(150)), k.up(40))
        assertTrue(k.typedThisTouch)
    }

    @Test fun a_settled_touch_presses_then_holds_the_tone() {
        val k = tracker()
        k.down(0)
        assertEquals(listOf(press), k.settle(100))
        assertEquals(listOf(app.parley.common.calls.KeyAction.StopTone(0)), k.up(600))
    }

    @Test fun long_press_says_whether_this_touch_typed() {
        val immediate = app.parley.common.calls.KeyPressTracker()
        immediate.down(0)
        immediate.longPressDue(500)
        assertTrue(immediate.typedThisTouch)
        val cancelled = app.parley.common.calls.KeyPressTracker()
        cancelled.down(0)
        // A cancelled gesture stops the tone (the finally path) and never presses again.
        assertEquals(listOf(app.parley.common.calls.KeyAction.StopTone(140)), cancelled.cancel(10))
        assertTrue(cancelled.up(20).isEmpty())
    }
}
