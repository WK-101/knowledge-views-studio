package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.circle.KeepInTouch.Fact
import app.parley.common.circle.PeopleInsights.Touch
import app.parley.common.circle.PeopleInsights.TouchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class KeepInTouchTest {
    private val day = NaturalRhythm.DAY
    private val now = 1_000 * day
    private val utc = ZoneOffset.UTC

    private fun t(daysAgo: Int, kind: TouchKind) = Touch("ana", now - daysAgo * day, kind)

    @Test fun parley_started_chats_are_you_reaching_out() {
        assertEquals(TouchKind.CHAT_OUT, PeopleInsights.loggedKind(Interactions.promptKey(InteractionChannel.SIGNAL, "ana", now)))
        assertEquals(TouchKind.WISHED, PeopleInsights.loggedKind(Interactions.wishedKey("x")))
        assertEquals(TouchKind.LOGGED, PeopleInsights.loggedKind(Interactions.manualKey("n")))
        assertTrue(TouchKind.CHAT_OUT.inTouch)
    }

    @Test fun their_turns_in_a_row_stop_at_your_call_or_chat() {
        val theirs = listOf(t(40, TouchKind.CALL_OUT), t(30, TouchKind.CALL_IN), t(20, TouchKind.MISSED_IN), t(10, TouchKind.CALL_IN))
        assertEquals(3, PeopleInsights.theirTurnsInARow(theirs))
        // A chat you started from Parley after them: your move.
        assertEquals(0, PeopleInsights.theirTurnsInARow(theirs + t(5, TouchKind.CHAT_OUT)))
        // A logged meeting has no direction: still their three.
        assertEquals(3, PeopleInsights.theirTurnsInARow(theirs + t(5, TouchKind.LOGGED)))
    }

    @Test fun your_turn_needs_due_three_in_a_row_and_the_switch() {
        val three = listOf(t(30, TouchKind.CALL_IN), t(20, TouchKind.CALL_IN), t(10, TouchKind.CALL_IN))
        assertEquals(3, KeepInTouch.yourTurn(three, due = true, firstMoverShown = true))
        assertNull(KeepInTouch.yourTurn(three, due = false, firstMoverShown = true))
        assertNull(KeepInTouch.yourTurn(three, due = true, firstMoverShown = false))
        assertNull(KeepInTouch.yourTurn(three.drop(1), due = true, firstMoverShown = true))
    }

    @Test fun facts_come_most_useful_first_and_at_most_three() {
        // Every ten days, them first each time; then their missed call two days ago.
        val touches = (1..6).map { t(it * 10, TouchKind.CALL_IN) } + t(2, TouchKind.MISSED_IN)
        val facts = KeepInTouch.facts(touches, due = true, firstMoverShown = true, now = now, zone = utc)
        assertEquals(3, facts.size)
        assertEquals(Fact.YourTurn(7), facts[0])
        assertEquals(Fact.Rhythm(10, 10), facts[1])
        // "Ana usually calls first" would repeat your turn: the loose end comes instead.
        assertTrue(facts[2] is Fact.LooseEnd)
        // Not due: no turn, so the balance is said.
        val calm = KeepInTouch.facts(touches, due = false, firstMoverShown = true, now = now, zone = utc)
        assertEquals(Fact.Balance(PeopleInsights.FirstMover.THEM), calm[1])
        // The switch off: neither turn nor balance.
        val off = KeepInTouch.facts(touches, due = true, firstMoverShown = false, now = now, zone = utc)
        assertTrue(off.none { it is Fact.YourTurn || it is Fact.Balance })
    }

    @Test fun summary_counts_answered_calls_talk_and_chats_of_the_last_year() {
        fun call(daysAgo: Int, type: CallType, sec: Long) = CallEntry(daysAgo.toLong(), "1", null, type, now - daysAgo * day, sec, null, false, false)
        val calls = listOf(call(1, CallType.INCOMING, 600), call(2, CallType.OUTGOING, 60), call(3, CallType.MISSED, 0), call(400, CallType.OUTGOING, 900))
        val s = KeepInTouch.summary(calls, listOf(t(5, TouchKind.CHAT_OUT), t(6, TouchKind.LOGGED)), now)
        assertEquals(KeepInTouch.Summary(2, 660, 1), s)
        assertNull(KeepInTouch.summary(emptyList(), emptyList(), now))
    }

    @Test fun usual_gap_is_the_plain_median() {
        val times = listOf(0, 14, 28, 42).map { now - it * day }
        assertEquals(14, NaturalRhythm.usualGap(times, now, utc))
        assertEquals(21, NaturalRhythm.learn(times, now, utc))
    }

    @Test fun glance_hides_below_four_and_keeps_only_counts_while_insights_are_hidden() {
        val four = listOf(CircleStatus.DUE, CircleStatus.SOON, CircleStatus.FINE, CircleStatus.FINE)
        val reach = PeopleInsights.Reach(3, 1, 4)
        assertNull(CircleGlance.of(four.drop(1), reach, 10, 20, insightsShown = true))
        assertEquals(CircleGlance.Glance(1, 1, 2, reach, 38), CircleGlance.of(four, reach, 38, 100, insightsShown = true))
        assertEquals(CircleGlance.Glance(1, 1, 2, null, null), CircleGlance.of(four, reach, 38, 100, insightsShown = false))
        // No calls this month, or none with the Circle: no share line.
        assertNull(CircleGlance.of(four, reach, 0, 0, insightsShown = true)!!.talkShare)
        assertNull(CircleGlance.of(four, reach, 0, 100, insightsShown = true)!!.talkShare)
    }
}
