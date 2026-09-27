package app.parley.common.calls

import app.parley.common.calls.RingEnd.Disconnect
import app.parley.common.calls.RingEnd.SilenceReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingEndTest {
    private fun facts(
        silenced: Boolean = false,
        quota: Boolean = false,
        ignored: Boolean = false,
        blocked: Boolean = false,
        reject: Boolean = false,
        tone: Pair<RingtoneSource, String?>? = null,
        connected: Boolean = false,
        disconnect: Disconnect = Disconnect.OTHER,
    ) = RingEnd.of(RingEnd.Facts(silenced, quota, ignored, blocked, reject, tone, connected, disconnect))

    @Test fun an_ordinary_answered_call_rang_with_the_system_tone() {
        val r = facts(connected = true)
        assertNull(r.silence)
        assertEquals(RingtoneSource.SYSTEM, r.ringtone)
        assertEquals(RingOutcome.ANSWERED, r.outcome)
    }

    @Test fun silence_reasons_in_order_quota_rules_ignore() {
        assertEquals(SilenceReason.QUOTA, facts(silenced = true, quota = true, ignored = true).silence)
        assertEquals(SilenceReason.RULES, facts(silenced = true, blocked = true).silence)
        assertEquals(SilenceReason.IGNORED, facts(silenced = true, ignored = true).silence)
        assertEquals(SilenceReason.OTHER, facts(silenced = true).silence)
    }

    @Test fun a_blocked_call_played_no_tone_and_a_rejected_one_counts_as_blocked() {
        val r = facts(silenced = true, blocked = true, reject = true, tone = RingtoneSource.RULE to "Work")
        assertEquals(RingtoneSource.NONE, r.ringtone)
        assertEquals(RingOutcome.BLOCKED, r.outcome)
        assertEquals(RingOutcome.MISSED, facts(silenced = true, blocked = true).outcome)
    }

    @Test fun ignoring_after_the_tone_started_keeps_the_tone() {
        val r = facts(silenced = true, ignored = true, tone = RingtoneSource.UNKNOWN_CALLER to null)
        assertEquals(RingtoneSource.UNKNOWN_CALLER, r.ringtone)
        assertEquals(RingtoneSource.SYSTEM, facts(silenced = true, ignored = true).ringtone)
        assertEquals(RingtoneSource.NONE, facts(silenced = true, quota = true).ringtone)
    }

    @Test fun declined_and_answered_elsewhere() {
        assertEquals(RingOutcome.DECLINED, facts(disconnect = Disconnect.REJECTED).outcome)
        assertEquals(RingOutcome.ANSWERED_ELSEWHERE, facts(disconnect = Disconnect.ANSWERED_ELSEWHERE).outcome)
    }
}
