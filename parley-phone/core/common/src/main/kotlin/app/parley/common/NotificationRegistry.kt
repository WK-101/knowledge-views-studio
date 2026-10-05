package app.parley.common

import app.parley.common.calls.MissedCalls

/**
 * Every notification id, tag and channel Parley posts, in one place (app and telecom).
 *
 * Android identifies a notification by (tag, id). Untagged notifications share one id space, so an untagged id
 * must never fall inside another feature's range: `cancel(id)` and `activeNotifications` checks would then hit the
 * wrong notification. Tagged notifications are kept apart by their tag (or tag prefix).
 */
object NotificationIds {
    // Untagged: in-call and missed calls. The missed-call children follow the summary.
    const val CALL_INCOMING = 4711
    const val MISSED_SUMMARY = 4712
    const val CALL_ONGOING = 4713

    /** A rescue call (no real call): ringing, then answered. Ids of their own, so a real call's are never touched. */
    const val RESCUE_INCOMING = 4714
    const val RESCUE_ONGOING = 4715
    const val MISSED_CHILD_BASE = 4720
    const val MISSED_CHILD_COUNT = MissedCalls.MAX_CHILDREN

    // Untagged: screening.
    const val SCREEN_BLOCKED = 5101
    const val SCREEN_LIKELY_SPAM = 5102

    /** Busy auto-reply: base plus 12 bits of the number's key. */
    const val SCREEN_BUSY_BASE = 5200
    const val SCREEN_BUSY_COUNT = 0x1000

    /** Untagged: the one "Exporting…" notification while jobs started from a screen run with Parley in the background. */
    const val JOB_RUNNING = 4690

    fun screenBusy(numberKey: String): Int = SCREEN_BUSY_BASE + (numberKey.hashCode() and (SCREEN_BUSY_COUNT - 1))

    fun missedChild(index: Int): Int {
        require(index in 0 until MISSED_CHILD_COUNT)
        return MISSED_CHILD_BASE + index
    }

    fun isMissedCall(tag: String?, id: Int, childrenOnly: Boolean = false): Boolean =
        tag == null && ((!childrenOnly && id == MISSED_SUMMARY) || id in MISSED_CHILD_BASE until MISSED_CHILD_BASE + MISSED_CHILD_COUNT)

    // Tagged, with a fixed id.
    const val TAG_BACKUP_FAILED = "backup_failed"
    const val TAG_BACKUP_REMINDER = "backup_reminder"

    /** Folder sync paused until the user confirms its deletions. */
    const val TAG_FOLDER_SYNC = "folder_sync"
    const val FOLDER_SYNC_ID = 0
    const val BACKUP_ID = 0

    /** Plan usage warnings: tag "plan", id = base plus 16 bits of the SIM id. */
    const val TAG_PLAN = "plan"
    const val PLAN_BASE = 20_000
    const val PLAN_COUNT = 0x10000

    fun plan(simId: String): Int = PLAN_BASE + (simId.hashCode() and (PLAN_COUNT - 1))

    /** Temporary-contact housekeeping: tag "temporary", id = notice index. */
    const val TAG_TEMPORARY = "temporary"

    /** Private-name requests: this tag, id derived from the asking package. */
    const val TAG_PRIVATE_NAME = "private-name-request"

    /** The one "To call" reminder (every call due at that time in one notification). */
    const val TAG_TO_CALL = "to_call"
    const val TO_CALL_ID = 0

    /** A long job started from a screen (export, import, backup) that finished after its screen was gone: id = job. */
    const val TAG_JOBS = "jobs"

    /** The sync watchdog's one notice about contacts gone missing (a newer one replaces it). */
    const val TAG_SYNC_WATCHDOG = "sync_watchdog"
    const val SYNC_WATCHDOG_ID = 0

    /** Once: the notes folder export that kept a folder up to date was replaced by Export contacts. */
    const val TAG_FOLDER_EXPORT = "folder_export"
    const val FOLDER_EXPORT_ID = 0

    // Tag prefixes, id 0: one notification per contact or event.
    const val PREFIX_BIRTHDAY = "birthday:"
    const val PREFIX_NUDGE = "nudge:"
    const val PREFIX_FOLLOW_UP = "followup:"
    const val TAG_CIRCLE_DIGEST = "circle:digest"

    fun followUp(contactId: Long): String = "$PREFIX_FOLLOW_UP$contactId"

    /** A block of untagged ids owned by one feature. */
    data class Range(val name: String, val first: Int, val count: Int) {
        val last: Int get() = first + count - 1
        fun overlaps(o: Range) = first <= o.last && o.first <= last
    }

