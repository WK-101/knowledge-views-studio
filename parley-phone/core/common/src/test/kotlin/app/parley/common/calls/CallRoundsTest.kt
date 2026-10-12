package app.parley.common.calls

import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry
import app.parley.common.people.ContactRef
import app.parley.common.who.Who
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call round: who's next, the score, what may show, and the note for the call screen. */
class CallRoundsTest {
    private fun person(key: String, number: String, private: Boolean = false) = RoundPerson(key, key.uppercase(), number, private = private)

    private val ana = person("ana", "+447700900001")
    private val ben = person("ben", "+447700900002")
    private val cat = person("cat", "+447700900003", private = true)

    private fun round() = CallRounds.start("The funeral is on Friday", listOf(ana, ben, cat), 1L)!!

    @Test fun a_round_needs_something_to_say_and_someone_to_call() {
        assertNull(CallRounds.start("  ", listOf(ana), 1L))
        assertNull(CallRounds.start("News", listOf(person("x", " ")), 1L))
        val r = CallRounds.start("News", listOf(ana, ana.copy(name = "Again"), ben), 1L)!!
        // Each person once, in the order chosen, all waiting.
        assertEquals(listOf("ana", "ben"), r.people.map { it.key })
        assertTrue(r.people.all { it.mark == RoundMark.WAITING })
        val many = (1..80).map { person("p$it", "+4477009$it") }
        assertEquals(CallRounds.MAX_PEOPLE, CallRounds.start("News", many, 1L)!!.people.size)
        assertEquals(CallRounds.MAX_MESSAGE, CallRounds.start("x".repeat(500), listOf(ana), 1L)!!.message.length)
    }

    @Test fun people_added_to_a_round_under_way_wait_at_the_end() {
        val dan = person("dan", "+447700900004")
        var r = CallRounds.mark(round(), "ana", RoundMark.REACHED)
        r = CallRounds.add(r, listOf(ben.copy(name = "Ben again"), dan, dan, person("eve", " ")))
        // Ben stays as he was, Dan joins once and waits; nobody without a number; the message and Ana's mark stay.
        assertEquals(listOf("ana", "ben", "cat", "dan"), r.people.map { it.key })
        assertEquals("BEN", r.people[1].name)
        assertEquals(RoundMark.REACHED, r.people[0].mark)
        assertEquals(RoundMark.WAITING, r.people.last().mark)
        assertEquals(listOf("+447700900004"), r.people.last().numbers)
        assertEquals("The funeral is on Friday", r.message)
        // Called after those already waiting.
        val told = CallRounds.mark(CallRounds.mark(r, "ben", RoundMark.REACHED), "cat", RoundMark.REACHED)
        assertEquals("dan", CallRounds.next(told)?.key)
        // Never past the limit.
        val full = CallRounds.add(r, (1..80).map { person("p$it", "+4477009$it") })
        assertEquals(CallRounds.MAX_PEOPLE, full.people.size)
        assertEquals(r, CallRounds.add(r, emptyList()))
    }

    @Test fun next_is_the_first_not_called_then_those_to_try_again() {
        var r = round()
        assertEquals("ana", CallRounds.next(r)?.key)
        r = CallRounds.mark(r, "ana", RoundMark.NO_ANSWER)
        assertEquals("ben", CallRounds.next(r)?.key)
        r = CallRounds.mark(r, "ben", RoundMark.LATER)
        r = CallRounds.mark(r, "cat", RoundMark.REACHED)
        // Everyone tried: the one put off first, then the one who didn't answer.
        assertEquals("ben", CallRounds.next(r)?.key)
        r = CallRounds.mark(r, "ben", RoundMark.REACHED)
        assertEquals("ana", CallRounds.next(r)?.key)
        r = CallRounds.mark(r, "ana", RoundMark.REACHED)
        assertNull(CallRounds.next(r))
        assertTrue(CallRounds.score(r).done)
    }

    @Test fun the_score_counts_each_mark() {
        var r = round()
        r = CallRounds.mark(r, "ana", RoundMark.REACHED)
        r = CallRounds.mark(r, "ben", RoundMark.NO_ANSWER)
        assertEquals(RoundScore(reached = 1, noAnswer = 1, later = 0, waiting = 1), CallRounds.score(r))
        assertFalse(CallRounds.score(r).done)
        assertEquals(listOf("ana", "cat"), CallRounds.remove(r, "ben").people.map { it.key })
    }

    @Test fun private_contacts_show_only_while_private_contacts_may() {
        val r = round()
        assertEquals(listOf("ana", "ben"), CallRounds.shown(r, privateShown = false).people.map { it.key })
        assertEquals(r, CallRounds.shown(r, privateShown = true))
        // Hidden, a private contact's number gets no note: it reads like a number nobody saved.
        assertNull(CallRounds.noteFor(CallRounds.shown(r, false), "+447700900003", "GB"))
        assertEquals(r.message, CallRounds.noteFor(CallRounds.shown(r, true), "+447700900003", "GB"))
    }

    @Test fun the_note_shows_for_their_numbers_until_they_are_told() {
        var r = CallRounds.start("News", listOf(ana.copy(numbers = listOf("+447700900099"))), 1L)!!
        // Any of their numbers, as written in the national form too.
        assertEquals("News", CallRounds.noteFor(r, "07700 900001", "GB"))
        assertEquals("News", CallRounds.noteFor(r, "+447700900099", "GB"))
        assertNull(CallRounds.noteFor(r, "+447700900555", "GB"))
        r = CallRounds.mark(r, "ana", RoundMark.REACHED)
        assertNull(CallRounds.noteFor(r, "+447700900001", "GB"))
        assertNull(CallRounds.noteFor(CallRound(), "+447700900001", "GB"))
    }

    @Test fun kept_and_read_back() {
        val r = CallRounds.mark(round(), "ben", RoundMark.LATER)
        assertEquals(r, CallRounds.decode(CallRounds.encode(r)))
        assertEquals(CallRound(), CallRounds.decode("not json"))
        assertFalse(CallRounds.decode(null).active)
    }

    @Test fun people_come_from_favourites_the_circle_or_a_label_never_everyone() {
        fun contact(id: Long, key: String, starred: Boolean = false, number: String? = "+4477009000$id") =
            ContactSummary(id, key, key, null, starred, listOfNotNull(number?.let { PhoneEntry(it, 2, null) }))
        val list = listOf(contact(1, "a", starred = true), contact(2, "b"), contact(-3, "", starred = true), contact(4, "d", number = null))
        val labels = mapOf(2L to listOf("Family "), -3L to listOf("family"))
        val pick = { w: Who -> CallRounds.pick(w, list, setOf("b"), { labels[it].orEmpty() }).map { it.id } }
        assertEquals(listOf(1L, -3L), pick(Who.FAVOURITES))
        assertEquals(listOf(2L), pick(Who.CIRCLE))
        assertEquals(listOf(2L), pick(Who.label("Family")))
        assertEquals(emptyList<Long>(), pick(Who.EVERYONE))
        assertEquals(emptyList<Long>(), pick(Who.CONTACTS))
        // A private contact is kept by its private key; nobody without a number is called.
        val privatePerson = CallRounds.personOf(list[2])!!
        assertEquals(ContactRef.privateKey(3), privatePerson.key)
        assertTrue(privatePerson.private)
        assertNull(CallRounds.personOf(list[3]))
    }
}
