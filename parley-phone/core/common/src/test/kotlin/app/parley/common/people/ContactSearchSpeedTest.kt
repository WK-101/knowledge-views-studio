package app.parley.common.people

import app.parley.common.ContactSummary
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Contacts list's search as the app runs it per keystroke ([ContactListSearch.run]: label and field filters, the
 * search over every field with its hints, and the nickname renaming and sort), over 5,000 contacts with every kind of
 * field. Wall-clock bounds would fail on a loaded CI machine, so the checks are relative to the machine: the time grows
 * in proportion with the contacts (a search that re-reads or re-folds per keystroke, or compares every pair, doesn't),
 * and only a very generous absolute bound catches a search that is plainly broken. The medians are printed for
 * docs/PERFORMANCE_BENCHMARKS.md.
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

    private fun entry(i: Int, doc: ContactSearch.Doc): ContactListSearch.Entry {
        val c = ContactSummary(i.toLong(), "k$i", "Person $i", null, false, emptyList(), displayNameAlt = "Family${i % 97}, Given$i")
        return ContactListSearch.Entry(c, ContactSearch.fold("${c.displayName} ${c.displayNameAlt}"), doc)
    }

    private val queries = listOf(
        "given4321", "lisboa", "zurich company 7", "912 345", "+351910004321", "1975", "may", "café", "zzz", "person 12 madrid", "b4999",
    )
    private val extras = (1..COUNT).associate {
        it.toLong() to PersonExtra(nickname = if (it % 5 == 0) "Nick$it" else "", labels = setOf(if (it % 3 == 0) "Work" else "Friends"))
    }
    private val order = Comparator<String> { a, b -> a.compareTo(b, ignoreCase = true) }

    /** The slowest query's median time in µs over [entries], as the list runs it (with nicknames shown and a label filter). */
    private fun slowestUs(entries: List<ContactListSearch.Entry>): Long {
        val filter = LabelFilter(labels = setOf("Work"))
        fun timeUs(q: String): Long {
            val t = System.nanoTime()
            ContactListSearch.run(entries, ContactSearch.Query(q), filter, extras, emptySet(), preferNickname = true, order = order)
            return (System.nanoTime() - t) / 1_000
        }
        // Warm up the JIT, as in a running app after a few keystrokes.
        repeat(3) { queries.forEach(::timeUs) }
        // Each query's median over a few rounds: one GC pause on a busy test machine isn't the search's speed.
        return queries.maxOf { q -> List(ROUNDS) { timeUs(q) }.sorted()[ROUNDS / 2] }
    }

    @Test fun the_list_search_grows_in_proportion_with_the_contacts() {
        val start = System.nanoTime()
        val all = (1..COUNT).map { entry(it, synthetic(it)) }
        val buildMs = (System.nanoTime() - start) / 1_000_000
        val quarter = all.take(COUNT / 4)
        val small = slowestUs(quarter)
        val full = slowestUs(all)
        println("Contacts list search: $COUNT contacts prepared in $buildMs ms; slowest query median ${full / 1000.0} ms (${COUNT / 4}: ${small / 1000.0} ms)")
        // Four times the contacts: about four times the time. Quadratic work would be sixteen.
        assertTrue("$COUNT contacts took ${full}µs, ${COUNT / 4} took ${small}µs", full <= maxOf(small, 1_000L) * 10)
        assertTrue("slowest query took ${full / 1000} ms over $COUNT contacts", full < 2_000_000)
    }

    @Test fun results_are_those_the_list_shows() {
        val all = (1..COUNT).map { entry(it, synthetic(it)) }
        val r = ContactListSearch.run(all, ContactSearch.Query("b4999"), LabelFilter(), extras, emptySet(), preferNickname = false, order = order)
        assertEquals(listOf(4999L), r.shown.map { it.id })
        assertEquals(ContactSearch.Field.CUSTOM, r.explained[4999L])
        // "PT" and "Portugal" are one country: two of every eight.
        val fields = FieldFilter().toggle(Facet.COUNTRY, ContactFacets.key("Portugal")).toggle(Facet.HAS, ContactFacets.HAS_EMAIL)
        val byCountry = ContactListSearch.run(all, ContactSearch.Query(""), LabelFilter(fields = fields), extras, emptySet(), false, order)
        assertEquals(COUNT / 4, byCountry.shown.size)
        // Nicknames shown: renamed and sorted again.
        val nick = ContactListSearch.run(all.take(10), ContactSearch.Query(""), LabelFilter(), extras, emptySet(), true, order)
        assertEquals("Nick10", nick.shown.first().displayName)
    }

    private companion object {
        const val COUNT = 5_000
        const val ROUNDS = 7
    }
}
