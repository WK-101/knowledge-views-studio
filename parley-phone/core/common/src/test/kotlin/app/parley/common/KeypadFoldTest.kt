package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadFoldTest {
    @Test fun drag_follows_the_finger_and_stays_in_range() {
        assertEquals(0.5f, DockFold.dragged(1f, 200f, 400f), 0.0001f)
        assertEquals(0f, DockFold.dragged(0.1f, 400f, 400f), 0.0001f)
        assertEquals(1f, DockFold.dragged(0.9f, -400f, 400f), 0.0001f)
        assertEquals(0.7f, DockFold.dragged(0.7f, 50f, 0f), 0.0001f) // not measured yet: nothing moves
    }

    @Test fun settle_uses_fling_speed_then_distance() {
        // A quick flick decides, whatever the distance.
        assertFalse(DockFold.settle(0.95f, 900f, startedOpen = true))
        assertTrue(DockFold.settle(0.05f, -900f, startedOpen = false))
        // A slow drag must go far enough.
        assertTrue(DockFold.settle(0.8f, 100f, startedOpen = true))
        assertFalse(DockFold.settle(0.6f, 100f, startedOpen = true))
        assertFalse(DockFold.settle(0.2f, -100f, startedOpen = false))
        assertTrue(DockFold.settle(0.35f, 0f, startedOpen = false))
    }

    @Test fun list_scroll_folds_first_then_scrolls() {
        // Scrolling on: the fold takes what it needs, the rest goes to the list.
        assertEquals(-30f, DockFold.preScroll(1f, -30f, 400f), 0.0001f)
        assertEquals(-40f, DockFold.preScroll(0.1f, -100f, 400f), 0.0001f)
        // Folded, scrolling back, or not measured: the list gets everything.
        assertEquals(0f, DockFold.preScroll(0f, -30f, 400f), 0.0001f)
        assertEquals(0f, DockFold.preScroll(0.5f, 30f, 400f), 0.0001f)
        assertEquals(0f, DockFold.preScroll(1f, -30f, 0f), 0.0001f)
    }

    @Test fun a_back_swipe_folds_part_of_the_way_and_never_further() {
        assertEquals(1f, DockFold.backPreview(0f), 0.0001f)
        assertEquals(1f - DockFold.BACK_PREVIEW / 2, DockFold.backPreview(0.5f), 0.0001f)
        assertEquals(1f - DockFold.BACK_PREVIEW, DockFold.backPreview(1f), 0.0001f)
        // Out-of-range progress from the system stays within the preview.
        assertEquals(1f - DockFold.BACK_PREVIEW, DockFold.backPreview(1.5f), 0.0001f)
        assertTrue(DockFold.backPreview(1f) > 0f)
    }
}
