package app.parley.ui.contact

import android.app.Application
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.lifecycle.SavedStateHandle
import app.parley.common.people.ContactRef
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.people.PeopleSettings
import app.parley.data.vault.VaultCrypto
import app.parley.testing.AppTestbed
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Private contacts while they are locked: the editor asks for their unlock instead of failing (and keeps every edit
 * when it is cancelled), "Lock private contacts" closes an open private page, and a contact's page lists its labels,
 * a device contact's and a private one's alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivateLockedFlowsTest {
    private lateinit var t: AppTestbed
    private val events = ArrayList<EditorEvent>()

    @Before fun setUp() {
        VaultCrypto.lockedByPerson = false
        t = AppTestbed()
        runBlocking { t.c.people.prefs.update { PeopleSettings() } }
    }

    @After fun tearDown() {
        VaultCrypto.lockedByPerson = false
        t.close()
    }

    private fun editor(args: EditorArgs, ready: (EditorViewModel) -> Boolean = { it.draft != null }): EditorViewModel {
        val vm = t.viewModel { EditorViewModel(t.c, SavedStateHandle()) }
        t.ui.launch { vm.events.collect { events += it } }
        vm.start(args)
        t.until({ "the editor, got $events" }) { ready(vm) }
        return vm
    }

    private fun unlockAsked(step: UnlockStep) = t.until({ "the unlock for $step, got $events" }) { EditorEvent.Unlock(step) in events }

    private fun noErrors() = assertTrue("no error message: $events", events.none { it is EditorEvent.Message })

    @Test fun saving_while_locked_asks_for_the_unlock_then_saves_the_edit() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = editor(EditorArgs(null, vaultId = v))
        vm.update { it.copy(family = "Hopper") }
        t.c.vault.lockAll()
        vm.save()
        unlockAsked(UnlockStep.SAVE)
        noErrors()
        assertTrue(events.none { it is EditorEvent.Done })
        assertEquals("Hopper", vm.draft!!.family)

        // The unlock succeeds: the same edit is saved.
        t.c.vault.unlockedByPerson()
        vm.unlocked(UnlockStep.SAVE)
        t.until({ "the save, got $events" }) { events.any { it is EditorEvent.Done } }
        assertEquals(-v, events.filterIsInstance<EditorEvent.Done>().single().savedId)
        assertEquals("Hopper", runBlocking { t.c.vault.details(v) }!!.family)
        noErrors()
    }

    @Test fun a_cancelled_unlock_keeps_the_editor_open_with_every_edit() {
        val vm = editor(EditorArgs(null, prefillName = "Ada Lovelace", prefillPhone = "+44 20 7946 0000"))
        vm.chooseAccount(null)
        vm.update { it.copy(note = "Met at the library") }
        t.c.vault.lockAll()
        vm.save()
        unlockAsked(UnlockStep.SAVE)
        vm.unlockDeclined(UnlockStep.SAVE)
        t.until("the editor to settle") { !vm.saving }
        // Nothing saved, nothing said, nothing lost: Save again asks again.
        assertTrue(events.none { it is EditorEvent.Done })
        noErrors()
        assertEquals("Met at the library", vm.draft!!.note)
        assertTrue(runBlocking { t.c.vault.summariesNow() }.isEmpty())
        vm.save()
        t.until("a second unlock") { events.count { it == EditorEvent.Unlock(UnlockStep.SAVE) } == 2 }
    }

    @Test fun opening_a_private_contact_while_locked_asks_for_the_unlock_first() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        t.c.vault.lockAll()
        val vm = editor(EditorArgs(null, vaultId = v), ready = { EditorEvent.Unlock(UnlockStep.OPEN) in events })
        assertNull(vm.draft)
        noErrors()
        t.c.vault.unlockedByPerson()
        vm.unlocked(UnlockStep.OPEN)
        t.until("the contact") { vm.draft != null }
        assertEquals("Grace", vm.draft!!.given)
    }

    @Test fun lock_all_shows_an_open_private_page_locked() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val page = t.viewModel { ContactDetailViewModel(t.c) }
        t.keep(page.state)
        page.start(ContactRef.Private(v).navId)
        t.until("the details") { page.state.value.access == PrivateAccess.OPEN }
        t.c.vault.lockAll()
        t.until("the locked state") { page.state.value.access == PrivateAccess.LOCKED }
        // Name and numbers stay (the caller-ID copy).
        assertEquals("Grace", page.state.value.details?.displayName)
    }

    @Test fun the_page_lists_a_device_contacts_labels_and_a_private_contacts_too() {
        val family = runBlocking { t.c.contacts.createGroup("Family", AccountRef(null, null)) }!!
        val book = runBlocking { t.c.contacts.createGroup("Book club", AccountRef(null, null)) }!!
        val ada = t.contact("Ada", "Lovelace", "+44 20 7946 0000")
        runBlocking { t.c.people.labels.addMembers(listOf(ada), t.c.contacts.groups().first { it.id == family }) }
        val grace = runBlocking {
            t.c.vault.save(null, ContactDetails(given = "Grace", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE)), groupIds = setOf(book)))
        }

        val device = t.viewModel { ContactDetailViewModel(t.c) }
        t.keep(device.state)
        t.keep(device.labels)
        device.start(ada)
        t.until({ "Ada's labels, got ${device.labels.value}" }) { device.labels.value.map { it.title } == listOf("Family") }

        val private = t.viewModel { ContactDetailViewModel(t.c) }
        t.keep(private.state)
        t.keep(private.labels)
        private.start(ContactRef.Private(grace).navId)
        t.until({ "Grace's labels, got ${private.labels.value}" }) { private.labels.value.map { it.title } == listOf("Book club") }
        // Readable while private contacts are locked too.
        t.c.vault.lockAll()
        private.reload()
        t.until("the locked page") { private.state.value.access == PrivateAccess.LOCKED }
        assertEquals(listOf("Book club"), private.labels.value.map { it.title })
        t.c.vault.unlockedByPerson()

        // "Add to label": the labels they aren't in, then the new one on the page.
        t.until("Grace's page") { private.state.value.details != null }
        val offered = runBlocking { private.labelChoices() }
        assertEquals(listOf("Family"), offered.map { it.title })
        private.addToLabel(offered.single())
        t.until({ "Grace in Family, got ${private.labels.value}" }) { private.labels.value.map { it.title } == listOf("Book club", "Family") }
    }
}
