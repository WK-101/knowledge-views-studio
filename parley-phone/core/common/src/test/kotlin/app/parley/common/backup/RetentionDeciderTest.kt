package app.parley.common.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class RetentionDeciderTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now: Instant = LocalDateTime.of(2026, 9, 24, 12, 0).toInstant(ZoneOffset.UTC)

    private fun file(t: Instant) = BackupFile(RetentionDecider.fileName(t, zone), t.toEpochMilli())
    private fun daysAgo(d: Long, hour: Int = 3): Instant = now.atZone(zone).toLocalDate().minusDays(d).atTime(hour, 0).toInstant(ZoneOffset.UTC)

    @Test fun nameRoundTrip() {
        val t = Instant.parse("2026-01-02T03:04:05Z")
        val name = RetentionDecider.fileName(t, zone)
        assertEquals("parley-backup-20260102-030405.parley", name)
        assertEquals(t, RetentionDecider.parseName(name, zone))
        assertNull(RetentionDecider.parseName("parley-backup-20261399-000000.parley", zone))
        assertNull(RetentionDecider.parseName("parley-backup-20260102-030405.parley.partial", zone))
    }

    @Test fun simpleKeepsLastN() {
        val files = (0L until 10).map { file(daysAgo(it)) }.shuffled()
        val d = RetentionDecider.decide(files, RetentionPolicy.Simple(3), now, zone)
        assertEquals((0L until 3).map { file(daysAgo(it)) }, d.keep)
        assertEquals(7, d.delete.size)
        assertEquals(file(daysAgo(9)), d.delete.last())
    }

    @Test fun allZeroPoliciesStillKeepNewest() {
        val files = (0L until 5).map { file(daysAgo(it)) }
        for (p in listOf(RetentionPolicy.Simple(0), RetentionPolicy.Periodic(0, 0, 0, 0))) {
            val d = RetentionDecider.decide(files, p, now, zone)
            assertEquals(listOf(file(daysAgo(0))), d.keep)
            assertEquals(4, d.delete.size)
        }
    }

    @Test fun grandfatherFatherSon() {
        // Two backups a day (03:00 and 08:00, both before 'now') for ~2.5 years.
        val files = (0L until 900).flatMap { listOf(file(daysAgo(it, 3)), file(daysAgo(it, 8))) }
        val d = RetentionDecider.decide(files, RetentionPolicy.Periodic(daily = 7, weekly = 4, monthly = 12, yearly = 3), now, zone)
        val keptTimes = d.keep.map { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.timestamp), zone) }

        // Daily: the newest backup of each of the last 7 days (the 08:00 one).
        for (i in 0L until 7) assertTrue("day $i", file(daysAgo(i, 8)) in d.keep)
        for (i in 0L until 7) assertFalse("older same-day copy $i", file(daysAgo(i, 3)) in d.keep)
        // Monthly: newest backup of each of the last 12 months is kept.
        val months = keptTimes.map { it.year * 100 + it.monthValue }.toSet()
        var ym = java.time.YearMonth.from(LocalDateTime.ofInstant(now, zone))
        repeat(12) { assertTrue("month $ym", ym.year * 100 + ym.monthValue in months); ym = ym.minusMonths(1) }
        // Yearly: 2026, 2025, 2024 present; the oldest kept is the newest backup of 2024 (Dec 31).
        assertEquals(setOf(2024, 2025, 2026), keptTimes.map { it.year }.toSet())
        val oldest = keptTimes.minOrNull()!!
        assertEquals(LocalDateTime.of(2024, 12, 31, 8, 0), oldest)
        // Kept count is bounded by the policy (buckets overlap).
        assertTrue(d.keep.size <= 7 + 4 + 12 + 3)
        assertEquals(files.size, d.keep.size + d.delete.size)
        assertTrue(d.keep.intersect(d.delete.toSet()).isEmpty())
    }

    @Test fun weeklyUsesIsoWeeks() {
        // 2026-01-04 is a Sunday (ISO week 2026-W01), 2026-01-05 is Monday (W02).
        val sun = file(Instant.parse("2026-01-04T10:00:00Z"))
        val sat = file(Instant.parse("2026-01-03T10:00:00Z"))
        val mon = file(Instant.parse("2026-01-05T10:00:00Z"))
        val d = RetentionDecider.decide(listOf(sat, sun, mon), RetentionPolicy.Periodic(0, 2, 0, 0), now, zone)
        assertEquals(listOf(mon, sun), d.keep)
        assertEquals(listOf(sat), d.delete)
    }

    @Test fun futureDatedFilesAreNeverDeletedAndDontCount() {
        val future = file(now.plus(Duration.ofDays(400)))
        val files = listOf(future) + (0L until 4).map { file(daysAgo(it)) }
        val d = RetentionDecider.decide(files, RetentionPolicy.Simple(1), now, zone)
        assertEquals(listOf(future, file(daysAgo(0))), d.keep)
        assertEquals(3, d.delete.size)
        val onlyFuture = RetentionDecider.decide(listOf(future), RetentionPolicy.Simple(0), now, zone)
        assertEquals(listOf(future), onlyFuture.keep)
    }

    @Test fun nonMatchingNamesAreUntouched() {
        val others = listOf(
            BackupFile("notes.txt", 0),
            BackupFile("parley-backup-20200101-000000.parley.partial", 0),
            BackupFile("Parley-backup-20200101-000000.parley", 0),
            BackupFile("parley-backup-20200101-000000.vcf", 0),
            BackupFile("parley-backup-2020-01-01.parley", 0),
        )
        val ours = (0L until 3).map { file(daysAgo(it)) }
        val d = RetentionDecider.decide(others + ours, RetentionPolicy.Simple(1), now, zone)
        assertEquals(others, d.ignored)
        assertTrue(d.delete.none { it in others })
        assertTrue(d.keep.none { it in others })
        assertEquals(2, d.delete.size)
    }

    @Test fun emptyFolder() {
        val d = RetentionDecider.decide(emptyList(), RetentionPolicy.Periodic(7, 4, 12, 1), now, zone)
        assertTrue(d.keep.isEmpty() && d.delete.isEmpty())
    }

    @Test fun massDeletionGuard() {
        assertTrue(RetentionDecider.mustPauseRotation(1000, 949)) // 51 gone
        assertFalse(RetentionDecider.mustPauseRotation(1000, 950)) // exactly 50, 5 %
        assertTrue(RetentionDecider.mustPauseRotation(100, 79)) // 21 %
        assertFalse(RetentionDecider.mustPauseRotation(100, 80)) // exactly 20 %
        assertTrue(RetentionDecider.mustPauseRotation(10, 7))
        assertTrue(RetentionDecider.mustPauseRotation(300, 0))
        assertFalse(RetentionDecider.mustPauseRotation(100, 150))
        assertFalse(RetentionDecider.mustPauseRotation(0, 0))
        assertFalse(RetentionDecider.mustPauseRotation(5, 5))
    }
}
