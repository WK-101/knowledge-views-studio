package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTabsTest {
    private val four = listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD)

    @Test fun default_shows_the_four_tabs_and_keeps_the_circle_hidden() {
        val t = NavTabs()
        assertEquals(four, t.visible)
        assertFalse(t.isVisible(StartTab.CIRCLE))
        assertEquals("FAVORITES,RECENTS,CONTACTS,KEYPAD,-CIRCLE", t.encode())
    }

    @Test fun round_trips_order_and_hidden() {
        val t = NavTabs().move(StartTab.KEYPAD, 0).setVisible(StartTab.FAVORITES, false)
        val back = NavTabs.decode(t.encode())
        assertEquals(t, back)
        assertEquals(listOf(StartTab.KEYPAD, StartTab.RECENTS, StartTab.CONTACTS), back.visible)
        assertEquals("KEYPAD,-FAVORITES,RECENTS,CONTACTS,-CIRCLE", t.encode())
        // A shown Circle stays shown.
        val shown = NavTabs().setVisible(StartTab.CIRCLE, true).move(StartTab.CIRCLE, 0)
        assertEquals(shown, NavTabs.decode(shown.encode()))
        assertEquals(StartTab.CIRCLE, NavTabs.decode(shown.encode()).visible.first())
    }

    @Test fun decode_repairs_bad_values() {
        assertEquals(NavTabs(), NavTabs.decode(null))
        assertEquals(NavTabs(), NavTabs.decode(""))
        // Unknown and duplicate tabs are dropped; missing tabs come back at the end, hidden.
        val t = NavTabs.decode("CONTACTS,BOGUS,CONTACTS,-RECENTS")
        assertEquals(listOf(StartTab.CONTACTS, StartTab.RECENTS, StartTab.FAVORITES, StartTab.KEYPAD, StartTab.CIRCLE), t.order)
        assertEquals(listOf(StartTab.CONTACTS), t.visible)
        // Everything hidden: the first tab is shown again.
        val all = NavTabs.decode("-KEYPAD,-RECENTS,-CONTACTS,-FAVORITES,-CIRCLE")
        assertEquals(listOf(StartTab.KEYPAD), all.visible)
    }

    /** U6: a saved bar from before an update keeps its tabs; the tab the update added arrives hidden. */
    @Test fun a_tab_added_by_an_update_arrives_hidden() {
        val saved = "RECENTS,KEYPAD,-FAVORITES,CONTACTS"
        val t = NavTabs.decode(saved)
        assertEquals(listOf(StartTab.RECENTS, StartTab.KEYPAD, StartTab.CONTACTS), t.visible)
        assertEquals(listOf(StartTab.RECENTS, StartTab.KEYPAD, StartTab.FAVORITES, StartTab.CONTACTS, StartTab.CIRCLE), t.order)
        assertTrue(StartTab.CIRCLE in t.hidden)
        assertEquals(StartTab.RECENTS, t.startTab(StartTab.RECENTS))
        // Every visible tab from the saved value is still visible, in the same order.
        assertEquals("RECENTS,KEYPAD,-FAVORITES,CONTACTS,-CIRCLE", t.encode())
        // Showing it later is the user's choice and sticks.
        val shown = t.setVisible(StartTab.CIRCLE, true)
        assertTrue(NavTabs.decode(shown.encode()).isVisible(StartTab.CIRCLE))
    }

    @Test fun last_visible_tab_cannot_be_hidden() {
        var t = NavTabs()
        t = t.setVisible(StartTab.FAVORITES, false).setVisible(StartTab.RECENTS, false).setVisible(StartTab.CONTACTS, false)
        assertEquals(listOf(StartTab.KEYPAD), t.visible)
        assertFalse(t.canHide(StartTab.KEYPAD))
        assertEquals(t, t.setVisible(StartTab.KEYPAD, false))
        assertTrue(t.setVisible(StartTab.RECENTS, true).isVisible(StartTab.RECENTS))
    }

    @Test fun move_up_and_down_are_clamped() {
        val t = NavTabs()
        assertEquals(t, t.moveUp(StartTab.FAVORITES))
        assertEquals(t, t.moveDown(StartTab.CIRCLE))
        assertEquals(listOf(StartTab.RECENTS, StartTab.FAVORITES, StartTab.CONTACTS, StartTab.KEYPAD, StartTab.CIRCLE), t.moveDown(StartTab.FAVORITES).order)
        assertEquals(listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.KEYPAD, StartTab.CONTACTS, StartTab.CIRCLE), t.moveUp(StartTab.KEYPAD).order)
        assertEquals(listOf(StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD, StartTab.CIRCLE, StartTab.FAVORITES), t.move(StartTab.FAVORITES, 99).order)
    }

    @Test fun start_tab_falls_back_to_first_visible() {
        val t = NavTabs().setVisible(StartTab.RECENTS, false)
        assertEquals(StartTab.FAVORITES, t.startTab(StartTab.RECENTS))
        assertEquals(StartTab.KEYPAD, t.startTab(StartTab.KEYPAD))
        assertEquals(StartTab.FAVORITES, t.startTab(StartTab.CIRCLE))
    }

    @Test fun hidden_tab_opened_by_a_link_shows_in_its_place() {
        val t = NavTabs().setVisible(StartTab.KEYPAD, false).move(StartTab.KEYPAD, 1)
        assertEquals(listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS), t.barTabs(StartTab.RECENTS))
        assertEquals(listOf(StartTab.FAVORITES, StartTab.KEYPAD, StartTab.RECENTS, StartTab.CONTACTS), t.barTabs(StartTab.KEYPAD))
    }
}
