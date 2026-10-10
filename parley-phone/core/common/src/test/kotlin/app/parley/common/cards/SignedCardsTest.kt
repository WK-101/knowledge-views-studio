package app.parley.common.cards

import app.parley.common.people.MeCard
import app.parley.common.people.MeCards
import app.parley.common.people.Profile
import app.parley.common.people.ProfileService
import app.parley.common.record.Col
import app.parley.common.record.Mime
import app.parley.common.spam.Ed25519
import app.parley.common.vcard.VCardStream
import app.parley.common.vcard.rows
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

    private fun shared(card: MeCard = ana, version: Long = 1, key: ByteArray = secret, parts: Set<MeCards.Part> = MeCards.signable): String {
        val signed = SignedCards.sign(CardFields.of(card, parts), "card-1", version, key, parts)
        return SignedCards.attach(MeCards.vcard(card, parts), signed)
    }

    private fun only(text: String): CardCheck = SignedCards.check(text).single()

    @Test fun a_shared_card_verifies_with_every_field() {
        val c = only(shared()) as CardCheck.Signed
        assertEquals("card-1", c.card.cardId)
        assertEquals(1L, c.card.version)
        assertEquals(CardFields.of(ana, MeCards.signable), c.card.fields)
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
        assertTrue(only(text.replace("X-PARLEY-CARD:2;card-1;1;", "X-PARLEY-CARD:2;card-1;2;")) is CardCheck.Broken)
        assertTrue(only(text.replace("END:VCARD", "TEL:+1 555 0100\r\nEND:VCARD")) is CardCheck.Broken)
        // Another card id with the same signature.
        assertEquals("card-2", (only(text.replace("card-1", "card-2")) as CardCheck.Broken).cardId)
        // No signature at all, or a damaged property.
        assertTrue(only(text.lines().filterNot { it.startsWith("X-PARLEY-SIG") }.joinToString("\n")) is CardCheck.Broken)
        assertTrue(only(text.replace("X-PARLEY-CARD:2;", "X-PARLEY-CARD:9;")) is CardCheck.Broken)
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

    // ---- M1: what shows as signed is what an importer saves.

    private fun broken(text: String) = assertTrue(text, only(text) is CardCheck.Broken)

    private fun withLine(line: String, before: String = "END:VCARD") = shared().replace(before, "$line\r\n$before")

    @Test fun a_name_put_before_the_signed_one_is_broken() {
        val text = shared()
        broken(text.replace("VERSION:3.0\r\n", "VERSION:3.0\r\nN:Evil;Mallory;;;\r\n"))
        broken(text.replace("VERSION:3.0\r\n", "VERSION:3.0\r\nFN:Mallory Evil\r\n"))
        // A second ORG, ADR or TITLE (an importer saves each).
        broken(withLine("ORG:Evil Corp"))
        broken(withLine("ADR:;;9 Evil Rd;;;;"))
        broken(withLine("TITLE:Boss"))
    }

    @Test fun extra_numbers_and_rows_are_broken() {
        broken(withLine("TEL:+1 555 0100"))
        broken(withLine("X-ANDROID-CUSTOM:vnd.android.cursor.item/phone_v2;+15550100;2;;;;;;;;;;;;;"))
        broken(withLine("NOTE:call me on +1 555 0100"))
        broken(withLine("IMPP:xmpp:mallory@example.org"))
        broken(withLine("X-SOCIALPROFILE;TYPE=twitter:https://x.com/mallory"))
        broken(withLine("item9.TEL:+1 555 0100"))
        broken(withLine("item1.X-ABLabel:Evil"))
        broken(withLine("this line has no colon"))
    }

    @Test fun parts_of_names_and_addresses_parley_never_writes_are_broken() {
        val text = shared()
        val n = text.lines().first { it.startsWith("N:") }
        broken(text.replace(n, n.removeSuffix("\r").replace(";;;", ";Evil;;")))
        val adr = text.lines().first { it.startsWith("ADR:") }
        broken(text.replace(adr, adr.removeSuffix("\r").replace(";;;;", ";Evil City;;;")))
        // Parameters that change how a value is read, and an unescaped separator an importer would split at.
        broken(text.replace("TEL;TYPE=CELL:", "TEL;ENCODING=QUOTED-PRINTABLE:"))
        broken(text.replace("ORG:Smith\\; Sons\\, Ltd", "ORG:Smith; Sons\\, Ltd"))
        broken(text.replace("VERSION:3.0", "VERSION:2.1"))
        // The display name says something else than the signed name.
        broken(text.replace(Regex("FN:[^\r\n]*"), "FN:Mallory"))
    }

    @Test fun padding_past_the_line_cap_is_broken_not_cut_off() {
        val padding = (1..450).joinToString("\r\n") { "URL:https://x$it.example" }
        broken(withLine("$padding\r\nTEL:+1 555 0100"))
        // One long value is not cut either.
        broken(withLine("URL:https://" + "a".repeat(2_100)))
        // A card inside the card.
        broken(withLine("BEGIN:VCARD\r\nTEL:+1 555 0100"))
    }

    @Test fun a_folded_honest_card_still_verifies() {
        val text = shared()
        val adr = text.lines().first { it.startsWith("ADR:") }.removeSuffix("\r")
        val folded = text.replace(adr, adr.substring(0, 10) + "\r\n " + adr.substring(10))
        assertTrue(only(folded) is CardCheck.Signed)
        // Lower-case property names and LF line ends, as some apps pass them on.
        assertTrue(only(text.replace("\r\n", "\n").replace("EMAIL:", "email:")) is CardCheck.Signed)
    }

    @Test fun the_importer_saves_what_the_signature_covers() {
        val text = shared()
        val signed = (only(text) as CardCheck.Signed).card.fields
        val r = VCardStream.readAll(text).first.single()
        // The display name may keep the owner's own spacing; the words are the signed ones.
        assertEquals(signed.name, r.displayName.split(Regex("\\s+")).joinToString(" "))
        assertEquals(signed.phones, r.rows(Mime.PHONE).map { it[Col.D1] })
        assertEquals(signed.emails, r.rows(Mime.EMAIL).map { it[Col.D1] })
        assertEquals(1, r.rows(Mime.ORG).size)
        assertEquals(1, r.rows(Mime.POSTAL).size)
    }

    @Test fun files_tell_how_many_cards_they_hold() {
        val text = shared() + MeCards.vcard(MeCard(name = "Look-alike", phones = listOf("+44 7700 900123")))
        assertEquals(1, SignedCards.check(text).size)
        assertEquals(2, SignedCards.count(text))
    }

    // ---- M3: the parts a share includes are signed.

    @Test fun the_shared_parts_are_signed() {
        val parts = setOf(MeCards.Part.NAME, MeCards.Part.PHONES)
        val text = shared(parts = parts)
        val c = only(text) as CardCheck.Signed
        assertEquals(parts, c.card.parts)
        // Claiming more (or other) parts than were signed breaks it.
        broken(text.replace(";NAME.PHONES", ";NAME.PHONES.EMAILS"))
        broken(text.replace(";NAME.PHONES", ";NAME.PHONES.SECRETS"))
        // A field of a part it doesn't share.
        broken(text.replace("END:VCARD", "EMAIL:mallory@example.org\r\nEND:VCARD"))
    }

    // ---- L4, M6.

    @Test fun profile_label_and_link_cannot_be_shifted() {
        val a = CardFields(profiles = listOf(CardProfile("a\tb", "c")))
        val b = CardFields(profiles = listOf(CardProfile("a", "b\tc")))
        assertFalse(SignedCards.payload("id", 1, "k", a).contentEquals(SignedCards.payload("id", 1, "k", b)))
    }

    @Test fun versions_never_repeat() {
        val now = 1_800_000_000_000L
        assertEquals(1_800_000_000L, SignedCards.nextVersion(7, now))
        // Ahead of the clock already (a phone with a later clock): still one more.
        assertEquals(1_900_000_001L, SignedCards.nextVersion(1_900_000_000L, now))
        // A backup's version from before later shares on the old phone: the next one is still newer than those.
        val oldPhoneShared = SignedCards.nextVersion(SignedCards.nextVersion(7, now - 60_000), now - 30_000)
        assertTrue(SignedCards.nextVersion(7, now) > oldPhoneShared)
    }
}
