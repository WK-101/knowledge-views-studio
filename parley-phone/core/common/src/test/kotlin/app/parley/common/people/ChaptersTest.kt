package app.parley.common.people

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Chapters: when a label's end comes, what is left of it, who joined for it, and what the end may offer. */
class ChaptersTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(date: String, hour: Int = 10): Long = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun an_end_on_a_date_keeps_that_whole_day_in_the_chapter() {
        val now = at("2026-10-04")
        val ends = Chapters.endsAt(Chapters.Length.OnDate(LocalDate.parse("2026-10-20")), now, zone)
        assertEquals(LocalDate.parse("2026-10-21").atStartOfDay(zone).toInstant().toEpochMilli(), ends)
        val c = Chapters.begin(emptyList(), ends, now)
        assertFalse(Chapters.isOver(c, at("2026-10-20", 23)))
        assertTrue(Chapters.isOver(c, at("2026-10-21", 0)))
        assertEquals(LocalDate.parse("2026-10-20"), Chapters.lastDay(c, zone))
    }

    @Test fun an_end_after_weeks_or_months_counts_from_today_and_is_bounded() {
        val now = at("2026-01-31")
        fun last(length: Chapters.Length) = Chapters.lastDay(Chapters.begin(emptyList(), Chapters.endsAt(length, now, zone), now), zone)
        assertEquals(LocalDate.parse("2026-02-14"), last(Chapters.Length.After(2, Chapters.Span.WEEKS)))
        // A month after 31 January is the end of February.
        assertEquals(LocalDate.parse("2026-02-28"), last(Chapters.Length.After(1, Chapters.Span.MONTHS)))
        // Nonsense counts are held to 1…max.
        val zero = Chapters.endsAt(Chapters.Length.After(0, Chapters.Span.WEEKS), now, zone)
        assertEquals(Chapters.endsAt(Chapters.Length.After(1, Chapters.Span.WEEKS), now, zone), zero)
        val huge = Chapters.endsAt(Chapters.Length.After(999, Chapters.Span.MONTHS), now, zone)
        assertEquals(Chapters.endsAt(Chapters.Length.After(Chapters.MAX_MONTHS, Chapters.Span.MONTHS), now, zone), huge)
        assertFalse(Chapters.validEnd(LocalDate.parse("2026-01-30"), now, zone))
        assertTrue(Chapters.validEnd(LocalDate.parse("2026-01-31"), now, zone))
    }

    @Test fun what_is_left_reads_in_days_then_weeks_then_months() {
        val now = at("2026-10-04")
        fun left(last: String): Chapters.Remaining {
            val ends = Chapters.endsAt(Chapters.Length.OnDate(LocalDate.parse(last)), now, zone)
            return Chapters.remaining(Chapters.begin(emptyList(), ends, now), now, zone)
        }
        assertEquals(Chapters.Remaining.LastDay, left("2026-10-04"))
        assertEquals(Chapters.Remaining.Days(1), left("2026-10-05"))
        assertEquals(Chapters.Remaining.Days(13), left("2026-10-17"))
        assertEquals(Chapters.Remaining.Weeks(2), left("2026-10-18"))
        assertEquals(Chapters.Remaining.Weeks(3), left("2026-10-25"))
        assertEquals(Chapters.Remaining.Months(3), left("2027-01-10"))
        val over = Chapters.begin(emptyList(), now - 1, now - 1000)
        assertEquals(Chapters.Remaining.Ended, Chapters.remaining(over, now, zone))
    }

    @Test fun an_ended_chapter_is_announced_once() {
        val now = at("2026-10-04")
        val ended = Chapters.begin(emptyList(), now - 1, now - 1000)
        val running = Chapters.begin(emptyList(), now + 1000, now - 1000)
        val map = mapOf("Trip" to ended, "Move" to running)
        assertEquals(setOf("Trip"), Chapters.toAnnounce(map, now))
        val asked = map + ("Trip" to ended.copy(askedAt = now))
        assertTrue("Trip" !in Chapters.toAnnounce(asked, now + 86_400_000L))
        // A new end in the future asks again when that one comes; an end moved into the past doesn't ask twice.
        assertEquals(0L, Chapters.withEnd(ended.copy(askedAt = now), now + 1, now).askedAt)
        assertEquals(now, Chapters.withEnd(ended.copy(askedAt = now), now - 5, now).askedAt)
    }

    @Test fun who_joined_for_the_chapter_is_who_was_not_there_at_its_start() {
        val before = listOf(Chapters.Member(1, "k1"), Chapters.Member(2, "k2"))
        val c = Chapters.begin(before, 100, 0)
        val now = listOf(
            Chapters.Member(1, "k1"),
            // The same person whose lookup key changed since: still there by id.
            Chapters.Member(2, "k2-new"),
            Chapters.Member(3, "k3"),
            Chapters.Member(4, "k4", temporary = true),
            Chapters.Member(-5, "parley-private:5", private = true),
        )
        assertEquals(listOf(3L, 4L, -5L), Chapters.joinedDuring(c, now).map { it.id })
        // Archive takes visible, lasting contacts only; temporary ones are Delete's, private ones stay as they are.
        assertEquals(listOf(3L), Chapters.toArchive(c, now).map { it.id })
        assertEquals(listOf(4L), Chapters.toDelete(c, now).map { it.id })
        assertEquals(
            listOf(Chapters.Outcome.KEEP, Chapters.Outcome.ARCHIVE_ADDED, Chapters.Outcome.REMOVE_LABEL, Chapters.Outcome.DELETE_TEMPORARY),
            Chapters.outcomes(c, now),
        )
        // Nobody joined: only Keep and Remove the label.
        assertEquals(listOf(Chapters.Outcome.KEEP, Chapters.Outcome.REMOVE_LABEL), Chapters.outcomes(c, before))
    }

    @Test fun a_restored_chapter_never_counts_anyone_as_joined_for_it() {
        val c = Chapters.begin(listOf(Chapters.Member(1, "k1")), 100, 0)
        val restored = Chapters.forBackup(mapOf("Trip" to c)).getValue("Trip")
        assertFalse(restored.membersKnown)
        assertTrue(restored.beforeKeys.isEmpty())
        assertTrue(Chapters.joinedDuring(restored, listOf(Chapters.Member(9, "k9"))).isEmpty())
        // What is here wins over the backup.
        val here = mapOf("Trip" to c)
        assertEquals(c, Chapters.merge(here, mapOf("Trip" to restored)).getValue("Trip"))
        assertEquals(restored, Chapters.merge(emptyMap(), mapOf("Trip" to restored)).getValue("Trip"))
    }

    @Test fun only_the_owner_decides_a_shared_label() {
        assertTrue(Chapters.decidesHere(shared = false, owner = null))
        assertTrue(Chapters.decidesHere(shared = true, owner = true))
        assertFalse(Chapters.decidesHere(shared = true, owner = false))
        assertFalse(Chapters.decidesHere(shared = true, owner = null))
    }

    @Test fun chapters_follow_renames_merges_and_deletes_and_survive_storage() {
        val a = Chapters.begin(listOf(Chapters.Member(1, "k1")), 100, 0)
        val b = Chapters.begin(emptyList(), 200, 0)
        val map = mapOf("Trip" to a, "Move" to b)
        assertEquals(setOf("Lisbon", "Move"), Chapters.renamed(map, mapOf("Trip" to "Lisbon")).keys)
        // Merged into a label with its own chapter: the target's stays.
        assertEquals(b, Chapters.renamed(map, mapOf("Trip" to "Move")).getValue("Move"))
        assertEquals(setOf("Move"), Chapters.deleted(map, setOf("Trip")).keys)
        assertEquals(map, Chapters.decode(Chapters.encode(map)))
        assertTrue(Chapters.decode("not json").isEmpty())
    }
}
