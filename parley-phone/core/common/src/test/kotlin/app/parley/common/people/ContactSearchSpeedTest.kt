package app.parley.common.people

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5,000 contacts with every kind of field: once prepared, a query (and a filter) runs well inside a frame budget.
 * The bound is generous for a slow CI machine; it is there to catch a search that re-reads or re-folds per keystroke.
 */
class ContactSearchSpeedTest {
    private val cities = listOf("Lisboa", "Porto", "Madrid", "Paris", "Berlin", "London", "Toronto", "Zürich")
    private val countries = listOf("PT", "Portugal", "Spain", "France", "DE", "UK", "Canada", "Switzerland")

    private fun synthetic(i: Int): ContactSearch.Doc = ContactSearch.Builder(i.toLong(), "PT").apply {
        row(Mime.NAME) { mapOf(Col.D1 to "Person $i", Col.D2 to "Given$i", Col.D3 to "Family${i % 97}")[it] }
        row(Mime.PHONE) { mapOf(Col.D1 to "+351 91${"%07d".format(i)}")[it] }
        row(Mime.EMAIL) { mapOf(Col.D1 to "person$i@example.org")[it] }
        row(Mime.POSTAL) {
            mapOf(Col.D4 to "Street $i", Col.D7 to cities[i % cities.size], Col.D9 to "${1000 + i}", Col.D10 to countries[i % countries.size])[it]
        }
        row(Mime.ORG) { mapOf(Col.D1 to "Company ${i % 50}", Col.D4 to "Title ${i % 13}")[it] }
        row(Mime.NOTE) { mapOf(Col.D1 to "Met at conference number ${i % 31}, likes café")[it] }
        row(Mime.EVENT) { mapOf(Col.D1 to "19${50 + i % 50}-${"%02d".format(1 + i % 12)}-${"%02d".format(1 + i % 28)}", Col.D2 to "3")[it] }
        row(Mime.RELATION) { mapOf(Col.D1 to "Relative $i", Col.D2 to "${2 + i % 13}")[it] }
        row(Mime.WEBSITE) { mapOf(Col.D1 to "https://person$i.dev")[it] }
        row(Mime.CUSTOM_FIELD) { mapOf(Col.D1 to "Badge", Col.D2 to "B$i")[it] }
        label(if (i % 3 == 0) "Work" else "Friends")
    }.build()

    @Test fun five_thousand_contacts_search_in_a_few_milliseconds() {
        val start = System.nanoTime()
        val docs = (1..COUNT).map(::synthetic)
        val buildMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("index build took $buildMs ms", buildMs < 30_000)

        val queries = listOf("given4321", "lisboa", "zurich company 7", "912 345", "+351910004321", "1975", "may", "café", "zzz", "person 12 madrid", "b4999")
        fun timeUs(q: String): Long {
            val t = System.nanoTime()
            val query = ContactSearch.Query(q)
            docs.count { ContactSearch.match(query, it) != null }
            return (System.nanoTime() - t) / 1_000
        }
        // Warm up the JIT, as in a running app after a few keystrokes.
        repeat(3) { queries.forEach(::timeUs) }
        // Each query's median over a few rounds: one GC pause on a busy test machine isn't the search's speed.
        val medians = queries.associateWith { q -> List(ROUNDS) { timeUs(q) }.sorted()[ROUNDS / 2] / 1_000.0 }
        println("Contacts search over $COUNT contacts: index built in $buildMs ms; median per query (ms): $medians")
        val worst = medians.values.max()
        assertTrue("slowest query took $worst ms over $COUNT contacts", worst < 50)

        val filter = FieldFilter().toggle(Facet.COUNTRY, ContactFacets.key("Portugal")).toggle(Facet.HAS, ContactFacets.HAS_EMAIL)
        val t = System.nanoTime()
        val shown = docs.count { filter.matches(it.facets, photo = false, temporary = false) }
        val filterMs = (System.nanoTime() - t) / 1_000_000
        // "PT" and "Portugal" are one country: two of every eight.
        assertEquals(COUNT / 4, shown)
        assertTrue("filter took $filterMs ms", filterMs < 50)
        assertEquals(1, docs.count { ContactSearch.match("b4999", it) != null })
    }

    private companion object {
        const val COUNT = 5_000
        const val ROUNDS = 7
    }
}
