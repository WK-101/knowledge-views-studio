package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

internal val UTC: ZoneId = ZoneId.of("UTC")

internal fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
    LocalDateTime.of(y, mo, d, h, mi).atZone(UTC).toInstant().toEpochMilli()

private var nextId = 1L

internal fun call(number: String, type: CallType, date: Long, dur: Long = 0, sim: String? = null, name: String? = null, hidden: Boolean = false) =
    CallEntry(nextId++, number, name, type, date, dur, sim, false, hidden)

class CallLogIndexTest {
    private val anna = IndexContact(1, "anna", "Anna", listOf("+33 6 12 34 56 78", "01 23 45 67 89"))

    @Test fun keys_are_e164_with_trunk_prefix_stripped() {
        assertEquals("+33612345678", NumberKeys.of("06 12 34 56 78", "FR"))
        assertEquals("+33612345678", NumberKeys.of("+33612345678", "FR"))
        assertEquals("+33612345678", NumberKeys.of("0033 6 12 34 56 78", "FR"))
        assertEquals("+4915112345678", NumberKeys.of("015112345678", "DE"))
        assertEquals("#112", NumberKeys.of("112", "DE"))
        assertEquals(NumberKeys.HIDDEN, NumberKeys.of("", "DE"))
    }

    @Test fun trunk_prefix_does_not_split_a_person() {
        val idx = CallLogIndex.build(
            listOf(
                call("0612345678", CallType.INCOMING, at(2026, 1, 1), 60),
                call("+33612345678", CallType.OUTGOING, at(2026, 1, 2), 30),
            ),
            contacts = emptyList(), countryIso = "FR", zone = UTC,
        )
        assertEquals(1, idx.people.size)
        val p = idx.people.values.single()
        assertEquals("n:+33612345678", p.key)
        assertEquals(2, idx.totals(personKey = p.key).total)
    }

    @Test fun contact_groups_all_numbers() {
        val idx = CallLogIndex.build(
            listOf(
                call("0612345678", CallType.INCOMING, at(2026, 1, 1), 60),
                call("0123456789", CallType.OUTGOING, at(2026, 1, 2), 30),
                call("0699999999", CallType.OUTGOING, at(2026, 1, 3), 30),
            ),
            listOf(anna), "FR", UTC,
        )
        val key = CallLogIndex.contactPersonKey(anna)
        val t = idx.totals(personKey = key)
        assertEquals(2, t.total)
        assertEquals(60, t.talkInSec)
        assertEquals(30, t.talkOutSec)
        assertEquals(key, idx.personKeyFor("+33 1 23 45 67 89"))
        assertEquals(setOf("+33612345678", "+33123456789"), idx.person(key)!!.numberKeys.toSet())
        assertEquals("Anna", idx.person(key)!!.name)
        assertEquals(true, idx.person(key)!!.isContact)
    }

    @Test fun failed_contacts_lookup_is_never_cached_as_unknown() {
        val calls = listOf(call("0612345678", CallType.INCOMING, at(2026, 1, 1), 60))
        val failed = CallLogIndex.build(calls, contacts = null, countryIso = "FR", zone = UTC)
        val p = failed.people.values.single()
        assertNull("no placeholder name when the lookup failed", p.name)
        assertNull("unknown, not 'not a contact'", p.isContact)
        assertFalse(failed.contactsKnown)
        // Once contacts load, the same call resolves to the contact and its name.
        val ok = CallLogIndex.build(calls, listOf(anna), "FR", UTC)
        assertEquals("Anna", ok.people.values.single().name)
        assertEquals(true, ok.people.values.single().isContact)
    }

    @Test fun cached_name_used_but_never_placeholder() {
        val idx = CallLogIndex.build(listOf(call("0612345678", CallType.MISSED, at(2026, 1, 1), name = "")), emptyList(), "FR", UTC)
        assertNull(idx.people.values.single().name)
        assertEquals(false, idx.people.values.single().isContact)
    }

    @Test fun duplicates_from_archive_are_dropped() {
        val d = at(2026, 2, 1)
        val idx = CallLogIndex.build(
            listOf(call("0612345678", CallType.OUTGOING, d, 10), call("+33612345678", CallType.OUTGOING, d + 400, 10)),
            emptyList(), "FR", UTC,
        )
        assertEquals(1, idx.calls.size)
    }

