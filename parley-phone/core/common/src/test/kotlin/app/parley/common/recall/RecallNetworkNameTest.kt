package app.parley.common.recall

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.PhoneIdentity
import app.parley.common.people.ContactListSearch
import app.parley.common.people.ContactSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** "Search everything" finds a number nobody saved by the name the network sent with its calls. */
class RecallNetworkNameTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun at(m: Int, d: Int): Long = LocalDate.of(2026, m, d).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun call(id: Long, number: String, date: Long, name: String? = null) =
        CallEntry(id, number, name, CallType.INCOMING, date, 30, null, isNew = false, presentationHidden = false)

    private val mike = run {
        val c = ContactSummary(1, "k1", "Mike Silva", null, false, listOf(PhoneEntry("+919812300001", 2, null)))
        val doc = ContactSearch.Builder(1, "IN").apply { name("Mike Silva"); number("+919812300001") }.build()
        ContactListSearch.Entry(c, ContactSearch.fold("Mike Silva"), doc)
    }

    /** The network called the plumber's number "Ravi Kumar", and Mike's number "Ravi Traders" (he is saved). */
    private val network = mapOf("+919812300002" to "Ravi Kumar", "+919812300001" to "Ravi Traders", "+919812300003" to "Ravi Private")

    private val corpus = RecallCorpus(
        contacts = listOf(mike),
        calls = listOf(
            call(3, "+919812300002", at(9, 20)),
            call(2, "+919812300001", at(9, 18)),
            // A call with a private contact (Parley's own history): never named by the network.
            call(-5, "+919812300003", at(9, 10)),
        ),
        region = "IN",
        networkName = { n, _ -> network.entries.firstOrNull { PhoneIdentity.same(it.key, n, "IN") }?.value },
    )

    private val contactOf: (String) -> Long? = { n -> if (PhoneIdentity.same(n, "+919812300001", "IN")) 1L else null }

    private fun search(text: String): List<RecallHit> {
        val q = RecallQuery.parse(text, today, Locale.UK)
        return RecallEngine(corpus, ZoneOffset.UTC).search(q, contactOf)[RecallSource.CALL].orEmpty()
    }

    @Test fun the_network_name_finds_the_number_and_says_where_it_came_from() {
        val hit = search("ravi kumar").single()
        assertEquals("Ravi Kumar", hit.title)
        assertEquals("+919812300002", hit.number)
        assertTrue(hit.fromNetwork)
        // Its number still finds it too, under the same name.
        assertEquals("Ravi Kumar", search("9812300002").single().title)
    }

    @Test fun a_saved_callers_calls_are_found_by_the_network_name_under_the_saved_name() {
        val hits = search("ravi")
        assertEquals(listOf("Ravi Kumar", "Mike Silva"), hits.map { it.title })
        val mike = hits.single { it.number == "+919812300001" }
        assertFalse("the saved name is the title", mike.fromNetwork)
        assertEquals("Ravi Traders", mike.networkMatch)
        assertEquals(1L, mike.contactId)
        // The unsaved number is tagged as the network's name and has no saved name to stand under.
        assertNull(hits.single { it.number == "+919812300002" }.networkMatch)
        // Mike is found by his own name, never as the network's.
        val mikes = search("mike")
        assertEquals("Mike Silva", mikes.single().title)
        assertFalse(mikes.single().fromNetwork)
        assertNull(mikes.single().networkMatch)
    }

    @Test fun a_private_contacts_call_never_goes_by_the_network_name() {
        assertTrue(search("private").isEmpty())
        assertNull(search("ravi").firstOrNull { it.number == "+919812300003" })
    }

    @Test fun a_saved_contact_is_a_result_with_the_network_name_as_the_reason() {
        // Plain words: the contact list finds contacts by what is saved; Recall adds those found by the network's name.
        val q = RecallQuery.parse("traders", today, Locale.UK)
        val found = RecallEngine(corpus, ZoneOffset.UTC).search(q, contactOf)
        val contact = found[RecallSource.CONTACT].orEmpty().single()
        assertEquals("Mike Silva", contact.title)
        assertEquals(1L, contact.contactId)
        assertEquals("Ravi Traders", contact.networkMatch)
        assertNull(contact.field)
        assertEquals("Mike Silva", found[RecallSource.CALL].orEmpty().single().title)
    }

    @Test fun a_private_contact_is_never_found_by_a_network_name() {
        val secret = run {
            val c = ContactSummary(-7, "p7", "Secret", null, false, listOf(PhoneEntry("+919812300003", 2, null)))
            val doc = ContactSearch.Builder(-7, "IN").apply { name("Secret"); number("+919812300003") }.build()
            ContactListSearch.Entry(c, ContactSearch.fold("Secret"), doc)
        }
        val withPrivate = RecallCorpus(contacts = listOf(mike, secret), region = "IN", networkName = corpus.networkName)
        val q = RecallQuery.parse("ravi private", today, Locale.UK)
        assertTrue(RecallEngine(withPrivate, ZoneOffset.UTC).search(q, contactOf)[RecallSource.CONTACT].orEmpty().isEmpty())
    }

    @Test fun a_network_name_that_is_the_saved_name_adds_nothing() {
        val same = RecallCorpus(
            contacts = listOf(mike), calls = listOf(call(2, "+919812300001", at(9, 18))), region = "IN", networkName = { _, _ -> "MIKE SILVA" },
        )
        val q = RecallQuery.parse("mike", today, Locale.UK)
        val found = RecallEngine(same, ZoneOffset.UTC).search(q, contactOf)
        // The contact list shows Mike for his own name; Recall adds no second row for the same name from the network.
        assertTrue(found[RecallSource.CONTACT].orEmpty().isEmpty())
        assertNull(found[RecallSource.CALL].orEmpty().single().networkMatch)
    }

    @Test fun nothing_is_found_by_the_network_while_names_are_not_remembered() {
        // The app gives no names while the setting is off: then neither the number nor the contact is found by one.
        val off = RecallCorpus(contacts = listOf(mike), calls = corpus.calls, region = "IN")
        val q = RecallQuery.parse("ravi", today, Locale.UK)
        val found = RecallEngine(off, ZoneOffset.UTC).search(q, contactOf)
        assertTrue(found.values.all { it.isEmpty() })
    }

    @Test fun the_name_the_call_log_kept_wins_over_the_network() {
        val logged = RecallCorpus(calls = listOf(call(4, "+919812300002", at(9, 1), name = "Plumber")), region = "IN", networkName = { _, _ -> "Ravi Kumar" })
        val q = RecallQuery.parse("plumber", today, Locale.UK)
        val hit = RecallEngine(logged, ZoneOffset.UTC).search(q, { null })[RecallSource.CALL].orEmpty().single()
        assertEquals("Plumber", hit.title)
        assertFalse(hit.fromNetwork)
    }

    @Test fun the_name_is_asked_with_the_calls_sim() {
        // A national number on a French SIM: only that SIM's reading of it finds the name.
        val french = CallEntry(6, "0612345678", null, CallType.INCOMING, at(9, 2), 30, "sim-fr", isNew = false, presentationHidden = false)
        val asked = ArrayList<Pair<String, String?>>()
        val corpus = RecallCorpus(calls = listOf(french), region = "IN", networkName = { n, account ->
            asked += n to account
            if (account == "sim-fr") "Claire Martin" else null
        })
        val q = RecallQuery.parse("claire", today, Locale.UK)
        val hit = RecallEngine(corpus, ZoneOffset.UTC).search(q, { null })[RecallSource.CALL].orEmpty().single()
        assertEquals("Claire Martin", hit.title)
        assertEquals(listOf("0612345678" to "sim-fr"), asked.distinct())
    }
}
