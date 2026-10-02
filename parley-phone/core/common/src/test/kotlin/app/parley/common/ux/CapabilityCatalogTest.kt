package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCatalogTest {
    private val rows = CapabilityCatalog.rows

    @Test fun every_row_leads_somewhere() {
        // Settings rows must name a real setting; screens are mapped exhaustively in the app (and opened in its graph test).
        assertEquals(emptyList<String>(), CapabilityCatalog.unknownSettings())
        assertEquals("keys are unique", rows.size, rows.map { it.key }.toSet().size)
        rows.forEach { assertTrue("${it.key} has a stable id", Regex("[a-z0-9_]{1,40}").matches(it.key)) }
    }

    @Test fun every_job_has_rows_and_every_row_is_one_line() {
        Job.entries.forEach { j -> assertTrue("$j has at least three rows", CapabilityCatalog.forJob(j).size >= 3) }
        rows.forEach { r ->
            assertTrue("${r.key} title is short", r.title.length <= 48)
            assertTrue("${r.key} summary is one line", r.summary.length <= 90 && '\n' !in r.summary)
        }
        assertEquals(rows.size, Job.entries.sumOf { CapabilityCatalog.forJob(it).size })
    }

    @Test fun search_matches_word_starts_in_any_field() {
        fun keys(q: String) = CapabilitySearch.search(q, rows).map { it.key }
        assertTrue("message_number" in keys("whatsapp"))
        // Accents and case don't matter; every word has to match.
        assertEquals(listOf("coming_from"), keys("IPHONE"))
        assertTrue("history_undo" in keys("Undo"))
        assertEquals(listOf("snapshots"), keys("daily snap"))
        // The job's name finds its rows.
        assertTrue(keys("spam").containsAll(CapabilityCatalog.forJob(Job.STOP_SPAM).map { it.key }.take(2)))
        // A blank query keeps everything; nonsense finds nothing.
        assertEquals(rows.size, CapabilitySearch.search("  ", rows).size)
        assertEquals(emptyList<String>(), keys("zzqx"))
    }

    @Test fun localised_texts_are_searched_too() {
        val german: (Capability) -> Pair<String, String> = { c -> if (c.key == "insights") "Anrufe im Überblick" to "" else c.title to c.summary }
        assertEquals(listOf("insights"), CapabilitySearch.search("anrufe", rows, german).map { it.key })
        assertEquals(listOf("insights"), CapabilitySearch.search("uberblick", rows, german).map { it.key })
    }

    @Test fun new_rows_by_release() {
        assertEquals("4.6", CapabilityCatalog.majorMinor("4.6.0-debug"))
        assertEquals("4.6", CapabilityCatalog.majorMinor("4.6"))
        assertTrue(CapabilityCatalog.newIn("4.6.0").any { it.key == "coming_from" })
        assertTrue(CapabilityCatalog.newIn("4.6.0").none { it.key == "sales_lines" })
        assertEquals(emptyList<Capability>(), CapabilityCatalog.newIn("1.0"))
    }

    @Test fun coming_from_covers_each_importer() {
        val grouped = ComingFrom.grouped()
        assertEquals(ComingFrom.Importer.entries.toList(), grouped.map { it.first })
        assertEquals(ComingFrom.Source.entries.size, grouped.sumOf { it.second.size })
        assertEquals(listOf(ComingFrom.Source.GOOGLE, ComingFrom.Source.IPHONE, ComingFrom.Source.SAMSUNG), grouped.first().second)
    }
}