    @Test fun totals_per_sim_day_and_type() {
        val idx = CallLogIndex.build(
            listOf(
                call("0612345678", CallType.INCOMING, at(2026, 3, 2, 9), 120, sim = "a"),
                call("0612345678", CallType.OUTGOING, at(2026, 3, 2, 10), 60, sim = "b"),
                call("0612345678", CallType.MISSED, at(2026, 3, 2, 11), sim = "a"),
                call("0612345678", CallType.REJECTED, at(2026, 3, 3, 11), sim = "a"),
                call("0712345678", CallType.BLOCKED, at(2026, 3, 2, 12), sim = "a"),
            ),
            emptyList(), "FR", UTC,
        )
        val day = idx.day(java.time.LocalDate.of(2026, 3, 2))
        assertEquals(1, day.incoming); assertEquals(1, day.outgoing); assertEquals(1, day.missed); assertEquals(1, day.blocked)
        assertEquals(0, day.rejected)
        assertEquals(180, day.talkSec)
        val sims = idx.perSim()
        assertEquals(4, sims["a"]!!.total)
        assertEquals(60, sims["b"]!!.talkOutSec)
    }

    @Test fun heatmap_and_weekly_bars() {
        // 2026-03-02 is a Monday.
        val idx = CallLogIndex.build(
            listOf(
                call("0612345678", CallType.INCOMING, at(2026, 3, 2, 19), 100),
                call("0612345678", CallType.OUTGOING, at(2026, 3, 9, 19), 50),
                call("0612345678", CallType.OUTGOING, at(2026, 3, 10, 8), 10),
            ),
            emptyList(), "FR", UTC,
        )
        val h = idx.heatmap()
        assertEquals(2, h[DayOfWeek.MONDAY, 19])
        assertEquals(1, h[DayOfWeek.TUESDAY, 8])
        assertEquals(DayOfWeek.MONDAY to 19, h.peak())
        val weeks = idx.weeklyTalk(Period.between(java.time.LocalDate.of(2026, 3, 2), java.time.LocalDate.of(2026, 3, 23), UTC))
        assertEquals(3, weeks.size)
        assertEquals(100, weeks[0].talkInSec)
        assertEquals(60, weeks[1].talkOutSec)
        assertEquals(0, weeks[2].calls)
    }

    @Test fun unreturned_calls() {
        val idx = CallLogIndex.build(
            listOf(
                // Returned: you called back later.
                call("0611111111", CallType.MISSED, at(2026, 4, 1, 9)),
                call("0611111111", CallType.OUTGOING, at(2026, 4, 1, 10), 0),
                // Not returned: two missed calls after the last conversation.
                call("0622222222", CallType.INCOMING, at(2026, 4, 1, 8), 30),
                call("0622222222", CallType.MISSED, at(2026, 4, 2, 9)),
                call("0622222222", CallType.REJECTED, at(2026, 4, 3, 9)),
                // Hidden numbers can't be returned.
                call("", CallType.MISSED, at(2026, 4, 3, 10), hidden = true),
            ),
            emptyList(), "FR", UTC,
        )
        val u = idx.unreturned()
        assertEquals(1, u.size)
        assertEquals("n:+33622222222", u[0].person.key)
        assertEquals(2, u[0].count)
        assertEquals(CallType.REJECTED, u[0].last.type)
    }

    @Test fun rhythm_suggests_interval() {
        val now = at(2026, 6, 1)
        val calls = (0 until 8).map { i -> call("0612345678", CallType.OUTGOING, now - (i * 9L + 1) * CallLogIndex.DAY, 120) }
        val idx = CallLogIndex.build(calls, emptyList(), "FR", UTC)
        val r = idx.rhythm("n:+33612345678", now)!!
        assertEquals(9, r.usualGapDays)
        assertEquals(14, r.suggestedReminderDays)
        assertEquals(1, r.daysSinceLast)
        assertNull("too few calls", CallLogIndex.build(calls.take(3), emptyList(), "FR", UTC).rhythm("n:+33612345678", now))
    }

    @Test fun answers_after_six_pm() {
        val base = at(2026, 5, 4, 0)
        val calls = buildList {
            repeat(4) { add(call("0612345678", CallType.OUTGOING, base + it * CallLogIndex.DAY + 19 * 3_600_000L, 60)) }
            repeat(4) { add(call("0612345678", CallType.OUTGOING, base + it * CallLogIndex.DAY + 10 * 3_600_000L, 0)) }
            add(call("0612345678", CallType.OUTGOING, base + 5 * CallLogIndex.DAY + 14 * 3_600_000L, 0))
        }
        val idx = CallLogIndex.build(calls, emptyList(), "FR", UTC)
        val ins = idx.insights("n:+33612345678", base + 30 * CallLogIndex.DAY)!!
        assertEquals(AnswerWindow.EVENING, ins.answerWindow)
        assertNotNull(ins.lastCall)
        assertTrue(ins.averagePerMonth > 0)
    }

