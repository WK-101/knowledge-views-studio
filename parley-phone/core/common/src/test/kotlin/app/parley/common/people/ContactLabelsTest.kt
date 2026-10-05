package app.parley.common.people

import app.parley.common.extras.TripMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The labels row under a contact's name, what its "Add to label" offers, and who the city scope looks at. */
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

    private val groups = listOf(
        ContactLabels.Group(1, "Family", "com.google/me@example.com"),
        ContactLabels.Group(2, "Family", "local/"),
        ContactLabels.Group(3, "Work", "com.google/me@example.com"),
        ContactLabels.Group(4, "Choir", "local/"),
        ContactLabels.Group(5, "Neighbours", "com.exchange/work@example.com"),
    )

    @Test fun a_device_contact_is_offered_labels_of_the_accounts_it_has_a_copy_in_and_not_the_ones_it_is_in() {
        val offered = ContactLabels.offered(groups, current = setOf("Work"), accounts = setOf("com.google/me@example.com"), private = false, order = order)
        assertEquals(listOf(1L), offered.map { it.id })
        // A copy in two accounts: each account's labels, one per title.
        val both = ContactLabels.offered(groups, emptySet(), setOf("com.google/me@example.com", "local/"), private = false, order = order)
        assertEquals(listOf("Choir", "Family", "Work"), both.map { it.title })
    }

    @Test fun a_contact_only_in_an_account_without_labels_is_offered_none() {
        assertTrue(ContactLabels.offered(groups, emptySet(), setOf("com.whatsapp/"), private = false, order = order).isEmpty())
    }

    @Test fun a_private_contact_joins_any_label_once_per_title() {
        val offered = ContactLabels.offered(groups, current = setOf("Choir"), accounts = null, private = true, order = order)
        assertEquals(listOf("Family", "Neighbours", "Work"), offered.map { it.title })
        assertEquals(1L, offered.first { it.title == "Family" }.id)
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
