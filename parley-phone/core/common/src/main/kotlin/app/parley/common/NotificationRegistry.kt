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
    const val MISSED_CHILD_BASE = 4720
    const val MISSED_CHILD_COUNT = MissedCalls.MAX_CHILDREN

    // Untagged: screening.
    const val SCREEN_BLOCKED = 5101
    const val SCREEN_LIKELY_SPAM = 5102

    /** Busy auto-reply: base plus 12 bits of the number's key. */
    const val SCREEN_BUSY_BASE = 5200
    const val SCREEN_BUSY_COUNT = 0x1000

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
        Range("missed.child", MISSED_CHILD_BASE, MISSED_CHILD_COUNT),
        Range("screen.blocked", SCREEN_BLOCKED, 1),
        Range("screen.likely", SCREEN_LIKELY_SPAM, 1),
        Range("screen.busy", SCREEN_BUSY_BASE, SCREEN_BUSY_COUNT),
    )

    /** Fixed tags, and prefixes of per-item tags (prefix ends with ':'). */
    val tags: List<String> = listOf(
        TAG_BACKUP_FAILED, TAG_BACKUP_REMINDER, TAG_FOLDER_SYNC, TAG_PLAN, TAG_TEMPORARY, TAG_PRIVATE_NAME, TAG_TO_CALL,
        PREFIX_BIRTHDAY, PREFIX_NUDGE, PREFIX_FOLLOW_UP, TAG_CIRCLE_DIGEST,
    )

    /** Pairs of ranges that share an id; empty when the registry is sound. */
    fun overlaps(ranges: List<Range> = untagged): List<Pair<Range, Range>> =
        ranges.indices.flatMap { i -> (i + 1 until ranges.size).filter { ranges[i].overlaps(ranges[it]) }.map { ranges[i] to ranges[it] } }

    /** Pairs of tags where one could produce the other (equal, or a prefix of it). */
    fun tagClashes(all: List<String> = tags): List<Pair<String, String>> =
        all.indices.flatMap { i -> (i + 1 until all.size).filter { all[i].startsWith(all[it]) || all[it].startsWith(all[i]) }.map { all[i] to all[it] } }
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
    const val BACKUPS = "backup_v1"
    const val PRIVATE_NAMES = "private_names_v1"

    /** "To call" reminders: never a badge. */
    const val TO_CALL = "to_call_v1"

    val all: List<String> = listOf(
        INCOMING_CALLS, ONGOING_CALLS, SILENCED_CALLS, MISSED_CALLS, SCREEN_BLOCKED, SCREEN_REPORTED, SCREEN_LIKELY_SPAM,
        SCREEN_BUSY_REPLY, PLAN, REMINDERS, HOUSEKEEPING, BACKUPS, PRIVATE_NAMES, TO_CALL,
    )
}
