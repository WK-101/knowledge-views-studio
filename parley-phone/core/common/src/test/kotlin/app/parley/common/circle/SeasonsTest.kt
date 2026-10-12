package app.parley.common.circle

import app.parley.common.Codecs
import app.parley.common.extras.LabelPolicies
import app.parley.common.extras.LabelPolicy
import app.parley.common.people.Chapter
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonsTest {
    private val day = NaturalRhythm.DAY
    private val now = 1_000 * day

    @Test fun a_season_counts_while_it_runs_then_the_usual_rhythm_comes_back_by_itself() {
        val r = Seasons.set(KeepRhythm(), 3, now + 10 * day)
        assertEquals(3, r.days(14, now))
        assertEquals(14, r.days(14))
        assertTrue(r.inSeason(now))
        // Over: the usual gap counts at once, before anything is cleared.
        assertEquals(14, r.days(14, now + 10 * day))
        assertTrue(r.seasonEnded(now + 10 * day))
        assertFalse(r.seasonEnded(now))
        assertEquals(KeepRhythm(), r.endSeason())
        // Ending it now from the dialog.
        assertEquals(KeepRhythm(), Seasons.set(r, null, null))
    }

    @Test fun a_season_over_a_natural_rhythm_keeps_what_was_learned() {
        val r = KeepRhythm(RhythmMode.NATURAL, learnedDays = 20, learnedAt = now).copy(seasonDays = 7, seasonUntil = now + day)
        assertEquals(7, r.days(30, now))
        assertEquals(20, r.days(30, now + day))
    }

    @Test fun a_season_and_a_fine_mark_travel_in_the_same_json() {
        val r = KeepRhythm(seasonDays = 3, seasonUntil = now, fineUntil = now + day)
        assertEquals(r, KeepRhythm.decode(r.encode()))
    }

    /** KeepRhythm as Parley 6.7 knew it (no season, no "They're fine"). */
    @Serializable
    private data class KeepRhythm67(
        val mode: RhythmMode = RhythmMode.EVERY,
        val learnedDays: Int? = null,
        val learnedAt: Long? = null,
        val snoozedUntil: Long? = null,
    )

    /** LabelPolicy as Parley 6.7 knew it (no Circle switch). */
    @Serializable
    private data class LabelPolicy67(
        val simId: String? = null,
        val rhythmDays: Int? = null,
        val allowThroughDnd: Boolean = false,
        val vibration: String? = null,
        val autoAnswer: Boolean = false,
    )

    @Test fun an_older_parley_reading_a_newer_backup_keeps_the_usual_rhythm_and_the_suggestion() {
        val newer = KeepRhythm(RhythmMode.EVERY, snoozedUntil = now, seasonDays = 3, seasonUntil = now + day, fineUntil = now).encode()!!
        val old = Codecs.stored.decodeFromString(KeepRhythm67.serializer(), newer)
        assertEquals(KeepRhythm67(RhythmMode.EVERY, snoozedUntil = now), old)
        val policy = Codecs.stored.encodeToString(LabelPolicy.serializer(), LabelPolicy(rhythmDays = 14, circleFromLabel = true))
        assertEquals(LabelPolicy67(rhythmDays = 14), Codecs.stored.decodeFromString(LabelPolicy67.serializer(), policy))
        // And this version reads what an older one wrote: no season, the switch off.
        val older = Codecs.stored.encodeToString(KeepRhythm67.serializer(), KeepRhythm67(learnedDays = 9))
        assertEquals(KeepRhythm(learnedDays = 9), KeepRhythm.decode(older))
        assertFalse(LabelPolicies.decode("""{"Family":{"rhythmDays":14}}""").getValue("Family").circleFromLabel)
    }

    @Test fun a_merge_keeps_a_fine_mark_only_when_both_had_one() {
        val a = KeepRhythm(fineUntil = now + 5 * day).encode()
        val b = KeepRhythm(fineUntil = now + 2 * day).encode()
        assertEquals(now + 2 * day, KeepRhythm.decode(KeepRhythm.merge(a, b)).fineUntil)
        assertNull(KeepRhythm.decode(KeepRhythm.merge(a, KeepRhythm(snoozedUntil = now).encode())).fineUntil)
    }

    @Test fun only_switched_on_labels_with_a_rhythm_feed_the_circle_and_a_chapter_only_while_it_runs() {
        val policies = mapOf(
            "Family" to LabelPolicy(rhythmDays = 14, circleFromLabel = true),
            // A rhythm from before: still only a suggestion. Nothing turns on by an update.
            "Work" to LabelPolicy(rhythmDays = 30),
            "Hospital stay" to LabelPolicy(rhythmDays = 3, circleFromLabel = true),
            "Ended" to LabelPolicy(rhythmDays = 3, circleFromLabel = true),
            "No rhythm" to LabelPolicy(circleFromLabel = true),
        )
        val chapters = mapOf("Hospital stay" to Chapter(0, now + 5 * day), "Ended" to Chapter(0, now))
        val feeds = CircleFeeds.of(policies, chapters, now)
        assertEquals(listOf(LabelFeed("Family", 14), LabelFeed("Hospital stay", 3, now + 5 * day)), feeds)
        assertFalse(LabelPolicy(rhythmDays = 14).feedsCircle)
        assertTrue(LabelPolicy(circleFromLabel = true).let { !it.isEmpty && !it.feedsCircle })
    }

    @Test fun own_rhythm_wins_each_person_counts_once_with_the_shortest() {
        val family = LabelFeed("Family", 14)
        val friends = LabelFeed("Friends", 7)
        // Through labels only: the shortest, and which label says so.
        assertEquals(CircleRhythm(7, "Friends"), CircleFeeds.effective(null, KeepRhythm(), listOf(family, friends), now))
        // A rhythm of their own wins, even a longer one.
        assertEquals(CircleRhythm(30), CircleFeeds.effective(30, KeepRhythm(), listOf(family, friends), now))
        // Nobody is in the Circle without a rhythm or a label.
        assertNull(CircleFeeds.effective(null, KeepRhythm(), emptyList(), now))
        // Switching the label off (no feed any more) takes out only those who were in through it.
        assertNull(CircleFeeds.effective(null, KeepRhythm(), emptyList(), now))
        assertEquals(CircleRhythm(30), CircleFeeds.effective(30, KeepRhythm(), emptyList(), now))
    }

    @Test fun a_chapter_carries_a_closer_rhythm_while_it_runs_and_an_own_season_wins() {
        val stay = LabelFeed("Hospital stay", 3, now + 5 * day)
        assertEquals(CircleRhythm(3, "Hospital stay", now + 5 * day), CircleFeeds.effective(14, KeepRhythm(), listOf(stay), now))
        assertEquals(CircleRhythm(14), CircleFeeds.effective(14, KeepRhythm(), listOf(stay), now + 5 * day))
        // Not closer than their own: their own stays.
        assertEquals(CircleRhythm(2), CircleFeeds.effective(2, KeepRhythm(), listOf(stay), now))
        val own = Seasons.set(KeepRhythm(), 7, now + 2 * day)
        assertEquals(CircleRhythm(7, until = now + 2 * day), CircleFeeds.effective(14, own, listOf(stay), now))
        // Someone only in the Chapter's label leaves the Circle when it ends.
        assertNull(CircleFeeds.effective(null, KeepRhythm(), listOf(stay), now + 5 * day))
    }

    @Test fun members_of_feeding_labels_are_gathered_per_person() {
        val family = LabelFeed("Family", 14)
        val friends = LabelFeed("Friends", 7)
        val members = mapOf("Family" to listOf("ana", "bo", ""), "Friends" to listOf("ana", "ana"))
        val by = CircleFeeds.byPerson(listOf(family, friends)) { members[it].orEmpty() }
        assertEquals(mapOf("ana" to listOf(family, friends), "bo" to listOf(family)), by)
    }
}
