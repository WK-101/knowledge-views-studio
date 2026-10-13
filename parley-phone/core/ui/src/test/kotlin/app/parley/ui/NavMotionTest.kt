package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Forward pages come in from the end side: the right left to right, the left in Arabic and Urdu. */
class NavMotionTest {
    @Test fun the_page_shift_points_to_the_end_side() {
        assertEquals(30, ParleyMotion.navShift(30, rtl = false))
        assertEquals(-30, ParleyMotion.navShift(30, rtl = true))
    }
}
