package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test fun size_classes_follow_material_breakpoints() {
        assertEquals(WindowWidth.COMPACT, WindowWidth.of(411))
        assertEquals(WindowWidth.COMPACT, WindowWidth.of(599))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(600))
        assertEquals(WindowWidth.MEDIUM, WindowWidth.of(839))
        assertEquals(WindowWidth.EXPANDED, WindowWidth.of(840))
    }

    @Test fun phones_keep_one_column_either_way_up() {
        val upright = WindowLayout(411, 891)
        assertFalse(upright.rail)
        assertFalse(upright.listDetail)
        // Sideways a phone is wide but short: a rail, as before, and still one list at a time.
        val sideways = WindowLayout(891, 411)
        assertTrue(sideways.short)
        assertTrue(sideways.rail)
        assertFalse(sideways.listDetail)
    }

    @Test fun tablets_and_open_foldables_held_sideways_show_list_and_detail() {
        assertTrue(WindowLayout(1280, 800).listDetail)
        // A 10-inch tablet upright.
        assertTrue(WindowLayout(800, 1280).listDetail)
        // An unfolded book-style foldable: sideways yes, upright it is a large phone.
        assertTrue(WindowLayout(882, 673).listDetail)
        assertFalse(WindowLayout(673, 841).listDetail)
        assertTrue(WindowLayout(673, 841).rail)
    }

    @Test fun the_list_pane_leaves_the_detail_at_least_a_phone_width() {
        listOf(760, 800, 1024, 1280, 1920).forEach { w ->
            val l = WindowLayout(w, 900)
            assertTrue("$w", l.listPaneDp in WindowLayout.LIST_MIN_DP..WindowLayout.LIST_MAX_DP)
            // After the 80 dp rail.
            assertTrue("$w", w - 80 - l.listPaneDp >= 360)
        }
    }

    @Test fun only_expanded_windows_cap_stretched_content() {
        assertEquals(Int.MAX_VALUE, WindowLayout(411, 891).contentMaxDp)
        assertEquals(Int.MAX_VALUE, WindowLayout(800, 1280).contentMaxDp)
        assertEquals(WindowLayout.CONTENT_MAX_DP, WindowLayout(1280, 800).contentMaxDp)
    }

    @Test fun the_keypad_stays_phone_sized_beyond_a_phone() {
        assertEquals(Int.MAX_VALUE, WindowLayout(411, 891).keypadMaxDp)
        // A phone sideways keeps its own landscape keypad.
        assertEquals(Int.MAX_VALUE, WindowLayout(891, 411).keypadMaxDp)
        assertEquals(WindowLayout.KEYPAD_MAX_DP, WindowLayout(800, 1280).keypadMaxDp)
        assertEquals(WindowLayout.KEYPAD_MAX_DP, WindowLayout(1280, 800).keypadMaxDp)
    }

    @Test fun a_tap_in_the_list_starts_over_and_links_go_on_top() {
        var s = PaneStack().select("c:1")
        assertEquals(listOf("c:1"), s.entries)
        s = s.push("c:2").push("c:2")
        assertEquals(listOf("c:1", "c:2"), s.entries)
        s = s.select("c:3")
        assertEquals(listOf("c:3"), s.entries)
    }

    @Test fun back_returns_to_the_one_before_until_empty() {
        var s = PaneStack().select("c:1").push("h:+44")
        s = s.back()
        assertEquals("c:1", s.top)
        s = s.back()
        assertTrue(s.isEmpty)
        assertNull(s.top)
        assertTrue(s.back().isEmpty)
    }

    @Test fun a_long_chain_of_links_keeps_the_latest() {
        var s = PaneStack()
        repeat(PaneStack.MAX + 5) { s = s.push("c:$it") }
        assertEquals(PaneStack.MAX, s.entries.size)
        assertEquals("c:${PaneStack.MAX + 4}", s.top)
    }
}
