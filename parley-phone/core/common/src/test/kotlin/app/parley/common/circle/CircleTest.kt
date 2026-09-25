package app.parley.common.circle

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.EventDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class CircleTest {
    private val day = NaturalRhythm.DAY
    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 9, 25).atStartOfDay(zone).toInstant().toEpochMilli() + 12 * 3_600_000L

    // R2 / R3

    @Test fun prompt_key_buckets_ten_minutes_per_channel_and_person() {
        val t = 1_000_000_000_000L - (1_000_000_000_000L % Interactions.BUCKET_MS)
        val a = Interactions.promptKey(InteractionChannel.WHATSAPP, "k1", t + 1_000)
        assertEquals(a, Interactions.promptKey(InteractionChannel.WHATSAPP, "k1", t + 9 * 60_000L))
        assertFalse(a == Interactions.promptKey(InteractionChannel.WHATSAPP, "k1", t + 11 * 60_000L))
        assertFalse(a == Interactions.promptKey(InteractionChannel.SIGNAL, "k1", t + 1_000))
        assertFalse(a == Interactions.promptKey(InteractionChannel.WHATSAPP, "k2", t + 1_000))
        assertFalse(Interactions.manualKey("a") == Interactions.manualKey("b"))
    }

    @Test fun channels_map_from_messenger_packages() {
        assertEquals(InteractionChannel.WHATSAPP, InteractionChannel.forPackage("com.whatsapp.w4b"))
        assertEquals(InteractionChannel.SIGNAL, InteractionChannel.forPackage("im.molly.app"))
        assertEquals(InteractionChannel.TELEGRAM, InteractionChannel.forPackage("org.thunderdog.challegram"))
        assertEquals(InteractionChannel.OTHER_APP, InteractionChannel.forPackage("com.viber.voip"))
        assertEquals(InteractionChannel.OTHER_APP, InteractionChannel.forPackage("ch.threema.app"))
        assertEquals(InteractionType.VIDEO, InteractionChannel.VIDEO.type)
    }

    @Test fun last_contact_is_the_later_of_answered_call_and_interaction() {
        assertNull(Interactions.lastContact(null, null))
        assertEquals(LastContact(5, ContactKind.CALL), Interactions.lastContact(5, 3L to InteractionType.MEET))
        assertEquals(LastContact(9, ContactKind.MEET), Interactions.lastContact(5, 9L to InteractionType.MEET))
        assertEquals(LastContact(9, ContactKind.VIDEO), Interactions.lastContact(null, 9L to InteractionType.VIDEO))
    }

    // G6: any interaction resets the clock.
    @Test fun an_interaction_makes_someone_fine_again() {
        val overdue = CirclePlanner.Member("k", 14, last = now - 40 * day)
        assertEquals(CircleStatus.DUE, CirclePlanner.status(overdue, now))
        val last = Interactions.lastContact(now - 40 * day, (now - day) to InteractionType.VIDEO)!!.time
        assertEquals(CircleStatus.FINE, CirclePlanner.status(overdue.copy(last = last), now))
    }

    // R4

    @Test fun natural_rhythm_is_median_gap_times_one_and_a_half_at_least_a_week() {
        // Every 10 days -> 15.
        val every10 = (0 until 8).map { now - it * 10 * day }
        assertEquals(15, NaturalRhythm.learn(every10, now, zone))
        // Daily calls still give a week.
        val daily = (0 until 30).map { now - it * day }
        assertEquals(NaturalRhythm.MIN_DAYS, NaturalRhythm.learn(daily, now, zone))
        // Several contacts on one day count once; too little history -> null.
        assertNull(NaturalRhythm.learn(listOf(now, now - 1000, now - 20 * day, now - 40 * day), now, zone))
        // Even count: median of the two middle gaps (10, 20 -> 15 -> 22.5 -> 23).
        val mixed = listOf(now, now - 10 * day, now - 20 * day, now - 40 * day, now - 60 * day)
        assertEquals(23, NaturalRhythm.learn(mixed, now, zone))
        // Old history (over two years) is ignored.
        assertNull(NaturalRhythm.learn((0 until 10).map { now - (800 + it * 10) * day }, now, zone))
    }

    @Test fun natural_rhythm_is_relearned_monthly_and_keeps_the_old_value_without_history() {
        val r = KeepRhythm(RhythmMode.NATURAL, learnedDays = 20, learnedAt = now - 10 * day)
        assertFalse(r.needsRelearn(now))
        assertEquals(r, NaturalRhythm.relearn(r, emptyList(), now, zone))
        val old = r.copy(learnedAt = now - 31 * day)
        assertTrue(old.needsRelearn(now))
        val next = NaturalRhythm.relearn(old, emptyList(), now, zone)
        assertEquals(20, next.learnedDays)
        assertEquals(now, next.learnedAt)
        assertEquals(15, NaturalRhythm.relearn(old, (0 until 8).map { now - it * 10 * day }, now, zone).learnedDays)
        assertFalse(KeepRhythm().needsRelearn(now))
        assertEquals(30, KeepRhythm().days(30))
        assertEquals(20, r.days(30))
        assertEquals(30, KeepRhythm(RhythmMode.NATURAL).days(30))
    }

    @Test fun rhythm_round_trips_and_defaults_store_nothing() {
        assertNull(KeepRhythm().encode())
        val r = KeepRhythm(RhythmMode.NATURAL, 12, 5, 99)
        assertEquals(r, KeepRhythm.decode(r.encode()))
        assertEquals(KeepRhythm(), KeepRhythm.decode("{broken"))
        // Merging keeps the destination's mode, and only keeps a snooze both had.
        val merged = KeepRhythm.decode(KeepRhythm.merge(KeepRhythm(RhythmMode.NATURAL, snoozedUntil = 50).encode(), KeepRhythm(snoozedUntil = 30).encode()))
        assertEquals(RhythmMode.NATURAL, merged.mode)
        assertEquals(30L, merged.snoozedUntil)
        assertNull(KeepRhythm.decode(KeepRhythm.merge(KeepRhythm(RhythmMode.NATURAL, snoozedUntil = 50).encode(), KeepRhythm(RhythmMode.NATURAL).encode())).snoozedUntil)
    }

    @Test fun status_due_soon_fine_and_snoozed() {
        fun st(last: Long?, days: Int = 30, snooze: Long? = null) = CirclePlanner.status(CirclePlanner.Member("k", days, last, snooze), now)
        assertEquals(CircleStatus.DUE, st(null))
        assertEquals(CircleStatus.DUE, st(now - 30 * day))
        assertEquals(CircleStatus.SOON, st(now - 25 * day))
        assertEquals(CircleStatus.FINE, st(now - 20 * day))
        // Short rhythms: "soon" is at least two days.
        assertEquals(CircleStatus.SOON, st(now - 6 * day, days = 7))
        // "Not now" hides someone until the snooze ends.
        assertEquals(CircleStatus.FINE, st(now - 90 * day, snooze = now + day))
        assertEquals(CircleStatus.DUE, st(now - 90 * day, snooze = now - day))
    }

    @Test fun not_now_doubles_the_gap_and_never_escalates() {
        val m = CirclePlanner.Member("k", 30, last = now - 30 * day)
        val until = CirclePlanner.snooze(now, m.days)
        assertEquals(now + 30 * day, until)
        // Due again only after a second full gap from the last contact.
        val snoozed = m.copy(snoozedUntil = until)
        assertEquals(CircleStatus.FINE, CirclePlanner.status(snoozed, now + 29 * day))
        assertEquals(CircleStatus.DUE, CirclePlanner.status(snoozed, now + 30 * day))
        assertEquals(until, CirclePlanner.dueAt(snoozed, now))
    }

    @Test fun sort_puts_most_urgent_first() {
        val a = CirclePlanner.Member("a", 30, now - 10 * day)
        val b = CirclePlanner.Member("b", 30, now - 60 * day)
        val c = CirclePlanner.Member("c", 30, now - 26 * day)
        val d = CirclePlanner.Member("d", 30, now - 35 * day)
        assertEquals(listOf("b", "d", "c", "a"), CirclePlanner.sort(listOf(a, b, c, d), now).map { it.lookupKey })
    }

    @Test fun digest_picks_one_due_one_date_one_quiet_each_once() {
        val due = CirclePlanner.Member("due", 14, now - 30 * day)
        val quiet = CirclePlanner.Member("quiet", 365, now - 200 * day)
        val fine = CirclePlanner.Member("fine", 30, now - 2 * day)
        val picks = CircleDigest.pick(
            listOf(fine, quiet, due), listOf(CircleDigest.UpcomingDate("bday", 3), CircleDigest.UpcomingDate("due", 1)), now,
        )
        assertEquals(
            listOf(CircleDigest.Pick("due", CircleDigest.Reason.DUE), CircleDigest.Pick("bday", CircleDigest.Reason.DATE), CircleDigest.Pick("quiet", CircleDigest.Reason.QUIET)),
            picks,
        )
        // Never the same quiet person twice in a row; dates too far ahead are left out.
        val next = CircleDigest.pick(listOf(quiet), listOf(CircleDigest.UpcomingDate("bday", 12)), now, lastQuiet = "quiet")
        assertTrue(next.isEmpty())
        // Snoozed people aren't picked.
        assertTrue(CircleDigest.pick(listOf(due.copy(snoozedUntil = now + day)), emptyList(), now).isEmpty())
    }

    @Test fun digest_goes_out_on_sundays_once_and_catches_up_after_a_missed_one() {
        val sunday = LocalDate.of(2026, 9, 27)
        assertTrue(CircleDigest.isDigestDay(sunday, null))
        assertFalse(CircleDigest.isDigestDay(sunday, sunday))
        assertTrue(CircleDigest.isDigestDay(sunday, sunday.minusDays(7)))
        assertFalse(CircleDigest.isDigestDay(sunday.plusDays(1), null))
        assertFalse(CircleDigest.isDigestDay(sunday.plusDays(1), sunday))
        // The phone was off last Sunday: Monday sends it.
        assertTrue(CircleDigest.isDigestDay(sunday.plusDays(1), sunday.minusDays(7)))
    }

    @Test fun as_due_respects_the_weekly_cap_and_skips_recent_reminders() {
        val a = CirclePlanner.Member("a", 14, now - 40 * day)
        val b = CirclePlanner.Member("b", 14, now - 30 * day)
        val c = CirclePlanner.Member("c", 14, now - 20 * day)
        assertEquals(listOf("a", "b"), CircleDigest.asDue(listOf(c, b, a), emptyMap(), now, cap = 2, sentThisWeek = 0))
        assertEquals(listOf("a"), CircleDigest.asDue(listOf(c, b, a), emptyMap(), now, cap = 3, sentThisWeek = 2))
        assertTrue(CircleDigest.asDue(listOf(a), emptyMap(), now, cap = 3, sentThisWeek = 3).isEmpty())
        assertEquals(listOf("b", "c"), CircleDigest.asDue(listOf(c, b, a), mapOf("a" to now - 2 * day), now, cap = 3, sentThisWeek = 0))
        assertEquals(LocalDate.of(2026, 9, 21), CircleDigest.weekOf(LocalDate.of(2026, 9, 27)))
    }

    @Test fun config_round_trips_and_repairs_bad_values() {
        val c = CircleConfig(ReminderDelivery.AS_DUE, weeklyCap = 5, dateLeadDays = 3).withLogMode(InteractionChannel.SMS, LogMode.ALWAYS)
        assertEquals(c, CircleConfig.decode(CircleConfig.encode(c)))
        assertEquals(LogMode.ALWAYS, c.logMode(InteractionChannel.SMS))
        assertEquals(LogMode.ASK, c.logMode(InteractionChannel.SIGNAL))
        assertEquals(CircleConfig(), CircleConfig.decode("nonsense"))
        val bad = CircleConfig.decode("""{"weeklyCap":99,"dateLeadDays":5}""")
        assertEquals(3, bad.weeklyCap)
        assertEquals(0, bad.dateLeadDays)
    }

    // R5

    @Test fun date_reminders_fire_on_the_lead_day_and_the_day_only() {
        val bday = EventDate(1990, 10, 2)
        val today = LocalDate.of(2026, 9, 25)
        assertEquals(DateReminders.Fire.LEAD, DateReminders.fire(bday, today, 7))
        assertNull(DateReminders.fire(bday, today.plusDays(1), 7))
        assertNull(DateReminders.fire(bday, today.plusDays(6), 7))
        assertEquals(DateReminders.Fire.ON_DAY, DateReminders.fire(bday, today.plusDays(7), 7))
        assertNull(DateReminders.fire(bday, today, 0))
        assertEquals(DateReminders.Fire.ON_DAY, DateReminders.fire(bday, LocalDate.of(2026, 10, 2), 0))
    }

    @Test fun lead_and_day_share_one_occasion_across_new_year() {
        val d = EventDate(null, 1, 1)
        val key = DateReminders.eventKey(3, d)
        val lead = DateReminders.occurrence(7, key, d, LocalDate.of(2026, 12, 29))
        val onDay = DateReminders.occurrence(7, key, d, LocalDate.of(2027, 1, 1))
        assertEquals(lead, onDay)
        assertEquals("7:3-0101:2027", lead)
        assertEquals("birthday:7:3-0101", DateReminders.tag(7, key))
        assertEquals("nudge:10005", DateReminders.nudgeTag(10005))
        // A birthday and an anniversary of one person have different tags (G5).
        assertFalse(DateReminders.tag(7, DateReminders.eventKey(1, d)) == DateReminders.tag(7, DateReminders.eventKey(3, d)))
    }

    @Test fun remembered_occasions_are_pruned_after_a_year() {
        val kept = DateReminders.prune(listOf("a|${now - day}", "b|${now - 500 * day}", "c|junk"), now)
        assertEquals(setOf("a|${now - day}"), kept)
        assertTrue(DateReminders.has(kept, "a"))
        assertFalse(DateReminders.has(kept, "b"))
    }

    // R1

    @Test fun suggestions_are_the_most_called_outside_the_circle() {
        val cands = (1..15).map { CircleSuggestions.Candidate("k$it", it, if (it % 2 == 0) it * 2 else null) } +
            CircleSuggestions.Candidate("", 99, null)
        val picked = CircleSuggestions.pick(cands, inCircle = setOf("k15"))
        assertEquals(10, picked.size)
        assertEquals("k14", picked.first().lookupKey)
        assertFalse(picked.any { it.lookupKey == "k15" || it.lookupKey.isEmpty() })
        assertTrue(CircleSuggestions.pick(listOf(CircleSuggestions.Candidate("x", 1, null)), emptySet()).isEmpty())
        assertEquals(CircleSuggestions.DEFAULT_DAYS, CircleSuggestions.daysFor(CircleSuggestions.Candidate("x", 3, null)))
        assertEquals(NaturalRhythm.MIN_DAYS, CircleSuggestions.daysFor(CircleSuggestions.Candidate("x", 3, 2)))
    }

    // R2 timeline

    @Test fun timeline_merges_sources_and_groups_by_month_newest_first() {
        val sep = LocalDate.of(2026, 9, 10).atStartOfDay(zone).toInstant().toEpochMilli()
        val aug = LocalDate.of(2026, 8, 3).atStartOfDay(zone).toInstant().toEpochMilli()
        val call = TimelineEntry.Call(CallEntry(1, "+1", null, CallType.INCOMING, sep, 60, null, false, false))
        val met = TimelineEntry.Logged(2, sep, InteractionType.MEET, null, "Coffee")
        val note = TimelineEntry.Note(3, aug, "Moving in May")
        val months = Timeline.group(listOf(note, call, met), zone)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), months.map { it.month })
        assertEquals(listOf<TimelineEntry>(met, call), months[0].entries)
        assertEquals(listOf<TimelineEntry>(note), months[1].entries)
        assertTrue(Timeline.group(emptyList(), zone).isEmpty())
    }

    @Test fun timeline_dates_fall_between_the_oldest_entry_and_today() {
        val bday = EventDate(2025, 3, 5)
        assertEquals(
            listOf(LocalDate.of(2025, 3, 5), LocalDate.of(2026, 3, 5)),
            Timeline.occurrences(bday, LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 25)),
        )
        assertEquals(listOf(LocalDate.of(2026, 3, 5)), Timeline.occurrences(EventDate(null, 3, 5), LocalDate.of(2025, 6, 1), LocalDate.of(2026, 9, 25)))
        assertTrue(Timeline.dates(listOf(Triple(3, null, bday)), emptyList(), LocalDate.of(2026, 9, 25), zone).isEmpty())
        val t = LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dates = Timeline.dates(listOf(Triple(3, null, bday)), listOf(TimelineEntry.Note(1, t, "x")), LocalDate.of(2026, 9, 25), zone)
        assertEquals(2, dates.size)
    }
}
