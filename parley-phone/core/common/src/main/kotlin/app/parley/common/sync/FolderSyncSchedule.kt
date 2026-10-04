package app.parley.common.sync

/**
 * When the background folder sync runs. A change on this phone always gets a run (a minute after the address book
 * settles); the start-up and periodic runs only catch up with what no trigger sees, so they are skipped when a run
 * finished shortly before, and the periodic one is spaced out unless labels are shared with other phones.
 */
object FolderSyncSchedule {
    enum class Trigger { START_UP, PERIODIC, CHANGE }

    /** Without shared labels only this phone writes the folder, and its changes have their own trigger. */
    const val QUIET_PERIOD_HOURS = 4L

    /** Shared labels: other people's phones write into the folders, and only the periodic run sees it. */
    const val SHARED_PERIOD_HOURS = 1L

    /** A start-up or periodic run this soon after the last one adds nothing. */
    const val COALESCE_MS = 20 * 60_000L

    fun periodHours(sharedLabels: Boolean): Long = if (sharedLabels) SHARED_PERIOD_HOURS else QUIET_PERIOD_HOURS

    /** Whether a run for [trigger] goes ahead; [lastRunAt] is when the last run finished (null: never). */
    fun shouldRun(trigger: Trigger, lastRunAt: Long?, now: Long): Boolean = when {
        trigger == Trigger.CHANGE -> true
        lastRunAt == null || lastRunAt > now -> true
        else -> now - lastRunAt >= COALESCE_MS
    }
}
