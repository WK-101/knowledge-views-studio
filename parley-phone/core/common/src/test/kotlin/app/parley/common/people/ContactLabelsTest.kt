package app.parley.common.people

import app.parley.common.extras.TripMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The labels row under a contact's name, and who the city scope looks at. */
class ContactLabelsTest {
    private val order = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }

    @Test fun a_contacts_labels_show_once_each_in_name_order_with_shared_ones_marked() {
        val chips = ContactLabels.chips(listOf("work", "Family ", "Family", " ", "Book club"), shared = setOf("Book club"), order = order)
        assertEquals(listOf("Book club", "Family", "work"), chips.map { it.title })
        assertEquals(listOf(true, false, false), chips.map { it.shared })
    }

    @Test fun no_labels_is_an_empty_row() {
        assertTrue(ContactLabels.chips(emptyList(), emptySet(), order).isEmpty())
    }

    @Test fun the_city_scope_includes_private_contacts_only_while_they_are_shown() {
        val device = listOf(TripMatch.Person(1, "Bo", places = listOf("Porto")))
        val private = listOf(TripMatch.Person(ContactRef.Private(7).navId, "Ana", places = listOf("Lisbon")))
        val shown = TripMatch.people(device, private, privateShown = true)
        assertEquals(listOf(-7L), TripMatch.match("Lisbon", shown).map { it.person.id })
        // Hidden (Hide private contacts, or a duress unlock forcing it): as if there were none.
        assertTrue(TripMatch.match("Lisbon", TripMatch.people(device, private, privateShown = false)).isEmpty())
        assertEquals(device, TripMatch.people(device, private, privateShown = false))
    }
}
