package app.parley.common.sync

import app.parley.common.sync.FolderSyncSchedule.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSyncScheduleTest {
    private val now = 10_000_000_000L

    @Test fun aChangeOnThisPhoneAlwaysRuns() {
        assertTrue(FolderSyncSchedule.shouldRun(Trigger.CHANGE, lastRunAt = now - 1_000, now = now))
    }

    @Test fun catchUpRunsRightAfterAnotherAreSkipped() {
        assertFalse(FolderSyncSchedule.shouldRun(Trigger.PERIODIC, lastRunAt = now - 60_000, now = now))
        assertFalse(FolderSyncSchedule.shouldRun(Trigger.START_UP, lastRunAt = now - 60_000, now = now))
        assertTrue(FolderSyncSchedule.shouldRun(Trigger.PERIODIC, lastRunAt = now - FolderSyncSchedule.COALESCE_MS, now = now))
        assertTrue(FolderSyncSchedule.shouldRun(Trigger.START_UP, lastRunAt = null, now = now))
        // A clock set back never blocks runs.
        assertTrue(FolderSyncSchedule.shouldRun(Trigger.PERIODIC, lastRunAt = now + 60_000, now = now))
    }

    @Test fun sharedLabelsKeepTheHourlyPeriod() {
        assertEquals(1L, FolderSyncSchedule.periodHours(sharedLabels = true))
        assertEquals(4L, FolderSyncSchedule.periodHours(sharedLabels = false))
    }
}
