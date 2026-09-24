package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavTabsTest {
    @Test fun default_shows_every_tab_in_order() {
        val t = NavTabs()
        assertEquals(NavTabs.DEFAULT_ORDER, t.visible)
        assertEquals("FAVORITES,RECENTS,CONTACTS,KEYPAD", t.encode())
    }

    @Test fun round_trips_order_and_hidden() {
        val t = NavTabs().move(StartTab.KEYPAD, 0).setVisible(StartTab.FAVORITES, false)
        val back = NavTabs.decode(t.encode())
        assertEquals(t, back)
        assertEquals(listOf(StartTab.KEYPAD, StartTab.RECENTS, StartTab.CONTACTS), back.visible)
        assertEquals("KEYPAD,-FAVORITES,RECENTS,CONTACTS", t.encode())
    }

    @Test fun decode_repairs_bad_values() {
        assertEquals(NavTabs(), NavTabs.decode(null))
        assertEquals(NavTabs(), NavTabs.decode(""))
        // Unknown and duplicate tabs are dropped; missing tabs come back at the end.
        val t = NavTabs.decode("CONTACTS,BOGUS,CONTACTS,-RECENTS")
        assertEquals(listOf(StartTab.CONTACTS, StartTab.RECENTS, StartTab.FAVORITES, StartTab.KEYPAD), t.order)
        assertEquals(setOf(StartTab.RECENTS), t.hidden)
        // Everything hidden: the first tab is shown again.
        val all = NavTabs.decode("-KEYPAD,-RECENTS,-CONTACTS,-FAVORITES")
        assertEquals(listOf(StartTab.KEYPAD), all.visible)
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
        assertEquals(t, t.moveDown(StartTab.KEYPAD))
        assertEquals(listOf(StartTab.RECENTS, StartTab.FAVORITES, StartTab.CONTACTS, StartTab.KEYPAD), t.moveDown(StartTab.FAVORITES).order)
        assertEquals(listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.KEYPAD, StartTab.CONTACTS), t.moveUp(StartTab.KEYPAD).order)
        assertEquals(listOf(StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD, StartTab.FAVORITES), t.move(StartTab.FAVORITES, 99).order)
    }

    @Test fun start_tab_falls_back_to_first_visible() {
        val t = NavTabs().setVisible(StartTab.RECENTS, false)
        assertEquals(StartTab.FAVORITES, t.startTab(StartTab.RECENTS))
        assertEquals(StartTab.KEYPAD, t.startTab(StartTab.KEYPAD))
    }

    @Test fun hidden_tab_opened_by_a_link_shows_in_its_place() {
        val t = NavTabs().setVisible(StartTab.KEYPAD, false).move(StartTab.KEYPAD, 1)
        assertEquals(listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS), t.barTabs(StartTab.RECENTS))
        assertEquals(listOf(StartTab.FAVORITES, StartTab.KEYPAD, StartTab.RECENTS, StartTab.CONTACTS), t.barTabs(StartTab.KEYPAD))
    }
}
