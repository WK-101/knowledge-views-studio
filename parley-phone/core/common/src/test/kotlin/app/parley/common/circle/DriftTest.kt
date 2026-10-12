package app.parley.common.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class DriftTest {
    private val day = NaturalRhythm.DAY
    private val now = 2_000 * day
    private val utc = ZoneOffset.UTC
    private val recentStart = now - Drift.RECENT_DAYS * day

    /** Days in touch every [gap] days, from [fromDaysAgo] down to [toDaysAgo]. */
    private fun every(gap: Int, fromDaysAgo: Int, toDaysAgo: Int): List<Long> = (fromDaysAgo downTo toDaysAgo step gap).map { now - it * day }

    @Test fun weekly_last_year_and_monthly_since_is_quieter_than_usual() {
        val times = every(7, 540, 190) + every(30, 175, 10)
        val c = Drift.of("marco", times, now, utc)
        assertNotNull(c)
        c!!
        assertEquals(7, c.usualBefore)
        assertEquals(30, c.usualNow)
        assertEquals(recentStart, c.since)
        // "Add to your Circle" starts from how often you used to talk, with the natural rhythm's margin.
        assertEquals(11, Drift.suggestedDays(c))
    }

    @Test fun thresholds_stay_strict() {
        // Fewer than six days in touch before: no pattern to compare with.
        assertNull(Drift.of("a", every(7, 220, 190) + every(60, 170, 50), now, utc))
        // Talking about as often as before.
        assertNull(Drift.of("b", every(7, 540, 7), now, utc))
        // Less than doubled.
        assertNull(Drift.of("c", every(10, 540, 190) + every(15, 180, 15), now, utc))
        // From daily to every few days is not drifting apart (under two weeks now).
        assertNull(Drift.of("d", every(1, 540, 183) + every(5, 180, 5), now, utc))
        // In touch within the old usual gap: you're in touch now.
        assertNull(Drift.of("e", every(7, 540, 190) + every(60, 180, 60) + listOf(now - 2 * day), now, utc))
    }

    @Test fun silence_since_counts_as_the_gap_now() {
        val c = Drift.of("f", every(7, 540, 190), now, utc)!!
        assertEquals(7, c.usualBefore)
        assertEquals(190, c.usualNow)
    }

    @Test fun one_pick_a_week_skipping_season_fine_and_last_weeks() {
        val a = Drift.Candidate("a", 7, 30, recentStart)
        val b = Drift.Candidate("b", 7, 60, recentStart)
        val c = Drift.Candidate("c", 10, 40, recentStart)
        assertEquals(b, Drift.pick(listOf(a, b, c), emptySet(), null))
        assertEquals(a, Drift.pick(listOf(a, b, c), setOf("b"), "c"))
        assertNull(Drift.pick(listOf(a), setOf("a"), null))
        assertNull(Drift.pick(listOf(a), emptySet(), "a"))
    }

    @Test fun they_re_fine_lasts_a_year() {
        val r = KeepRhythm(fineUntil = Drift.fineUntil(now))
        assertEquals(true, r.isFine(now + 364 * day))
        assertEquals(false, r.isFine(now + 365 * day))
    }

    @Test fun the_digest_puts_someone_quieter_than_usual_in_the_serendipity_place() {
        val d = Drift.Candidate("marco", 7, 30, recentStart)
        val quiet = listOf(CircleDigest.Quiet("old", now - 400 * day))
        val with = CircleDigest.pick(emptyList(), emptyList(), now, quiet = quiet, drift = d)
        assertEquals(listOf(CircleDigest.Pick("marco", CircleDigest.Reason.DRIFT, drift = d)), with)
        // Without one (or while insights are hidden, callers pass none): the long-quiet pick as before.
        assertEquals(listOf(CircleDigest.Pick("old", CircleDigest.Reason.QUIET)), CircleDigest.pick(emptyList(), emptyList(), now, quiet = quiet))
        // Already in this digest as due: said once, as due.
        val due = CirclePlanner.Member("marco", 7, now - 30 * day)
        val picks = CircleDigest.pick(listOf(due), emptyList(), now, quiet = quiet, drift = d)
        assertEquals(listOf(CircleDigest.Reason.DUE, CircleDigest.Reason.QUIET), picks.map { it.reason })
    }
}
