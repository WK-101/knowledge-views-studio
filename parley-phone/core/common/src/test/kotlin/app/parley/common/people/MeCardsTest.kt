package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeCardsTest {
    @Test fun me_card_merges_parley_and_profile_and_makes_a_vcard() {
        val own = MeCards.fromMyDetails("Anna Maria Smith", "+44 7700 900123")
        val profile = MeCard(name = "A. Smith", phones = listOf("+447700900123", "+441234"), emails = listOf("Anna@X.org"), company = "Acme")
        val m = MeCards.merge(own, profile)
        assertEquals("Anna Maria Smith", m.name)
        assertEquals(listOf("+44 7700 900123", "+441234"), m.phones)
        assertEquals("Acme", m.company)
        val v = MeCards.vcard(m.copy(note = "secret"), setOf(MeCards.Part.NAME, MeCards.Part.PHONES))
        assertTrue(v.contains("N:Smith;Anna Maria;;;"))
        assertTrue(v.contains("TEL;TYPE=CELL:+44 7700 900123"))
        assertFalse("Only the chosen parts are shared", v.contains("Acme"))
        assertFalse("The private note is never shared", v.contains("secret"))
        assertEquals("a\\,b\\;c", MeCards.esc("a,b;c"))
        assertTrue(MeCard().isEmpty)
    }

    @Test fun the_editor_splits_and_joins_the_cards_name_and_address_without_loss() {
        assertEquals("Anna Maria" to "Smith", MeCards.splitName(" Anna  Maria Smith "))
        assertEquals("Cher" to "", MeCards.splitName("Cher"))
        assertEquals("" to "", MeCards.splitName(""))
        val (given, family) = MeCards.splitName("Anna Maria Smith")
        assertEquals("Anna Maria Smith", MeCards.joinName(given, "", family))
        assertEquals("Anna B Smith", MeCards.joinName("Anna", " B ", "Smith"))
        // One line typed before stays as it was; structured lines join with commas.
        assertEquals("1 High St, London", MeCards.joinAddress("1 High St, London", listOf("", " ")))
        assertEquals("1 High St, SW1A 1AA, London, UK", MeCards.joinAddress("1 High St", listOf("SW1A 1AA", "London", "", "UK")))
    }

    @Test fun shared_parts_round_trip_and_default_to_name_and_numbers() {
        assertEquals(MeCards.defaultParts, MeCards.decodeParts(null))
        val parts = setOf(MeCards.Part.EMAILS, MeCards.Part.NAME)
        assertEquals("NAME,EMAILS", MeCards.encodeParts(parts))
        assertEquals(parts, MeCards.decodeParts(MeCards.encodeParts(parts)))
        assertEquals(setOf(MeCards.Part.NAME), MeCards.decodeParts("NAME,SOMETHING_NEW"))
        // Nothing chosen is a choice too (not the default).
        assertEquals(emptySet<MeCards.Part>(), MeCards.decodeParts(""))
    }

    @Test fun profiles_are_shared_as_labelled_links_only_when_chosen() {
        val card = MeCard(
            name = "Ana Lima",
            profiles = listOf(
                Profile(ProfileService.INSTAGRAM, "ana.lima"), Profile(ProfileService.MASTODON, "ana@example.town"), Profile(ProfileService.GITHUB, " "),
            ),
        )
        assertFalse(card.isEmpty)
        assertEquals("Blank handles are dropped", 2, card.cleaned().profiles.size)
        val v = MeCards.vcard(card, setOf(MeCards.Part.NAME, MeCards.Part.PROFILES))
        assertTrue(v.contains("item1.URL:https://www.instagram.com/ana.lima/\r\nitem1.X-ABLabel:Instagram\r\n"))
        assertTrue(v.contains("item2.URL:https://example.town/@ana\r\nitem2.X-ABLabel:Mastodon\r\n"))
        assertFalse(MeCards.vcard(card, setOf(MeCards.Part.NAME)).contains("URL"))
        // A shared card reads back as the same profiles.
        val read = app.parley.common.vcard.VCardStream.readAll(v).first.single().raws.flatMap { it.rows }
            .filter { it.mimeType == Mime.WEBSITE }.map { it.values }
        assertEquals(card.cleaned().profiles, read.mapNotNull { SocialProfiles.fromWebsite(it[Col.D1]!!, it[Col.D2]?.toInt(), it[Col.D3]) })
        assertEquals(card.cleaned().profiles, MeCards.merge(card, MeCard(name = "x")).profiles)
    }

    @Test fun the_quick_edit_sets_the_name_and_first_number_and_keeps_the_rest() {
        val card = MeCard(name = "Ana", phones = listOf("+44 7700 900123", "+44 20 7946 0000"), emails = listOf("ana@x.org"), company = "Acme")
        val renamed = MeCards.withNameAndNumber(card, " Ana Lima ", "+447700900123")
        assertEquals("Ana Lima", renamed.name)
        assertEquals(listOf("+447700900123", "+44 20 7946 0000"), renamed.phones)
        assertEquals(listOf("ana@x.org"), renamed.emails)
        assertEquals("Acme", renamed.company)
        // A new number replaces the first; the second one, typed again, moves up.
        assertEquals(listOf("+33 6 12 34 56 78", "+44 20 7946 0000"), MeCards.withNameAndNumber(card, "Ana", "+33 6 12 34 56 78").phones)
        assertEquals(listOf("+442079460000", "+44 7700 900123"), MeCards.withNameAndNumber(card, "Ana", "+442079460000").phones)
        assertEquals(listOf("+44 20 7946 0000"), MeCards.withNameAndNumber(card, "Ana", " ").phones)
        assertEquals(listOf("+1 555"), MeCards.withNameAndNumber(MeCard(), "", "+1 555").phones)
    }

    @Test fun old_my_details_fold_into_my_card_without_losing_anything() {
        assertEquals(MeCard(name = "Ana", phones = listOf("+44 7700 900123")), MeCards.absorbMyDetails(MeCard(), " Ana ", " +44 7700 900123 "))
        val card = MeCard(name = "Ana", phones = listOf("+44 7700 900123"), emails = listOf("ana@x.org"))
        // Edited later in the old dialog: those values win; empty ones never clear the card.
        assertEquals(card.copy(name = "Ana Lima", phones = listOf("+33 6 12 34 56 78")), MeCards.absorbMyDetails(card, "Ana Lima", "+33 6 12 34 56 78"))
        assertEquals(card, MeCards.absorbMyDetails(card, "", ""))
        assertEquals(card, MeCards.absorbMyDetails(card, "Ana", "+447700900123").copy(phones = card.phones))
    }

    @Test fun send_my_details_sends_parleys_name_and_number_as_typed() {
        val profile = MeCard(name = "Ana Profile", phones = listOf("+44 20 7946 0000"))
        // Nothing set in Parley: the profile's.
        assertEquals("+44 20 7946 0000", MeCards.forSending(MeCard(), profile).firstNumber)
        // The number cleared in the quick edit stays out; the profile doesn't bring it back.
        val cleared = MeCards.withNameAndNumber(MeCard(name = "Ana", phones = listOf("+1 202 555 0100")), "Ana", "")
        val sent = MeCards.forSending(cleared, profile)
        assertEquals("Ana", sent.name)
        assertEquals(null, sent.firstNumber)
    }
}
