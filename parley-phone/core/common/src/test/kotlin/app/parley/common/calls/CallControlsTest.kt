package app.parley.common.calls

import app.parley.common.calls.CallControl.ADD_CALL
import app.parley.common.calls.CallControl.AUDIO
import app.parley.common.calls.CallControl.HOLD
import app.parley.common.calls.CallControl.KEYPAD
import app.parley.common.calls.CallControl.MANAGE
import app.parley.common.calls.CallControl.MERGE
import app.parley.common.calls.CallControl.MORE
import app.parley.common.calls.CallControl.MUTE
import app.parley.common.calls.CallControl.SWAP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallControlsTest {
    private val single = CallControls.Caps(
        canMute = true, canHold = true, canMerge = false, canSwap = false, isConference = false, otherCalls = 0, hasAudioRoutes = true,
    )

    private fun controls(caps: CallControls.Caps) = CallControls.layout(caps).grid.map { it.control }

    @Test fun one_call_gets_the_six_usual_buttons_and_nothing_in_more() {
        val l = CallControls.layout(single)
        assertEquals(listOf(MUTE, KEYPAD, AUDIO, HOLD, ADD_CALL, MORE), l.grid.map { it.control })
        assertTrue(l.grid.all { it.enabled })
        assertTrue(l.more.isEmpty())
    }

    @Test fun the_grid_is_always_two_full_rows() {
        val all = single.copy(canMerge = true, canSwap = true, isConference = true, otherCalls = 1)
        listOf(single, all, single.copy(canSwap = true, otherCalls = 1), single.copy(isConference = true)).forEach {
            assertEquals(CallControls.COLUMNS * 2, controls(it).size)
            assertEquals(MORE, controls(it).last())
        }
    }

    @Test fun merge_comes_first_and_the_rest_go_to_more_in_order() {
        val l = CallControls.layout(single.copy(canMerge = true, canSwap = true, isConference = true, otherCalls = 1))
        assertEquals(MERGE, l.grid[4].control)
        assertEquals(listOf(SWAP, MANAGE, ADD_CALL), l.more.map { it.control })
    }

    @Test fun swap_then_manage_take_the_fifth_place() {
        assertEquals(SWAP, controls(single.copy(canSwap = true, otherCalls = 1))[4])
        assertEquals(MANAGE, controls(single.copy(isConference = true))[4])
        assertEquals(listOf(ADD_CALL), CallControls.layout(single.copy(isConference = true)).more.map { it.control })
    }

    @Test fun buttons_the_call_does_not_allow_stay_in_place_disabled() {
        val l = CallControls.layout(single.copy(canMute = false, canHold = false, hasAudioRoutes = false))
        assertEquals(listOf(MUTE, KEYPAD, AUDIO, HOLD, ADD_CALL, MORE), l.grid.map { it.control })
        assertFalse(l.grid[0].enabled)
        assertFalse(l.grid[2].enabled)
        assertFalse(l.grid[3].enabled)
        assertTrue(l.grid[1].enabled)
    }

    @Test fun add_call_is_off_while_a_second_call_can_be_neither_merged_nor_swapped() {
        assertFalse(CallControls.layout(single.copy(otherCalls = 1)).grid[4].enabled)
        val swappable = CallControls.layout(single.copy(otherCalls = 1, canSwap = true))
        assertTrue(swappable.more.single { it.control == ADD_CALL }.enabled)
    }
}
