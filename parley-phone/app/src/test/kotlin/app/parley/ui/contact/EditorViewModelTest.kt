package app.parley.ui.contact

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.lifecycle.SavedStateHandle
import app.parley.R
import app.parley.common.people.EditorForm
import app.parley.common.people.ExpiryChange
import app.parley.common.people.MeCards
import app.parley.common.people.TemporaryChoice
import app.parley.data.AccountRef
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PostalItem
import app.parley.data.HandleItem
import app.parley.data.EventItem
import app.parley.data.CustomFieldItem
import app.parley.common.people.NativeName
import app.parley.common.people.HandleService
import app.parley.data.people.PeopleSettings
import app.parley.testing.AppTestbed
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
 * The contact editor's view model: what it loads for a new, an existing, a private contact and My card; when Save is
 * ready; the save paths (device, private, temporary); a contact changed elsewhere meanwhile; and the draft kept in
 * saved state while the process is stopped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EditorViewModelTest {
    private lateinit var t: AppTestbed
    private val events = ArrayList<EditorEvent>()

    @Before fun setUp() {
        t = AppTestbed()
        runBlocking { t.c.people.prefs.update { PeopleSettings() } }
        t.c.people.me.save(app.parley.common.people.MeCard())
        t.c.people.me.setShareParts(MeCards.defaultParts)
    }

    @After fun tearDown() = t.close()

    private fun str(res: Int) = t.context.getString(res)

    /** An editor opened with [args] (and the saved state [saved]), once its draft has loaded. */
    private fun editor(args: EditorArgs, saved: Bundle? = null, ready: (EditorViewModel) -> Boolean = { it.draft != null }): EditorViewModel {
        val vm = t.viewModel { EditorViewModel(t.c, if (saved == null) SavedStateHandle() else SavedStateHandle(mapOf("editor" to saved))) }
        t.ui.launch { vm.events.collect { events += it } }
        vm.start(args)
        t.until("the editor to load") { ready(vm) }
        return vm
    }

    private fun messages() = events.filterIsInstance<EditorEvent.Message>().map { it.text }

    /** The last message, once one has arrived. */
    private fun lastMessage(): String {
        t.until("a message") { messages().isNotEmpty() }
        return messages().last()
    }

    private fun saved(): EditorEvent.Done {
        t.until({ "the save, got $events" }) { events.any { it is EditorEvent.Done } }
        return events.filterIsInstance<EditorEvent.Done>().single()
    }

    private val ada = ContactDetails(
        given = "Ada", family = "Lovelace",
        phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE)),
        emails = listOf(DataItem(null, "ada@example.org", Email.TYPE_WORK)),
    )

    private fun saveAda(): Long = runBlocking { t.c.contacts.save(null, ada, null, null, false)!!.contactId }

    private fun EditorViewModel.setGiven(name: String) = update { it.copy(given = name) }

    // ---------------------------------------------------------------- a new contact

    @Test fun a_new_contact_starts_from_the_name_number_and_email_it_was_given() {
        val vm = editor(EditorArgs(null, prefillName = "Ada King Lovelace", prefillPhone = "+44 20 7946 0000", prefillEmail = "ada@example.org"))
        val d = vm.draft!!
        assertEquals("Ada", d.given)
        assertEquals("King Lovelace", d.family)
        assertEquals("+44 20 7946 0000", d.phones.single().value)
        assertEquals("ada@example.org", d.emails.single().value)
        assertTrue(vm.isNew)
        assertFalse(vm.isVault)
    }

    @Test fun an_empty_new_contact_has_one_number_row_and_cannot_be_saved_yet() {
        val vm = editor(EditorArgs(null))
        assertEquals(1, vm.draft!!.phones.size)
        assertFalse(vm.changed)
        assertFalse(vm.canSave)
        vm.setGiven("Grace")
        assertTrue(vm.changed)
        assertTrue(vm.canSave)
    }

    @Test fun a_new_contact_is_saved_and_the_editor_leaves() {
        val vm = editor(EditorArgs(null))
        vm.update { it.copy(given = "Grace", family = "Hopper", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))) }
        vm.save()
        val id = saved().savedId!!
        assertTrue(id > 0)
        val d = runBlocking { t.c.contacts.editable(id) }!!
        assertEquals("Grace", d.given)
        assertEquals("+1 202 555 0100", d.phones.single().value)
    }

    @Test fun saving_an_empty_contact_asks_for_a_name_first() {
        val vm = editor(EditorArgs(null))
        vm.save()
        t.until("the message") { messages().isNotEmpty() }
        assertEquals(listOf(str(R.string.edit_add_name_first)), messages())
        assertTrue(events.none { it is EditorEvent.Done })
        assertTrue(t.contacts.rows("raw_contacts").isEmpty())
    }

    @Test fun a_contact_from_another_app_is_a_change_from_the_start() {
        val vm = editor(EditorArgs(null, prefill = ContactDetails(given = "Pasted", phones = listOf(DataItem(null, "+44 20 7946 0101", Phone.TYPE_WORK)))))
        assertTrue(vm.changed)
        assertTrue(vm.canSave)
        assertEquals("Pasted", vm.draft!!.given)
    }

    @Test fun a_blank_row_added_and_left_empty_is_not_a_change() {
        val vm = editor(EditorArgs(saveAda()), ready = { it.original != null })
        vm.update { it.copy(emails = it.emails + DataItem(null, "", Email.TYPE_HOME)) }
        assertFalse(vm.changed)
        vm.update { it.copy(emails = it.emails.dropLast(1) + DataItem(null, "home@example.org", Email.TYPE_HOME)) }
        assertTrue(vm.changed)
    }

    // ---------------------------------------------------------------- an existing contact

    @Test fun an_existing_contact_loads_with_its_rows_and_is_unchanged() {
        val id = saveAda()
        val vm = editor(EditorArgs(id), ready = { it.original != null })
        assertFalse(vm.isNew)
        assertFalse(vm.changed)
        assertFalse(vm.canSave)
        assertEquals("ada@example.org", vm.draft!!.emails.single().value)
        assertNotNull(vm.draft!!.phones.single().id)
        assertEquals(AccountRef(null, null), vm.account)
    }

    @Test fun a_number_added_from_another_app_is_ready_to_save() {
        val id = saveAda()
        val vm = editor(EditorArgs(id, addPhone = "+44 20 7946 0999"), ready = { it.original != null })
        assertEquals(listOf("+44 20 7946 0000", "+44 20 7946 0999"), vm.draft!!.phones.map { it.value })
        assertTrue(vm.changed)
        assertTrue(vm.canSave)
    }

    @Test fun an_edit_to_an_existing_contact_is_saved() {
        val id = saveAda()
        val vm = editor(EditorArgs(id), ready = { it.original != null })
        vm.update { d -> d.copy(phones = d.phones.map { it.copy(value = "+44 20 7946 1111") }) }
        vm.save()
        assertEquals(id, saved().savedId)
        assertEquals("+44 20 7946 1111", runBlocking { t.c.contacts.editable(id) }!!.phones.single().value)
    }

    @Test fun clearing_every_field_of_a_contact_is_refused() {
        val id = saveAda()
        val vm = editor(EditorArgs(id), ready = { it.original != null })
        vm.update { ContactDetails(id = it.id, lookupKey = it.lookupKey, editRawId = it.editRawId, rawContacts = it.rawContacts) }
        vm.save()
        t.until("the message") { messages().isNotEmpty() }
        assertEquals(listOf(str(R.string.edit_nothing_left)), messages())
        assertEquals("Ada", runBlocking { t.c.contacts.editable(id) }!!.given)
    }

    @Test fun picking_and_removing_a_photo_are_changes() {
        val vm = editor(EditorArgs(saveAda()), ready = { it.original != null })
        vm.pickPhoto(Uri.parse("content://media/picked/1"))
        assertTrue(vm.changed)
        assertFalse(vm.removePhoto)
        vm.clearPhoto()
        assertNull(vm.photo)
        assertTrue(vm.removePhoto)
        assertTrue(vm.changed)
    }

    @Test fun making_an_existing_contact_temporary_is_a_change() {
        val vm = editor(EditorArgs(saveAda()), ready = { it.original != null })
        assertNull(vm.expiresAt)
        vm.pickExpiry(ExpiryChange.After(7))
        assertTrue(vm.changed)
        // "Keep" on a contact that isn't temporary changes nothing.
        vm.pickExpiry(ExpiryChange.Keep)
        assertFalse(vm.changed)
    }

    @Test fun starting_again_with_the_same_arguments_keeps_the_edit() {
        val id = saveAda()
        val vm = editor(EditorArgs(id), ready = { it.original != null })
        vm.setGiven("Augusta")
        vm.start(EditorArgs(id))
        t.until("a settled editor") { true }
        assertEquals("Augusta", vm.draft!!.given)
    }

    // ---------------------------------------------------------------- private and temporary

    @Test fun private_by_default_sends_new_contacts_to_the_vault() {
        runBlocking { t.c.people.prefs.update { it.copy(privateByDefault = true) } }
        val vm = editor(EditorArgs(null, prefillName = "Grace", prefillPhone = "+1 202 555 0100"))
        assertTrue(vm.privateNew)
        assertTrue(vm.isVault)
        vm.save()
        val id = saved().savedId!!
        assertTrue(id < 0)
        assertEquals("Grace", runBlocking { t.c.vault.details(-id) }!!.given)
        assertTrue(t.contacts.rows("raw_contacts").isEmpty())
    }

    @Test fun the_save_to_menu_switches_between_private_and_an_account() {
        val vm = editor(EditorArgs(null))
        vm.chooseAccount(null)
        assertTrue(vm.isVault)
        val phone = AccountRef(null, null)
        vm.chooseAccount(phone)
        assertFalse(vm.isVault)
        assertEquals(phone, vm.account)
    }

    @Test fun a_temporary_contact_is_private_unless_chosen_otherwise() {
        val vm = editor(EditorArgs(null))
        vm.chooseTemporary()
        assertTrue(vm.temporaryNew)
        assertTrue(vm.isVault)
        vm.changeTemporary(TemporaryChoice(days = 7, private = false))
        assertFalse(vm.isVault)
        // Choosing an account leaves "Temporary".
        vm.chooseAccount(AccountRef(null, null))
        assertFalse(vm.temporaryNew)
    }

    @Test fun a_temporary_contact_says_when_it_goes() {
        val vm = editor(EditorArgs(null, prefillName = "Plumber", prefillPhone = "+44 20 7946 0123"))
        vm.chooseTemporary()
        vm.changeTemporary(TemporaryChoice(days = 7, private = false))
        vm.save()
        val id = saved().savedId!!
        assertTrue(id > 0)
        t.until("the message") { messages().isNotEmpty() }
        assertEquals(t.context.resources.getQuantityString(R.plurals.temp_deletes_in_days, 7, 7), messages().last())
        val key = runBlocking { t.c.contacts.lookupKeyOf(id) }!!
        assertNotNull(runBlocking { t.c.temporaries.forKey(key) })
    }

    @Test fun a_private_contact_loads_from_the_vault_and_saves_back_there() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = editor(EditorArgs(null, vaultId = v))
        assertTrue(vm.isVault)
        assertFalse(vm.isNew)
        assertEquals("Grace", vm.draft!!.given)
        assertFalse(vm.changed)
        vm.update { it.copy(family = "Hopper") }
        vm.save()
        assertEquals(-v, saved().savedId)
        assertEquals("Hopper", runBlocking { t.c.vault.details(v) }!!.family)
    }

    @Test fun details_added_to_a_private_contact_count_as_a_change() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val vm = editor(EditorArgs(null, vaultId = v, prefill = ContactDetails(emails = listOf(DataItem(null, "grace@example.org", Email.TYPE_WORK)))))
        assertEquals("grace@example.org", vm.draft!!.emails.single().value)
        assertTrue(vm.changed)
    }

    // ---------------------------------------------------------------- changed elsewhere

    /** Opens Ada, changes her [mine] in the editor while another app changes her [theirs], and saves. */
    private fun conflicted(mine: (ContactDetails) -> ContactDetails, theirs: (ContactDetails) -> ContactDetails): Pair<Long, EditorViewModel> {
        val id = saveAda()
        val vm = editor(EditorArgs(id), ready = { it.original != null })
        runBlocking {
            val now = t.c.contacts.editable(id)!!
            t.c.contacts.save(now, theirs(now), null, null, false)
        }
        vm.update(mine)
        vm.save()
        t.until({ "the changed-elsewhere choices, got $events" }) { vm.conflict != null }
        return id to vm
    }

    @Test fun a_contact_changed_elsewhere_is_not_overwritten_and_the_choices_show() {
        val (id, vm) = conflicted({ it.copy(given = "Augusta") }, { it.copy(given = "Countess") })
        val k = vm.conflict!!
        assertEquals("Countess", k.theirs!!.given)
        assertEquals("Augusta", k.mine.given)
        assertTrue(k.conflicts.isNotEmpty())
        assertEquals("Countess", runBlocking { t.c.contacts.editable(id) }!!.given)
        assertTrue(events.none { it is EditorEvent.Done })
    }

    @Test fun show_their_version_puts_the_contact_as_it_is_now_in_the_editor() {
        val (_, vm) = conflicted({ it.copy(given = "Augusta") }, { it.copy(given = "Countess") })
        vm.useTheirs()
        assertNull(vm.conflict)
        assertEquals("Countess", vm.draft!!.given)
        assertFalse(vm.changed)
        assertEquals(str(R.string.edit_changed_reloaded), lastMessage())
    }

    @Test fun keep_mine_saves_the_edit_over_their_version() {
        val (id, vm) = conflicted({ it.copy(given = "Augusta") }, { it.copy(given = "Countess") })
        vm.keepMine()
        assertEquals(id, saved().savedId)
        assertEquals("Augusta", runBlocking { t.c.contacts.editable(id) }!!.given)
    }

    @Test fun merging_keeps_both_sides_changes_to_different_fields() {
        val (id, vm) = conflicted(
            { d -> d.copy(phones = d.phones.map { it.copy(value = "+44 20 7946 2222") }) },
            { it.copy(family = "King") },
        )
        vm.merge(emptyMap())
        assertNull(vm.conflict)
        assertEquals("King", vm.draft!!.family)
        assertEquals("+44 20 7946 2222", vm.draft!!.phones.single().value)
        assertEquals(str(R.string.edit_changed_merged), lastMessage())
        vm.save()
        assertEquals(id, saved().savedId)
        val now = runBlocking { t.c.contacts.editable(id) }!!
        assertEquals("King", now.family)
        assertEquals("+44 20 7946 2222", now.phones.single().value)
    }

    @Test fun closing_the_choices_keeps_the_draft_for_a_later_save() {
        val (_, vm) = conflicted({ it.copy(given = "Augusta") }, { it.copy(given = "Countess") })
        vm.dismissConflict()
        assertNull(vm.conflict)
        assertEquals("Augusta", vm.draft!!.given)
    }

    // ---------------------------------------------------------------- saved state

    @Test fun a_new_contacts_draft_and_choices_come_back_after_the_process_stops() {
        val first = editor(EditorArgs(null))
        first.update { it.copy(given = "Grace", phones = listOf(DataItem(null, "+1 202 555 0100", Phone.TYPE_MOBILE))) }
        first.chooseTemporary()
        first.changeTemporary(TemporaryChoice(days = 3, private = false, purgeHistory = false))
        first.moreName = true
        first.revealed = setOf(EditorForm.Kind.entries.first())
        first.pickPhoto(Uri.parse("content://media/picked/2"))
        val state = first.toBundle()
        assertNotNull("kept in plain text for a device contact", state.getString("draft"))

        val again = editor(EditorArgs(null), saved = state, ready = { it.draft?.given == "Grace" })
        assertTrue(again.temporaryNew)
        assertEquals(TemporaryChoice(days = 3, private = false, purgeHistory = false), again.temporary)
        assertTrue(again.moreName)
        assertEquals(setOf(EditorForm.Kind.entries.first()), again.revealed)
        assertEquals(Uri.parse("content://media/picked/2"), again.photo)
        assertEquals("+1 202 555 0100", again.draft!!.phones.single().value)
    }

    @Test fun a_private_draft_is_sealed_in_saved_state_and_comes_back() {
        val v = t.privateContact("Grace", "+1 202 555 0100")
        val first = editor(EditorArgs(null, vaultId = v))
        first.update { it.copy(family = "Hopper") }
        val state = first.toBundle()
        assertNull("never in plain text", state.getString("draft"))
        assertNotNull(state.getByteArray("sealedDraft"))
        assertFalse(state.toString().contains("Hopper"))

        val again = editor(EditorArgs(null, vaultId = v), saved = state, ready = { it.draft?.family == "Hopper" })
        assertTrue(again.changed)
    }

    @Test fun an_existing_contacts_expiry_choice_comes_back() {
        val id = saveAda()
        val first = editor(EditorArgs(id), ready = { it.original != null })
        first.pickExpiry(ExpiryChange.After(30))
        first.setGiven("Augusta")
        val again = editor(EditorArgs(id), saved = first.toBundle(), ready = { it.draft?.given == "Augusta" })
        assertEquals(ExpiryChange.After(30), again.expiryPick)
    }

    // ---------------------------------------------------------------- My card

    @Test fun my_card_is_edited_with_the_same_form_and_saved_in_parley() {
        val vm = editor(EditorArgs(null, meCard = true))
        assertFalse(vm.isNew)
        assertEquals(1, vm.draft!!.phones.size)
        vm.update { it.copy(given = "Me", phones = listOf(DataItem(null, "+44 20 7946 0500", Phone.TYPE_MOBILE))) }
        vm.save()
        assertNull(saved().savedId)
        assertEquals(str(R.string.me_saved), messages().single())
        assertEquals(listOf("+44 20 7946 0500"), t.c.people.me.card.value.phones)
        // Nothing reaches the address book.
        assertTrue(t.contacts.rows("raw_contacts").isEmpty())
    }

    @Test fun my_cards_shared_parts_are_a_change_and_are_saved() {
        val vm = editor(EditorArgs(null, meCard = true))
        assertFalse(vm.changed)
        // Name, numbers and e-mail until chosen otherwise; the note only when ticked.
        assertEquals(setOf(MeCards.Part.NAME, MeCards.Part.PHONES, MeCards.Part.EMAILS), vm.meParts)
        vm.toggleMePart(MeCards.Part.NOTE)
        assertTrue(vm.changed)
        assertTrue(MeCards.Part.NOTE in vm.meParts)
        vm.save()
        saved()
        assertTrue(MeCards.Part.NOTE in t.c.people.me.shareParts.value)
    }

    /** Every field and option a contact has, as My card keeps it: what the full editor shows for a contact. */
    private val everything = ContactDetails(
        prefix = "Dr", given = "Ana", middle = "Maria", family = "Lima", suffix = "Jr", phoneticGiven = "Ah-na", phoneticMiddle = "Ma-ria",
        phoneticFamily = "Lee-ma", secondSurname = "Souza", generation = "II", nickname = "Annie", pronouns = "she/her",
        nativeName = NativeName(full = "Анна Лима", language = "ru"), company = "Acme", title = "Engineer", department = "Labs",
        phones = listOf(
            DataItem(value = "+44 20 7946 0500", type = Phone.TYPE_WORK, isPrimary = true),
            DataItem(value = "+44 7700 900123", type = Phone.TYPE_MOBILE),
            DataItem(value = "+44 7700 900999", type = 0, label = "Boat"),
        ),
        emails = listOf(DataItem(value = "ana@example.org", type = Email.TYPE_WORK), DataItem(value = "ana@home.example", type = Email.TYPE_HOME)),
        addresses = listOf(
            PostalItem(street = "1 High St", city = "London", postcode = "SW1A 1AA", country = "UK", type = 1),
            PostalItem(street = "2 Low Rd", city = "Leeds", type = 2),
        ),
        events = listOf(EventItem(date = "1990-05-01", type = 3), EventItem(date = "--06-12", type = 1)),
        websites = listOf(DataItem(value = "https://ana.example", type = 1), DataItem(value = "https://github.com/analima", type = 0, label = "GitHub")),
        relations = listOf(DataItem(value = "Sam Lima", type = 0, label = "Husband"), DataItem(value = "Bo Lima", type = 3)),
        handles = listOf(HandleItem(service = HandleService.SIGNAL, value = "+44 7700 900123")),
        languages = listOf("pt-BR", "en"), citizenships = listOf("BR", "GB"),
        customFields = listOf(CustomFieldItem(label = "Shoe size", value = "38")),
        note = "Allergic to cats",
    )

    @Test fun my_card_keeps_every_field_a_contact_has() {
        val vm = editor(EditorArgs(null, meCard = true))
        vm.update { everything }
        vm.save()
        saved()
        // Reordered rows, labels and every name part come back as typed.
        val again = editor(EditorArgs(null, meCard = true))
        assertEquals(everything, again.draft!!.copy(displayName = "", photoUri = null))
        // The short form "Send my details" uses: the name and the default number first.
        val card = t.c.people.me.card.value
        assertEquals("Dr Ana Maria Lima Jr", card.name)
        assertEquals("+44 20 7946 0500", card.firstNumber)
        // Nothing reaches the address book.
        assertTrue(t.contacts.rows("raw_contacts").isEmpty())
    }

    @Test fun my_cards_relations_remember_the_contact_picked_for_them() {
        val vm = editor(EditorArgs(null, meCard = true))
        vm.update { it.copy(given = "Ana", relations = listOf(DataItem(value = "Sam", type = 0, label = "Husband"))) }
        vm.linkRelation("Sam", app.parley.common.people.RelationLinks.Link("sam-key", 7))
        vm.save()
        saved()
        assertEquals("sam-key", t.c.people.me.links.value["sam"]?.lookupKey)
        // "My husband" shows on Sam's page as Husband, from My card.
        val shown = app.parley.data.people.RelationsFromOthers.fromMyCard(t.c, "sam-key").single()
        assertTrue(shown.fromMe)
        assertEquals("husband", shown.row.typeKey)
        assertEquals("Ana", shown.row.name)
    }
}
