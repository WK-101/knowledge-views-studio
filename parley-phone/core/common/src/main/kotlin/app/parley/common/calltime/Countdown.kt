package app.parley.common.calltime

enum class CountdownEvent { REMINDER, WARN, QUOTA_USED, END }

/**
 * The timing state of one call, on the `elapsedRealtime` clock (T4): it keeps counting through deep sleep and
 * ignores wall-clock changes. Immutable; [step] returns the next state and what happened.
 *
 * The budget starts when the call connected ([startElapsed]), not when it was dialled or answered in the UI.
 */
data class Countdown(
    val startElapsed: Long,
    val limitMs: Long? = null,
    val warnBeforeMs: Long = 60_000,
    val reminderEveryMs: Long? = null,
    val quotaLeftMs: Long? = null,
    val canExtend: Boolean = true,
    /** Added by "+2 min" / "+5 min". */
    val extraMs: Long = 0,
    /** "End in 1 min": an end time that applies even without a limit. */
    val endOverride: Long? = null,
    /** "Don't end": the limit is off for the rest of this call. */
    val dontEnd: Boolean = false,
    /** The end time the warning was given for; a new end time (after "+5 min") warns again. */
    val warnedFor: Long? = null,
    val remindersFired: Int = 0,
    val quotaWarned: Boolean = false,
    /** Bumped whenever the end time changes, so notifications re-post only then (T3). */
    val revision: Int = 0,
) {
    /** When the call will be ended, or null if it won't. */
    val endAt: Long?
        get() = if (dontEnd) null else listOfNotNull(limitMs?.let { startElapsed + it + extraMs }, endOverride).minOrNull()

    val warnAt: Long? get() = endAt?.let { maxOf(startElapsed, it - warnBeforeMs) }

    val hasEnd: Boolean get() = endAt != null
    val isLimited: Boolean get() = limitMs != null || endOverride != null

    fun remainingMs(now: Long): Long? = endAt?.let { (it - now).coerceAtLeast(0) }

    fun extend(ms: Long): Countdown {
        if (!canExtend) return this
        return copy(extraMs = extraMs + ms, endOverride = endOverride?.plus(ms), revision = revision + 1)
    }

    /** "End in 1 min". Also allowed in supervised mode: it only shortens the call. */
    fun endIn(now: Long, ms: Long): Countdown = copy(endOverride = now + ms, dontEnd = false, revision = revision + 1)

    fun keepGoing(): Countdown = if (!canExtend) this else copy(dontEnd = true, endOverride = null, revision = revision + 1)

    /**
     * Advances to [now]. At most one event of each kind is reported per step, so a phone waking late never
     * plays a burst of missed reminders. END is reported alone.
     */
    fun step(now: Long): Pair<Countdown, List<CountdownEvent>> {
        val end = endAt
        if (end != null && now >= end) return this to listOf(CountdownEvent.END)
        var next = this
        val events = ArrayList<CountdownEvent>(2)
        val warn = warnAt
        if (end != null && warn != null && warnedFor != end && now >= warn) {
            next = next.copy(warnedFor = end)
            events += CountdownEvent.WARN
        }
        val every = reminderEveryMs
        if (every != null && every > 0) {
            val n = ((now - startElapsed) / every).toInt()
            if (n > remindersFired) {
                next = next.copy(remindersFired = n)
                // A warning at the same moment already makes a sound.
                if (CountdownEvent.WARN !in events) events += CountdownEvent.REMINDER
            }
        }
        val quota = quotaLeftMs
        if (quota != null && !quotaWarned && now - startElapsed >= quota) {
            next = next.copy(quotaWarned = true)
            events += CountdownEvent.QUOTA_USED
        }
        return next to events
    }

    /** The next moment something is due, for scheduling. */
    fun nextDueAt(): Long? {
        val times = ArrayList<Long>(4)
        endAt?.let { times += it }
        warnAt?.let { w -> if (warnedFor != endAt) times += w }
        reminderEveryMs?.takeIf { it > 0 }?.let { times += startElapsed + (remindersFired + 1) * it }
        quotaLeftMs?.let { if (!quotaWarned) times += startElapsed + it }
        return times.minOrNull()
    }

    companion object {
        /** A call first seen after its limit was already over still gets this much warning. */
        const val LATE_GRACE_MS = 30_000L

        /**
         * Starts timing a call that connected [connectedForMs] ago. When the process started mid-call (or the
         * plan arrived late) past reminders are skipped and an overdue limit leaves [LATE_GRACE_MS] to finish.
         */
        fun start(nowElapsed: Long, connectedForMs: Long, plan: CallTimePlan): Countdown {
            val start = nowElapsed - connectedForMs.coerceAtLeast(0)
            val limit = plan.limitMs
            val extra = if (limit != null && start + limit < nowElapsed + LATE_GRACE_MS) nowElapsed + LATE_GRACE_MS - (start + limit) else 0L
            val every = plan.reminderEveryMs?.takeIf { it > 0 }
            return Countdown(
                startElapsed = start,
                limitMs = limit,
                warnBeforeMs = plan.warnBeforeMs,
                reminderEveryMs = every,
                quotaLeftMs = plan.quotaLeftMs,
                canExtend = plan.canExtend,
                extraMs = extra,
                remindersFired = if (every != null) (connectedForMs.coerceAtLeast(0) / every).toInt() else 0,
                quotaWarned = plan.quotaLeftMs != null && connectedForMs >= plan.quotaLeftMs,
            )
        }

        /** Bounded wake lock while a limited call runs: until the end plus a minute, never more than 4 hours. */
        fun wakeLockTimeoutMs(nowElapsed: Long, endAt: Long): Long = (endAt - nowElapsed + 60_000L).coerceIn(60_000L, 4 * 60 * 60_000L)
    }
}

