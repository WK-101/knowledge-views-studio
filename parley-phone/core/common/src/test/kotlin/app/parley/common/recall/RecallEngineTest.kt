package app.parley.common.recall

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.people.ContactListSearch
import app.parley.common.people.ContactSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class RecallEngineTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 4)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12): Long = LocalDate.of(y, m, d).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun contact(id: Long, name: String, number: String, company: String? = null, note: String? = null): ContactListSearch.Entry {
        val c = ContactSummary(id, "k$id", name, null, false, listOf(PhoneEntry(number, 2, null)))
        val doc = ContactSearch.Builder(id, "PT").apply {
            name(name)
            number(number)
            work(company, null)
            note(note)
        }.build()
        return ContactListSearch.Entry(c, ContactSearch.fold(name), doc)
    }

    private val mike = contact(1, "Mike Silva", "+351 912 000 001", company = "Plumber Silva Lda", note = "Fixed the boiler")
    private val bank = contact(2, "Azul Bank", "+351 213 000 002", company = "Azul Bank")
    private val ana = contact(-7, "Ana Private", "+351 913 000 007")

    private fun call(id: Long, number: String, type: CallType, date: Long, name: String? = null, sec: Long = 60) =
        CallEntry(id, number, name, type, date, sec, null, isNew = false, presentationHidden = false)

    private val calls = listOf(
        call(10, "+351 213 000 002", CallType.INCOMING, at(2026, 9, 29)),
        call(9, "+351 912 000 001", CallType.MISSED, at(2026, 3, 20)),
        call(8, "+351 912 000 001", CallType.OUTGOING, at(2026, 3, 18)),
        call(7, "+351 966 555 444", CallType.INCOMING, at(2026, 3, 2), name = "Garage Lopes"),
        call(6, "+351 912 000 001", CallType.INCOMING, at(2025, 11, 2)),
    ).sortedByDescending { it.date }

    private val corpus = RecallCorpus(
        contacts = listOf(mike, bank, ana),
        calls = calls,
        notes = listOf(
            RecallCorpus.Note(RecallCorpus.Note.Kind.PINNED, "Ask about the boiler warranty\n[ ] send the invoice to the bank", "k1", "Mike Silva", null, 0),
            RecallCorpus.Note(RecallCorpus.Note.Kind.CIRCLE, "Lunch, talked about the garden", "k2", "Azul Bank", null, at(2026, 3, 10)),
            RecallCorpus.Note(RecallCorpus.Note.Kind.CALL, "Quote: 240 for the boiler", null, null, "+351 912 000 001", at(2026, 3, 18), id = 44),
        ),
        deleted = listOf(RecallCorpus.Gone("Old Plumber", listOf("+351 999 111 222"), at(2026, 9, 1), "31")),
        snapshots = listOf(
            RecallCorpus.Gone("Old Plumber", listOf("+351 999 111 222"), at(2026, 8, 30), "1"),
            RecallCorpus.Gone("Electrician Rui", listOf("+351 999 333 444"), at(2026, 7, 1), "2"),
        ),
        messaged = listOf(RecallCorpus.Messaged("+351 999 333 444", "Signal", at(2026, 9, 28))),
        region = "PT",
    )

    private val engine = RecallEngine(corpus, zone)

    /** Numbers of the listed contacts by their national digits (the app uses PhoneIdentity's line map). */
    private val contactOf: (String) -> Long? = { n ->
        corpus.contacts.firstOrNull { e -> e.contact.phones.any { app.parley.common.PhoneIdentity.same(it.number, n, "PT") } }?.contact?.id
    }

    private fun run(text: String): RecallResult {
        val q = RecallQuery.parse(text, today, java.util.Locale.UK)
        return RecallRanking.merge(q, engine.search(q, contactOf), limit = 10, region = "PT")
    }

    private fun RecallResult.group(s: RecallSource) = groups.firstOrNull { it.source == s }

    @Test fun plumberMarchFindsThePlumbersMarchCallsByHisCompany() {
        val r = run("plumber march")
        val calls = r.group(RecallSource.CALL)!!
        // Mike's company says plumber; his two March calls, newest first. November and the garage aren't.
        assertEquals(listOf(at(2026, 3, 20), at(2026, 3, 18)), calls.hits.map { it.at })
        assertTrue(calls.hits.all { it.contactId == 1L && it.title == "Mike Silva" })
        assertEquals(ContactSearch.Field.COMPANY, calls.hits.first().field)
        // The contact itself (the list above searched "plumber march" and found nobody).
        assertEquals("Mike Silva", r.group(RecallSource.CONTACT)!!.hits.single().title)
        // The deleted "Old Plumber" went in September: not March.
        assertNull(r.group(RecallSource.DELETED))
    }

    @Test fun whoCalledInMarchIsEveryCallThatCameIn() {
        val r = run("who called in march")
        assertEquals(RecallSource.CALL, r.groups.first().source)
        val hits = r.group(RecallSource.CALL)!!.hits
        // The missed call and the garage's, not the outgoing one.
        assertEquals(listOf(9L, 7L).map { it.toString() }, hits.map { it.ref })
        assertEquals("Garage Lopes", hits[1].title)
        assertEquals(1, r.groups.size)
    }

    @Test fun bankLastWeek() {
        val r = run("bank last week")
        val hit = r.group(RecallSource.CALL)!!.hits.single()
        assertEquals("Azul Bank", hit.title)
        assertEquals(CallType.INCOMING, hit.callType)
        // Accents and case aside, where the word is in the name.
        assertEquals(listOf(5..8), hit.titleMarks)
    }

    @Test fun aPartialNumberFindsCallsGoneContactsAndChats() {
        val r = run("333 444")
        assertEquals("Electrician Rui", r.group(RecallSource.SNAPSHOT)!!.hits.single().title)
        val chat = r.group(RecallSource.MESSAGED)!!.hits.single()
        assertEquals("Signal", chat.detail)
        // The digits are marked through the spaces.
        assertEquals(listOf(9..15), chat.titleMarks)
        assertNull(r.group(RecallSource.CALL))
    }

    @Test fun promisesAndNotesAndCallNotes() {
        val promise = run("invoice").group(RecallSource.PROMISE)!!.hits.single()
        assertEquals("send the invoice to the bank", promise.detail)
        assertEquals("k1", promise.ref)
        val boiler = run("boiler")
        assertEquals("k1", boiler.group(RecallSource.NOTE)!!.hits.single().ref)
        val callNote = boiler.group(RecallSource.CALL_NOTE)!!.hits.single()
        assertEquals("44", callNote.ref)
        assertEquals("+351 912 000 001", callNote.number)
        // A note found by its date alone; the pinned note has none.
        val march = run("garden march")
        assertEquals("Azul Bank", march.group(RecallSource.NOTE)!!.hits.single().title)
    }

    @Test fun aContactBothDeletedAndInTheSnapshotsShowsOnceAsDeleted() {
        val r = run("old plumber")
        assertEquals("31", r.group(RecallSource.DELETED)!!.hits.single().ref)
        assertNull(r.group(RecallSource.SNAPSHOT))
    }

    @Test fun groupsAreCutWithTheirTotalKept() {
        val many = (1..30).map { i -> call(100L + i, "+351 912 000 001", CallType.INCOMING, at(2026, 3, 1) + i * 60_000L) }.sortedByDescending { it.date }
        val e = RecallEngine(RecallCorpus(contacts = listOf(mike), calls = many, region = "PT"), zone)
        val q = RecallQuery.parse("mike", today, java.util.Locale.UK)
        val r = RecallRanking.merge(q, e.search(q, contactOf, limit = 20), limit = 5)
        val g = r.group(RecallSource.CALL)!!
        assertEquals(5, g.hits.size)
        assertEquals(20, g.total)
        assertEquals(many.first().date, g.hits.first().at)
    }

    @Test fun rankingPrefersTheNameThenTheNewest() {
        val q = RecallQuery.parse("x", today)
        fun hit(score: Int, at: Long) = RecallHit(RecallSource.NOTE, "t", at = at, score = score)
        val r = RecallRanking.merge(q, mapOf(RecallSource.NOTE to listOf(hit(20, 5), hit(30, 1), hit(20, 9))), limit = 10)
        assertEquals(listOf(30 to 1L, 20 to 9L, 20 to 5L), r.groups.single().hits.map { it.score to it.at })
    }

    @Test fun groupsFollowTheSourceOrder() {
        val q = RecallQuery.parse("x", today)
        val r = RecallRanking.merge(
            q,
            mapOf(
                RecallSource.SNAPSHOT to listOf(RecallHit(RecallSource.SNAPSHOT, "a")),
                RecallSource.CALL to listOf(RecallHit(RecallSource.CALL, "b")),
                RecallSource.NOTE to emptyList(),
            ),
            limit = 10,
        )
        assertEquals(listOf(RecallSource.CALL, RecallSource.SNAPSHOT), r.groups.map { it.source })
        assertFalse(r.isEmpty)
        assertEquals(2, r.count)
    }

    @Test fun nothingForAnEmptyQuery() {
        val q = RecallQuery.parse("  ", today)
        assertTrue(RecallRanking.merge(q, engine.search(q, contactOf), 10).isEmpty)
    }
}
