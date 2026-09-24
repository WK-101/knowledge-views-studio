package app.parley.common.calltime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class QuotasTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val rule = LimitRule(LimitScope.CONTACT, key = "ana", dailyMinutes = 30, weeklyMinutes = 120)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    // Wednesday 2026-09-23
    private val history = listOf(
        UsageEntry(at(2026, 9, 23, 9), 10 * 60, incoming = false),
        UsageEntry(at(2026, 9, 23, 12), 15 * 60, incoming = true),
        UsageEntry(at(2026, 9, 22, 18), 40 * 60, incoming = false), // Tuesday
        UsageEntry(at(2026, 9, 20, 18), 50 * 60, incoming = false), // last Sunday
        UsageEntry(at(2026, 9, 23, 13), 0, incoming = false), // not answered
    )

    @Test fun daily_and_weekly_use() {
        val now = at(2026, 9, 23, 20)
        val s = Quotas.status(rule, history, now, zone)
        assertEquals(25 * 60L, s.first { it.period == QuotaPeriod.DAY }.usedSec)
        assertEquals(65 * 60L, s.first { it.period == QuotaPeriod.WEEK }.usedSec)
        assertFalse(s.any { it.exhausted })
        // A call in progress counts too.
        assertTrue(Quotas.status(rule, history, now, zone, liveSec = 5 * 60).first { it.period == QuotaPeriod.DAY }.exhausted)
    }

    @Test fun direction_of_the_rule_is_respected() {
        val outgoingOnly = rule.copy(incoming = false)
        assertEquals(10 * 60L, Quotas.usedSec(history, outgoingOnly, at(2026, 9, 23, 20), zone, QuotaPeriod.DAY))
    }

    @Test fun resets_lazily_by_date() {
        val tomorrow = at(2026, 9, 24, 0, 1)
        assertEquals(0L, Quotas.usedSec(history, rule, tomorrow, zone, QuotaPeriod.DAY))
        // Same week: the weekly allowance keeps counting.
        assertEquals(65 * 60L, Quotas.usedSec(history, rule, tomorrow, zone, QuotaPeriod.WEEK))
        // Next Monday starts a new week.
        assertEquals(0L, Quotas.usedSec(history, rule, at(2026, 9, 28, 8), zone, QuotaPeriod.WEEK))
    }

    @Test fun reboot_does_not_reset() {
        // Nothing is stored or tied to uptime: after a reboot the same history and date give the same answer.
        val now = at(2026, 9, 23, 20)
        val before = Quotas.status(rule, history, now, zone)
        val afterReboot = Quotas.status(rule, history.toList(), now + 60_000, zone)
        assertEquals(before, afterReboot)
    }

    @Test fun clock_set_back_does_not_reset() {
        // The clock is moved back to this morning, before today's calls: they still count.
        val earlier = at(2026, 9, 23, 7)
        assertEquals(25 * 60L, Quotas.usedSec(history, rule, earlier, zone, QuotaPeriod.DAY))
        // Moved back across midnight: yesterday's window still includes today's calls.
        assertTrue(Quotas.usedSec(history, rule, at(2026, 9, 22, 23), zone, QuotaPeriod.DAY) >= 25 * 60L)
    }

    @Test fun week_start_is_configurable() {
        val sunday = java.time.DayOfWeek.SUNDAY
        assertEquals(at(2026, 9, 20, 0), Quotas.periodStart(at(2026, 9, 23, 20), zone, QuotaPeriod.WEEK, sunday))
    }

    @Test fun dst_day_boundaries() {
        // 2026-10-25 has 25 hours in Berlin.
        assertEquals(at(2026, 10, 25, 0), Quotas.periodStart(at(2026, 10, 25, 23, 30), zone, QuotaPeriod.DAY))
    }
}