/**
 * Countdowns of all live calls, keyed by call id. Each countdown only ever produces actions for its own call,
 * so a limit can never end a ringing or held call it doesn't belong to (the Call Limiter bug).
 */
class CallTimeBook {
    private val entries = LinkedHashMap<String, Countdown>()

    val all: Map<String, Countdown> get() = entries.toMap()

    operator fun get(id: String): Countdown? = entries[id]

    fun track(id: String, countdown: Countdown) {
        entries[id] = countdown
    }

    fun update(id: String, f: (Countdown) -> Countdown): Countdown? {
        val cur = entries[id] ?: return null
        return f(cur).also { entries[id] = it }
    }

    fun untrack(id: String): Countdown? = entries.remove(id)

    /** Keeps only calls that still exist. */
    fun retain(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    /** Advances every countdown; returns (call id, event) pairs. Calls that end are removed. */
    fun step(now: Long): List<Pair<String, CountdownEvent>> {
        val out = ArrayList<Pair<String, CountdownEvent>>()
        for ((id, cd) in entries.toList()) {
            val (next, events) = cd.step(now)
            if (CountdownEvent.END in events) entries.remove(id) else entries[id] = next
            events.forEach { out += id to it }
        }
        return out
    }

    fun nextDueAt(): Long? = entries.values.mapNotNull { it.nextDueAt() }.minOrNull()

    /** Latest end time of all limited calls, for the wake lock; null when no call will be ended. */
    fun latestEnd(): Long? = entries.values.mapNotNull { it.endAt }.maxOrNull()

    fun isEmpty(): Boolean = entries.isEmpty()
}

/**
 * What the ongoing-call notification shows (T3). A countdown uses the platform chronometer counting down to
 * the end time, so the notification is posted once per change of end time, never once per second.
 */
object CallChronometer {
    data class Display(val countDown: Boolean, val whenMillis: Long, val signature: String)

    fun display(connectWallMs: Long, countdown: Countdown?, nowElapsed: Long, nowWall: Long): Display {
        val end = countdown?.endAt
        return if (end == null) {
            Display(false, connectWallMs, "up")
        } else {
            // The wall time is derived from the monotonic end time at posting; only the revision identifies it.
            Display(true, nowWall + (end - nowElapsed), "down:${countdown.revision}")
        }
    }
}

/** Vibration patterns for call events (A6): off/on timings in milliseconds, starting with a pause. */
enum class CallHaptic(val timings: LongArray) {
    CONNECT(longArrayOf(0, 45)),
    DISCONNECT(longArrayOf(0, 30, 90, 30)),
    SWAP(longArrayOf(0, 20, 70, 20)),
    MERGE(longArrayOf(0, 20, 50, 20, 50, 20)),
    REMINDER(longArrayOf(0, 120)),
    WARN(longArrayOf(0, 250, 150, 250)),
    /** P7: answering rises (short, then longer); declining is one firm buzz. Both differ from connect. */
    ANSWER(longArrayOf(0, 25, 60, 70)),
    DECLINE(longArrayOf(0, 110)),
}
