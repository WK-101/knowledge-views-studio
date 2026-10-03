package app.parley.ui.contact

import android.app.Application
import android.provider.CallLog.Calls
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Relation
import app.parley.R
import app.parley.common.people.ContactRef
import app.parley.common.people.ContactStorage
import app.parley.common.people.MessengerPrefs
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.testing.AppTestbed
import app.parley.testing.AppTestbed.Companion.MINUTE
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A contact's page: what it reads for a device contact, a private one and a contact that is gone (their calls only,
 * by line), and its actions (note for calls, star, voicemail, expiry, usual app, default number, dates, relations,
 * deleting a private contact).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContactDetailViewModelTest {
    private lateinit var t: AppTestbed
    private val messages = ArrayList<String>()

    @Before fun setUp() {
        t = AppTestbed()
    }

    @After fun tearDown() = t.close()

    private fun page(navId: Long, ready: (ContactDetailUiState) -> Boolean = { it.loaded }): ContactDetailViewModel {
        val vm = t.viewModel { ContactDetailViewModel(t.c) }
        t.keep(vm.state)
        t.ui.launch { vm.events.collect { messages += it } }
        vm.start(navId)
        t.until({ "the page, got ${vm.state.value}" }) { ready(vm.state.value) }
        return vm
    }

    private fun ContactDetailViewModel.until(what: String, check: (ContactDetailUiState) -> Boolean) {
        t.until({ "$what, got ${state.value}" }) { check(state.value) }
    }

    private fun lastMessage(): String {
        t.until("a message") { messages.isNotEmpty() }
        return messages.last()
    }

    private val ada = ContactDetails(
        given = "Ada", family = "Lovelace",
        phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE), DataItem(null, "+44 20 7946 0001", Phone.TYPE_WORK)),
    )

    private fun saveAda(details: ContactDetails = ada): Long = runBlocking { t.c.contacts.save(null, details, null, null, false)!!.contactId }

    // ---------------------------------------------------------------- what it shows

    @Test fun a_device_contacts_page_shows_its_details() {
        val id = saveAda()
        val s = page(id) { it.details != null }.state.value
        assertEquals("Ada", s.details!!.given)
        assertEquals(ContactRef.Device(id), s.ref)
        assertEquals(ContactStorage.DEVICE, s.storage)
        assertFalse(s.isPrivate)
        assertEquals(PrivateAccess.OPEN, s.access)
    }

    @Test fun a_contact_that_is_gone_loads_as_nothing() {
        val s = page(4242).state.value
        assertTrue(s.loaded)
        assertNull(s.details)
    }

    @Test fun the_page_lists_only_their_calls_on_any_of_their_numbers() {
        val now = System.currentTimeMillis()
        t.call("+442079460000", now - MINUTE, Calls.INCOMING_TYPE, 30)
        t.call("+44 20 7946 0001", now - 2 * MINUTE, Calls.OUTGOING_TYPE, 30)
        t.call("+44 20 7946 0002", now - 3 * MINUTE, Calls.INCOMING_TYPE, 30)
        val id = saveAda()
        val s = page(id) { it.history.size == 2 }.state.value
        assertEquals(setOf("+442079460000", "+44 20 7946 0001"), s.history.map { it.number }.toSet())
        // Newest first.
        assertTrue(s.history[0].date > s.history[1].date)
    }

    @Test fun a_private_contacts_page_shows_its_sealed_details_and_its_private_calls() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        runBlocking { t.c.vault.storePrivateCall(v, "+1 202 555 0100", "Grace", System.currentTimeMillis() - MINUTE, 60, Calls.INCOMING_TYPE) }
        val s = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.history.isNotEmpty() }.state.value
        assertTrue(s.isPrivate)
        assertEquals(ContactRef.privateKey(v), s.details!!.lookupKey)
        assertEquals(-v, s.details!!.id)
        assertTrue(s.history.single().id < 0)
        assertNotNull(s.privateIndex)
    }

    // ---------------------------------------------------------------- actions on a device contact

    @Test fun the_note_for_calls_is_kept_and_removed() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        vm.setPinnedNote("  Gate code 1234  ")
        vm.until("the note") { it.meta?.pinnedNote == "Gate code 1234" }
        vm.setPinnedNote("   ")
        vm.until("the note to go") { it.meta?.pinnedNote == null }
    }

    @Test fun starring_a_device_contact_writes_the_address_books_star() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        vm.setStarred(true)
        t.until("the star") { runBlocking { t.c.contacts.details(id) }?.starred == true }
        vm.reload()
        vm.until("the page to show it") { it.details?.starred == true }
    }

    @Test fun send_to_voicemail_is_written_for_a_device_contact() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        vm.setSendToVoicemail(true)
        vm.until("voicemail") { it.details?.sendToVoicemail == true }
    }

    @Test fun a_device_contact_is_made_temporary_then_kept() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        vm.setExpiry(7)
        assertEquals(t.context.resources.getQuantityString(R.plurals.detail_deletes_in_days, 7, 7), lastMessage())
        vm.until("the expiry") { it.temporary != null }
        assertTrue(vm.state.value.variants.isTemporary)
        vm.setExpiry(null)
        vm.until("the expiry to go") { it.temporary == null }
        assertEquals(t.context.getString(R.string.detail_kept), messages.last())
    }

    @Test fun the_usual_app_is_remembered() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        val prefs = MessengerPrefs(message = "org.example.chat")
        vm.setMessengerPrefs(prefs)
        vm.until("the usual app") { it.prefs == prefs }
    }

    @Test fun a_date_from_the_chips_is_added_to_the_address_book() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        val ok = runBlocking { vm.addDate(vm.state.value.details!!, EventItem(date = "--12-10", type = 3)) }
        assertTrue(ok)
        assertEquals(listOf("--12-10"), runBlocking { t.c.contacts.editable(id) }!!.events.map { it.date })
    }

    @Test fun the_sim_for_a_number_is_remembered() {
        val id = saveAda()
        val vm = page(id) { it.details != null }
        vm.setSimFor("+44 20 7946 0000", "sim-2")
        vm.until("the SIM choice") { s -> s.simPrefs.any { it.phoneAccountId == "sim-2" } }
    }

    // ---------------------------------------------------------------- actions on a private contact

    @Test fun a_private_contacts_star_stays_in_parley() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.details != null }
        vm.setStarred(true)
        t.until("the star") { runBlocking { t.c.vault.summary(v) }?.starred == true }
        assertTrue(t.contacts.rows("raw_contacts").isEmpty())
    }

    @Test fun a_private_contacts_note_for_calls_is_sealed_with_it() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.details != null }
        vm.setPinnedNote("Speaks slowly")
        t.until("the note") { runBlocking { t.c.vault.details(v) }?.pinnedNote == "Speaks slowly" }
        vm.until("the page to show it") { it.meta?.pinnedNote == "Speaks slowly" }
    }

    @Test fun a_private_contact_is_made_temporary_in_its_vault_entry() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.details != null }
        vm.setExpiry(3)
        t.until("the expiry") { runBlocking { t.c.vault.summary(v) }?.expiresAt != null }
        vm.until("the page to show it") { it.temporary != null }
        assertEquals(-v, vm.state.value.temporary!!.contactId)
    }

    @Test fun a_private_contacts_default_number_is_marked_in_its_details() {
        val v = runBlocking {
            t.c.vault.save(null, ContactDetails(given = "Grace", phones = listOf(DataItem(null, "+1 202 555 0100", 2), DataItem(null, "+1 202 555 0199", 3))))
        }
        val vm = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.details != null }
        val second = vm.state.value.details!!.phones.last()
        vm.setDefault(second, Phone.CONTENT_ITEM_TYPE, on = true)
        assertEquals(t.context.getString(R.string.detail_default_set), lastMessage())
        val phones = runBlocking { t.c.vault.details(v) }!!.phones
        assertEquals(listOf(false, true), phones.map { it.isPrimary })
    }

    @Test fun deleting_a_private_contact_removes_it_and_closes_the_page() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = page(ContactRef.Private(v).navId) { it.access == PrivateAccess.OPEN && it.details != null }
        var closed = false
        vm.deletePrivate(keepCopy = false) { closed = true }
        t.until("the page to close") { closed }
        assertNull(runBlocking { t.c.vault.summary(v) })
    }

    // ---------------------------------------------------------------- relations

    private fun relation(target: (RelationTarget) -> Unit, vm: ContactDetailViewModel, name: String): RelationTarget {
        var got: RelationTarget? = null
        vm.openRelation(name) { got = it }
        t.until("the relation") { got != null }
        target(got!!)
        return got!!
    }

    @Test fun a_relation_opens_the_contact_of_that_name() {
        val mum = saveAda(ContactDetails(given = "Anne", family = "Byron"))
        val id = saveAda(ada.copy(relations = listOf(DataItem(null, "Anne Byron", Relation.TYPE_MOTHER))))
        val vm = page(id) { it.details != null }
        relation({ assertEquals(RelationTarget.Contact(mum), it) }, vm, "Anne Byron")
    }

    @Test fun a_relation_nobody_has_is_offered_as_a_new_contact() {
        val id = saveAda(ada.copy(relations = listOf(DataItem(null, "Charles Babbage", Relation.TYPE_FRIEND))))
        val vm = page(id) { it.details != null }
        relation({ assertEquals(RelationTarget.None("Charles Babbage"), it) }, vm, "Charles Babbage")
    }

    @Test fun namesakes_are_offered_to_choose_from() {
        saveAda(ContactDetails(given = "Anne", family = "Byron", phones = listOf(DataItem(null, "+44 20 7946 0300", 2))))
        saveAda(ContactDetails(given = "Anne", family = "Byron", phones = listOf(DataItem(null, "+44 20 7946 0301", 2))))
        val id = saveAda(ada.copy(relations = listOf(DataItem(null, "Anne Byron", Relation.TYPE_MOTHER))))
        val vm = page(id) { it.details != null }
        relation({ assertEquals(2, (it as RelationTarget.Choose).people.size) }, vm, "Anne Byron")
    }
}
