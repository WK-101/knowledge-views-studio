package app.parley.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Long work a person starts from a screen (exports, imports, backups and restores, preparing a share), run in the
 * app's scope so leaving the screen never cancels it half way and leaves a partial file behind.
 *
 * Screens show [running] (a progress bar while their kind of job runs, also after coming back to them). The end of a
 * job is said once: as a snackbar when a Parley screen is showing ([finished] has a collector, see ParleyRoot), or
 * else through [notify] (a notification).
 */
class UserJobs(
    private val scope: CoroutineScope,
    private val notify: (Finished) -> Unit,
) {
    enum class Kind { EXPORT, IMPORT, BACKUP, RESTORE, SHARE }

    /** A job in progress. [total] is 0 while the amount of work isn't known (an indeterminate bar). */
    data class Running(val id: Long, val kind: Kind, val label: String, val done: Int = 0, val total: Int = 0) {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** How a job ended, in words for the person ([message] is never an exception's own text). */
    data class Finished(val id: Long, val kind: Kind, val message: String, val failed: Boolean)

    /** Given to the work so it can report how far it is. */
    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    private val ids = AtomicLong()
    private val _running = MutableStateFlow<List<Running>>(emptyList())
    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 8)

    val running: StateFlow<List<Running>> = _running.asStateFlow()

    /** Ends of jobs while a screen is showing. Collected only while the main screen is started. */
    val finished: SharedFlow<Finished> = _finished.asSharedFlow()

    /** Whether a job of [kind] is running (to disable a second start of the same export, for example). */
    fun isRunning(kind: Kind): Boolean = _running.value.any { it.kind == kind }

    /**
     * Starts [work] in the app's scope. Its result is the message to show when it succeeds (null for none); a failure
     * is turned into words by [failure]. Cancellation (the app closing) is silent.
     */
    @Suppress("TooGenericExceptionCaught")
    fun start(
        kind: Kind,
        label: String,
        failure: (Throwable) -> String,
        work: suspend (Progress) -> String?,
    ): Job {
        val id = ids.incrementAndGet()
        _running.update { it + Running(id, kind, label) }
        val progress = Progress { done, total -> _running.update { list -> list.map { if (it.id == id) it.copy(done = done, total = total) else it } } }
        return scope.launch {
            val outcome = try {
                work(progress)?.let { Finished(id, kind, it, failed = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever went wrong becomes the job's message: the work started from a screen that may be gone.
                Finished(id, kind, failure(e), failed = true)
            } finally {
                _running.update { list -> list.filterNot { it.id == id } }
            }
            outcome?.let(::announce)
        }
    }

    private fun announce(f: Finished) {
        // A screen is listening (Parley is in front): a snackbar there. Otherwise the person left: a notification.
        if (_finished.subscriptionCount.value > 0 && _finished.tryEmit(f)) return
        notify(f)
    }
}
