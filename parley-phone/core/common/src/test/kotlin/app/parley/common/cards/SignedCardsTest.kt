package app.parley.common.cards

import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.common.spam.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedCardsTest {
    private val secret = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it * 7 + 1).toByte() }
    private val ana = MeCard(
        name = "Ana  María Pérez", phones = listOf("+44 7700 900123", "+44 20 7946 0000"), emails = listOf("ana@example.org"),
        company = "Smith; Sons, Ltd", title = "Head\\of things", websites = listOf("https://ana.example"),
        address = "1 High St\nLondon, SW1A 1AA", note = "never shared",
        profiles = listOf(Profile(ProfileService.entries.first(), "ana")),
    )

    private fun shared(card: MeCard = ana, version: Long = 1, key: ByteArray = secret, parts: Set<MeCards.Part> = MeCards.Part.entries.toSet()): String {
        val signed = SignedCards.sign(CardFields.of(card, parts), "card-1", version, key)
        return SignedCards.attach(MeCards.vcard(card, parts), signed)
    }

    private fun only(text: String): CardCheck = SignedCards.check(text).single()

    @Test fun a_shared_card_verifies_with_every_field() {
        val c = only(shared()) as CardCheck.Signed
        assertEquals("card-1", c.card.cardId)
        assertEquals(1L, c.card.version)
        assertEquals(CardFields.of(ana, MeCards.Part.entries.toSet()), c.card.fields)
        assertEquals("Ana María Pérez", c.card.fields.name)
        assertEquals("Smith; Sons, Ltd", c.card.fields.company)
        assertEquals("1 High St\nLondon, SW1A 1AA", c.card.fields.address)
        assertEquals(1, c.card.fields.profiles.size)
        assertTrue(c.card.fields.websites == listOf("https://ana.example"))
        assertFalse("the private note is never shared", c.card.fields.toString().contains("never shared"))
        assertEquals(Ed25519.fingerprint(Ed25519.publicKey(secret)), c.card.fingerprint)
    }

    @Test fun only_the_chosen_parts_are_signed() {
        val parts = setOf(MeCards.Part.NAME, MeCards.Part.PHONES)
        val c = only(shared(parts = parts)) as CardCheck.Signed
        assertEquals(listOf("+44 7700 900123", "+44 20 7946 0000"), c.card.fields.phones)
        assertTrue(c.card.fields.emails.isEmpty() && c.card.fields.company.isEmpty() && c.card.fields.address.isEmpty())
        // A card without a name (work only) keeps the company out of the name.
        val work = only(shared(parts = setOf(MeCards.Part.WORK))) as CardCheck.Signed
        assertEquals("", work.card.fields.name)
        assertEquals("Smith; Sons, Ltd", work.card.fields.company)
    }

    @Test fun tampering_breaks_the_signature() {
        val text = shared()
        assertTrue(only(text.replace("900123", "900999")) is CardCheck.Broken)
        assertTrue(only(text.replace("X-PARLEY-CARD:1;card-1;1;", "X-PARLEY-CARD:1;card-1;2;")) is CardCheck.Broken)
        assertTrue(only(text.replace("END:VCARD", "TEL:+1 555 0100\r\nEND:VCARD")) is CardCheck.Broken)
        // Another card id with the same signature.
        assertEquals("card-2", (only(text.replace("card-1", "card-2")) as CardCheck.Broken).cardId)
        // No signature at all, or a damaged property.
        assertTrue(only(text.lines().filterNot { it.startsWith("X-PARLEY-SIG") }.joinToString("\n")) is CardCheck.Broken)
        assertTrue(only(text.replace("X-PARLEY-CARD:1;", "X-PARLEY-CARD:9;")) is CardCheck.Broken)
    }

    @Test fun folded_lines_and_line_endings_still_verify() {
        val text = shared().replace("\r\n", "\n")
        val sigLine = text.lines().first { it.startsWith("X-PARLEY-SIG:") }
        val folded = text.replace(sigLine, sigLine.substring(0, 30) + "\n " + sigLine.substring(30))
        assertTrue(only(folded) is CardCheck.Signed)
    }

    @Test fun plain_vcards_and_other_text_are_not_signed_cards() {
        assertTrue(SignedCards.check(MeCards.vcard(ana)).isEmpty())
        assertTrue(SignedCards.check("hello").isEmpty())
        assertFalse(SignedCards.mayHold(MeCards.vcard(ana)))
    }

    @Test fun several_cards_in_one_file() {
        val a = shared()
        val bo = SignedCards.sign(CardFields(name = "Bo", phones = listOf("+1 555 0100")), "card-b", 3, other)
        val b = SignedCards.attach(MeCards.vcard(MeCard(name = "Bo", phones = listOf("+1 555 0100"))), bo)
        val checks = SignedCards.check(a + MeCards.vcard(MeCard(name = "Plain")) + b)
        assertEquals(2, checks.size)
        assertEquals(listOf("card-1", "card-b"), checks.map { (it as CardCheck.Signed).card.cardId })
    }

    @Test fun versions_are_ordered_and_keys_must_match() {
        val v1 = (only(shared(version = 1)) as CardCheck.Signed).card
        val v2 = (only(shared(ana.copy(phones = listOf("+44 7700 900456")), version = 2)) as CardCheck.Signed).card
        val forged = (only(shared(ana.copy(phones = listOf("+44 7700 900666")), version = 5, key = other)) as CardCheck.Signed).card
        val link = CardLinks.first(v1, 0)
        assertEquals(CardArrival.UPDATE, CardLinks.arrival(link, v2))
        assertEquals(CardArrival.SAME, CardLinks.arrival(link, v1))
        assertEquals(CardArrival.DIFFERENT_SIGNER, CardLinks.arrival(link, forged))
        assertEquals(CardArrival.NEW, CardLinks.arrival(null, v1))
        val waiting = CardLinks.receive(link, v2, 10)
        assertNotNull(waiting.pending)
        assertEquals(CardArrival.SAME, CardLinks.arrival(waiting, v2))
        // A different signer never becomes the pending update.
        assertEquals(waiting, CardLinks.receive(waiting, forged, 11))
        val settled = CardLinks.settle(waiting)
        assertEquals(2L, settled.version)
        assertEquals(listOf("+44 7700 900456"), settled.fields.phones)
        assertEquals(CardArrival.OLDER, CardLinks.arrival(settled, v1))
    }
}

