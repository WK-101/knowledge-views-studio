package app.parley.ui.people.cards

import app.parley.common.cards.CardDiff
import app.parley.common.cards.CardField
import app.parley.common.cards.CardFields
import app.parley.common.cards.CardProfile
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.PostalItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** A reviewed card update edits only what it names, and keeps the contact's own rows (types, labels, extra numbers). */
class CardUpdateApplyTest {
    private val contact = ContactDetails(
        given = "Mum",
        phones = listOf(DataItem(value = "07700 900123", type = 2, label = null, isPrimary = true), DataItem(value = "+44 20 7946 0000", type = 1)),
        emails = listOf(DataItem(value = "ana@example.org", type = 1)),
        addresses = listOf(PostalItem(street = "1 High St", city = "London", type = 1)),
    )
    private val before = CardFields(name = "Ana Pérez", phones = listOf("+447700900123"), emails = listOf("ana@example.org"), address = "1 High St, London")

    @Test fun a_new_number_takes_the_old_ones_row() {
        val after = before.copy(
            phones = listOf("+447700900456"), address = "2 Low St, Leeds", profiles = listOf(CardProfile("Instagram", "https://instagram.com/ana")),
        )
        val changes = CardDiff.changes(before, after, CardUpdateApply.fieldsOf(contact), "GB")
        val out = CardUpdateApply.apply(contact, changes, "GB")
        assertEquals(listOf("+447700900456", "+44 20 7946 0000"), out.phones.map { it.value })
        // The replaced row keeps its type and default flag; the user's own landline stays.
        assertEquals(2, out.phones[0].type)
        assertEquals(true, out.phones[0].isPrimary)
        assertEquals("2 Low St, Leeds", out.addresses.single().street)
        assertEquals("Instagram", out.websites.single().label)
        // The name is the user's ("Mum") unless they tick it.
        assertEquals("Mum", out.given)
        val named = CardUpdateApply.apply(contact, changes.filter { it.field == CardField.NAME } +
            CardDiff.changes(null, after, CardUpdateApply.fieldsOf(contact), "GB").filter { it.field == CardField.NAME }, "GB")
        assertEquals("Ana" to "Pérez", named.given to named.family)
    }

    @Test fun removed_values_go_and_nothing_else_changes() {
        val after = before.copy(emails = emptyList())
        val changes = CardDiff.changes(before, after, CardUpdateApply.fieldsOf(contact), "GB")
        val out = CardUpdateApply.apply(contact, changes, "GB")
        assertEquals(emptyList<DataItem>(), out.emails)
        assertEquals(contact.copy(emails = emptyList()), out)
    }

    @Test fun the_users_own_address_is_never_overwritten() {
        // Bo typed the home address; the work one came from Ana's card.
        val bo = contact.copy(addresses = listOf(PostalItem(street = "9 Home Lane", type = 1), PostalItem(street = "1 High St", city = "London", type = 2)))
        val after = before.copy(address = "2 Low St, Leeds")
        val changes = CardDiff.changes(
            before, after, CardUpdateApply.fieldsOf(bo), "GB", contactAddresses = CardUpdateApply.addressesOf(bo),
        )
        val out = CardUpdateApply.apply(bo, changes.filter { it.preselected }, "GB")
        assertEquals(listOf("9 Home Lane", "2 Low St, Leeds"), out.addresses.map { it.street })
        assertEquals(listOf(1, 2), out.addresses.map { it.type })
        // Bo removed the card's address: the new one is added after his own, which stays.
        val own = contact.copy(addresses = listOf(PostalItem(street = "9 Home Lane", type = 1)))
        val add = CardDiff.changes(before, after, CardUpdateApply.fieldsOf(own), "GB", contactAddresses = CardUpdateApply.addressesOf(own))
        assertEquals(listOf("9 Home Lane", "2 Low St, Leeds"), CardUpdateApply.apply(own, add, "GB").addresses.map { it.street })
    }

    @Test fun removals_are_offered_but_never_ticked() {
        val changes = CardDiff.changes(before, before.copy(emails = emptyList()), CardUpdateApply.fieldsOf(contact), "GB")
        assertEquals(1, changes.size)
        assertEquals(false, changes.single().preselected)
        assertEquals(contact, CardUpdateApply.apply(contact, changes.filter { it.preselected }, "GB"))
    }
}
