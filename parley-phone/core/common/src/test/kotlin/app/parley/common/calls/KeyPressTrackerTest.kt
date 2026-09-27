package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPressTrackerTest {
    @Test fun tone_starts_on_press_and_lasts_at_least_the_minimum() {
        val k = KeyPressTracker()
        assertEquals(listOf(KeyAction.Press), k.down(1_000))
        // A 40 ms tap: the tone is stopped 110 ms later, so it lasts 150 ms.
        assertEquals(listOf(KeyAction.StopTone(110)), k.up(1_040))
        assertFalse(k.pressed)
    }

    @Test fun a_held_key_stops_right_away_on_release() {
        val k = KeyPressTracker()
        k.down(0)
        assertEquals(listOf(KeyAction.StopTone(0)), k.up(400))
    }

    @Test fun long_press_fires_once_and_stops_the_tone() {
        val k = KeyPressTracker()
        k.down(0)
        assertTrue(k.longPressDue(300).isEmpty())
        assertEquals(listOf(KeyAction.StopTone(0), KeyAction.LongPress), k.longPressDue(500))
        assertTrue(k.longPressed)
        assertTrue(k.longPressDue(900).isEmpty())
        // The tone already stopped: releasing does nothing more.
        assertTrue(k.up(1_000).isEmpty())
    }

    @Test fun sliding_off_cancels_the_long_press_and_stops_the_tone() {
        val k = KeyPressTracker()
        k.down(0)
        assertTrue(k.move(inside = true, now = 50).isEmpty())
        assertEquals(listOf(KeyAction.StopTone(90)), k.move(inside = false, now = 60))
        // Coming back doesn't re-arm it.
        assertTrue(k.move(inside = true, now = 100).isEmpty())
        assertTrue(k.longPressDue(600).isEmpty())
        assertFalse(k.longPressed)
        assertTrue(k.up(700).isEmpty())
    }

    @Test fun keys_roll_over_independently() {
        val one = KeyPressTracker()
        val two = KeyPressTracker()
        one.down(0)
        two.down(80) // second finger before the first is lifted
        assertEquals(listOf(KeyAction.StopTone(30)), one.up(120))
        assertTrue(two.pressed)
        assertEquals(listOf(KeyAction.StopTone(0)), two.up(400))
    }
}
