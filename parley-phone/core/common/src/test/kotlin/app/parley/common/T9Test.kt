package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {
    private fun m(q: String, name: String, vararg nums: String) = T9.match(q, T9.Encoded(name), nums.toList())

    @Test fun word_prefix() {
        assertEquals(listOf(0 until 4), m("5646", "John Smith")!!.nameRanges)
        assertNotNull(m("76484", "John Smith"))
    }

    @Test fun spanning_words() {
        val r = m("564676", "John Smith")!!
        assertEquals(2, r.nameRanges.size)
    }

    @Test fun initials() {
        assertNotNull(m("57", "John Smith"))
    }

    @Test fun accents_and_cyrillic() {
        assertNotNull(m("3645", "Émile"))
        assertNotNull(m("2", "Борис"))
        assertEquals("25", T9.encode("Бо"))
    }

    @Test fun number_match() {
        val r = m("0612", "Zed", "06 12 34 56 78")!!
        assertEquals("06 12 34 56 78", r.matchedNumber)
        assertNotNull(m("3456", "Zed", "06 12 34 56 78"))
    }

    @Test fun inner_substring() {
        assertNotNull(m("78663", "Caitlin Kingstone"))
    }

    @Test fun no_match() {
        assertNull(m("999", "John Smith"))
    }

    @Test fun ranking_prefers_first_name() {
        val a = m("5", "John Smith")!!.score
        val b = m("5", "Smith John")!!.score
        assertTrue(a > b)
    }
}
