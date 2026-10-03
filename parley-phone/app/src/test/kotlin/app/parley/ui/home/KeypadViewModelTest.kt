package app.parley.ui.home

import android.app.Application
import android.provider.CallLog.Calls
import app.parley.DialResult
import app.parley.data.TemporaryContacts
import app.parley.testing.AppTestbed
import app.parley.testing.AppTestbed.Companion.MINUTE
import kotlinx.coroutines.flow.first
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
 * The keypad: T9 and number matches over contacts, private contacts and recent numbers, the header search, the last
 * number, speed dial and "Save for a while", over the real stores (fake providers).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KeypadViewModelTest {
    private lateinit var t: AppTestbed
    private var now = 0L

    @Before fun setUp() {
        t = AppTestbed()
        now = System.currentTimeMillis()
    }

    @After fun tearDown() = t.close()

    private fun keypad(): KeypadViewModel = t.viewModel { KeypadViewModel(t.c) }.also { vm ->
        t.keep(vm.results)
        t.keep(vm.search)
        t.keep(vm.preferredSim)
    }

    /** The results for [typed], once [ready] holds. */
    private fun KeypadViewModel.type(typed: String, ready: (List<DialResult>) -> Boolean = { it.isNotEmpty() }): List<DialResult> {
        input.value = typed
        t.until({ "results for $typed, got ${results.value.map { it.contact?.displayName to it.number }}" }) { ready(results.value) }
        return results.value
    }

    private fun people() {
        t.contact("Ada", "Lovelace", "+44 20 7946 0001")
        t.contact("Bob", "Marley", "+1 202 555 0142")
    }

    // ---------------------------------------------------------------- T9

    @Test fun the_letters_of_a_first_name_find_the_contact() {
        people()
        val hits = keypad().type("232")
        assertEquals("Ada Lovelace", hits.first().contact?.displayName)
        assertTrue(hits.none { it.contact?.displayName == "Bob Marley" })
    }

    @Test fun the_letters_of_a_last_name_find_the_contact() {
        people()
        val hits = keypad().type("62753")
        assertEquals("Bob Marley", hits.first().contact?.displayName)
    }

    @Test fun digits_of_a_number_find_its_contact() {
        people()
        val hits = keypad().type("5550142")
        assertEquals("Bob Marley", hits.first().contact?.displayName)
        assertEquals("+1 202 555 0142", hits.first().number)
    }

    @Test fun typing_more_narrows_the_results() {
        people()
        t.contact("Adam", "Smith", "+44 20 7946 0003")
        val vm = keypad()
        assertEquals(setOf("Ada Lovelace", "Adam Smith"), vm.type("23") { it.size >= 2 }.mapNotNull { it.contact?.displayName }.toSet())
        assertEquals(listOf("Adam Smith"), vm.type("2326") { it.size == 1 }.mapNotNull { it.contact?.displayName })
    }

    @Test fun nothing_typed_shows_no_results() {
        people()
        val vm = keypad()
        vm.type("232")
        assertTrue(vm.type("") { it.isEmpty() }.isEmpty())
    }

    @Test fun star_and_hash_codes_are_not_searched() {
        people()
        val vm = keypad()
        vm.type("232")
        assertTrue(vm.type("*#06#") { it.isEmpty() }.isEmpty())
    }

    @Test fun a_private_contact_is_found_with_its_private_id() {
        val v = t.privateContact("Grace", "+44 20 7946 0009")
        val hit = keypad().type("47223").first()
        assertEquals("Grace", hit.contact?.displayName)
        assertEquals(-v, hit.contact?.id)
    }

    @Test fun discreet_mode_leaves_private_contacts_out_of_the_keypad() {
        t.privateContact("Grace", "+44 20 7946 0009")
        people()
        val vm = keypad()
        vm.type("47223")
        runBlocking { t.c.settings.update { it.copy(hideVault = true) } }
        assertTrue(vm.type("47223") { it.none { h -> h.contact?.displayName == "Grace" } }.none { it.contact != null })
        // The device contacts are still found.
        assertEquals("Ada Lovelace", vm.type("232").first().contact?.displayName)
    }

    @Test fun a_recent_number_without_a_contact_is_offered() {
        t.call("+44 20 7946 0777", now - MINUTE, Calls.INCOMING_TYPE, 30)
        val hits = keypad().type("0777")
        assertTrue(hits.any { it.contact == null && it.number.endsWith("0777") })
    }

    // ---------------------------------------------------------------- last number

    @Test fun call_with_nothing_typed_brings_back_the_last_number_called() {
        t.call("+44 20 7946 0001", now - MINUTE, Calls.INCOMING_TYPE, 30)
        t.call("+44 20 7946 0002", now - 2 * MINUTE, Calls.OUTGOING_TYPE, 30)
        t.call("+44 20 7946 0003", now - 3 * MINUTE, Calls.OUTGOING_TYPE, 30)
        val vm = keypad()
        t.until("the call log") { t.c.history.calls.value?.size == 3 }
        assertTrue(vm.recallLastNumber())
        assertEquals("+44 20 7946 0002", vm.input.value)
    }

    @Test fun with_no_call_made_there_is_no_last_number() {
        t.call("+44 20 7946 0001", now - MINUTE, Calls.MISSED_TYPE)
        val vm = keypad()
        t.until("the call log") { t.c.history.calls.value?.size == 1 }
        assertFalse(vm.recallLastNumber())
        assertEquals("", vm.input.value)
    }

    // ---------------------------------------------------------------- header search

    private fun KeypadViewModel.searchFor(q: String, ready: (KeypadSearch) -> Boolean): KeypadSearch {
        searchQuery.value = q
        t.until({ "search for $q, got ${search.value}" }) { search.value?.takeIf { it.query == q.trim() }?.let(ready) == true }
        return search.value!!
    }

    @Test fun the_header_search_finds_contacts_by_name() {
        people()
        val found = keypad().searchFor("marl") { it.contacts.isNotEmpty() }
        assertEquals(listOf("Bob Marley"), found.contacts.map { it.displayName })
        assertTrue(found.vault.isEmpty())
    }

    @Test fun the_header_search_finds_contacts_by_number() {
        people()
        val found = keypad().searchFor("0142") { it.contacts.isNotEmpty() }
        assertEquals(listOf("Bob Marley"), found.contacts.map { it.displayName })
    }

    @Test fun the_header_search_finds_private_contacts_as_private_rows() {
        val v = t.privateContact("Grace", "+44 20 7946 0009")
        val found = keypad().searchFor("gra") { it.vault.isNotEmpty() }
        assertEquals(-v, found.vault.single().id)
        assertEquals("Grace", found.vault.single().displayName)
    }

    @Test fun the_header_search_hides_private_contacts_in_discreet_mode() {
        t.privateContact("Grace", "+44 20 7946 0009")
        runBlocking { t.c.settings.update { it.copy(hideVault = true) } }
        val vm = keypad()
        t.until("discreet mode") { t.c.settings.settings.value.hideVault }
        val found = vm.searchFor("gra") { true }
        assertTrue(found.vault.isEmpty())
    }

    @Test fun a_blank_header_search_finds_nothing() {
        people()
        val found = keypad().searchFor("  ") { true }
        assertTrue(found.contacts.isEmpty() && found.vault.isEmpty())
        assertEquals("", found.query)
    }

    // ---------------------------------------------------------------- speed dial and SIM

    @Test fun a_speed_dial_key_calls_its_number() {
        runBlocking { t.c.prefs.setSpeedDial(7, "+44 20 7946 0001", "Ada") }
        var called: Pair<String, String?>? = null
        var unassigned = false
        keypad().speedDial(7, onCall = { n, l -> called = n to l }, onUnassigned = { unassigned = true })
        t.until("the speed dial") { called != null || unassigned }
        assertEquals("+44 20 7946 0001" to "Ada", called)
    }

    @Test fun an_empty_speed_dial_key_says_so() {
        runBlocking { t.c.prefs.clearSpeedDial(8) }
        var called = false
        var unassigned = false
        keypad().speedDial(8, onCall = { _, _ -> called = true }, onUnassigned = { unassigned = true })
        t.until("the speed dial") { called || unassigned }
        assertTrue(unassigned)
        assertFalse(called)
    }

    @Test fun one_sim_shows_no_preferred_sim() {
        val vm = keypad()
        vm.simCount.value = 1
        vm.input.value = "+44 20 7946 0001"
        t.until("the SIM choice to settle") { true }
        assertNull(vm.preferredSim.value)
    }

    // ---------------------------------------------------------------- save for a while

    private fun saveForAWhile(visible: Boolean): TemporaryContacts.Saved {
        var result: TemporaryContacts.Saved? = null
        var done = false
        keypad().saveTemporary("+44 20 7946 0123", "Plumber", days = 7, deleteHistory = true, visible = visible) {
            result = it
            done = true
        }
        t.until("the save") { done }
        assertNotNull("saved", result)
        return result!!
    }

    @Test fun save_for_a_while_keeps_a_visible_contact_with_an_expiry() {
        val saved = saveForAWhile(visible = true)
        assertFalse(saved.private)
        assertTrue(saved.id > 0)
        val key = runBlocking { t.c.contacts.lookupKeyOf(saved.id) }!!
        val entry = runBlocking { t.c.temporaries.forKey(key) }
        assertNotNull(entry)
        assertTrue(entry!!.purgeHistory)
    }

    @Test fun save_for_a_while_can_keep_the_contact_private() {
        val saved = saveForAWhile(visible = false)
        assertTrue(saved.private)
        val entry = runBlocking { t.c.vault.contacts.first { list -> list.isNotEmpty() } }.single()
        assertEquals("Plumber", entry.name)
        assertNotNull(runBlocking { t.c.vault.summary(entry.id) }?.expiresAt)
    }
}
