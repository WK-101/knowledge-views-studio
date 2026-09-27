package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesV34Test {
    private val slop = 18f

    // ---------------------------------------------------------------- S1 swipe intent

    @Test fun small_moves_stay_undecided() {
        assertEquals(SwipeIntent.UNDECIDED, SwipeGesture.classify(0f, 0f, slop, false))
        assertEquals(SwipeIntent.UNDECIDED, SwipeGesture.classify(10f, 5f, slop, false))
        assertEquals(SwipeIntent.UNDECIDED, SwipeGesture.classify(-17f, 2f, slop, false))
    }

    @Test fun clearly_sideways_is_a_swipe_both_ways() {
        assertEquals(SwipeIntent.HORIZONTAL, SwipeGesture.classify(20f, 0f, slop, false))
        assertEquals(SwipeIntent.HORIZONTAL, SwipeGesture.classify(-30f, 12f, slop, false))
        assertEquals(SwipeIntent.HORIZONTAL, SwipeGesture.classify(25f, -12f, slop, false))
    }

    @Test fun diagonal_and_vertical_moves_scroll() {
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(20f, 10f, slop, false)) // exactly 2:1 isn't enough
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(25f, 15f, slop, false))
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(2f, 18f, slop, false))
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(40f, -19f, slop, false)) // past the slop vertically first
    }

    @Test fun never_while_the_list_flings() {
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(60f, 0f, slop, true))
        assertEquals(SwipeIntent.VERTICAL, SwipeGesture.classify(0f, 0f, slop, true))
    }

    @Test fun threshold_is_a_third_within_bounds() {
        assertEquals(72f * 2, SwipeGesture.threshold(300f, 2f)) // small rows: at least 72 dp
        assertEquals(330f, SwipeGesture.threshold(1000f, 3f))
        assertEquals(180f * 2, SwipeGesture.threshold(4000f, 2f))
    }

    @Test fun offset_follows_allowed_sides_and_rubber_bands_the_others() {
        assertEquals(100f, SwipeGesture.offset(100f, rightAllowed = true, leftAllowed = false, widthPx = 1000f, density = 2f))
        assertEquals(1000f, SwipeGesture.offset(1500f, true, false, 1000f, 2f))
        assertEquals(-20f, SwipeGesture.offset(-100f, true, false, 1000f, 2f))
        assertEquals(-40f, SwipeGesture.offset(-1000f, true, false, 1000f, 2f)) // at most 20 dp
        assertEquals(0f, SwipeGesture.offset(0f, false, false, 1000f, 2f))
    }

    @Test fun commit_by_distance_or_flick() {
        val t = 200f
        val flick = 2000f
        assertTrue(SwipeGesture.commits(210f, 0f, t, flick))
        assertTrue(SwipeGesture.commits(-210f, 0f, t, flick))
        assertFalse(SwipeGesture.commits(150f, 500f, t, flick))
        assertTrue(SwipeGesture.commits(60f, 2500f, t, flick)) // quick flick past a quarter
        assertFalse(SwipeGesture.commits(40f, 2500f, t, flick)) // too short even for a flick
        assertFalse(SwipeGesture.commits(250f, -2500f, t, flick)) // flicked back: cancel
        assertFalse(SwipeGesture.commits(250f, 0f, t, flick, allowed = false))
        assertFalse(SwipeGesture.commits(0f, 3000f, t, flick))
    }

    // ---------------------------------------------------------------- A4 fast scroll

    @Test fun index_under_the_finger() {
        assertEquals(0, FastScroll.indexAt(-5f, 260f, 26))
        assertEquals(0, FastScroll.indexAt(9f, 260f, 26))
        assertEquals(1, FastScroll.indexAt(10f, 260f, 26))
        assertEquals(25, FastScroll.indexAt(259f, 260f, 26))
        assertEquals(25, FastScroll.indexAt(400f, 260f, 26))
        assertEquals(-1, FastScroll.indexAt(10f, 260f, 0))
        assertEquals(0, FastScroll.indexAt(10f, 0f, 5))
    }

    @Test fun section_at_the_top_of_the_list() {
        val starts = listOf(3, 10, 25)
        assertEquals(-1, FastScroll.sectionAt(0, starts))
        assertEquals(0, FastScroll.sectionAt(3, starts))
        assertEquals(0, FastScroll.sectionAt(9, starts))
        assertEquals(1, FastScroll.sectionAt(10, starts))
        assertEquals(2, FastScroll.sectionAt(400, starts))
        assertEquals(-1, FastScroll.sectionAt(5, emptyList()))
    }

    @Test fun bubble_stays_inside_the_rail() {
        assertEquals(0f, FastScroll.bubbleTop(10f, 500f, 64f))
        assertEquals(136f, FastScroll.bubbleTop(200f, 500f, 64f))
        assertEquals(435f, FastScroll.bubbleTop(499f, 500f, 64f))
        assertEquals(0f, FastScroll.bubbleTop(30f, 40f, 64f))
    }
}
