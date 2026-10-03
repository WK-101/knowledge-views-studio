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
    private const val K_ENDED = "ended_at"
    private val mutex = Mutex()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When the newest call that may still be in the system call log ended; null when none waits. */
    @VisibleForTesting
    internal fun markedAt(context: Context): Long? = prefs(context).getLong(K_ENDED, 0L).takeIf { it > 0 }

    @VisibleForTesting
    internal fun mark(context: Context, endedAt: Long) {
        // The oldest waiting call decides how far back the next start looks.
        val kept = markedAt(context)
        prefs(context).edit().putLong(K_ENDED, if (kept != null) minOf(kept, endedAt) else endedAt).commit()
    }

    @VisibleForTesting
    internal fun clear(context: Context, ifAt: Long? = null) {
        if (ifAt == null || markedAt(context) == ifAt) prefs(context).edit().remove(K_ENDED).commit()
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
            if (sweep(c, since, endedElapsed) >= 0) clear(context, ifAt = endedAt)
        }
    }

    /** At start: a call marked by a process that ended before its sweep finished is swept now. */
    suspend fun recheck(context: Context, c: DataContainer) {
        val at = markedAt(context) ?: return
        if (!c.settings.current().privateVaultHistory) return clear(context)
        if (sweep(c, PrivateCallSweepPlan.sinceFor(at), null) >= 0) clear(context, ifAt = at)
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
