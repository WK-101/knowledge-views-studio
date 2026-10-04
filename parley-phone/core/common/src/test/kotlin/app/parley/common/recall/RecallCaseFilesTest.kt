package app.parley.common.recall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Recall's "Case files" group: found by the organisation's name, a number or a reference's label, never its value. */
class RecallCaseFilesTest {
    private val today = LocalDate.of(2026, 10, 4)
    private fun at(m: Int, d: Int): Long = LocalDate.of(2026, m, d).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val corpus = RecallCorpus(
        cases = listOf(
            RecallCorpus.Case("c1", "Northwind Energy", listOf("+351 213 000 009"), listOf("Complaint"), at(9, 12)),
            RecallCorpus.Case("c2", "City Council", listOf("+351 213 000 010"), emptyList(), at(3, 2)),
        ),
        region = "PT",
    )
    private val engine = RecallEngine(corpus, ZoneOffset.UTC)

    private fun run(text: String): RecallResult {
        val q = RecallQuery.parse(text, today, java.util.Locale.UK)
        return RecallRanking.merge(q, engine.search(q, { null }), limit = 10, region = "PT")
    }

    private fun RecallResult.cases() = groups.firstOrNull { it.source == RecallSource.CASE_FILE }?.hits.orEmpty()

    @Test fun found_by_name_number_label_and_date() {
        assertEquals(listOf("c1"), run("northwind").cases().map { it.ref })
        assertEquals(listOf("c2"), run("213 000 010").cases().map { it.ref })
        assertEquals(listOf("c1"), run("complaint").cases().map { it.ref })
        assertEquals(listOf("c2"), run("council march").cases().map { it.ref })
        val hit = run("northwind").cases().single()
        assertEquals("Northwind Energy", hit.title)
        assertEquals("+351 213 000 009", hit.number)
        assertEquals(at(9, 12), hit.at)
    }

    @Test fun nothing_for_other_words() {
        assertNull(run("plumber").groups.firstOrNull { it.source == RecallSource.CASE_FILE })
    }
}
