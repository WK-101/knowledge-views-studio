package app.parley.common

import app.parley.common.people.SwipeAction
import app.parley.common.people.SwipeConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDirectionTest {
    private val c = SwipeConfig(enabled = true, right = SwipeAction.CALL, left = SwipeAction.MESSAGE)

    @Test fun right_is_physically_right_in_both_directions() {
        // Left to right: towards the end is rightwards.
        assertEquals(SwipeAction.CALL, c.action(towardsEnd = true, rtl = false))
        // Right to left: towards the end is leftwards, so it runs the left action.
        assertEquals(SwipeAction.MESSAGE, c.action(towardsEnd = true, rtl = true))
        assertEquals(SwipeAction.CALL, c.action(towardsEnd = false, rtl = true))
    }
}
