package app.parley.ui.birthdays

import app.parley.common.AppSettings
import app.parley.common.people.ContactRef
import app.parley.common.security.DuressState
import app.parley.common.security.PrivacyView
import app.parley.data.EventItem
import app.parley.data.vault.VaultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Birthdays & dates lists private contacts' dates while they show, and none while they are hidden. */
class PrivateEventsTest {
    private val open = PrivacyView.of(AppSettings(hideVault = false), DuressState(), appLocked = false)
    private val grace = VaultSummary(
        7, "Grace", listOf("+1 202 555 0100"), expiresAt = null,
        dates = listOf(EventItem(date = "1906-12-09", type = 3), EventItem(date = "--06-01", type = 1, label = null)),
    )
    private val archived = VaultSummary(
        8, "Ada", listOf("+44 20 7946 0000"), expiresAt = null, archivedAt = 1L, dates = listOf(EventItem(date = "--12-10", type = 3)),
    )

    @Test fun visible_private_contacts_dates_are_listed_and_open_their_page() {
        val events = PrivateEvents.of(listOf(grace, archived), open, lockedByPerson = false) { "photo:$it" }
        assertEquals(listOf("1906-12-09", "--06-01"), events.map { it.date })
        val e = events.first()
        assertEquals(ContactRef.Private(7).navId, e.contactId)
        assertEquals(ContactRef.privateKey(7), e.lookupKey)
        assertEquals("Grace", e.name)
        assertEquals("+1 202 555 0100", e.phone)
        assertEquals("photo:7", e.photoUri)
    }

    @Test fun none_while_hidden_locked_or_under_duress() {
        val hidden = PrivacyView.of(AppSettings(hideVault = true), DuressState(), false)
        val duress = PrivacyView.of(AppSettings(hideVault = false), DuressState(hiding = true, vaultLocked = true), false)
        assertTrue("Hide private contacts", PrivateEvents.of(listOf(grace), hidden, false).isEmpty())
        assertTrue("Lock private contacts", PrivateEvents.of(listOf(grace), open, lockedByPerson = true).isEmpty())
        assertTrue("duress", PrivateEvents.of(listOf(grace), duress, false).isEmpty())
        assertTrue("not known yet", PrivateEvents.of(listOf(grace), PrivacyView.CLOSED, false).isEmpty())
    }

    @Test fun the_screen_lists_them_and_the_lock_screen_widgets_and_notices_never_do() {
        val app = File(File("..").canonicalFile, "app/src/main/kotlin/app/parley")
        assertTrue(File(app, "ui/birthdays/BirthdaysScreen.kt").readText().contains("vm.privateEvents"))
        // The widget and the reminders' notifications keep to the address book's dates.
        listOf("shortcuts/CircleDrawing.kt", "work/RemindersWorker.kt").forEach { f ->
            val text = File(app, f).readText()
            assertFalse("$f reads private dates", text.contains("PrivateEvents") || text.contains("privateEvents") || text.contains("PrivateDates"))
        }
    }
}
