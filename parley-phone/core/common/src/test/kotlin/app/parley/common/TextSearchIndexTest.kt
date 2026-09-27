package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchIndexTest {
    private data class Person(val name: String, val numbers: List<String> = emptyList())

    private val people = listOf(
        Person("José Álvarez", listOf("+33 6 12 34 56 78")),
        Person("Caitlin Kingstone"),
        Person("Bob", listOf("020 7946 0018")),
        Person("Zoë"),
    )
    private val index = TextSearchIndex(people, { it.name }, { it.numbers })

    @Test fun finds_by_folded_name_words() {
        assertEquals(listOf("José Álvarez"), index.search("alv jo").map { it.name })
        assertEquals(listOf("Caitlin Kingstone"), index.search("STONE").map { it.name })
        assertEquals(listOf("Zoë"), index.search("zoe").map { it.name })
    }

    @Test fun finds_by_digits() {
        assertEquals(listOf("José Álvarez"), index.search("1234").map { it.name })
        assertEquals(listOf("Bob"), index.search("7946").map { it.name })
    }

    @Test fun blank_query_returns_everyone_in_order() {
        assertEquals(people, index.search("  "))
    }

    @Test fun no_match_is_empty() {
        assertTrue(index.search("zzz").isEmpty())
    }

    /** The index answers exactly like [TextSearch.matches] for names and numbers. */
    @Test fun agrees_with_text_search() {
        val queries = listOf("jo", "José", "12 34", "1", "b", "kings tone", "é", "+33", "0 2", "ob 79")
        for (q in queries) {
            val expected = people.filter { TextSearch.matches(q, it.name, it.numbers) }
            assertEquals("query '$q'", expected, index.search(q))
        }
    }

    @Test fun prepared_query_matches_extra_fields() {
        val q = TextSearch.Query("Élise@")
        assertTrue(q.matchesPrepared(TextSearch.normalize("Bob"), emptyList(), listOf("elise@example.org")))
        assertFalse(q.matchesPrepared(TextSearch.normalize("Bob"), emptyList(), listOf("ann@example.org")))
    }

    @Test fun single_digit_is_not_a_number_search() {
        // One digit is too short to search numbers (as before): "Bob" has a 1 in his number but isn't found.
        assertFalse(TextSearch.matches("1", "Bob", listOf("111")))
        assertTrue(index.search("1").isEmpty())
    }
}
