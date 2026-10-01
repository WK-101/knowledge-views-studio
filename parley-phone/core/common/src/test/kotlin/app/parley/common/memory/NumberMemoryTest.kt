package app.parley.common.memory

import app.parley.common.memory.NumberMemory.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberMemoryTest {
    private val gb = "GB"
    private val day = 86_400_000L

    private fun hint(source: MemorySource, at: Long = 0, private: Boolean = false) = MemoryHint(source, name = source.name, at = at, private = private)

    @Test fun a_deleted_contact_wins_over_everything_else() {
        val hints = listOf(hint(MemorySource.CALLS, 9), hint(MemorySource.NOTE, 8), hint(MemorySource.DELETED_CONTACT, 1), hint(MemorySource.SNAPSHOT, 7))
        assertEquals(MemorySource.DELETED_CONTACT, NumberMemory.best(hints, Place.CALL, privateAllowed = false)?.source)
        assertEquals(
            listOf(MemorySource.DELETED_CONTACT, MemorySource.SNAPSHOT, MemorySource.NOTE, MemorySource.CALLS),
            NumberMemory.rank(hints, Place.KEYPAD, privateAllowed = false).map { it.source },
        )
    }

    @Test fun within_a_kind_the_newest_wins() {
        val hints = listOf(hint(MemorySource.NOTE, 1).copy(excerpt = "old"), hint(MemorySource.NOTE, 5).copy(excerpt = "new"))
        assertEquals("new", NumberMemory.best(hints, Place.CALL, false)?.excerpt)
    }

    @Test fun private_hints_only_while_the_vault_is_open() {
        val hints = listOf(hint(MemorySource.DELETED_PRIVATE, 5, private = true), hint(MemorySource.CALLS, 1))
        assertEquals(MemorySource.CALLS, NumberMemory.best(hints, Place.CALL, privateAllowed = false)?.source)
        assertEquals(MemorySource.DELETED_PRIVATE, NumberMemory.best(hints, Place.CALL, privateAllowed = true)?.source)
        assertNull(NumberMemory.best(listOf(hint(MemorySource.NOTE, private = true)), Place.KEYPAD, privateAllowed = false))
    }

    @Test fun the_number_history_doesnt_repeat_what_it_lists() {
        val hints = listOf(hint(MemorySource.CALLS, 9), hint(MemorySource.CALL_NOTE, 9), hint(MemorySource.MESSAGED, 9))
        assertNull(NumberMemory.best(hints, Place.HISTORY, true))
        assertEquals(MemorySource.CALL_NOTE, NumberMemory.best(hints, Place.KEYPAD, true)?.source)
    }

    @Test fun hints_survive_encoding() {
        val h = MemoryHint(MemorySource.NOTE, name = "Ana", excerpt = "Dr Lee's office", at = 42, ref = "key", private = true, relatedTo = "Sam")
        assertEquals(h, NumberMemory.decode(NumberMemory.encode(h)))
        assertNull(NumberMemory.decode("not json"))
        // No number is ever part of a hint.
        assertTrue("+44" !in NumberMemory.encode(h))
    }

    @Test fun deleted_contacts_give_one_hint_per_number_with_their_relation() {
        val d = NumberMemory.Deleted(7, "k1", "Plumber Mike", listOf("+447700900123", "+447700900124", "+447700900123", " "), 100)
        val out = NumberMemory.deleted(listOf(d)) { if (it == "k1") "Ana" else null }
        assertEquals(listOf("+447700900123", "+447700900124"), out.map { it.number })
        assertEquals("7", out.first().hint.ref)
        assertEquals("Ana", out.first().hint.relatedTo)
        assertEquals(MemorySource.DELETED_CONTACT, out.first().hint.source)
    }

    @Test fun snapshots_remember_numbers_the_address_book_lost() {
        val mike = NumberMemory.Person("Plumber Mike", listOf("07700 900123"))
        val ana = NumberMemory.Person("Ana", listOf("07700 900200", "07700 900201"))
        val anaNow = NumberMemory.Person("Ana", listOf("+447700900200"))
        val snaps = listOf(
            NumberMemory.SnapshotPeople(1 * day, mapOf("mike" to mike, "ana" to ana)),
            NumberMemory.SnapshotPeople(2 * day, mapOf("mike" to mike, "ana" to ana)),
            NumberMemory.SnapshotPeople(3 * day, mapOf("ana" to anaNow)),
        )
        val out = NumberMemory.snapshotHints(snaps, gb).associate { it.number to it.hint }
        // Mike was deleted after day 2; Ana's second number was removed; Ana's first number is still hers (any format).
        assertEquals(setOf("07700 900123", "07700 900201"), out.keys)
        assertEquals("Plumber Mike", out["07700 900123"]?.name)
        assertEquals(2 * day, out["07700 900123"]?.at)
        assertEquals((2 * day).toString(), out["07700 900123"]?.ref)
        assertEquals("Ana", out["07700 900201"]?.name)
        assertTrue(NumberMemory.snapshotHints(emptyList(), gb).isEmpty())
    }

    @Test fun past_calls_count_per_line_and_keep_the_newest_name() {
        val calls = listOf(
            NumberMemory.PastCall("07700 900123", 10, "Plumber Mike"),
            NumberMemory.PastCall("+447700900123", 30, null),
            NumberMemory.PastCall("+44 7700 900123", 20, "Mike (old)"),
            NumberMemory.PastCall("+447700900999", 5, null),
        )
        val out = NumberMemory.pastCalls(calls, gb)
        val counts = out.filter { it.hint.source == MemorySource.CALLS }.associate { it.number to it.hint }
        assertEquals(3, counts["+447700900123"]?.count)
        assertEquals(10L, counts["+447700900123"]?.since)
        assertEquals(30L, counts["+447700900123"]?.at)
        assertEquals(1, counts["+447700900999"]?.count)
        val names = out.filter { it.hint.source == MemorySource.ARCHIVE_NAME }
        assertEquals(listOf("Mike (old)"), names.map { it.hint.name })
    }

    @Test fun notes_mention_numbers_with_the_words_around_them() {
        val note = NumberMemory.Note("Likes jazz\n[ ] Dr Lee's office: 020 7946 0000\nBus 42", "ana-key", "Ana", 5, private = false)
        val out = NumberMemory.noteHints(listOf(note), gb)
        assertEquals(1, out.size)
        assertEquals("+442079460000", out.single().number)
        assertEquals("Dr Lee's office", out.single().hint.excerpt)
        assertEquals("Ana", out.single().hint.name)
        assertEquals("ana-key", out.single().hint.ref)
        // A bus number or a short code is not a phone number.
        assertTrue(NumberMemory.mentions("Bus 42, code 1234", gb).isEmpty())
    }

    @Test fun a_number_alone_on_its_line_takes_the_notes_other_line() {
        assertEquals("Gas engineer", NumberMemory.mentions("Gas engineer\n+44 20 7946 0000", gb).single().second)
        assertNull(NumberMemory.mentions("+44 20 7946 0000", gb).single().second)
    }

    @Test fun long_excerpts_are_clipped() {
        val text = "x".repeat(100) + " 020 7946 0000"
        val excerpt = NumberMemory.mentions(text, gb).single().second!!
        assertEquals(NumberMemory.EXCERPT_MAX, excerpt.length)
        assertTrue(excerpt.endsWith("…"))
    }

    @Test fun call_notes_use_their_first_line() {
        assertEquals("Gas meter", NumberMemory.callNote("+447700900123", "\n  Gas meter \nmore", 3)?.hint?.excerpt)
        assertNull(NumberMemory.callNote("+447700900123", "  \n ", 3))
    }
}
