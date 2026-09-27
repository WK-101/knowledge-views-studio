package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportsTest {
    @Test fun crash_reports_and_raw_dumps_are_masked() {
        val c = Reports.Crash(0, "main", "java.lang.IllegalStateException: bad number +44 7700 900123 for anna@x.org", "3.1", "15")
        val text = Reports.crashText(c)
        assertFalse(text.contains("7700 900123"))
        assertFalse(text.contains("anna@"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(Reports.crashText(c, mask = false).contains("+44 7700 900123"))
        assertEquals("Aaaa +99 9900", Reports.shape("Anna +44 7700"))
        assertEquals("null", Reports.shape(null))
        assertTrue(Reports.shape("x".repeat(100), 10).startsWith("aaaaaaaaaa…"))
        assertEquals(3, Reports.trimStack((1..10).joinToString("\n"), 2).lines().size)
    }

    @Test fun contacts_copy_as_plain_text() {
        val t = Reports.contactsAsText(
            listOf(
                Reports.TextContact("Anna", listOf("+44 7700" to "Mobile", "123" to null), listOf("a@x.org")),
                Reports.TextContact("Ben", emptyList(), emptyList()),
            ),
        )
        assertEquals("Anna\nMobile: +44 7700\n123\na@x.org\n\nBen", t)
    }
}
