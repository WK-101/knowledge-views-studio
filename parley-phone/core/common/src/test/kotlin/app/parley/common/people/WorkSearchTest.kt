package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkSearchTest {
    private val base = WorkSearch.ENTERPRISE_ID_BASE

    @Test fun onlyEnterpriseIdsAreWorkContacts() {
        assertTrue(WorkSearch.isWorkId(base))
        assertTrue(WorkSearch.isWorkId(base + 42))
        assertFalse(WorkSearch.isWorkId(42))
        assertFalse(WorkSearch.isWorkId(-3))
    }

    @Test fun queriesAreTrimmedAndBounded() {
        assertNull(WorkSearch.queryOf("   "))
        assertEquals("ana", WorkSearch.queryOf("  ana "))
        assertEquals(WorkSearch.MAX_QUERY, WorkSearch.queryOf("x".repeat(500))!!.length)
    }

    @Test fun nameMatchesComeFirstAndBorrowTheirNumber() {
        val ana = WorkContact(base + 1, "c-1", "Ana")
        val bo = WorkContact(base + 2, "c-2", "Bo")
        val anaPhone = ana.copy(number = "+1 555 0100", numberLabel = "Work")
        val cy = WorkContact(base + 3, "c-3", "Cy", number = "+1 555 0101")
        val merged = WorkSearch.merge(listOf(ana, bo), listOf(cy, anaPhone))
        assertEquals(listOf(base + 1, base + 2, base + 3), merged.map { it.id })
        assertEquals("+1 555 0100", merged[0].number)
        assertEquals("Work", merged[0].numberLabel)
        assertNull(merged[1].number)
    }

    @Test fun personalAndNamelessRowsAreLeftOutAndTheListIsCapped() {
        val personal = WorkContact(7, "p", "Personal")
        val nameless = WorkContact(base + 9, "c-9", " ")
        val many = (10L..60L).map { WorkContact(base + it, "c-$it", "W$it") }
        val merged = WorkSearch.merge(listOf(personal, nameless) + many, emptyList())
        assertEquals(WorkSearch.LIMIT, merged.size)
        assertTrue(merged.all { WorkSearch.isWorkId(it.id) && it.name.isNotBlank() })
    }
}
