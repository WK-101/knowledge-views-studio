package app.parley.ui.contact

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import app.parley.common.people.MapLinks
import app.parley.common.people.PasteParser
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Paste details" fills the editor: typed values are never replaced, empty rows are used first, nothing doubles. */
class PasteFillTest {
    private val signature = """
        Jane Doe
        Head of Partnerships | Acme Widgets Ltd
        M: +44 7911 123456 | T: +44 20 7946 0958
        jane.doe@acmewidgets.co.uk | www.acmewidgets.co.uk
        Instagram @jane.doe
        12 High Street, London SW1A 1AA
        https://www.openstreetmap.org/?mlat=51.5&mlon=-0.12
        Birthday: 12 March 1990
    """.trimIndent()

    private fun fields() = PasteParser.parse(signature, "GB").single().fields

    @Test
    fun the_details_become_contact_rows() {
        val d = PasteFill.details(fields())
        assertEquals("Jane", d.given)
        assertEquals("Doe", d.family)
        assertEquals("Acme Widgets Ltd", d.company)
        assertEquals("Head of Partnerships", d.title)
        assertEquals(listOf(Phone.TYPE_MOBILE, Phone.TYPE_WORK), d.phones.map { it.type })
        assertEquals(Email.TYPE_WORK, d.emails.single().type)
        val a = d.addresses.single()
        assertEquals(StructuredPostal.TYPE_WORK, a.type)
        assertEquals("London", a.city)
        // The profile is a labelled website row; the map link is the address's "Map (Work)" row.
        assertTrue(d.websites.any { it.type == SocialProfiles.TYPE_CUSTOM && it.label == "Instagram" })
        assertTrue(d.websites.any { it.label == MapLinks.label("Work") && it.value.contains("openstreetmap") })
        assertEquals(0, AddressMapLinks.matches(d).keys.single())
        assertEquals(Event.TYPE_BIRTHDAY, d.events.single().type)
        assertEquals("1990-03-12", d.events.single().date)
    }

    @Test
    fun only_the_ticked_fields_are_used() {
        val picked = fields().filter { it.kind == PasteParser.Kind.NAME || it.kind == PasteParser.Kind.EMAIL }
        val d = PasteFill.details(picked)
        assertEquals("Jane", d.given)
        assertTrue(d.phones.isEmpty())
        assertTrue(d.addresses.isEmpty())
        assertEquals("", d.company)
    }

    @Test
    fun filling_a_new_contact_keeps_what_was_typed() {
        val typed = ContactDetails(
            given = "Janie",
            phones = listOf(DataItem(value = "", type = Phone.TYPE_MOBILE)),
            emails = listOf(DataItem(value = "jane.doe@acmewidgets.co.uk", type = Email.TYPE_HOME)),
        )
        val d = PasteFill.into(typed, fields())
        assertEquals("Janie", d.given)
        assertEquals("", d.family)
        // The empty phone row is used first, then the second number is added.
        assertEquals(listOf("+447911123456", "+442079460958"), d.phones.map { it.value })
        // The email it already had isn't added again, and keeps its type.
        assertEquals(1, d.emails.size)
        assertEquals(Email.TYPE_HOME, d.emails.single().type)
        assertEquals("Acme Widgets Ltd", d.company)
    }

    @Test
    fun a_second_paste_adds_nothing_twice() {
        val once = PasteFill.into(ContactDetails(), fields())
        val twice = PasteFill.into(once, fields())
        assertEquals(once.phones, twice.phones)
        assertEquals(once.websites, twice.websites)
        assertEquals(once.addresses, twice.addresses)
        assertEquals(once.events, twice.events)
    }

    @Test
    fun a_map_link_without_an_address_makes_one_from_its_place() {
        val f = PasteParser.parse("Hut 7\ngeo:46.5763,7.9904?q=46.5763,7.9904(Hut)\nhut@example.ch", "CH").single().fields
        val d = PasteFill.details(f)
        assertEquals(1, d.addresses.size)
        assertEquals(0, AddressMapLinks.matches(d).keys.single())
    }

    @Test fun add_to_an_existing_contact_skips_what_it_already_has() {
        // L9: pasting Ana's signature into "Add to Ana" doesn't double her website, address or birthday.
        val ana = PasteFill.into(ContactDetails(), fields())
        val draft = PasteFill.into(ContactDetails(phones = listOf(DataItem(value = "", type = Phone.TYPE_MOBILE))), fields())
            .copy(phones = listOf(DataItem(value = "", type = Phone.TYPE_MOBILE)) + PasteFill.into(ContactDetails(), fields()).phones)
        val out = app.parley.InsertPrefill.appendTo(ana, draft)
        assertEquals(ana.phones.map { it.value }, out.phones.map { it.value })
        assertEquals(ana.websites.size, out.websites.size)
        assertEquals(ana.addresses.size, out.addresses.size)
        assertEquals(1, out.events.count { it.type == Event.TYPE_BIRTHDAY })
        assertTrue(out.phones.none { it.value.isBlank() })
        // Something new still goes in.
        val more = app.parley.InsertPrefill.appendTo(ana, ContactDetails(emails = listOf(DataItem(value = "new@example.org", type = Email.TYPE_WORK))))
        assertEquals(ana.emails.size + 1, more.emails.size)
    }
}