class CardLinkBookTest {
    private val secret = ByteArray(32) { 3 }
    private fun card(version: Long, phone: String) = SignedCards.sign(CardFields(name = "Ana", phones = listOf(phone)), "id-a", version, secret)

    @Test fun held_cards_link_to_the_contact_saved_from_them() {
        val now = 1_000_000L
        var b = CardLinkBook().hold(card(1, "+447700900123"), now)
        // An older copy never replaces a newer held one.
        assertEquals(b, b.hold(card(1, "+447700900123"), now + 1))
        assertEquals(null, b.linkHeld("k", now) { "+15550100" in it.phones })
        b = b.linkHeld("k", now) { "+447700900123" in it.phones }!!
        assertEquals("id-a", b.links["k"]?.cardId)
        assertTrue(b.held.isEmpty())
        // Held too long: gone.
        val old = CardLinkBook().hold(card(1, "+447700900123"), 0)
        assertEquals(null, old.linkHeld("k", CardLinkBook.HOLD_MS + 1) { true })
        assertEquals(b, CardLinkBook.decode(CardLinkBook.encode(b)))
    }

    @Test fun rekeying_keeps_the_newer_link() {
        val v1 = CardLinks.first(card(1, "+1"), 0)
        val v2 = CardLinks.first(card(2, "+2"), 0)
        val b = CardLinkBook(mapOf("old" to v2, "new" to v1)).rekey("old", "new")
        assertEquals(mapOf("new" to v2), b.links)
        assertEquals(b, b.rekey("missing", "x"))
        assertTrue(b.forget("new").links.isEmpty())
    }
}
