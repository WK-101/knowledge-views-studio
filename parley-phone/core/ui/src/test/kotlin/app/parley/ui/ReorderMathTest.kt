package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Drag-to-reorder's index maths, shared by every reorderable list. */
class ReorderMathTest {
    @Test fun moving_takes_the_item_to_its_new_place_and_keeps_the_rest_in_order() {
        val list = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), ReorderMath.moved(list, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), ReorderMath.moved(list, 3, 1))
        // Out of range or nowhere to go: the same list.
        assertSame(list, ReorderMath.moved(list, 0, 4))
        assertSame(list, ReorderMath.moved(list, -1, 0))
        assertSame(list, ReorderMath.moved(list, 2, 2))
    }

    @Test fun a_row_moves_once_the_finger_is_past_half_a_row_and_stays_under_it() {
        // Not far enough: stays, offset unchanged.
        assertEquals(ReorderMath.Step(1, 40f), ReorderMath.rowStep(40f, 100f, 1, 4))
        assertEquals(ReorderMath.Step(1, -50f), ReorderMath.rowStep(-50f, 100f, 1, 4))
        // Past half a row: one place down (or up), and the offset is now from that place.
        assertEquals(ReorderMath.Step(2, -40f), ReorderMath.rowStep(60f, 100f, 1, 4))
        assertEquals(ReorderMath.Step(0, 30f), ReorderMath.rowStep(-70f, 100f, 1, 4))
        // One place at a time, however far the finger went in one move.
        assertEquals(ReorderMath.Step(2, 150f), ReorderMath.rowStep(250f, 100f, 1, 4))
    }

    @Test fun a_row_never_moves_past_either_end_or_before_it_is_measured() {
        assertEquals(ReorderMath.Step(3, 90f), ReorderMath.rowStep(90f, 100f, 3, 4))
        assertEquals(ReorderMath.Step(0, -90f), ReorderMath.rowStep(-90f, 100f, 0, 4))
        assertEquals(ReorderMath.Step(1, 90f), ReorderMath.rowStep(90f, 0f, 1, 4))
    }
}
