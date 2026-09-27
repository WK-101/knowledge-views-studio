package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The docked keypad's deferred presses keep their order and only a downward drag drops one. */
class PressOrderTest {
    private val press = KeyAction.Press

    /** Two deferred keys sharing one [PressOrder], logging what they type. */
    private class Pad {
        val order = PressOrder()
        val typed = StringBuilder()
        fun key(d: Char) = Key(d)
        inner class Key(val d: Char) {
            val t = KeyPressTracker(deferPress = true)
            var token = -1L
            private fun run(a: List<KeyAction>) = a.forEach { if (it == KeyAction.Press) typed.append(d) }
            fun down(now: Long) {
                run(t.down(now))
                token = order.waiting { run(t.settle(now)) }
            }
            fun settle(now: Long) = run(t.settle(now))
            fun move(inside: Boolean, now: Long, scrolled: Boolean) = run(t.move(inside, now, scrolled))
            fun up(now: Long) { order.done(token); run(t.up(now)) }
            fun cancel(now: Long) { order.done(token); run(t.cancel(now)) }
        }
    }

    @Test fun rolling_to_a_second_key_keeps_digit_order() {
        val p = Pad()
        val a = p.key('1')
        val b = p.key('2')
        a.down(0)
        b.down(30) // commits '1' now
        b.up(90)
        a.settle(100) // A's timer: already typed, nothing more
        a.up(200)
        assertEquals("12", p.typed.toString())
        assertEquals(0, p.order.waitingCount)
    }

    @Test fun a_dropped_press_is_not_revived_by_a_later_key() {
        val p = Pad()
        val a = p.key('1')
        val b = p.key('2')
        a.down(0)
        a.move(inside = true, now = 20, scrolled = true) // a fold drag
        b.down(40)
        b.up(80)
        a.cancel(300)
        assertEquals("2", p.typed.toString())
    }

    @Test fun three_keys_in_quick_succession() {
        val p = Pad()
        val a = p.key('1'); val b = p.key('2'); val c = p.key('3')
        a.down(0); b.down(10); c.down(20)
        c.up(50); b.up(60); a.up(70)
        assertEquals("123", p.typed.toString())
    }

    @Test fun only_a_downward_drag_cancels_on_the_docked_keypad() {
        assertTrue(KeyPressTracker.cancelsPending(0f, 20f, 8f, downOnly = true))
        assertFalse(KeyPressTracker.cancelsPending(0f, -20f, 8f, downOnly = true))
        assertFalse(KeyPressTracker.cancelsPending(20f, 0f, 8f, downOnly = true))
        assertFalse(KeyPressTracker.cancelsPending(-20f, 5f, 8f, downOnly = true))
        // Elsewhere (the in-call keypad scrolls either way) any move past the slop does.
        assertTrue(KeyPressTracker.cancelsPending(0f, -20f, 8f, downOnly = false))
        assertTrue(KeyPressTracker.cancelsPending(20f, 0f, 8f, downOnly = false))
        assertFalse(KeyPressTracker.cancelsPending(3f, 3f, 8f, downOnly = false))
    }

    @Test fun sliding_off_sideways_still_types() {
        val t = KeyPressTracker(deferPress = true)
        t.down(0)
        assertTrue(t.move(inside = true, now = 20, scrolled = false).isEmpty())
        assertEquals(listOf(press, KeyAction.StopTone(150)), t.move(inside = false, now = 40, scrolled = false))
        assertTrue(t.typedThisTouch)
        assertTrue(t.up(60).isEmpty())
    }
}
