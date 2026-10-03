package app.parley.common.calls

/**
 * When Parley looks for a private contact's call in the system call log after the call ends ("Private call history").
 * Telecom writes the row a moment after the call ends; until Parley moves it into the private history, any app that can
 * read the call log can see it. Parley watches the call log for the insert and also checks on this schedule, in case
 * the change notice comes late or not at all; [WINDOW_MS] after the call it stops watching. A call whose sweep may not
 * have run (the process ended) is marked, and the next start sweeps first ([sinceFor]).
 */
object PrivateCallSweepPlan {
    /** Checks after the call ends, in milliseconds from the end: quick at first, then a few later tries. */
    val CHECKS_MS: List<Long> = listOf(0L, 300L, 1_000L, 2_500L, 5_000L, 10_000L, 20_000L)

    /** How long the call log is watched for the insert. */
    const val WINDOW_MS: Long = 30_000L

    /** How far back a sweep looks from the call's end: the call itself started earlier (a long call, a slow clock). */
    const val LOOK_BACK_MS: Long = 6 * 60 * 60 * 1000L

    /** The oldest call-log date to sweep for a call that ended at [endedAt] (a marker left by an earlier process). */
    fun sinceFor(endedAt: Long): Long = (endedAt - LOOK_BACK_MS).coerceAtLeast(0L)

    /** Whether a check at [elapsedMs] after the end still belongs to the window (later change notices are ignored). */
    fun watching(elapsedMs: Long): Boolean = elapsedMs in 0..WINDOW_MS
}
