package app.parley.data.people

import app.parley.common.people.ContactFacets
import app.parley.common.people.ContactSearch.Field
import app.parley.common.people.ContactSearch
import app.parley.common.people.Facet
import app.parley.common.people.HandleService
import app.parley.data.ContactDetails
import app.parley.data.CustomFieldItem
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.HandleItem
import app.parley.data.PostalItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A private contact's opened details are searched by every field, like an address-book contact's rows. */
class DetailsSearchTest {
    private val details = ContactDetails(
        displayName = "Ana Lima", given = "Ana", family = "Lima", secondSurname = "Gonçalves", phoneticGiven = "Ah-na",
        nickname = "Nana", pronouns = "she/her", language = "pt", company = "Acme", title = "Engineer", department = "Research",
        officeLocation = "Room 42", note = "Met in Porto", context = "Friend of Bruno's", pinnedNote = "Call after six",
        phones = listOf(DataItem(value = "+351 912 345 678")), emails = listOf(DataItem(value = "ana@example.org")),
        websites = listOf(DataItem(value = "https://ana.dev")), relations = listOf(DataItem(value = "Bruno", type = 13)),
        addresses = listOf(PostalItem(street = "Rua Augusta", city = "Lisboa", postcode = "1100-053", country = "PT", parts = "floor=3")),
        events = listOf(EventItem(date = "1990-05-14", type = 3)),
        handles = listOf(HandleItem(service = HandleService.SIGNAL, value = "ana.42")),
        customFields = listOf(CustomFieldItem(label = "Shoe size", value = "38")),
    )
    private val doc = DetailsSearch.doc(-7, details, listOf("Climbing"), "PT")

    @Test fun every_field_of_the_details_is_searched() {
        fun m(q: String) = ContactSearch.match(q, doc)
        assertEquals(Field.NAME, m("goncalves"))
        assertEquals(Field.PHONETIC, m("ah-na"))
        assertEquals(Field.NICKNAME, m("nana"))
        assertEquals(Field.NUMBER, m("912345678"))
        assertEquals(Field.EMAIL, m("example.org"))
        assertEquals(Field.ADDRESS, m("1100-053"))
        assertEquals(Field.COMPANY, m("research"))
        assertEquals(Field.COMPANY, m("room 42"))
        assertEquals(Field.WEBSITE, m("ana.dev"))
        assertEquals(Field.RELATION, m("sister"))
        assertEquals(Field.DATE, m("1990"))
        assertEquals(Field.NOTE, m("porto"))
        assertEquals(Field.NOTE, m("after six"))
        assertEquals(Field.HANDLE, m("signal"))
        assertEquals(Field.CUSTOM, m("shoe"))
        assertEquals(Field.PRONOUNS, m("she/her"))
        assertEquals(Field.LANGUAGE, m("portuguese"))
        assertEquals(Field.LABEL, m("climbing"))
        assertEquals(-7L, doc.id)
    }

    @Test fun its_facets_feed_the_filters() {
        assertEquals(setOf("Portugal"), doc.facets.values[Facet.COUNTRY]!!.values.toSet())
        assertTrue(doc.facets.has(ContactFacets.HAS_BIRTHDAY))
        assertTrue(doc.facets.named)
        val number = "+351 912 000 000"
        val numberOnly = DetailsSearch.doc(-8, ContactDetails(displayName = number, phones = listOf(DataItem(value = number))), emptyList(), "PT")
        assertFalse("a number shown as the name isn't a name", numberOnly.facets.named)
        assertTrue(DetailsSearch.doc(-9, ContactDetails(displayName = "Grandma"), emptyList(), "PT").facets.named)
    }
}
