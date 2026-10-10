package app.parley.common.calls

import app.parley.common.calls.RingRamp.Restore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Increasing" and "Vibrate first, then ring": when the ring volume ramps, its steps, and how it is put back. */
class RingRampTest {
    private fun applies(
        style: RingStyle = RingStyle.INCREASING,
        ringer: RingerMode = RingerMode.NORMAL,
        dnd: DndState = DndState.OFF,
        otherCall: Boolean = false,
        ringLoud: Boolean = false,
        systemRamps: Boolean = false,
        volume: Int = 5,
    ) = RingRamp.applies(style, ringer, dnd, otherCall, ringLoud, systemRamps, volume)

    @Test fun ramps_only_a_call_that_rings_out_loud_on_its_own() {
        assertTrue(applies())
        assertTrue(applies(style = RingStyle.VIBRATE_FIRST))
        assertFalse(applies(style = RingStyle.NORMAL))
        assertFalse(applies(ringer = RingerMode.VIBRATE))
        assertFalse(applies(ringer = RingerMode.SILENT))
        assertFalse(applies(dnd = DndState.PRIORITY))
        assertFalse("a call waiting only beeps", applies(otherCall = true))
        assertFalse("Ring loud wins", applies(ringLoud = true))
        assertFalse("Android ramps already", applies(systemRamps = true))
        assertFalse("nothing to grow to", applies(volume = 1))
        assertFalse(applies(volume = 0))
    }

    @Test fun vibrate_first_needs_the_call_to_vibrate() {
        assertTrue(RingRamp.vibratesFirst(RingStyle.VIBRATE_FIRST, vibrates = true))
        assertFalse(RingRamp.vibratesFirst(RingStyle.VIBRATE_FIRST, vibrates = false))
        assertFalse(RingRamp.vibratesFirst(RingStyle.INCREASING, vibrates = true))
    }

    @Test fun steps_climb_one_level_at_a_time_to_the_users_volume() {
        val steps = RingRamp.steps(target = 5, durationMs = 20_000)
        assertEquals(listOf(2, 3, 4, 5), steps.map { it.volume })
        assertEquals(listOf(5_000L, 10_000L, 15_000L, 20_000L), steps.map { it.atMs })
        // After the vibration alone, for "Vibrate first".
        assertEquals(RingRamp.VIBRATE_FIRST_MS + 20_000, RingRamp.steps(5, 20_000, RingRamp.VIBRATE_FIRST_MS).last().atMs)
        assertTrue(RingRamp.steps(1).isEmpty())
        assertTrue(RingRamp.steps(0).isEmpty())
        // Never louder than the user's own volume.
        assertEquals(15, RingRamp.steps(15).maxOf { it.volume })
    }

    @Test fun the_users_volume_is_put_back_unless_they_changed_it() {
        assertEquals(Restore.Nothing, RingRamp.restore(saved = null, lastSet = null, current = 3, audible = true))
        assertEquals(Restore.To(6), RingRamp.restore(saved = 6, lastSet = 2, current = 2, audible = true))
        // Left over by a crash with no step noted: put back.
        assertEquals(Restore.To(6), RingRamp.restore(saved = 6, lastSet = null, current = 1, audible = true))
        // The volume keys or the panel moved it: theirs stays.
        assertEquals(Restore.KeepUsers, RingRamp.restore(saved = 6, lastSet = 2, current = 4, audible = true))
        // On vibrate or silent the ring volume reads as muted: wait for the next call or app start.
        assertEquals(Restore.Later, RingRamp.restore(saved = 6, lastSet = 2, current = 0, audible = false))
    }

    @Test fun the_ramp_stops_when_the_user_moves_the_volume() {
        assertFalse(RingRamp.userTookOver(lastSet = 3, current = 3))
        assertTrue(RingRamp.userTookOver(lastSet = 3, current = 5))
        assertTrue(RingRamp.userTookOver(lastSet = 3, current = 2))
    }

    @Test fun a_step_noted_but_not_yet_set_still_counts_as_the_ramps_own() {
        // The process died after noting step 3 on disk and before setting it: the volume is still the previous step.
        assertEquals(Restore.To(6), RingRamp.restore(saved = 6, lastSet = 3, current = 2, audible = true, previous = 2))
        // Or after setting it: the noted one.
        assertEquals(Restore.To(6), RingRamp.restore(saved = 6, lastSet = 3, current = 3, audible = true, previous = 2))
        // Anything else is the user's.
        assertEquals(Restore.KeepUsers, RingRamp.restore(saved = 6, lastSet = 3, current = 5, audible = true, previous = 2))
        assertFalse(RingRamp.userTookOver(lastSet = 3, current = 2, previous = 2))
        assertTrue(RingRamp.userTookOver(lastSet = 3, current = 4, previous = 2))
    }

    @Test fun do_not_disturb_muting_the_ring_waits_instead_of_dropping_the_users_volume() {
        // Do Not Disturb came on mid-ramp: the ring stream reads 0 and can't be set. Never taken as the user's choice.
        assertEquals(Restore.Later, RingRamp.restore(saved = 6, lastSet = 2, current = 0, audible = false, previous = 1))
    }
}
