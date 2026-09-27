package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeActionsTest {
    @Test fun swipe_actions_are_off_by_default() {
        val c = SwipeConfig()
        assertEquals(SwipeAction.NONE, c.action(true))
        val on = c.copy(enabled = true)
        assertEquals(SwipeAction.CALL, on.action(true))
        assertEquals(SwipeAction.MESSAGE, on.action(false))
        assertFalse(on.available(SwipeAction.CALL, hasNumber = false, canDelete = true))
        assertTrue(on.available(SwipeAction.DELETE, hasNumber = false, canDelete = true))
        assertEquals(SwipeAction.BLOCK, SwipeAction.parse("BLOCK", SwipeAction.NONE))
        assertEquals(SwipeAction.CALL, SwipeAction.parse("nonsense", SwipeAction.CALL))
    }
}
