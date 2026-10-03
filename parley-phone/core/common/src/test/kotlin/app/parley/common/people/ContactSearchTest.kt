package app.parley.common.people

import app.parley.common.people.ContactSearch.Field
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchTest {
    /** A contact with every field, built from address-book rows as the index reads them. */
    private val ana = ContactSearch.Builder(1, region = "PT").apply {
        row(Mime.NAME) {
            mapOf(Col.D1 to "Ana Lima", Col.D2 to "Ana", Col.D3 to "Lima", Col.D4 to "Dr", Col.D5 to "Sofia", Col.D7 to "Ah-na", Col.D9 to "Lee-ma")[it]
        }
        row(Mime.NAME_PARTS) { mapOf(Col.D1 to "Gonçalves", Col.D2 to "Jr.")[it] }
        row(Mime.NICKNAME) { mapOf(Col.D1 to "Nana")[it] }
        row(Mime.PHONE) { mapOf(Col.D1 to "+351 912 345 678")[it] }
        row(Mime.EMAIL) { mapOf(Col.D1 to "ana@example.org")[it] }
        row(Mime.POSTAL) {
            mapOf(
                Col.D4 to "Rua Augusta 12", Col.D5 to "PO 7", Col.D6 to "Baixa", Col.D7 to "Lisboa", Col.D8 to "Lisbon District",
                Col.D9 to "1100-053", Col.D10 to "PT", AddressParts.COLUMN to "floor=3\nbuilding=Tower B",
            )[it]
        }
        row(Mime.ORG) { mapOf(Col.D1 to "Acme", Col.D4 to "Engineer", Col.D5 to "Research", Col.D6 to "Builds rockets", Col.D9 to "Room 42")[it] }
        row(Mime.WEBSITE) { mapOf(Col.D1 to "https://ana.dev")[it] }
        row(Mime.WEBSITE) { mapOf(Col.D1 to "https://instagram.com/ana.lima")[it] }
        row(Mime.IM) { mapOf(Col.D1 to "@ana:matrix.org", Col.D6 to "Matrix")[it] }
        row(Mime.SIP) { mapOf(Col.D1 to "ana@sip.example")[it] }
        row(Mime.RELATION) { mapOf(Col.D1 to "Bruno", Col.D2 to "13")[it] } // TYPE_SISTER = 13, "Sister"
        row(Mime.EVENT) { mapOf(Col.D1 to "1990-05-14", Col.D2 to "3")[it] }
        row(Mime.NOTE) { mapOf(Col.D1 to "Met at the café in Porto")[it] }
        row(Mime.CUSTOM_FIELD) { mapOf(Col.D1 to "Shoe size", Col.D2 to "38")[it] }
        row(Mime.PRONOUNS) { mapOf(Col.D1 to "she/her")[it] }
        row(Mime.LANGUAGE) { mapOf(Col.D1 to "pt-BR")[it] }
        label("Climbing club")
        account("Google · ana@gmail.com")
    }.build()

    private fun m(q: String) = ContactSearch.match(q, ana)

    @Test fun every_field_is_searched_and_named() {
        assertEquals(Field.NAME, m("lima"))
        assertEquals(Field.NAME, m("sofia"))
        assertEquals(Field.NAME, m("goncalves"))
        assertEquals(Field.PHONETIC, m("lee-ma"))
        assertEquals(Field.NICKNAME, m("nana"))
        assertEquals(Field.NUMBER, m("912 345"))
        assertEquals(Field.EMAIL, m("example.org"))
        assertEquals(Field.ADDRESS, m("augusta"))
        assertEquals(Field.ADDRESS, m("baixa"))
        assertEquals(Field.ADDRESS, m("1100-053"))
        assertEquals(Field.ADDRESS, m("lisbon district"))
        assertEquals("RFC 9554 parts", Field.ADDRESS, m("tower"))
        assertEquals("the ISO code as stored", Field.ADDRESS, m("pt"))
        assertEquals(Field.COMPANY, m("acme"))
        assertEquals(Field.COMPANY, m("engineer"))
        assertEquals(Field.COMPANY, m("research"))
        assertEquals(Field.COMPANY, m("room 42"))
        assertEquals(Field.COMPANY, m("rockets"))
        assertEquals(Field.WEBSITE, m("ana.dev"))
        assertEquals(Field.PROFILE, m("@ana.lima"))
        assertEquals(Field.PROFILE, m("instagram"))
        assertEquals(Field.HANDLE, m("matrix.org"))
        assertEquals(Field.HANDLE, m("sip.example"))
        assertEquals(Field.RELATION, m("bruno"))
        assertEquals(Field.RELATION, m("sister"))
        assertEquals(Field.DATE, m("1990"))
        assertEquals(Field.DATE, m("may"))
        assertEquals(Field.DATE, m("14 may"))
        assertEquals(Field.DATE, m("birthday"))
        assertEquals(Field.NOTE, m("porto"))
        assertEquals(Field.CUSTOM, m("shoe size"))
        assertEquals(Field.CUSTOM, m("38"))
        assertEquals(Field.PRONOUNS, m("she/her"))
        assertEquals(Field.LANGUAGE, m("portuguese"))
        assertEquals(Field.LANGUAGE, m("pt-br"))
        assertEquals(Field.LABEL, m("climbing"))
        assertEquals(Field.ACCOUNT, m("gmail.com"))
        assertNull(m("zebra"))
    }

    @Test fun accents_case_and_letters_that_do_not_decompose() {
        assertEquals(Field.NOTE, m("CAFE"))
        assertEquals(Field.NAME, m("GONÇALVES"))
        val doc = ContactSearch.Builder(2).apply {
            name("Søren Straße")
            address(null, null, null, "Łódź", null, null, "Poland")
        }.build()
        assertEquals(Field.NAME, ContactSearch.match("soren strasse", doc))
        assertEquals(Field.ADDRESS, ContactSearch.match("lodz", doc))
    }

    @Test fun every_word_must_be_found_somewhere() {
        assertEquals(Field.ADDRESS, m("ana lisboa"))
        assertEquals(Field.COMPANY, m("lima acme"))
        // The hint names the field earliest in Field's order among those the words needed.
        assertEquals(Field.COMPANY, m("porto acme"))
        assertNull("one word found nowhere", m("ana madrid"))
        assertEquals(Field.NAME, m("ana lima"))
        assertEquals("a number word with a name word", Field.NUMBER, m("ana 912"))
        assertEquals("a number word beside an explained one", Field.ADDRESS, m("augusta 912"))
    }

    @Test fun a_name_word_with_digits_needs_both() {
        // The digits match Ana's number, but "zed" is in none of her fields: no match.
        assertNull(m("zed 912"))
        assertNull(m("jo 12"))
        assertNull("the digits must be in a number too", m("ana 777"))
        assertEquals(Field.NUMBER, m("ana 912"))
        assertEquals(Field.NUMBER, m("912 ana"))
        assertEquals("no letters: the whole query is a number", Field.NUMBER, m("+351 912 345"))
        val rui = ContactSearch.Builder(9, region = "PT").apply { name("Rui"); number("+351 21 000 0000") }.build()
        assertEquals(Field.NUMBER, ContactSearch.match("rui 21", rui))
        assertNull(ContactSearch.match("ana 21", rui))
    }

    @Test fun months_and_countries_in_the_phone_s_language() {
        val pt = ContactSearch.Builder(10, languages = listOf(java.util.Locale.ENGLISH, java.util.Locale.forLanguageTag("pt"))).apply {
            event("1990-05-14", ContactSearch.TYPE_BIRTHDAY, null)
        }.build()
        assertEquals(Field.DATE, ContactSearch.match("maio", pt))
        assertEquals(Field.DATE, ContactSearch.match("14 maio", pt))
        assertEquals("English always", Field.DATE, ContactSearch.match("may", pt))
        val de = ContactSearch.Builder(11, languages = listOf(java.util.Locale.ENGLISH, java.util.Locale.GERMAN)).apply {
            event("1990-05-14", ContactSearch.TYPE_BIRTHDAY, null)
            address(null, null, null, "Berlin", null, null, "Deutschland")
        }.build()
        assertEquals(Field.DATE, ContactSearch.match("mai", de))
        assertEquals("the country by its English name", Field.ADDRESS, ContactSearch.match("germany", de))
        assertEquals(setOf("germany"), de.facets.values[Facet.COUNTRY]?.keys)
    }

    @Test fun numbers_match_in_any_written_form() {
        assertEquals(Field.NUMBER, m("+351912345678"))
        assertEquals(Field.NUMBER, m("00351 912 345 678"))
        assertEquals(Field.NUMBER, m("912345678"))
        assertEquals(Field.NUMBER, m("(912) 345-678"))
        val uk = ContactSearch.Builder(3, region = "GB").apply { number("07700 900123") }.build()
        assertEquals("national stored, international typed", Field.NUMBER, ContactSearch.match("+44 7700 900123", uk))
        assertEquals(Field.NUMBER, ContactSearch.match("447700900123", uk))
        val intl = ContactSearch.Builder(4, region = "GB").apply { number("+44 7700 900123") }.build()
        assertEquals("international stored, national typed", Field.NUMBER, ContactSearch.match("07700 900123", intl))
        assertNull(ContactSearch.match("0800", intl))
    }

    @Test fun the_shown_name_is_searched_as_the_name() {
        val bare = ContactSearch.Builder(5).apply { email("x@y.z") }.build()
        assertEquals(Field.NAME, ContactSearch.match("dora", bare, name = ContactSearch.fold("Dora")))
        assertEquals(Field.EMAIL, ContactSearch.match("dora x@y", bare, name = ContactSearch.fold("Dora")))
    }

    @Test fun blank_query_matches_everyone_and_hints_skip_name_and_number() {
        assertEquals(Field.NAME, m("  "))
        assertTrue(ContactSearch.explains(Field.ADDRESS))
        assertTrue(ContactSearch.explains(Field.DATE))
        assertFalse(ContactSearch.explains(Field.NAME))
        assertFalse(ContactSearch.explains(Field.NUMBER))
        assertFalse(ContactSearch.explains(null))
    }

    @Test fun a_row_with_only_its_formatted_address_is_searched() {
        val doc = ContactSearch.Builder(6).apply {
            row(Mime.POSTAL) { mapOf(Col.D1 to "5 Avenue Anatole France, Paris")[it] }
        }.build()
        assertEquals(Field.ADDRESS, ContactSearch.match("anatole", doc))
        assertTrue(doc.facets.has(ContactFacets.HAS_ADDRESS))
    }

    @Test fun number_forms() {
        val forms = ContactSearch.numberForms("+44 20 7946 0000", "GB")
        assertTrue(forms.containsAll(listOf("442079460000", "02079460000", "2079460000")))
        assertEquals(emptyList<String>(), ContactSearch.numberForms("", "GB"))
        assertEquals(listOf("123"), ContactSearch.numberForms("123", "GB"))
    }

    @Test fun private_details_are_searched_only_while_they_may_be_read() {
        val device = mapOf(1L to ana)
        val details = mapOf(-7L to ContactSearch.Builder(-7).apply { note("secret garden") }.build())
        assertTrue(SearchDocs.privateDetailsSearchable(vaultOpen = true, discreet = false, appLocked = false))
        assertFalse(SearchDocs.privateDetailsSearchable(vaultOpen = false, discreet = false, appLocked = false))
        assertFalse(SearchDocs.privateDetailsSearchable(vaultOpen = true, discreet = true, appLocked = false))
        assertFalse(SearchDocs.privateDetailsSearchable(vaultOpen = true, discreet = false, appLocked = true))

        val open = SearchDocs.combine(device, details, searchable = true, discreet = false)
        assertEquals(Field.NOTE, ContactSearch.match("garden", open.getValue(-7)))
        assertNull("locked: the details aren't searched", SearchDocs.combine(device, details, searchable = false, discreet = false)[-7])
        val discreet = SearchDocs.combine(device + (-8L to ana), details, searchable = true, discreet = true)
        assertEquals(setOf(1L), discreet.keys)
        // A device id is never replaced by a private doc.
        assertEquals(ana, SearchDocs.combine(device, mapOf(1L to details.getValue(-7)), searchable = true, discreet = false)[1])
    }
}
