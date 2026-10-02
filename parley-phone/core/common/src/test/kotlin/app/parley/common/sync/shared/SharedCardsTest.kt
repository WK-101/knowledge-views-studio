package app.parley.common.sync.shared

import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.record.Col
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedCardsTest {
    private val photo = DataRow(Mime.PHOTO, emptyMap(), blob = byteArrayOf(1, 2, 3))
    private val family = DataRow(Mime.GROUP, mapOf(Col.D1 to "4", Col.GROUP_TITLE to "Family"))

    @Test fun a_card_keeps_only_the_shared_fields() {
        val card = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), note = "Surgery", extra = listOf(photo, family)))
        val kinds = card.raws.single().rows.map { it.mimeType }.toSet()
        assertEquals(setOf(Mime.NAME, Mime.PHONE, Mime.NOTE), kinds)
        assertNull(card.raws.single().accountType)
        assertEquals("", card.key)
        assertEquals("Dr Lee", card.displayName)
    }

    @Test fun encoding_round_trips_and_decoding_strips_other_kinds() {
        val card = SharedCards.project(person("Ana", listOf("+1 555 0100"), listOf("ana@example.com")))
        val back = SharedCards.decode(SharedCards.encode(card))
        assertNotNull(back)
        assertEquals(SharedCards.hash(card), SharedCards.hash(back!!))
        // A crafted card carrying a photo and a label loses both.
        val crafted = SharedCards.encode(card.copy(raws = listOf(card.raws.single().copy(rows = card.raws.single().rows + family))))
        assertTrue(SharedCards.decode(crafted)!!.raws.single().rows.none { it.mimeType == Mime.GROUP })
        assertNull(SharedCards.decode("not json"))
    }

    @Test fun the_hash_ignores_order_and_follows_content() {
        val a = SharedCards.project(person("Ana", listOf("+1 555 0100", "+1 555 0101")))
        val b = SharedCards.project(person("Ana", listOf("+1 555 0101", "+1 555 0100")))
        assertEquals(SharedCards.hash(a), SharedCards.hash(b))
        assertNotEquals(SharedCards.hash(a), SharedCards.hash(SharedCards.project(person("Ana", listOf("+1 555 0100")))))
    }

    @Test fun changed_fields_name_what_differs() {
        val before = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), note = "Surgery"))
        val after = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 1111"), note = "Surgery"))
        assertEquals(setOf(CardField.PHONES), SharedCards.changedFields(before, after))
        assertEquals(setOf(CardField.NAME, CardField.PHONES, CardField.NOTE), SharedCards.changedFields(null, before))
    }

    @Test fun edits_to_different_fields_merge_without_asking() {
        val base = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), note = "Surgery"))
        val mine = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), note = "Surgery, 2nd floor"))
        val theirs = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 1111"), note = "Surgery"))
        val m = SharedCards.merge(base, mine, theirs)
        assertTrue(m.conflicts.isEmpty())
        assertEquals("+44 20 7946 1111", SharedCards.text(m.card, CardField.PHONES))
        assertEquals("Surgery, 2nd floor", SharedCards.text(m.card, CardField.NOTE))
    }

    @Test fun the_same_field_changed_differently_is_a_conflict_decided_by_picks() {
        val base = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000")))
        val mine = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 2222")))
        val theirs = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 1111")))
        val m = SharedCards.merge(base, mine, theirs)
        assertEquals(setOf(CardField.PHONES), m.conflicts)
        assertEquals("+44 20 7946 2222", SharedCards.text(m.card, CardField.PHONES)) // waits with this phone's value
        val picked = SharedCards.merge(base, mine, theirs, mapOf(CardField.PHONES to Side.THEIRS))
        assertEquals("+44 20 7946 1111", SharedCards.text(picked.card, CardField.PHONES))
    }

    @Test fun without_a_base_a_field_one_side_lacks_is_taken_and_only_real_differences_ask() {
        val mine = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), note = "Mine"))
        val theirs = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 0000"), listOf("lee@example.com"), note = "Theirs"))
        val m = SharedCards.merge(null, mine, theirs)
        assertEquals(setOf(CardField.NOTE), m.conflicts)
        assertEquals("lee@example.com", SharedCards.text(m.card, CardField.EMAILS))
    }

    @Test fun overlay_keeps_what_the_label_does_not_carry() {
        val local = person("Dr Lee", listOf("+44 20 7946 0000"), extra = listOf(family, photo)).copy(starred = true, customRingtone = "content://tone")
        val shared = SharedCards.project(person("Dr Lee", listOf("+44 20 7946 1111")))
        val out = SharedCards.overlay(local, shared)
        val rows = out.raws.single().rows
        assertTrue(out.starred)
        assertEquals("content://tone", out.customRingtone)
        assertTrue(rows.any { it.mimeType == Mime.GROUP })
        assertTrue(rows.none { it.mimeType == Mime.PHOTO })
        assertEquals(listOf("+44 20 7946 1111"), rows.filter { it.mimeType == Mime.PHONE }.map { it[Col.D1] })
        assertEquals("com.google", out.raws.single().accountType)
    }

    @Test fun an_imported_card_carries_its_label_by_title() {
        val card = SharedCards.forImport(SharedCards.project(person("Ana")), "Family")
        assertEquals("Family", card.raws.single().rows.single { it.mimeType == Mime.GROUP }[Col.GROUP_TITLE])
    }

    @Test fun match_keys_are_numbers_and_emails() {
        val keys = SharedCards.matchKeys(SharedCards.project(person("Ana", listOf("+44 20 7946 0000"), listOf("Ana@Example.com"))))
        assertTrue("e:ana@example.com" in keys)
        assertTrue(keys.any { it.startsWith("p:") })
    }
}
