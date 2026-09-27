package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKeyMigrationTest {
    @Test fun old_keys_resolve_through_known_numbers() {
        val plan = PhoneKeyMigration.plan(
            stored = listOf("612345678", "+33699999999", "~d112", "555000111"),
            knownNumbers = listOf("06 12 34 56 78", "+33 6 12 34 56 78", "0699999999", null, ""),
            region = "FR",
        )
        // Only digit-only (old) keys move; keys already in the new form and unknown numbers stay.
        assertEquals(mapOf("612345678" to "+33612345678"), plan)
    }

    @Test fun ambiguous_old_keys_stay() {
        // Two countries sharing the last 9 digits: the old key could be either, so it isn't moved.
        val plan = PhoneKeyMigration.plan(listOf("612345678"), listOf("+33612345678", "+34612345678"), "FR")
        assertTrue(plan.isEmpty())
    }

    @Test fun short_numbers_move_to_their_digit_key() {
        val plan = PhoneKeyMigration.plan(listOf("3434"), listOf("3434"), "FR")
        assertEquals(mapOf("3434" to "~d3434"), plan)
        // After the move the row is still found by the number.
        assertTrue(PhoneIdentity.matchesStored("~d3434", "3434", "FR"))
    }

    @Test fun rekey_map_merges_into_existing_new_key() {
        val entries = mapOf("612345678" to 1, "+33612345678" to 5, "700000000" to 3)
        val out = PhoneKeyMigration.rekeyMap(entries, mapOf("612345678" to "+33612345678")) { a, b -> maxOf(a, b) }
        assertEquals(mapOf("+33612345678" to 5, "700000000" to 3), out)
    }

    @Test fun nothing_to_do_without_old_keys() {
        assertTrue(PhoneKeyMigration.plan(listOf("+33612345678"), listOf("0612345678"), "FR").isEmpty())
        val same = mapOf("a" to 1)
        assertEquals(same, PhoneKeyMigration.rekeyMap(same, emptyMap()))
    }

    @Test fun messaged_record_legacy_entries_move() {
        val old = MessagedRecord.fromLegacy("612345678", "org.example", "Chat", 10)!!
        val newer = MessagedEntry("+33612345678", "+33612345678", "org.example", "Chat", 20)
        val other = MessagedRecord.fromLegacy("700000000", null, "SMS", 5)!!
        val out = MessagedRecord.rekeyLegacy(listOf(other, old, newer), mapOf("612345678" to "+33612345678"))
        assertEquals(listOf(other, newer), out)
        // Moved when the line had no entry yet.
        val moved = MessagedRecord.rekeyLegacy(listOf(old), mapOf("612345678" to "+33612345678")).single()
        assertEquals("+33612345678", moved.key)
        assertEquals(moved, MessagedRecord.find(listOf(moved), "06 12 34 56 78", "FR"))
    }
}
