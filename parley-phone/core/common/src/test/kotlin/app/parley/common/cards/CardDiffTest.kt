package app.parley.common.cards

import app.parley.common.people.MeCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDiffTest {
    private val before = CardFields(name = "Ana Pérez", phones = listOf("+447700900123"), emails = listOf("ana@example.org"), company = "Acme")

    @Test fun a_new_number_replaces_the_old_one() {
        val after = before.copy(phones = listOf("+447700900456"))
        val contact = before.copy(phones = listOf("07700 900123", "+44 20 7946 0000"))
        val changes = CardDiff.changes(before, after, contact, "GB")
        assertEquals(listOf(CardChange(CardField.PHONE, "+447700900123", "+447700900456", preselected = true)), changes)
        assertEquals(listOf(CardField.PHONE), CardDiff.fields(changes))
    }

    @Test fun what_the_contact_already_has_is_not_offered() {
        val after = before.copy(phones = listOf("+447700900456"), emails = listOf("ANA@example.org"))
        val contact = before.copy(phones = listOf("+44 7700 900456"))
        assertTrue(CardDiff.changes(before, after, contact, "GB").isEmpty())
    }

    @Test fun numbers_the_user_added_are_never_removed_and_removals_are_never_ticked() {
        val after = before.copy(phones = listOf("+447700900123", "+447700900999"))
        val contact = before.copy(phones = listOf("+447700900123", "+442079460000"))
        assertEquals(listOf(CardChange(CardField.PHONE, null, "+447700900999", preselected = true)), CardDiff.changes(before, after, contact, "GB"))
        // Dropped from the card, and the contact still has it: offered as a removal, unticked.
        val dropped = CardDiff.changes(before, before.copy(phones = emptyList()), before, "GB")
        assertEquals(listOf(CardChange(CardField.PHONE, "+447700900123", null, preselected = false)), dropped)
        assertTrue(dropped.single().removed)
    }

    @Test fun scalars_change_and_a_name_is_never_blanked() {
        val after = before.copy(name = "", company = "", title = "Boss", address = "2 Low St")
        val contact = before.copy(address = "1 High St")
        val changes = CardDiff.changes(before, after, contact)
        assertEquals(
            listOf(
                CardChange(CardField.COMPANY, "Acme", null, preselected = false),
                CardChange(CardField.TITLE, null, "Boss", preselected = true),
                // The card had no address before: Bo's own one stays, the card's is added beside it.
                CardChange(CardField.ADDRESS, null, "2 Low St", preselected = true),
            ),
            changes,
        )
        // An address written differently but the same is not a change.
        assertTrue(CardDiff.changes(null, CardFields(address = "1 High St, London"), CardFields(address = "1 high st london")).isEmpty())
    }

    @Test fun a_first_link_only_adds_what_is_missing() {
        val card = before.copy(websites = listOf("https://ana.example/"), profiles = listOf(CardProfile("Instagram", "https://instagram.com/ana")))
        val contact = CardFields(name = "Mum", phones = listOf("+447700900123"), websites = listOf("ana.example"))
        val changes = CardDiff.changes(null, card, contact, "GB")
        assertEquals(
            listOf(
                CardChange(CardField.NAME, "Mum", "Ana Pérez", preselected = false),
                CardChange(CardField.EMAIL, null, "ana@example.org"),
                CardChange(CardField.COMPANY, null, "Acme"),
                CardChange(CardField.WEBSITE, null, "https://instagram.com/ana", "Instagram"),
            ),
            changes,
        )
    }

    // ---- Addresses are matched by the card's previous value.

    @Test fun the_users_own_address_is_never_replaced() {
        val b = before.copy(address = "5 Work Rd")
        val a = b.copy(address = "7 New Rd")
        val contact = b.copy(address = "")
        // Bo's home first, then the work address that came from Ana's card.
        val changes = CardDiff.changes(b, a, contact, contactAddresses = listOf("1 Home Lane", "5 Work Rd"))
        assertEquals(listOf(CardChange(CardField.ADDRESS, "5 Work Rd", "7 New Rd", preselected = true)), changes)
        // Bo deleted the card's address: the new one is an add, his own stays.
        val add = CardDiff.changes(b, a, contact, contactAddresses = listOf("1 Home Lane"))
        assertEquals(listOf(CardChange(CardField.ADDRESS, null, "7 New Rd")), add)
        // Taken off the card: offered unticked, and only the card's own address.
        val off = CardDiff.changes(b, b.copy(address = ""), contact, contactAddresses = listOf("1 Home Lane", "5 Work Rd"))
        assertEquals(listOf(CardChange(CardField.ADDRESS, "5 Work Rd", null, preselected = false)), off)
    }

    @Test fun a_company_the_user_typed_is_offered_unticked() {
        val changes = CardDiff.changes(before, before.copy(company = "Acme Ltd"), before.copy(company = "My own words"))
        assertEquals(listOf(CardChange(CardField.COMPANY, "My own words", "Acme Ltd", preselected = false)), changes)
        // Still what the card said: ticked.
        assertTrue(CardDiff.changes(before, before.copy(company = "Acme Ltd"), before).single().preselected)
    }

    // ---- A part the new card doesn't share is unknown, not removed.

    @Test fun parts_left_out_of_a_share_are_not_removals() {
        val full = before.copy(websites = listOf("https://ana.example"), address = "1 High St")
        val narrow = full.copy(phones = listOf("+447700900456")).restrictedTo(setOf(MeCards.Part.NAME, MeCards.Part.PHONES))
        val changes = CardDiff.changes(full, narrow, full, "GB", afterParts = setOf(MeCards.Part.NAME, MeCards.Part.PHONES))
        assertEquals(listOf(CardChange(CardField.PHONE, "+447700900123", "+447700900456", preselected = true)), changes)
        assertFalse(changes.any { it.removed })
        // The earlier card didn't share emails: the new one's are compared as on a first link (only additions).
        val c = CardDiff.changes(
            before.restrictedTo(setOf(MeCards.Part.NAME)), before.copy(emails = listOf("new@example.org")), before, "GB",
            beforeParts = setOf(MeCards.Part.NAME),
        )
        assertEquals(listOf(CardChange(CardField.EMAIL, null, "new@example.org")), c)
    }
}
