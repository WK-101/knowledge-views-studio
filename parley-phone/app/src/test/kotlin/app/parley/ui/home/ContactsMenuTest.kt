package app.parley.ui.home

import app.parley.common.AppSettings
import app.parley.common.DuressView
import app.parley.common.ux.LockButton
import app.parley.security.DiscreetSwitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contacts ⋮: no second "Reorder favourites" (the section has its own) and no "Hide private contacts" tick (the top
 * bar's lock hides them now), but "Recently deleted"; and the top bar's private-contacts lock locks and hides them.
 */
class ContactsMenuTest {
    private val source = File("src/main/kotlin/app/parley/ui/home/HomeScreen.kt").readText()
    private val home = source.substringAfter("fun ColumnScope.TabMenu(")
    private val lock = File("src/main/kotlin/app/parley/ui/people/PrivateLock.kt").readText()
    private val strings = File("src/main/res/values/strings_contact_page.xml").readText()

    @Test fun contacts_menu_has_recently_deleted_and_no_hide_tick_or_reorder() {
        val contacts = home.substringAfter("StartTab.CONTACTS -> {").substringBefore("StartTab.KEYPAD ->")
        assertFalse(contacts.contains("HidePrivateMenuItem") || contacts.contains("set_hide_vault_title"))
        assertTrue(contacts.contains("cs_menu_recently_deleted") && contacts.contains("Routes.Journal(HistoryTab.CONTACTS.key)"))
        assertFalse(contacts.contains("home_reorder_title"))
        // Seven at most: Select all, Sort by, duplicates, Recently deleted, Archived (once there are some), Tools, Settings.
        assertTrue(Regex("""MenuItem\(""").findAll(contacts).count() + 2 <= 7)
        // The Favourites tab's ⋮ has no reorder either: Reorder lives with the tiles.
        assertFalse(home.substringAfter("StartTab.FAVORITES -> ").substringBefore("\n").contains("reorder", ignoreCase = true))
    }

    @Test fun the_top_bar_lock_locks_and_hides_and_says_so() {
        val button = lock.substringAfter("fun ContactsLockButton(").substringBefore("/** The same action as a row")
        // Its label, alone and in the menu with Lock Parley, is "Lock and hide private contacts".
        assertTrue(strings.contains("""<string name="pl_lock_hide">Lock and hide private contacts</string>"""))
        assertFalse(button.contains("R.string.pl_lock)"))
        assertEquals(2, Regex("""R\.string\.pl_lock_hide""").findAll(button).count())
        // Locking and hiding is DiscreetSwitch's (the tile's rules), and the snackbar leads to Settings' switch.
        assertTrue(lock.substringAfter("fun lockAndHidePrivateContacts(").contains("DiscreetSwitch.lockAndHide("))
        assertTrue(lock.contains("SettingsCategory.PRIVACY, HIDE_PRIVATE_ROW") && lock.contains("HIDE_PRIVATE_ROW = \"hide_vault\""))
        // Lock only stays on a private contact's page.
        assertTrue(lock.substringAfter("fun LockPrivateRow(").contains("lockPrivateContacts(vm)"))
        assertEquals(LockButton.PRIVATE_CONTACTS, LockButton.of(privateUnlocked = true, appLock = false))
    }

    @Test fun the_switch_shows_as_left_even_during_a_duress_unlock() {
        assertTrue(DiscreetSwitch.shown(AppSettings(hideVault = true)))
        assertFalse(DiscreetSwitch.shown(AppSettings(hideVault = false)))
        // While a duress unlock forces hiding, Settings shows the switch as the user left it.
        assertEquals(false, DiscreetSwitch.shown(AppSettings(hideVault = true, duress = DuressView(hideVault = false, privateVaultHistory = false))))
    }
}
