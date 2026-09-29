package app.parley.common.calls

/**
 * I10 "I'm on hold": a manual waiting mode for queues and phone menus. Parley can't hear the call (no microphone
 * access), so it only keeps time and buzzes at [REMINDER_MINUTES] to say how long it has been.
 */
object HoldMode {
    val REMINDER_MINUTES = listOf(15, 30)

    /** When the next reminder is due (on the same clock as [since] and [now]), or null when all have passed. */
    fun nextReminderAt(since: Long, now: Long): Long? =
        REMINDER_MINUTES.map { since + it * MINUTE }.firstOrNull { it > now }

    private const val MINUTE = 60_000L
}
