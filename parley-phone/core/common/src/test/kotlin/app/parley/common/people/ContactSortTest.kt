package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.ux.ListSections
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactSortTest {
    private fun device(id: Long, name: String) = ContactSummary(id, "k$id", name, null, false, emptyList())
    private fun private(vaultId: Long, name: String) = PrivateListing.row(vaultId, name, listOf("+1555000$vaultId"), false, null)

    private val ada = device(10, "Ada")
    private val bob = device(30, "Bob")
    private val cy = device(20, "Cy")
    private val dee = private(1, "Dee")
    private val eve = private(2, "Eve")

    /** Name order, as the list arrives. */
    private val list = listOf(ada, bob, cy, dee, eve)
    private val order = Collation.Order()

    private fun names(rows: List<ListSections.Row<String, ContactSummary>>) = rows.map {
        when (it) {
            is ListSections.Row.Header -> "[${it.section}]"
            is ListSections.Row.Item -> it.item.displayName
        }
    }

    private fun rows(sort: ContactSort, facts: SortFacts = SortFacts()) = names(ContactSorting.rows(list, sort, facts, order, "No company", "Not called yet"))

    @Test fun by_name_keeps_the_list_and_its_letters() {
        assertEquals(listOf("[A]", "Ada", "[B]", "Bob", "[C]", "Cy", "[D]", "Dee", "[E]", "Eve"), rows(ContactSort.NAME))
    }

    @Test fun recently_added_puts_the_newest_first_private_contacts_by_when_they_were_saved() {
        // Bob (id 30) is the newest address-book contact, then Cy, then Ada. Dee was saved after Bob last changed, Eve long ago.
        val facts = SortFacts(addedAt = mapOf(bob.id to 3_000L, cy.id to 2_000L, ada.id to 1_000L, dee.id to 3_500L, eve.id to 500L))
        assertEquals(listOf("Dee", "Bob", "Cy", "Ada", "Eve"), rows(ContactSort.RECENTLY_ADDED, facts))
        // Without times, device contacts still go newest first by id, and private ones follow.
        assertEquals(listOf("Bob", "Cy", "Ada", "Dee", "Eve"), rows(ContactSort.RECENTLY_ADDED))
    }

    @Test fun most_called_first_then_the_rest_in_name_order() {
        val facts = SortFacts(calls = mapOf(cy.id to 2, eve.id to 7, ada.id to 2))
        // Ties keep name order (Ada before Cy); a private contact counts like anyone.
        assertEquals(listOf("Eve", "Ada", "Cy", "[Not called yet]", "Bob", "Dee"), rows(ContactSort.MOST_CALLED, facts))
        assertEquals(listOf("[Not called yet]", "Ada", "Bob", "Cy", "Dee", "Eve"), rows(ContactSort.MOST_CALLED))
    }

    @Test fun by_company_groups_however_it_is_written_and_puts_the_rest_last() {
        val facts = SortFacts(company = mapOf(bob.id to "Zeta Ltd", ada.id to "acme", dee.id to " Acme ", cy.id to "  "))
        assertEquals(listOf("[acme]", "Ada", "Dee", "[Zeta Ltd]", "Bob", "[No company]", "Cy", "Eve"), rows(ContactSort.COMPANY, facts))
    }

    @Test fun every_contact_is_listed_once_in_every_order() {
        val facts = SortFacts(addedAt = mapOf(dee.id to 9L), calls = mapOf(bob.id to 1), company = mapOf(eve.id to "X"))
        ContactSort.entries.forEach { s ->
            val items = ContactSorting.rows(list, s, facts, order, "-", "-").filterIsInstance<ListSections.Row.Item<ContactSummary>>().map { it.item }
            assertEquals(s.name, list.toSet(), items.toSet())
            assertEquals(s.name, list.size, items.size)
        }
    }
}
