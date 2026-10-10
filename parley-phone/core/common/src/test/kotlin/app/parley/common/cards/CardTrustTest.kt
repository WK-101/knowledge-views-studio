package app.parley.common.cards

import app.parley.common.people.MeCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H1: trust on first explicit link, pinned key after. Each way the review found to take over a contact's updates is
 * tried here and must change nothing the user didn't choose.
 */
class CardTrustTest {
    private val anaKey = ByteArray(32) { 3 }
    private val malloryKey = ByteArray(32) { 9 }
    private val now = 1_000_000L

    private fun card(id: String, version: Long, phone: String, key: ByteArray = anaKey, name: String = "Ana") =
        SignedCards.sign(CardFields(name = name, phones = listOf(phone)), id, version, key)

    /** Bo's book: his contact "ana-key" is linked to Ana's real card. */
    private val linked = CardLinkBook().link("ana-key", card("ana-card", 1, "+447700900123"), now)

    @Test fun a_fresh_card_id_with_anas_number_never_replaces_her_link() {
        val mallory = card("fresh-id", 1, "+447700900123", malloryKey)
        val out = CardIntake.receive(linked, mallory, numberMatch = "ana-key", now = now) { true }
        assertEquals(CardIntake.Kind.DIFFERENT_SIGNER, out.kind)
        assertEquals("ana-key", out.key)
        assertEquals(linked.links, out.book.links)
        // Her next "version" isn't an update for Ana either.
        val next = card("fresh-id", 2, "+15550666", malloryKey)
        val again = CardIntake.receive(out.book, next, numberMatch = null, now = now + 1) { true }
        assertEquals(CardIntake.Kind.HELD, again.kind)
        assertEquals(linked.links, again.book.links)
    }

    @Test fun anas_card_id_signed_by_another_key_is_only_a_warning() {
        val forged = card("ana-card", 9, "+15550666", malloryKey)
        val out = CardIntake.receive(linked, forged, numberMatch = "ana-key", now = now) { true }
        assertEquals(CardIntake.Kind.DIFFERENT_SIGNER, out.kind)
        assertEquals(linked.links, out.book.links)
        assertNull(out.book.links.getValue("ana-key").pending)
    }

    @Test fun a_first_card_is_never_linked_by_itself() {
        val first = card("ana-card", 1, "+447700900123")
        val out = CardIntake.receive(CardLinkBook(), first, numberMatch = "ana-key", now = now) { true }
        assertEquals(CardIntake.Kind.OFFER, out.kind)
        assertTrue(out.book.links.isEmpty())
        // The user says "Link": now it is, and later versions from the same key wait as updates.
        val chosen = out.book.linkHeld("ana-key", first.cardId, first.publicKey, now)!!
        assertEquals("ana-card", chosen.links["ana-key"]?.cardId)
        assertTrue(chosen.held.isEmpty())
        val update = CardIntake.receive(chosen, card("ana-card", 2, "+447700900456"), numberMatch = null, now = now) { true }
        assertEquals(CardIntake.Kind.UPDATE, update.kind)
        assertNotNull(update.book.links["ana-key"]?.pending)
    }

    @Test fun a_held_card_is_never_replaced_by_another_key() {
        val real = card("ana-card", 1, "+447700900123")
        val forged = card("ana-card", 5, "+15550666", malloryKey)
        val b = CardLinkBook().hold(real, now).hold(forged, now + 1)
        // Both are kept apart: the forged one didn't push Ana's out.
        assertEquals(2, b.held.size)
        assertTrue(b.held.any { it.publicKey == real.publicKey && it.version == 1L })
        // A newer version from the same key does replace its own.
        val newer = b.hold(card("ana-card", 2, "+447700900123"), now + 2)
        assertEquals(2, newer.held.size)
        assertEquals(2L, newer.held.first { it.publicKey == real.publicKey }.version)
        // An older copy never replaces a newer held one.
        assertEquals(newer, newer.hold(real, now + 3))
    }

