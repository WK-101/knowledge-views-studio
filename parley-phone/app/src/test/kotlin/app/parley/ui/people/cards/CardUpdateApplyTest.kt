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
}