    val untagged: List<Range> = listOf(
        Range("call.incoming", CALL_INCOMING, 1),
        Range("missed.summary", MISSED_SUMMARY, 1),
        Range("call.ongoing", CALL_ONGOING, 1),
        Range("rescue", RESCUE_INCOMING, 2),
        Range("missed.child", MISSED_CHILD_BASE, MISSED_CHILD_COUNT),
        Range("screen.blocked", SCREEN_BLOCKED, 1),
        Range("screen.likely", SCREEN_LIKELY_SPAM, 1),
        Range("screen.busy", SCREEN_BUSY_BASE, SCREEN_BUSY_COUNT),
        Range("jobs.running", JOB_RUNNING, 1),
    )

    /** Fixed tags, and prefixes of per-item tags (prefix ends with ':'). */
    val tags: List<String> = listOf(
        TAG_BACKUP_FAILED, TAG_BACKUP_REMINDER, TAG_FOLDER_SYNC, TAG_PLAN, TAG_TEMPORARY, TAG_PRIVATE_NAME, TAG_TO_CALL,
        TAG_SYNC_WATCHDOG, TAG_JOBS, TAG_FOLDER_EXPORT, PREFIX_BIRTHDAY, PREFIX_NUDGE, PREFIX_FOLLOW_UP, TAG_CIRCLE_DIGEST,
    )

    /** Pairs of ranges that share an id; empty when the registry is sound. */
    fun overlaps(ranges: List<Range> = untagged): List<Pair<Range, Range>> =
        ranges.indices.flatMap { i -> (i + 1 until ranges.size).filter { ranges[i].overlaps(ranges[it]) }.map { ranges[i] to ranges[it] } }

    /** Pairs of tags where one could produce the other (equal, or a prefix of it). */
    fun tagClashes(all: List<String> = tags): List<Pair<String, String>> =
        all.indices.flatMap { i -> (i + 1 until all.size).filter { all[i].startsWith(all[it]) || all[it].startsWith(all[i]) }.map { all[i] to all[it] } }
}

/**
 * PendingIntent request codes of notifications and their buttons. Android tells PendingIntents apart by request code
 * and intent, and FLAG_UPDATE_CURRENT rewrites the extras of a match: so no two blocks here share a code, whatever
 * their actions. Codes derived from a tag or a package name (reminders, private-name requests) are hashed per item
 * and stay with their feature; those intents carry their own target.
 */
object NotificationRequests {
    /** The in-call notification (CallNotifier): 1 opens the call screen, 2 answers, 3–19 its buttons. */
    const val CALL = 1
    const val CALL_ANSWER = 2

    /** Re-posts a call notification swiped away: plus the notification id modulo 100. */
    const val CALL_DISMISS = 100

    /** "Decline this call?" on the call screen: plus the button's code from [CALL]. */
    const val CALL_ASK_DECLINE = 200
    const val PIP_MUTE = 220
    const val PIP_HANG_UP = 221
    const val PIP_HOLD_END = 222

    /** A rescue call's notification: opens its call screen, answers, declines, hangs up; and its alarm. */
    const val RESCUE_OPEN = 230
    const val RESCUE_ANSWER = 231
    const val RESCUE_DECLINE = 232
    const val RESCUE_HANG_UP = 233
    const val RESCUE_ALARM = 234

    const val MISSED_OPEN = 300
    const val MISSED_CLEAR = 301
    const val MISSED_REALERT = 302

    /** Per missed-call child (plus its index). */
    const val MISSED_DISMISS = 310
    const val MISSED_CALL_BACK = 320
    const val MISSED_MESSAGE = 330
    const val MISSED_BLOCK = 340
    const val MISSED_REMIND = 350

    const val SCREEN_OPEN = 400
    const val SCREEN_NOT_SPAM = 401
    const val SCREEN_SNOOZE = 402

    const val TO_CALL_OPEN = 500
    const val TO_CALL_NOT_NOW = 501
    const val TO_CALL_CALL = 502

    const val BACKUP_FAILED = 600
    const val BACKUP_REMINDER = 601
    const val FOLDER_SYNC = 602
    const val SYNC_WATCHDOG = 603
    const val TEMPORARY_EXPIRED = 604
    const val TEMPORARY_DUE = 605

    /** The one-time notice that the folder export of notes was replaced by Export contacts. */
    const val FOLDER_EXPORT = 606

    /** The due-temporaries buttons: plus the decision's ordinal. */
    const val TEMPORARY_DUE_ACTION = 610

    const val JOB_OPEN = 700