    @Test fun trend_direction() {
        val now = at(2026, 6, 1)
        val recent = (1..8).map { call("0612345678", CallType.INCOMING, now - it * CallLogIndex.DAY, 30) }
        val old = (1..2).map { call("0612345678", CallType.INCOMING, now - (100 + it) * CallLogIndex.DAY, 30) }
        val idx = CallLogIndex.build(recent + old, emptyList(), "FR", UTC)
        val t = idx.insights("n:+33612345678", now)!!.trend
        assertEquals(8, t.recent); assertEquals(2, t.previous)
        assertEquals(TrendDirection.UP, t.direction)
    }

    @Test fun top_people() {
        val idx = CallLogIndex.build(
            listOf(
                call("0611111111", CallType.OUTGOING, at(2026, 1, 1), 1000),
                call("0622222222", CallType.OUTGOING, at(2026, 1, 1, 13), 10),
                call("0622222222", CallType.OUTGOING, at(2026, 1, 1, 14), 10),
                call("0622222222", CallType.OUTGOING, at(2026, 1, 1, 15), 10),
            ),
            emptyList(), "FR", UTC,
        )
        assertEquals("n:+33611111111", idx.topByTalkTime(Period.ALL).first().person.key)
        assertEquals("n:+33622222222", idx.topByCount(Period.ALL).first().person.key)
        assertNotEquals(idx.topByTalkTime(Period.ALL).first(), idx.topByCount(Period.ALL).first())
    }

    @Test fun merge_prefers_provider_and_keeps_archive_only_rows() {
        val d = at(2026, 1, 1)
        val provider = listOf(call("0612345678", CallType.INCOMING, d, 10))
        val archive = listOf(call("+33612345678", CallType.INCOMING, d + 200, 10), call("0612345678", CallType.INCOMING, d - 86_400_000L, 5))
        val merged = HistoryMerge.merge(provider, archive)
        assertEquals(2, merged.size)
        assertEquals(provider[0].id, merged[0].id)
        assertEquals(5, merged[1].durationSec)
    }

    @Test fun range_delete_cutoffs() {
        val now = at(2026, 6, 15, 12)
        assertEquals(Long.MIN_VALUE, DeleteRange.ALL.since(now, UTC))
        assertEquals(at(2026, 6, 8, 12), DeleteRange.LAST_WEEK.since(now, UTC))
        assertEquals(at(2025, 6, 15, 12), DeleteRange.LAST_YEAR.since(now, UTC))
        assertEquals(at(2026, 6, 1, 0), DeleteRange.SINCE_DATE.since(now, UTC, java.time.LocalDate.of(2026, 6, 1)))
        val calls = listOf(call("1", CallType.INCOMING, at(2026, 6, 14)), call("1", CallType.INCOMING, at(2026, 1, 1)))
        assertEquals(1, DeleteRange.select(calls, DeleteRange.LAST_MONTH.since(now, UTC)).size)
    }

    @Test fun saved_filter_matching_and_codec() {
        val now = at(2026, 6, 15, 12)
        val f = HistoryFilter("Work", setOf(TypeGroup.OUTGOING), "sim2", FilterPeriod.LAST_7_DAYS, minDurationSec = 60)
        assertTrue(f.matches(call("1", CallType.OUTGOING, at(2026, 6, 14), 61, sim = "sim2"), now, UTC))
        assertFalse(f.matches(call("1", CallType.OUTGOING, at(2026, 6, 14), 59, sim = "sim2"), now, UTC))
        assertFalse(f.matches(call("1", CallType.OUTGOING, at(2026, 6, 14), 61, sim = "sim1"), now, UTC))
        assertFalse(f.matches(call("1", CallType.INCOMING, at(2026, 6, 14), 61, sim = "sim2"), now, UTC))
        assertFalse(f.matches(call("1", CallType.OUTGOING, at(2026, 6, 1), 61, sim = "sim2"), now, UTC))
        assertEquals(listOf(f), HistoryFilter.decodeList(HistoryFilter.encodeList(listOf(f))))
        assertEquals(emptyList<HistoryFilter>(), HistoryFilter.decodeList("not json"))
        assertTrue(HistoryFilter(name = "x").isEmpty)
        assertTrue(f.sameCriteria(f.copy(name = "Other")))
        assertFalse(f.sameCriteria(f.copy(minDurationSec = 30)))
    }
}
