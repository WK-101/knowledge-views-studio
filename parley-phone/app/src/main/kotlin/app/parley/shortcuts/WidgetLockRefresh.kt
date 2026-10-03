package app.parley.shortcuts

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.parley.common.catching
import app.parley.container
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Redraws the home-screen widgets when Parley's lock delay runs out after you leave it, so names give way to counts
 * then and not only at the next screen-on (the lock itself engages only when Parley next starts).
 *
 * Two ways, neither needing an exact-alarm permission: a main-thread timer while the process runs, and a one-time
 * WorkManager job for when Android freezes or ends the process meanwhile (it may run a few minutes late; a new process
 * counts as locked anyway). Coming back to Parley cancels both.
 */
object WidgetLockRefresh {
    private const val NAME = "parley-widget-lock-refresh"

    /** Past the moment, so [app.parley.security.AppLock.lockedFor] already says "away past the delay". */
    private const val MARGIN_MS = 1_000L

    private val main = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    /** Whether this process scheduled a redraw (a job left by an earlier process just redraws as things are). */
    @Volatile private var scheduled = false

    /** Parley went to the background with a lock delay of [delayMs]. */
    fun schedule(context: Context, delayMs: Long) {
        val ctx = context.applicationContext
        cancelTimer()
        val r = Runnable { ctx.container.scope.launch { redraw(ctx) } }
        pending = r
        main.postDelayed(r, delayMs + MARGIN_MS)
        scheduled = true
        catching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<WidgetLockWorker>().setInitialDelay(delayMs + MARGIN_MS, TimeUnit.MILLISECONDS).build(),
            )
        }
    }

    /** Parley is in front again: nothing to hide on a timer. */
    fun cancel(context: Context) {
        cancelTimer()
        if (!scheduled) return
        scheduled = false
        catching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME) }
    }

    private fun cancelTimer() {
        pending?.let(main::removeCallbacks)
        pending = null
    }

    internal suspend fun redraw(context: Context) {
        catching { CircleWidget.refresh(context) }
        catching { FavoritesWidget.refresh(context) }
    }
}

/** [WidgetLockRefresh]'s redraw when the process may have been frozen or ended meanwhile. */
class WidgetLockWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetLockRefresh.redraw(applicationContext)
        return Result.success()
    }
}
