package app.parley.common.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotClearingTest {
    private val day = 86_400_000L
    private val now = 1_000 * day
    private val all = listOf(now - 200 * day, now - 31 * day, now - 30 * day, now - 2 * day, now - day)

    @Test fun noneDropsEverything() = assertEquals(all.toSet(), SnapshotClearing.toDrop(all, SnapshotKeep.NONE, now))

    @Test fun latestKeepsOnlyTheNewest() =
        assertEquals((all - (now - day)).toSet(), SnapshotClearing.toDrop(all, SnapshotKeep.LATEST, now))

    @Test fun recentKeepsTheLastThirtyDays() =
        assertEquals(setOf(now - 200 * day, now - 31 * day), SnapshotClearing.toDrop(all, SnapshotKeep.RECENT, now))

    @Test fun emptyStaysEmpty() {
        SnapshotKeep.entries.forEach { assertEquals(emptySet<Long>(), SnapshotClearing.toDrop(emptyList(), it, now)) }
    }
}
