package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollTest {
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
