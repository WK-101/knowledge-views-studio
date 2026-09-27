package app.parley.common.calltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class UsageLedgerTest {
    private val t0 = 1_700_000_000_000L

    @Test fun history_rows_of_ledger_calls_count_once() {
        // The call log dates the call from when it rang (20 s before it connected).
        val ledger = listOf(UsageEntry(t0 + 20_000, 300, incoming = true))
        val history = listOf(UsageEntry(t0, 301, incoming = true), UsageEntry(t0 - 3_600_000, 120, incoming = false))
        val merged = Quotas.mergeUsage(ledger, history)
        assertEquals(2, merged.size)
        assertEquals(420L, merged.sumOf { it.durationSec })
    }

    @Test fun cleared_call_log_still_counts_the_ledger() {
        val ledger = listOf(UsageEntry(t0, 600, incoming = false))
        assertEquals(ledger, Quotas.mergeUsage(ledger, emptyList()))
    }

    @Test fun no_ledger_keeps_history_as_before() {
        val history = listOf(UsageEntry(t0, 60, incoming = true))
        assertEquals(history, Quotas.mergeUsage(emptyList(), history))
    }

    @Test fun different_calls_close_together_both_count() {
        val ledger = listOf(UsageEntry(t0 + 10_000, 30, incoming = true))
        // Same time, but a different direction and length: another call.
        val history = listOf(UsageEntry(t0, 30, incoming = false), UsageEntry(t0, 400, incoming = true))
        assertEquals(3, Quotas.mergeUsage(ledger, history).size)
    }

    @Test fun each_ledger_row_absorbs_one_history_row() {
        val ledger = listOf(UsageEntry(t0 + 5_000, 60, true))
        val history = listOf(UsageEntry(t0, 60, true), UsageEntry(t0 + 1_000, 60, true))
        assertEquals(2, Quotas.mergeUsage(ledger, history).size)
    }

    @Test fun unknown_usage_fails_closed() {
        val rule = LimitRule(LimitScope.GLOBAL, dailyMinutes = 30, weeklyMinutes = 120)
        val q = Quotas.unknownUsage(rule)
        assertEquals(2, q.size)
        assertTrue(q.all { it.exhausted })
        val config = CallingConfig(rules = listOf(rule), supervised = true, silenceIncomingOverQuota = true)
        val facts = CallFacts(incoming = false, isEmergency = false)
        assertTrue(CallLimits.outgoingBlocker(config, facts, q) != null)
        assertTrue(CallLimits.silenceIncoming(config, facts.copy(incoming = true), q))
        // Emergency calls are never held back, whatever the ledger says.
        assertEquals(null, CallLimits.outgoingBlocker(config, facts.copy(isEmergency = true), q))
        assertTrue(Quotas.unknownUsage(null).isEmpty())
    }

    @Test fun merged_usage_feeds_status() {
        val rule = LimitRule(LimitScope.GLOBAL, dailyMinutes = 10)
        val now = t0 + 60_000
        val used = Quotas.mergeUsage(listOf(UsageEntry(t0, 600, false)), listOf(UsageEntry(t0 - 15_000, 600, false)))
        val s = Quotas.status(rule, used, now, ZoneOffset.UTC).single()
        assertEquals(600L, s.usedSec)
        assertTrue(s.exhausted)
    }
}
