package app.parley.calls

import android.content.Context
import android.database.ContentObserver
import android.os.SystemClock
import android.provider.CallLog
import android.util.Log
import androidx.annotation.VisibleForTesting
import app.parley.common.calls.PrivateCallSweepPlan
import app.parley.common.catching
import app.parley.data.DataContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Private call history": a private contact's call leaves the system call log as soon as Telecom writes it. Started by
 * the in-call service when the call ends ([afterCall]): Parley watches the call log for the insert and sweeps at once,
 * with checks on [PrivateCallSweepPlan.CHECKS_MS] in case the change notice is late. Before anything, the call is
 * marked in a small file, so when the process ends first, the next start sweeps ([recheck]); the daily upkeep catches
 * anything older. What remains is the moment between Telecom's insert and Parley's delete (see SECURITY_MODEL.md).
 */
object PrivateCallLogSweep {
    private const val TAG = "PrivateCallLog"
    private const val PREFS = "private_call_sweep"
    private const val K_ENDED = "ended"
    private const val K_ENDED_ONE = "ended_at"
    private val mutex = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * When each call that may still be in the system call log ended. One entry per call, so a call's sweep ending
     * clears only its own: two private calls ending close together each keep theirs until their own sweep is done.
     */
    @VisibleForTesting
    internal fun marks(context: Context): Set<Long> {
        val p = prefs(context)
        val set = p.getStringSet(K_ENDED, null).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
        // The single mark an earlier version kept.
        p.getLong(K_ENDED_ONE, 0L).takeIf { it > 0 }?.let { set += it }
        return set
    }

    /** When the oldest call that may still be in the system call log ended (how far back a start looks); null when none waits. */
    @VisibleForTesting
    internal fun markedAt(context: Context): Long? = marks(context).minOrNull()

    @VisibleForTesting
    @Synchronized
    internal fun mark(context: Context, endedAt: Long) {
        prefs(context).edit().putStringSet(K_ENDED, (marks(context) + endedAt).mapTo(HashSet()) { it.toString() }).remove(K_ENDED_ONE).commit()
    }

    /** Removes the marks of the calls in [ended] (every mark when null); marks set meanwhile stay. */
    @VisibleForTesting
    @Synchronized
    internal fun clear(context: Context, ended: Set<Long>? = null) {
        val left = if (ended == null) emptySet() else marks(context) - ended
        val e = prefs(context).edit().remove(K_ENDED_ONE)
        if (left.isEmpty()) e.remove(K_ENDED).commit() else e.putStringSet(K_ENDED, left.mapTo(HashSet()) { it.toString() }).commit()
    }

    /** A call with [number] ended: when it is a private contact's and private call history is on, sweep it now. */
    fun afterCall(context: Context, c: DataContainer, number: String) {
        val endedAt = System.currentTimeMillis()
        val endedElapsed = SystemClock.elapsedRealtime()
        c.scope.launch(Dispatchers.IO) {
            if (!c.settings.current().privateVaultHistory) return@launch
            if (catching { c.vault.lookup(number) }.getOrNull() == null) return@launch
            mark(context, endedAt)
            val since = PrivateCallSweepPlan.sinceFor(endedAt)
            val changes = Channel<Unit>(Channel.CONFLATED)
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    changes.trySend(Unit)
                }
            }
            val cr = context.contentResolver
            val watching = catching { cr.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }.isSuccess
            try {
                withTimeoutOrNull(PrivateCallSweepPlan.WINDOW_MS) {
                    launch {
                        for (at in PrivateCallSweepPlan.CHECKS_MS) {
                            delay((at - (SystemClock.elapsedRealtime() - endedElapsed)).coerceAtLeast(0L))
                            changes.trySend(Unit)
                        }
                    }
                    while (true) {
                        changes.receive()
                        sweep(c, since, endedElapsed)
                    }
                }
            } finally {
                if (watching) catching { cr.unregisterContentObserver(observer) }
            }
            // A last look once the window is over; the mark goes only when that sweep could run.
            if (sweep(c, since, endedElapsed) >= 0) clear(context, setOf(endedAt))
        }
    }

    /** At start: a call marked by a process that ended before its sweep finished is swept now. */
    suspend fun recheck(context: Context, c: DataContainer) {
        val waiting = marks(context)
        val at = waiting.minOrNull() ?: return
        if (!c.settings.current().privateVaultHistory) return clear(context)
        // Swept from the oldest, so every call marked so far is covered; one marked meanwhile keeps its mark.
        if (sweep(c, PrivateCallSweepPlan.sinceFor(at), null) >= 0) clear(context, waiting)
    }

    /** Rows moved, or -1 when the sweep failed (the mark stays). One at a time: overlapping sweeps would only repeat work. */
    @Suppress("TooGenericExceptionCaught") // Any failure (provider, vault, database) only means "try again later".
    private suspend fun sweep(c: DataContainer, since: Long, endedElapsed: Long?): Int = mutex.withLock {
        try {
            c.vault.sweepCallLog(since).also { moved ->
                // The measured window: how long after the call ended its row was gone (stripped from release builds).
                if (moved > 0 && endedElapsed != null) Log.i(TAG, "Moved $moved row(s) ${SystemClock.elapsedRealtime() - endedElapsed} ms after the call ended")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sweep failed: ${e.javaClass.simpleName}")
            -1
        }
    }
}
