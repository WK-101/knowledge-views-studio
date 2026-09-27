package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupNudgeTest {
    private val day = BackupNudge.DAY_MS

    private val now = 1_800_000_000_000L

    // ---------------------------------------------------------------- C2 back up first

    @Test fun backup_first_only_for_large_changes_with_an_old_backup() {
        // Backed up yesterday: never asks.
        assertFalse(BackupNudge.backupFirst(now - day, now, 500, BackupNudge.LARGE_IMPORT))
        // Backed up 8 days ago: asks for a large import, not a small one.
        assertTrue(BackupNudge.backupFirst(now - 8 * day, now, 20, BackupNudge.LARGE_IMPORT))
        assertFalse(BackupNudge.backupFirst(now - 8 * day, now, 19, BackupNudge.LARGE_IMPORT))
        // Exactly 7 days is still fresh enough.
        assertFalse(BackupNudge.backupFirst(now - 7 * day, now, 50, BackupNudge.LARGE_IMPORT))
        // Never backed up counts as old.
        assertTrue(BackupNudge.backupFirst(0, now, 5, BackupNudge.LARGE_DELETE))
        // A merge passes threshold 1: any merge asks when the backup is old.
        assertTrue(BackupNudge.backupFirst(0, now, 1, 1))
    }

    // ---------------------------------------------------------------- C3 reminder

    @Test fun reminder_days_are_14_or_30() {
        assertEquals(30, BackupNudge.reminderDays(0))
        assertEquals(14, BackupNudge.reminderDays(14))
        assertEquals(30, BackupNudge.reminderDays(7))
    }

    @Test fun the_clock_starts_at_install_when_there_was_never_a_backup() {
        val installed = now - 3 * day
        assertEquals(installed, BackupNudge.since(0, installed))
        assertFalse(BackupNudge.overdue(BackupNudge.since(0, installed), now, 14))
        assertEquals(now - day, BackupNudge.since(now - day, installed))
    }

    @Test fun banner_shows_when_due_and_comes_back_after_a_snooze() {
        val since = now - 31 * day
        assertTrue(BackupNudge.showBanner(since, now, 30, snoozedUntil = 0))
        assertFalse(BackupNudge.showBanner(now - 29 * day, now, 30, 0))
        assertTrue(BackupNudge.showBanner(now - 15 * day, now, 14, 0))
        // Dismissed now: hidden for the snooze, then back (never silenced for good).
        val until = BackupNudge.snoozeUntil(now)
        assertFalse(BackupNudge.showBanner(since, now + day, 30, until))
        assertTrue(BackupNudge.showBanner(since, now + BackupNudge.SNOOZE_DAYS * day, 30, until))
    }

    @Test fun at_most_one_notification_a_month() {
        val since = now - 40 * day
        assertTrue(BackupNudge.mayNotify(since, now, 30, lastNotifiedAt = 0))
        assertFalse(BackupNudge.mayNotify(since, now, 30, lastNotifiedAt = now - 29 * day))
        assertTrue(BackupNudge.mayNotify(since, now, 30, lastNotifiedAt = now - 30 * day))
        // Not due: no notification however long ago the last one was.
        assertFalse(BackupNudge.mayNotify(now - 2 * day, now, 14, 0))
    }
}
