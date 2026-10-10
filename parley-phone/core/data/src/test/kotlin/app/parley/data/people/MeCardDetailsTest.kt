package app.parley.data.people

import app.parley.common.people.MeCard
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.common.people.SocialProfiles
import app.parley.data.ContactDetails
import app.parley.common.people.MeCards
import app.parley.data.DataItem
import app.parley.data.EventItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** My card through the contact editor and back: no website row is ever lost. */
class MeCardDetailsTest {
    @Test fun a_labelled_link_that_is_not_a_profile_stays_a_website() {
        val article = "https://www.linkedin.com/pulse/how-we-work-ana-lima"
        val links = "https://linktr.ee/analima"
        val d = ContactDetails(
            given = "Ana",
            websites = listOf(
                DataItem(value = "https://www.linkedin.com/in/ana-lima-123", type = SocialProfiles.TYPE_CUSTOM, label = "LinkedIn"),
                DataItem(value = article, type = SocialProfiles.TYPE_CUSTOM, label = "LinkedIn"),
                DataItem(value = links, type = SocialProfiles.TYPE_CUSTOM, label = "Instagram"),
                DataItem(value = "https://ana.example", type = 1),
            ),
        )
        val card = MeCardDetails.toCard(d)
        assertEquals(listOf(Profile(ProfileService.LINKEDIN, "ana-lima-123")), card.profiles)
        assertEquals(listOf(article, links, "https://ana.example"), card.websites)
        // And again: saving twice changes nothing.
        assertEquals(card, MeCardDetails.toCard(MeCardDetails.toDetails(card)))
    }

    @Test fun a_card_round_trips() {
        val card = MeCard(name = "Ana Lima", websites = listOf("https://ana.example"), profiles = listOf(Profile(ProfileService.GITHUB, "analima")))
        assertEquals(card, MeCardDetails.toCard(MeCardDetails.toDetails(card)))
    }

    private val full = ContactDetails(
        given = "Ana", family = "Lima", nickname = "Annie", pronouns = "she/her",
        phones = listOf(DataItem(value = "+447700900123", type = 2)), emails = listOf(DataItem(value = "ana@example.org", type = 1)),
        company = "Acme", title = "Engineer",
        relations = listOf(DataItem(value = "Sam", type = 14)), note = "Door code 1234",
        events = listOf(EventItem(date = "1990-05-01", type = 3)), languages = listOf("pt"),
    )

    @Test fun a_share_holds_only_the_ticked_parts_and_never_the_note_or_relations_by_default() {
        val d = MeCardDetails.restrict(full, MeCards.defaultParts)
        assertEquals("Ana", d.given)
        assertEquals(listOf("+447700900123"), d.phones.map { it.value })
        assertEquals(listOf("ana@example.org"), d.emails.map { it.value })
        assertEquals("", d.note)
        assertEquals(emptyList<DataItem>(), d.relations)
        assertEquals("", d.nickname)
        assertEquals("", d.company)
        assertEquals(emptyList<EventItem>(), d.events)
        // The defaults are signable, so the QR code and file stay signed cards.
        assertTrue(MeCards.isSignable(MeCards.defaultParts))
        // Ticked, they go along.
        val more = MeCardDetails.restrict(full, MeCards.defaultParts + MeCards.Part.NOTE + MeCards.Part.RELATIONS + MeCards.Part.NAME_DETAILS)
        assertEquals("Door code 1234", more.note)
        assertEquals("Sam", more.relations.single().value)
        assertEquals("Annie", more.nickname)
    }

    @Test fun a_rich_share_is_a_full_vcard_with_only_what_was_ticked() {
        val plain = MeCardDetails.vcard(full, MeCards.defaultParts + MeCards.Part.DATES)
        assertTrue(plain, plain.contains("BDAY"))
        assertFalse(plain, plain.contains("Door code"))
        assertFalse(plain, plain.contains("Sam"))
        assertFalse(MeCards.isSignable(MeCards.defaultParts + MeCards.Part.DATES))
        val withNote = MeCardDetails.vcard(full, MeCards.defaultParts + MeCards.Part.NOTE + MeCards.Part.RELATIONS)
        assertTrue(withNote, withNote.contains("Door code 1234"))
        assertTrue(withNote, withNote.contains("Sam"))
    }

    @Test fun send_my_details_changes_only_the_name_and_first_number() {
        val d = full.copy(phones = listOf(DataItem(value = "+447700900123", type = 2), DataItem(value = "+441234567", type = 3, label = null)))
        val next = MeCardDetails.withNameAndNumber(d, "Ana Lima", "+44 1234 567")
        // The number already on the card moves up and keeps its type; the rest stays.
        assertEquals(listOf("+44 1234 567", "+447700900123"), next.phones.map { it.value })
        assertEquals(3, next.phones.first().type)
        assertEquals("Annie", next.nickname)
        assertEquals(d.relations, next.relations)
        val renamed = MeCardDetails.withNameAndNumber(d, "Ana Maria Souza", "")
        assertEquals("Ana Maria" to "Souza", renamed.given to renamed.family)
        assertEquals(listOf("+441234567"), renamed.phones.map { it.value })
    }
}
