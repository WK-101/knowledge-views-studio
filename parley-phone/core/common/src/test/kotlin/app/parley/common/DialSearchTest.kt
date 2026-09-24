package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** K3 every number, K7 text search, K9 narrowing, A7 redial. */
class DialSearchTest {
    private fun contact(id: Long, name: String, vararg phones: PhoneEntry) =
        ContactSummary(id, "k$id", name, null, false, phones.toList())

    private fun entries(vararg c: ContactSummary) = c.map { DialSearch.Entry(it, T9.Encoded(it.displayName)) }

    private fun call(number: String, type: CallType, date: Long = 1000L) = CallEntry(date, number, null, type, date, 0, null, false, false)

    private val john = contact(
        1, "John Smith",
        PhoneEntry("+33 1 11 11 11 11", 3, null),
        PhoneEntry("+33 6 12 34 56 78", 2, null, isPrimary = true),
        PhoneEntry("+33 6 99 99 99 99", 2, null),
    )

    @Test fun every_number_with_the_default_first() {
        val hits = DialSearch().search("5646", entries(john), emptyList())
        assertEquals(listOf("+33 6 12 34 56 78", "+33 1 11 11 11 11", "+33 6 99 99 99 99"), hits.map { it.number })
        assertTrue(hits[0].primary)
        assertFalse(hits[0].secondary)
        assertTrue(hits[1].secondary && hits[2].secondary)
        // Only the main row carries the name highlight.
        assertTrue(hits[0].match.nameRanges.isNotEmpty())
        assertTrue(hits[1].match.nameRanges.isEmpty())
    }

    @Test fun matched_number_comes_first() {
        val hits = DialSearch().search("336999", entries(john), emptyList())
        assertEquals("+33 6 99 99 99 99", hits.first().number)
        assertFalse(hits.first().primary)
    }

    @Test fun single_number_is_not_labelled_primary() {
        val solo = contact(2, "Ann", PhoneEntry("123456789", 2, null, isPrimary = true))
        val hits = DialSearch().search("266", entries(solo), emptyList())
        assertEquals(1, hits.size)
        assertFalse(hits[0].primary)
    }

    @Test fun recent_callers_rank_higher() {
        val a = contact(1, "Jon Alpha", PhoneEntry("111111111", 2, null))
        val b = contact(2, "Jon Beta", PhoneEntry("222222222", 2, null))
        val now = 100L * 86_400_000L
        val hits = DialSearch().search("566", entries(a, b), listOf(call("222222222", CallType.OUTGOING, now - 1000)), now)
        assertEquals("Jon Beta", hits.first().contact!!.displayName)
    }

    @Test fun narrowing_tests_only_previous_candidates_and_gives_the_same_results() {
        val names = listOf("John Smith", "Johanna Berg", "Kate Olsen", "Jonas Kahn", "Mia Jones", "Leo Johnson") +
            (1..200).map { "Person $it" }
        val list = entries(*names.mapIndexed { i, n -> contact(i.toLong(), n, PhoneEntry("0${600000000 + i}", 2, null)) }.toTypedArray())
        val search = DialSearch()
        var typed = ""
        for (d in "5646") {
            typed += d
            val incremental = search.search(typed, list, emptyList())
            val full = DialSearch().search(typed, list, emptyList())
            assertEquals("query $typed", full, incremental)
        }
        // After "564" (3 digits) the fourth digit only re-tested the earlier candidates.
        assertTrue(search.lastScanned < list.size)
        // A different list means a full scan.
        search.search("56464", list.toList(), emptyList())
        assertEquals(list.size, search.lastScanned)
        // Deleting a digit is a full scan too.
        search.search("5646", list, emptyList())
        search.search("564", list, emptyList())
        assertEquals(list.size, search.lastScanned)
    }

    @Test fun short_queries_are_never_narrowed() {
        // "34" matches nothing (number substrings need 3 digits), but "345" is inside the number.
        val list = entries(contact(1, "Zed", PhoneEntry("0612345", 2, null)))
        val s = DialSearch()
        assertTrue(s.search("34", list, emptyList()).isEmpty())
        assertEquals(1, s.search("345", list, emptyList()).size)
    }

    @Test fun letters_from_a_hardware_keyboard_search_as_text() {
        val list = entries(john, contact(2, "Kate", PhoneEntry("5646", 2, null)))
        val hits = DialSearch().search("smi", list, emptyList())
        assertEquals(1, hits.map { it.contact!!.id }.distinct().size)
        assertEquals(listOf(5..7), hits.first().match.nameRanges)
        // As digits, "smi" would be 764, which matches nothing else here either way; "jo" as text isn't T9 "56".
        assertTrue(DialSearch().search("kz", list, emptyList()).isEmpty())
    }

    @Test fun recent_unknown_numbers_are_listed() {
        val hits = DialSearch().search("0655", emptyList(), listOf(call("0655443322", CallType.INCOMING)))
        assertEquals("0655443322", hits.single().number)
        assertNull(hits.single().contact)
    }

    @Test fun service_codes_have_no_results() {
        assertTrue(DialSearch().search("*#06#", entries(john), emptyList()).isEmpty())
    }

    // ---- A7: Call with nothing typed recalls the last number you called ----

    @Test fun empty_call_press_recalls_last_outgoing_number() {
        val calls = listOf(
            call("0611111111", CallType.INCOMING, 3000),
            call("0622222222", CallType.OUTGOING, 2000),
            call("0633333333", CallType.OUTGOING, 1000),
        )
        assertEquals("0622222222", DialSearch.lastOutgoing(calls))
        assertNull(DialSearch.lastOutgoing(listOf(call("0611111111", CallType.MISSED))))
        assertNull(DialSearch.lastOutgoing(null))
        assertNull(DialSearch.lastOutgoing(listOf(call("", CallType.OUTGOING))))
    }
}
