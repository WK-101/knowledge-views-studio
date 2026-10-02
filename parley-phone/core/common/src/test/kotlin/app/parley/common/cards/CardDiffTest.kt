package app.parley.common.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardDiffTest {
    private val before = CardFields(name = "Ana Pérez", phones = listOf("+447700900123"), emails = listOf("ana@example.org"), company = "Acme")

    @Test fun a_new_number_replaces_the_old_one() {
        val after = before.copy(phones = listOf("+447700900456"))
        val contact = before.copy(phones = listOf("07700 900123", "+44 20 7946 0000"))
        val changes = CardDiff.changes(before, after, contact, "GB")
        assertEquals(listOf(CardChange(CardField.PHONE, "+447700900123", "+447700900456")), changes)
        assertEquals(listOf(CardField.PHONE), CardDiff.fields(changes))
    }

    @Test fun what_the_contact_already_has_is_not_offered() {
        val after = before.copy(phones = listOf("+447700900456"), emails = listOf("ANA@example.org"))
        val contact = before.copy(phones = listOf("+44 7700 900456"))
        assertTrue(CardDiff.changes(before, after, contact, "GB").isEmpty())
    }

    @Test fun numbers_the_user_added_are_never_removed() {
        val after = before.copy(phones = listOf("+447700900123", "+447700900999"))
        val contact = before.copy(phones = listOf("+447700900123", "+442079460000"))
        assertEquals(listOf(CardChange(CardField.PHONE, null, "+447700900999")), CardDiff.changes(before, after, contact, "GB"))
        // Dropped from the card, and the contact still has it: offered as a removal.
        val dropped = CardDiff.changes(before, before.copy(phones = emptyList()), before, "GB")
        assertEquals(listOf(CardChange(CardField.PHONE, "+447700900123", null)), dropped)
        assertTrue(dropped.single().removed)
    }

    @Test fun scalars_change_and_a_name_is_never_blanked() {
        val after = before.copy(name = "", company = "", title = "Boss", address = "2 Low St")
        val contact = before.copy(address = "1 High St")
        val changes = CardDiff.changes(before, after, contact)
        assertEquals(
            listOf(
                CardChange(CardField.COMPANY, "Acme", null),
                CardChange(CardField.TITLE, null, "Boss"),
                CardChange(CardField.ADDRESS, "1 High St", "2 Low St"),
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
                CardChange(CardField.NAME, "Mum", "Ana Pérez"),
                CardChange(CardField.EMAIL, null, "ana@example.org"),
                CardChange(CardField.COMPANY, null, "Acme"),
                CardChange(CardField.WEBSITE, null, "https://instagram.com/ana", "Instagram"),
            ),
            changes,
        )
    }
}
