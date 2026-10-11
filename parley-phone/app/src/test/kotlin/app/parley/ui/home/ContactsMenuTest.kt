package app.parley.ui.home

import app.parley.common.AppSettings
import app.parley.common.DuressView
import app.parley.security.DiscreetSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contacts ⋮: no second "Reorder favourites" (the section has its own), and a "Hide private contacts" tick instead. */
class ContactsMenuTest {
    private val home = File("src/main/kotlin/app/parley/ui/home/HomeScreen.kt").readText().substringAfter("fun ColumnScope.TabMenu(")

    @Test fun contacts_menu_has_hide_private_contacts_and_no_reorder() {
        val contacts = home.substringAfter("StartTab.CONTACTS -> {").substringBefore("StartTab.KEYPAD ->")
        assertTrue(contacts.contains("HidePrivateMenuItem(vm, close)"))
        assertFalse(contacts.contains("home_reorder_title"))
        // The Favourites tab's ⋮ has no reorder either: Reorder lives with the tiles.
        assertFalse(home.substringAfter("StartTab.FAVORITES -> ").substringBefore("\n").contains("reorder", ignoreCase = true))
    }

    @Test fun the_tick_shows_the_switch_as_left_even_during_a_duress_unlock() {
        assertTrue(DiscreetSwitch.shown(AppSettings(hideVault = true)))
        assertFalse(DiscreetSwitch.shown(AppSettings(hideVault = false)))
        // While a duress unlock forces hiding, the menu shows the switch as the user left it (as the Privacy page does).
        assertEquals(false, DiscreetSwitch.shown(AppSettings(hideVault = true, duress = DuressView(hideVault = false, privateVaultHistory = false))))
    }
}
