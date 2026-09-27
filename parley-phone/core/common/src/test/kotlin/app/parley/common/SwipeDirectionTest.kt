package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeDirectionTest {
    private val c = app.parley.common.people.SwipeConfig(enabled = true, right = app.parley.common.people.SwipeAction.CALL, left = app.parley.common.people.SwipeAction.MESSAGE)

    @Test fun right_is_physically_right_in_both_directions() {
        // Left to right: towards the end is rightwards.
        assertEquals(app.parley.common.people.SwipeAction.CALL, c.action(towardsEnd = true, rtl = false))
        // Right to left: towards the end is leftwards, so it runs the left action.
        assertEquals(app.parley.common.people.SwipeAction.MESSAGE, c.action(towardsEnd = true, rtl = true))
        assertEquals(app.parley.common.people.SwipeAction.CALL, c.action(towardsEnd = false, rtl = true))
    }
}
