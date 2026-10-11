package app.parley.common.recall

import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.people.ContactListSearch
import app.parley.common.people.ContactSearch
import app.parley.common.testing.testCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Recall over 10,000 contacts and 50,000 calls, as a keystroke runs it once the search has its corpus. Bounds are
 * generous for a loaded CI machine: they are there to catch a search that names every call's number again per
 * keystroke or compares every call with every contact, not to measure.
 */
class RecallScaleTest {
    private val today = LocalDate.of(2026, 10, 4)
    private val zone = ZoneOffset.UTC
    private val start = today.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun number(i: Int) = "+351 91${"%07d".format(i)}"

    private val contacts = (1..CONTACTS).map { i ->
        val c = ContactSummary(i.toLong(), "k$i", "Person $i", null, false, listOf(PhoneEntry(number(i), 2, null)))
        val doc = ContactSearch.Builder(i.toLong(), "PT").apply {
            name("Person $i")
            number(number(i))
            work(if (i % 100 == 0) "Plumber $i" else "Company ${i % 50}", null)
        }.build()
        ContactListSearch.Entry(c, ContactSearch.fold(c.displayName), doc)
    }

    // A call every ~10 minutes back from today, with one in three to a number nobody has.
    private val calls = (0 until CALLS).map { i ->
        val who = if (i % 3 == 0) 20_000 + i % 4_000 else 1 + i % CONTACTS
        val type = CallType.entries[i % 3]
        testCall(i.toLong(), number(who), null, type, start - i * 600_000L, 60)
    }

    /** The app names numbers through PhoneIdentity's line map, built once; here a plain map of the same numbers. */
    private val byNumber = contacts.associate { it.contact.phones.first().number to it.contact.id }
    private var asked = 0
    private val contactOf: (String) -> Long? = { n ->
        asked++
        byNumber[n]
    }

    @Test fun aKeystrokeStaysAScanOfPreparedStrings() {
        val built = System.nanoTime()
        val engine = RecallEngine(RecallCorpus(contacts = contacts, calls = calls, region = "PT"), zone)
        val buildMs = (System.nanoTime() - built) / 1_000_000
        fun run(text: String): RecallResult {
            val q = RecallQuery.parse(text, today, Locale.UK)
            return RecallRanking.merge(q, engine.search(q, contactOf), limit = 20)
        }
        // The first search names each distinct number once.
        val first = System.nanoTime()
        val plumbers = run("plumber march")
        val firstMs = (System.nanoTime() - first) / 1_000_000
        val distinct = calls.map { it.number }.toSet().size
        assertTrue("named $asked numbers, $distinct distinct", asked <= distinct)
        assertTrue(plumbers.groups.first { it.source == RecallSource.CALL }.hits.all { it.title.startsWith("Person") })

        val queries = listOf("who called in march", "person 4321", "missed yesterday", "912 0004", "bank last week", "zzz", "plumber")
        repeat(2) { queries.forEach(::run) }
        val askedBefore = asked
        val t = System.nanoTime()
        queries.forEach(::run)
        val perQueryMs = (System.nanoTime() - t) / 1_000_000 / queries.size
        // Later keystrokes don't name a number again.
        assertEquals(askedBefore, asked)
        println("Recall: corpus $buildMs ms, first search $firstMs ms, then $perQueryMs ms per search over $CONTACTS contacts and $CALLS calls")
        assertTrue("first search took $firstMs ms", firstMs < 20_000)
        assertTrue("a search took $perQueryMs ms", perQueryMs < 5_000)
    }

    private companion object {
        const val CONTACTS = 10_000
        const val CALLS = 50_000
    }
}
