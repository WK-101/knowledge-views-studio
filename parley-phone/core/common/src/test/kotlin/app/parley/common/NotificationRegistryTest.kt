package app.parley.common

import app.parley.common.circle.DateReminders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRegistryTest {
    @Test fun untagged_ranges_never_overlap() {
        assertEquals(emptyList<Any>(), NotificationIds.overlaps())
    }

    @Test fun overlap_check_catches_a_clash() {
        val backup = NotificationIds.Range("old.backup", 4720, 1)
        assertEquals(1, NotificationIds.overlaps(NotificationIds.untagged + backup).size)
    }

    @Test fun tags_and_prefixes_are_distinct() {
        assertEquals(emptyList<Any>(), NotificationIds.tagClashes())
        assertEquals(1, NotificationIds.tagClashes(listOf("plan", "plan:x")).size)
    }

    @Test fun channels_are_unique() {
        assertEquals(NotificationChannels.all.size, NotificationChannels.all.toSet().size)
    }

    @Test fun reminder_group_holds_existing_channels_only() {
        assertTrue(NotificationChannels.all.containsAll(NotificationChannels.reminderChannels))
        assertFalse(NotificationChannels.REMINDERS_GROUP in NotificationChannels.all)
        assertFalse(NotificationChannels.REMINDERS_GROUP == NotificationChannels.SCREENING_GROUP)
        // Calls keep their own channel: a muted Reminders group must never hide a missed call.
        assertFalse(NotificationChannels.MISSED_CALLS in NotificationChannels.reminderChannels)
        // Failed backups keep their own channel too: muting reminders must never hide one.
        assertFalse(NotificationChannels.BACKUPS in NotificationChannels.reminderChannels)
        // The ids people may have customised are the ones they had before the group existed; only the backup
        // reminder, split off from backup results, is new.
        assertEquals("backup_v1", NotificationChannels.BACKUPS)
        assertEquals(listOf("reminders_v1", "to_call_v1", "backup_reminder_v1"), NotificationChannels.reminderChannels)
    }

    @Test fun computed_ids_stay_inside_their_range() {
        listOf("", "0", "+15551234567", "a".repeat(40)).forEach {
            assertTrue(NotificationIds.screenBusy(it) in NotificationIds.SCREEN_BUSY_BASE until NotificationIds.SCREEN_BUSY_BASE + NotificationIds.SCREEN_BUSY_COUNT)
            assertTrue(NotificationIds.plan(it) in NotificationIds.PLAN_BASE until NotificationIds.PLAN_BASE + NotificationIds.PLAN_COUNT)
        }
    }

    @Test fun missed_call_check_ignores_tagged_notifications() {
        assertTrue(NotificationIds.isMissedCall(null, NotificationIds.MISSED_SUMMARY))
        assertFalse(NotificationIds.isMissedCall(null, NotificationIds.MISSED_SUMMARY, childrenOnly = true))
        assertTrue(NotificationIds.isMissedCall(null, NotificationIds.missedChild(0), childrenOnly = true))
        assertFalse(NotificationIds.isMissedCall(NotificationIds.TAG_BACKUP_FAILED, NotificationIds.missedChild(0)))
        assertFalse(NotificationIds.isMissedCall(null, NotificationIds.CALL_ONGOING))
    }

    @Test fun per_item_tags_use_registered_prefixes() {
        assertTrue(DateReminders.tag(1, "b").startsWith(NotificationIds.PREFIX_BIRTHDAY))
        assertTrue(DateReminders.nudgeTag(1).startsWith(NotificationIds.PREFIX_NUDGE))
        assertEquals(NotificationIds.TAG_CIRCLE_DIGEST, DateReminders.DIGEST_TAG)
        assertTrue(NotificationIds.followUp(7).startsWith(NotificationIds.PREFIX_FOLLOW_UP))
    }
}
