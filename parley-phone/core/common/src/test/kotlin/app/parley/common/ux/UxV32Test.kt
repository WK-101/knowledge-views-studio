package app.parley.common.ux

import app.parley.common.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UxV32Test {
    // ---------------------------------------------------------------- U3 call colours

    @Test fun every_call_type_has_a_fixed_hue() {
        assertEquals(CallHue.INCOMING, CallHue.of(CallType.INCOMING))
        assertEquals(CallHue.INCOMING, CallHue.of(CallType.ANSWERED_EXTERNALLY))
        assertEquals(CallHue.OUTGOING, CallHue.of(CallType.OUTGOING))
        assertEquals(CallHue.MISSED, CallHue.of(CallType.MISSED))
        assertEquals(CallHue.MISSED, CallHue.of(CallType.REJECTED))
        assertEquals(CallHue.BLOCKED, CallHue.of(CallType.BLOCKED))
        // No type falls through to an unexpected family.
        CallType.entries.forEach { CallHue.of(it) }
    }

    // ---------------------------------------------------------------- C2 back up first

    private val day = BackupNudge.DAY_MS
    private val now = 1_800_000_000_000L

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

    // ---------------------------------------------------------------- U2 tips

    @Test fun tips_round_trip_and_drop_junk() {
        val seen = setOf(Tips.HEADER_SEARCH, Tips.KEYPAD_SPEED_DIAL)
        assertEquals(seen, Tips.decode(Tips.encode(seen)))
        assertEquals(setOf("ok_1", "x"), Tips.decode("ok_1, Bad Id,,x,y;z"))
        assertEquals(emptySet<String>(), Tips.decode(null))
    }

    @Test fun one_tip_at_a_time() {
        val requested = listOf(Tips.HEADER_SEARCH, Tips.RECENTS_SWIPE)
        assertEquals(Tips.HEADER_SEARCH, Tips.visible(requested, emptySet(), null))
        assertEquals(Tips.RECENTS_SWIPE, Tips.visible(requested, setOf(Tips.HEADER_SEARCH), null))
        // The one already showing keeps its place even if an earlier one asks later.
        assertEquals(Tips.RECENTS_SWIPE, Tips.visible(requested, emptySet(), Tips.RECENTS_SWIPE))
        assertNull(Tips.visible(requested, requested.toSet(), null))
    }

    // ---------------------------------------------------------------- U6 what's new

    @Test fun whats_new_once_per_update_never_on_a_fresh_install() {
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(seenVersion = 4, currentVersion = 5, freshInstall = false))
        // Updated from a version that didn't record anything yet.
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(0, 5, freshInstall = false))
        assertEquals(WhatsNew.Decision.MARK_SEEN, WhatsNew.decide(0, 5, freshInstall = true))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(5, 5, freshInstall = false))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(6, 5, freshInstall = false))
    }
}
