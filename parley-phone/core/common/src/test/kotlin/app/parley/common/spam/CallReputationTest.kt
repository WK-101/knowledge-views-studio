package app.parley.common.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class CallReputationTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 9, 30, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** [daysAgo] days before now, at [hour]:00 UTC. */
    private fun at(daysAgo: Int, hour: Int = 11): Long =
        LocalDateTime.of(2026, 9, 30, hour, 0).minusDays(daysAgo.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun missed(line: String, t: Long, ringMs: Long? = null) = RepCall(line, t, RepKind.MISSED, ringMillis = ringMs)
    private fun declined(line: String, t: Long) = RepCall(line, t, RepKind.DECLINED)
    private fun answered(line: String, t: Long, sec: Long) = RepCall(line, t, RepKind.ANSWERED, durationSec = sec)

    private fun signals(r: Reputation?) = r?.reasons?.map { it.signal }.orEmpty()

    // ---------- one line

    @Test fun short_rings_and_never_answered_look_like_sales() {
        val n = "+447700900123"
        val r = CallReputation.score(listOf(missed(n, at(1), 2_000), missed(n, at(2), 3_000), missed(n, at(3), 4_000)), zone)
        assertTrue(r.looksLikeSales)
        assertEquals(listOf(RepSignal.SHORT_RINGS, RepSignal.NEVER_ANSWERED, RepSignal.NO_VOICEMAIL), signals(r))
        // Short rings are capped at 30 points.
        assertEquals(30, r.reasons.first { it.signal == RepSignal.SHORT_RINGS }.points)
        assertEquals(60, r.score)
    }

    @Test fun always_declined_counts() {
        val n = "+33612345678"
        val r = CallReputation.score(listOf(declined(n, at(1)), declined(n, at(4))), zone)
        assertEquals(listOf(RepSignal.ALWAYS_DECLINED, RepSignal.NEVER_ANSWERED, RepSignal.NO_VOICEMAIL), signals(r))
        assertEquals(55, r.score)
        assertTrue(r.looksLikeSales)
    }

    @Test fun calls_you_ended_at_once_count() {
        val n = "+33612345678"
        val r = CallReputation.score(listOf(answered(n, at(1), 2), answered(n, at(2), 1), answered(n, at(3), 3)), zone)
        assertEquals(listOf(RepSignal.SHORT_HANGUPS), signals(r))
        assertEquals(40, r.score)
        // One reason alone is never enough, however strong.
        assertFalse(r.looksLikeSales)
    }

    @Test fun odd_hours_only_when_most_calls_are() {
        val n = "+33612345678"
        val night = CallReputation.score(listOf(missed(n, at(1, 22)), missed(n, at(2, 6)), missed(n, at(3, 11))), zone)
        assertTrue(RepSignal.ODD_HOURS in signals(night))
        assertEquals(2, night.reasons.first { it.signal == RepSignal.ODD_HOURS }.count)
        val day = CallReputation.score(listOf(missed(n, at(1, 22)), missed(n, at(2, 10)), missed(n, at(3, 11))), zone)
        assertFalse(RepSignal.ODD_HOURS in signals(day))
    }

    @Test fun a_voicemail_clears_no_voicemail() {
        val n = "+33612345678"
        val r = CallReputation.score(listOf(missed(n, at(1)), missed(n, at(2)), RepCall(n, at(2), RepKind.VOICEMAIL)), zone)
        assertFalse(RepSignal.NO_VOICEMAIL in signals(r))
    }

    @Test fun one_missed_call_says_nothing() {
        val n = "+33612345678"
        val r = CallReputation.score(listOf(missed(n, at(1))), zone)
        assertEquals(0, r.score)
        assertTrue(r.reasons.isEmpty())
    }

    @Test fun reasons_explain_the_score_exactly() {
        val n = "+447700900123"
        val r = CallReputation.score(listOf(missed(n, at(1, 22), 1_000), declined(n, at(2, 23)), missed(n, at(3, 7))), zone)
        assertEquals(r.reasons.sumOf { it.points }.coerceAtMost(100), r.score)
        assertEquals(r.reasons.sortedBy { it.signal.ordinal }, r.reasons)
    }

    @Test fun deterministic_for_any_order() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(1), 1_000), declined(n, at(2)), missed(n, at(3), 2_000), declined(n, at(9, 22)))
        val a = CallReputation.index(calls, now, zone)
        val b = CallReputation.index(calls.reversed(), now, zone)
        assertEquals(a, b)
    }

    // ---------- false-positive guards

    @Test fun contacts_are_never_scored() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(1), 1_000), missed(n, at(2), 1_000), declined(n, at(3)))
        assertTrue(CallReputation.score(calls, zone).looksLikeSales)
        assertEquals(0, CallReputation.score(calls, zone) { it == n }.score)
        assertNull(CallReputation.index(calls, now, zone) { it == n }.lookup(n))
    }

    @Test fun numbers_you_called_are_never_scored() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(1), 1_000), missed(n, at(2), 1_000), declined(n, at(3)), RepCall(n, at(40), RepKind.OUTGOING))
        assertEquals(0, CallReputation.score(calls, zone).score)
        assertNull(CallReputation.index(calls, now, zone).lookup(n))
    }

    @Test fun a_long_call_ever_clears_the_number() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(1), 1_000), missed(n, at(2), 1_000), declined(n, at(3)), answered(n, at(60), 75))
        assertNull(CallReputation.index(calls, now, zone).lookup(n))
    }

    @Test fun short_codes_and_unparsed_numbers_have_no_range_and_no_entry() {
        assertNull(CallReputation.rangeOf("112"))
        assertNull(CallReputation.rangeOf("+3361"))
        assertNull(CallReputation.rangeOf("*100#"))
        val calls = listOf(missed("3115", at(1), 1_000), missed("3115", at(2), 1_000), declined("3115", at(3)))
        assertTrue(CallReputation.index(calls, now, zone).isEmpty)
    }

    @Test fun emergency_numbers_reported_known_are_never_scored() {
        val gp = "+441632960001"
        val calls = listOf(missed(gp, at(1), 1_000), missed(gp, at(2), 1_000), declined(gp, at(3)))
        assertNull(CallReputation.index(calls, now, zone) { it == gp }.lookup(gp))
    }

    @Test fun calls_screening_stopped_never_count_against_a_number() {
        val n = "+447700900123"
        // Silenced by Parley three times: you never had the chance to answer.
        val calls = List(3) { RepCall(n, at(it + 1), RepKind.SCREENED, ringMillis = 1_000) }
        assertEquals(0, CallReputation.score(calls, zone).score)
    }

    @Test fun a_long_call_long_ago_still_counts() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(1), 1_000), missed(n, at(2), 1_000), declined(n, at(3)), answered(n, at(400), 600))
        assertNull(CallReputation.index(calls, now, zone).lookup(n))
    }

    @Test fun older_calls_are_forgotten() {
        val n = "+447700900123"
        val calls = listOf(missed(n, at(100), 1_000), missed(n, at(101), 1_000), declined(n, at(102)))
        assertTrue(CallReputation.index(calls, now, zone).isEmpty)
    }

    // ---------- ranges

    @Test fun range_of_a_number() {
        assertEquals("+33612345", CallReputation.rangeOf("+33612345678"))
        assertEquals("+12025550", CallReputation.rangeOf("+12025550123"))
    }

    @Test fun many_numbers_from_one_range_in_a_week() {
        val lines = (1..4).map { "+3361234560$it" }
        val calls = lines.mapIndexed { i, l -> missed(l, at(i + 1)) }
        val idx = CallReputation.index(calls, now, zone)
        // A number never seen before, from the same range.
        val fresh = idx.lookup("+33612345699")
        assertNotNull(fresh)
        assertEquals(listOf(RepSignal.RANGE_BURST, RepSignal.RANGE_UNANSWERED), signals(fresh))
        assertEquals(4, fresh!!.reasons.first().count)
        assertTrue(fresh.looksLikeSales)
        // Each of the four is tagged too, with its own calls and the range's.
        assertTrue(lines.all { idx.lookup(it)?.looksLikeSales == true })
        // Another range is untouched.
        assertNull(idx.lookup("+33698765432"))
    }

    @Test fun spread_over_months_is_not_a_burst() {
        val calls = (1..4).map { missed("+3361234560$it", at(it * 20)) }
        val r = CallReputation.index(calls, now, zone).lookup("+33612345699")
        assertFalse(RepSignal.RANGE_BURST in signals(r))
    }

    @Test fun numbers_you_blocked_in_the_range_count() {
        val n = "+33612345601"
        val calls = listOf(declined(n, at(1)), missed(n, at(2)))
        val idx = CallReputation.index(calls, now, zone, blockedLines = setOf("+33612345677", "+33612345688"))
        // Never answered alone isn't enough; with two blocked neighbours it's a tag.
        val r = idx.lookup(n)
        assertNotNull(r)
        assertEquals(listOf(RepSignal.RANGE_BLOCKED), signals(r).filter { it == RepSignal.RANGE_BLOCKED })
        assertEquals(2, r!!.reasons.first { it.signal == RepSignal.RANGE_BLOCKED }.count)
        assertEquals(30, CallReputation.score(calls, zone).score)
        assertTrue(r.looksLikeSales)
        // Blocked neighbours alone never tag a number that hasn't called.
        assertNull(idx.lookup("+33612345699"))
    }

    @Test fun a_range_holding_a_contact_is_never_scored() {
        val lines = (1..4).map { "+3361234560$it" }
        val calls = lines.mapIndexed { i, l -> missed(l, at(i + 1)) }
        // A colleague's number at the same company.
        val idx = CallReputation.index(calls, now, zone, knownLines = setOf("+33612345650"))
        assertNull(idx.lookup("+33612345699"))
        assertTrue(lines.all { idx.lookup(it) == null })
    }

    @Test fun a_range_holding_a_number_you_talked_to_is_never_scored() {
        val lines = (1..4).map { "+3361234560$it" }
        val calls = lines.mapIndexed { i, l -> missed(l, at(i + 1)) } + answered("+33612345690", at(30), 300)
        assertNull(CallReputation.index(calls, now, zone).lookup("+33612345699"))
    }

    // ---------- "Block this range?"

    @Test fun proposes_the_narrowest_prefix() {
        val calls = listOf(
            missed("+33612345601", at(1)), missed("+33612345602", at(2)), declined("+33612345611", at(3)), missed("+33612345601", at(4)),
            // Another range: not covered.
            missed("+33698765432", at(1)),
        )
        val p = CallReputation.proposeRange("+33612345601", calls, now)
        assertEquals(RangeProposal("+336123456", numbers = 3, calls = 4), p)
        // Only two related numbers that share more digits: a narrower prefix.
        assertEquals("+3361234560", CallReputation.proposeRange("+33612345601", calls.filterNot { it.line == "+33612345611" }, now)?.prefix)
    }

    @Test fun no_range_offer_for_a_single_number() {
        val calls = listOf(missed("+33612345601", at(1)), declined("+33612345601", at(2)))
        assertNull(CallReputation.proposeRange("+33612345601", calls, now))
    }

    @Test fun no_range_offer_when_the_range_holds_someone_you_know() {
        val calls = listOf(missed("+33612345601", at(1)), missed("+33612345602", at(2)), RepCall("+33612345690", at(5), RepKind.OUTGOING))
        assertNull(CallReputation.proposeRange("+33612345601", calls, now))
        val plain = listOf(missed("+33612345601", at(1)), missed("+33612345602", at(2)))
        assertNotNull(CallReputation.proposeRange("+33612345601", plain, now))
        assertNull(CallReputation.proposeRange("+33612345601", plain, now, knownLines = setOf("+33612345699")))
    }

    // ---------- storage form

    @Test fun round_trips_and_tolerates_garbage() {
        val rep = Reputation(60, listOf(RepReason(RepSignal.SHORT_RINGS, 2, 30), RepReason(RepSignal.NEVER_ANSWERED, 3, 20)))
        val map = mapOf("k1" to rep)
        assertEquals(map, CallReputation.decode(CallReputation.encode(map)))
        assertTrue(CallReputation.decode("{not json").isEmpty())
        assertTrue(CallReputation.decode(null).isEmpty())
    }
}
