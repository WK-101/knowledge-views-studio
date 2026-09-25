package app.parley.common.ux

/**
 * C2 and C3: when Parley suggests a backup. Everything is decided from timestamps (epoch millis) so it can be tested;
 * nothing here counts or scores how "late" someone is, and nothing is ever silenced for good.
 */
object BackupNudge {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** C2: "Back up first?" appears when the last backup is older than this. */
    const val BACKUP_FIRST_DAYS = 7

    /** C2: a file import with at least this many contacts counts as large. */
    const val LARGE_IMPORT = 20

    /** C2: deleting at least this many contacts at once counts as a bulk delete. */
    const val LARGE_DELETE = 5

    /** C3: the reminder thresholds the user can pick; the first is the default. */
    val REMINDER_DAYS = listOf(30, 14)

    /** C3: dismissing the banner hides it for this long, then it comes back if still due. */
    const val SNOOZE_DAYS = 7

    /** C3: at most one reminder notification in this many days. */
    const val NOTIFY_EVERY_DAYS = 30

    /** A reminder threshold that is one of [REMINDER_DAYS] (older or edited values fall back to the default). */
    fun reminderDays(stored: Int): Int = if (stored in REMINDER_DAYS) stored else REMINDER_DAYS.first()

    /**
     * The moment the backup clock starts: the last backup, or when Parley was installed if there has never been
     * one (so a fresh install isn't greeted with a reminder).
     */
    fun since(lastBackupAt: Long, installedAt: Long): Long = if (lastBackupAt > 0) lastBackupAt else installedAt.coerceAtLeast(0)

    /**
     * C2: whether to offer "Back up first?" before a change touching [count] contacts; [threshold] is the size
     * that counts as large for that change (1 = always, for merges). No backup ever counts as old.
     */
    fun backupFirst(lastBackupAt: Long, now: Long, count: Int, threshold: Int): Boolean =
        count >= threshold && (lastBackupAt <= 0 || now - lastBackupAt > BACKUP_FIRST_DAYS * DAY_MS)

    /** C3: whether a backup is due, [days] after [since]. */
    fun overdue(since: Long, now: Long, days: Int): Boolean = now - since >= reminderDays(days) * DAY_MS

    /** C3: the banner shows while a backup is due and the last dismissal's snooze has run out. */
    fun showBanner(since: Long, now: Long, days: Int, snoozedUntil: Long): Boolean = overdue(since, now, days) && now >= snoozedUntil

    /** C3: when a banner dismissed [now] may come back. */
    fun snoozeUntil(now: Long): Long = now + SNOOZE_DAYS * DAY_MS

    /** C3: a reminder notification is sent when a backup is due, and never twice within [NOTIFY_EVERY_DAYS]. */
    fun mayNotify(since: Long, now: Long, days: Int, lastNotifiedAt: Long): Boolean =
        overdue(since, now, days) && (lastNotifiedAt <= 0 || now - lastNotifiedAt >= NOTIFY_EVERY_DAYS * DAY_MS)

    /** Whole days since [since], for "Last backup 3 weeks ago"-style text (never negative). */
    fun daysSince(since: Long, now: Long): Int = ((now - since).coerceAtLeast(0) / DAY_MS).toInt()
}