    @Test fun a_held_card_is_linked_only_by_the_users_choice() {
        val stranger = card("switchboard", 1, "+442079460000", malloryKey, name = "Front desk")
        val b = CardLinkBook().hold(stranger, now)
        // Opening a colleague's page that shares the switchboard number only asks; nothing is linked.
        val offer = CardLinks.offer(null, b.held, now) { "+442079460000" in it.phones }
        assertEquals(HeldOffer.Kind.LINK, offer?.kind)
        assertTrue(b.links.isEmpty())
        // "Don't link" drops it; a key that isn't held links nothing.
        assertTrue(b.dropHeld(stranger.cardId, stranger.publicKey).held.isEmpty())
        assertNull(b.linkHeld("colleague", stranger.cardId, "another-key", now))
        // Held too long: gone.
        assertNull(b.linkHeld("colleague", stranger.cardId, stranger.publicKey, now + CardLinkBook.HOLD_MS + 1))
        assertNull(CardLinks.offer(null, b.held, now + CardLinkBook.HOLD_MS + 1) { true })
    }

    @Test fun a_linked_contact_gets_the_different_signer_warning_on_its_page() {
        val forged = card("fresh-id", 1, "+447700900123", malloryKey)
        val b = linked.hold(forged, now)
        val link = b.links.getValue("ana-key")
        val offer = CardLinks.offer(link, b.held, now) { "+447700900123" in it.phones }
        assertEquals(HeldOffer.Kind.DIFFERENT_SIGNER, offer?.kind)
        // "Trust the new card" is the only way to change the link, and it is explicit (a real key change).
        val trusted = b.linkHeld("ana-key", forged.cardId, forged.publicKey, now)!!
        assertEquals(forged.publicKey, trusted.links["ana-key"]?.publicKey)
        // The linked card's own newer versions are updates, never held offers.
        val own = linked.hold(card("ana-card", 3, "+447700900123"), now)
        assertNull(CardLinks.offer(own.links.getValue("ana-key"), own.held, now) { true })
    }

    @Test fun updates_never_relink_and_a_gone_contact_only_holds() {
        // The link's own update keeps its key and card.
        val upd = linked.put("ana-key", CardLinks.receive(linked.links.getValue("ana-key"), card("ana-card", 2, "+447700900456"), now))
        assertEquals(linked.links.getValue("ana-key").publicKey, upd.links.getValue("ana-key").publicKey)
        val gone = CardIntake.receive(linked, card("ana-card", 2, "+447700900456"), numberMatch = null, now = now) { false }
        assertEquals(CardIntake.Kind.HELD, gone.kind)
        assertTrue(gone.book.links.isEmpty())
    }

    @Test fun a_wider_share_of_the_same_version_is_an_update_and_parts_merge() {
        val parts = setOf(MeCards.Part.NAME, MeCards.Part.PHONES)
        val all = CardFields(name = "Ana", phones = listOf("+447700900123"), emails = listOf("ana@example.org"))
        val narrow = SignedCards.sign(all, "ana-card", 5, anaKey, parts)
        val wide = SignedCards.sign(all, "ana-card", 5, anaKey)
        val link = CardLinks.first(narrow, now)
        assertEquals(CardArrival.UPDATE, CardLinks.arrival(link, wide))
        assertEquals(CardArrival.SAME, CardLinks.arrival(CardLinks.first(wide, now), narrow))
        // A later narrow share keeps the emails seen before.
        val later = SignedCards.sign(all.copy(phones = listOf("+447700900456")), "ana-card", 6, anaKey, parts)
        val settled = CardLinks.settle(CardLinks.receive(CardLinks.first(wide, now), later, now))
        assertEquals(listOf("ana@example.org"), settled.fields.emails)
        assertEquals(listOf("+447700900456"), settled.fields.phones)
        assertEquals(CardFields.ALL_PARTS, settled.parts)
    }

    @Test fun rekeying_keeps_the_newer_link_and_the_book_round_trips() {
        val v1 = CardLinks.first(card("id-a", 1, "+1"), 0)
        val v2 = CardLinks.first(card("id-a", 2, "+2"), 0)
        val b = CardLinkBook(mapOf("old" to v2, "new" to v1)).rekey("old", "new")
        assertEquals(mapOf("new" to v2), b.links)
        assertEquals(b, b.rekey("missing", "x"))
        assertTrue(b.forget("new").links.isEmpty())
        val withHeld = b.hold(card("id-b", 1, "+3"), now)
        assertEquals(withHeld, CardLinkBook.decode(CardLinkBook.encode(withHeld)))
    }
}