    /** A finished job's file: plus the job id modulo [JOB_FILES]. */
    const val JOB_FILE = 710
    const val JOB_FILES = 50

    val blocks: List<NotificationIds.Range> = listOf(
        NotificationIds.Range("call", CALL, 19),
        NotificationIds.Range("call.dismiss", CALL_DISMISS, 100),
        NotificationIds.Range("call.ask-decline", CALL_ASK_DECLINE, 20),
        NotificationIds.Range("pip", PIP_MUTE, 3),
        NotificationIds.Range("rescue", RESCUE_OPEN, 5),
        NotificationIds.Range("missed", MISSED_OPEN, 3),
        NotificationIds.Range("missed.dismiss", MISSED_DISMISS, MissedCalls.MAX_CHILDREN),
        NotificationIds.Range("missed.call-back", MISSED_CALL_BACK, MissedCalls.MAX_CHILDREN),
        NotificationIds.Range("missed.message", MISSED_MESSAGE, MissedCalls.MAX_CHILDREN),
        NotificationIds.Range("missed.block", MISSED_BLOCK, MissedCalls.MAX_CHILDREN),
        NotificationIds.Range("missed.remind", MISSED_REMIND, MissedCalls.MAX_CHILDREN),
        NotificationIds.Range("screen", SCREEN_OPEN, 3),
        NotificationIds.Range("to-call", TO_CALL_OPEN, 3),
        NotificationIds.Range("notices", BACKUP_FAILED, 7),
        NotificationIds.Range("temporary.due", TEMPORARY_DUE_ACTION, 3),
        NotificationIds.Range("job.open", JOB_OPEN, 1),
        NotificationIds.Range("job.file", JOB_FILE, JOB_FILES),
    )
}

/** Every notification channel id. Renaming one creates a new channel and drops the user's settings for the old one. */
object NotificationChannels {
    const val INCOMING_CALLS = "incoming_calls_v1"
    const val ONGOING_CALLS = "ongoing_calls_v1"
    const val SILENCED_CALLS = "silenced_calls_v1"
    const val MISSED_CALLS = "missed_calls_v1"
    const val SCREEN_BLOCKED = "screen_blocked_v1"
    const val SCREEN_REPORTED = "screen_reported_v1"
    const val SCREEN_LIKELY_SPAM = "screen_likely_spam_v1"
    const val SCREEN_BUSY_REPLY = "screen_busy_reply_v1"
    const val SCREENING_GROUP = "screening"
    const val PLAN = "plan_v1"
    const val REMINDERS = "reminders_v1"
    const val HOUSEKEEPING = "contacts_housekeeping_v1"

    /** Backup results: a scheduled backup that failed, or rotation paused. Never in [REMINDERS_GROUP]. */
    const val BACKUPS = "backup_v1"

    /** The monthly "time for a backup" reminder, split from [BACKUPS] so muting reminders never hides a failure. */
    const val BACKUP_REMINDER = "backup_reminder_v1"
    const val PRIVATE_NAMES = "private_names_v1"

    /** "To call" reminders: never a badge. */
    const val TO_CALL = "to_call_v1"

    /** "Export finished", "Import failed": the end of work you started, when its screen is no longer showing. */
    const val JOBS = "jobs_v1"

    /** Contacts that went missing (the sync watchdog): rare, so it may make a sound where housekeeping doesn't. */
    const val CONTACTS_SAFETY = "contacts_safety_v1"

    /**
     * The channel group "Reminders" (Settings › Reminders lists the same kinds). Only the group is new: the channels
     * keep their ids, so whatever someone set for them stays. Missed calls stay with calls, temporary contacts with
     * housekeeping, and backup results in [BACKUPS] (those channels also carry notices that aren't reminders: muting
     * the group must never hide a missed call or a failed backup). Only the backup reminder has a channel of its own
     * in the group.
     */
    const val REMINDERS_GROUP = "reminders"

    /** The channels in [REMINDERS_GROUP]: birthdays, keep in touch and follow-ups; To call; the backup reminder. */
    val reminderChannels: List<String> = listOf(REMINDERS, TO_CALL, BACKUP_REMINDER)

    val all: List<String> = listOf(
        INCOMING_CALLS, ONGOING_CALLS, SILENCED_CALLS, MISSED_CALLS, SCREEN_BLOCKED, SCREEN_REPORTED, SCREEN_LIKELY_SPAM,
        SCREEN_BUSY_REPLY, PLAN, REMINDERS, HOUSEKEEPING, BACKUPS, PRIVATE_NAMES, TO_CALL,
        CONTACTS_SAFETY, BACKUP_REMINDER, JOBS,
    )
}
