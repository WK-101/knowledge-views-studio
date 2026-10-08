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
        networkName = { n -> network.entries.firstOrNull { PhoneIdentity.same(it.key, n, "IN") }?.value },
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

    @Test fun a_saved_contact_and_a_private_contact_never_go_by_the_network_name() {
        val hits = search("ravi")
        assertEquals(listOf("Ravi Kumar"), hits.map { it.title })
        assertNull(hits.firstOrNull { it.number == "+919812300001" })
        // Mike is found by his own name, never as the network's.
        val mikes = search("mike")
        assertEquals("Mike Silva", mikes.single().title)
        assertFalse(mikes.single().fromNetwork)
    }

    @Test fun the_name_the_call_log_kept_wins_over_the_network() {
        val logged = RecallCorpus(calls = listOf(call(4, "+919812300002", at(9, 1), name = "Plumber")), region = "IN", networkName = { "Ravi Kumar" })
        val q = RecallQuery.parse("plumber", today, Locale.UK)
        val hit = RecallEngine(logged, ZoneOffset.UTC).search(q, { null })[RecallSource.CALL].orEmpty().single()
        assertEquals("Plumber", hit.title)
        assertFalse(hit.fromNetwork)
    }
}
