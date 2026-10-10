package app.parley.jobs

import app.parley.common.catching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Long work a person starts from a screen (exports, imports, backups and restores, preparing a share), run in the
 * app's scope so leaving the screen never cancels it half way and leaves a partial file behind.
 *
 * Each job also asks the system to keep Parley running until it ends ([Host.keepAlive]: expedited WorkManager work,
 * see [UserJobWorker]), so pressing Home doesn't freeze or end it half way. If Parley is stopped anyway, the work
 * request runs again in the next process: it deletes the half-written file and says the job didn't finish.
 *
 * Screens show [running] (a progress bar while their kind of job runs, also after coming back to them). The end of a
 * job is said once: as a snackbar when a Parley screen is showing ([finished] has a collector, see ParleyRoot), or
 * else through [notify] (a notification). A job never opens another app itself: a prepared file ([Opener]) is shared
 * or printed when the person taps that snackbar or notification.
 */
class UserJobs(
    private val scope: CoroutineScope,
    private val host: Host = Host.None,
    private val notify: (Finished) -> Unit,
) {
    enum class Kind { EXPORT, IMPORT, BACKUP, RESTORE, SHARE }

    /** What keeps jobs going beyond Parley's screens, and cleans up after one that failed. */
    interface Host {
        /** Asks the system to keep Parley running while job [job] runs ([token]: this process, see [UserJobs.token]). */
        fun keepAlive(job: Running, output: String?, token: String) = Unit

        /** Job [id] ended: nothing more to keep running for it. */
        fun ended(id: Long, token: String) = Unit

        /** Deletes [output] (a document URI), which a failed or cancelled job left half written. */
        fun discard(output: String) = Unit

        object None : Host
    }

    /** A job in progress. [total] is 0 while the amount of work isn't known (an indeterminate bar). */
    data class Running(val id: Long, val kind: Kind, val label: String, val done: Int = 0, val total: Int = 0) {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * A file a job prepared for another app: [file] is its name in the export folder, [mime] its type; [print] opens
     * the print dialog instead of the share sheet.
     */
    data class Opener(val file: String, val mime: String, val print: Boolean = false)

    /** How a job ended, in words for the person ([message] is never an exception's own text). */
    data class Finished(val id: Long, val kind: Kind, val message: String, val failed: Boolean, val opener: Opener? = null)

    /** What a job that prepares a file returns: the words to say and the file to hand over when tapped. */
    data class Ready(val message: String, val opener: Opener)

    /** Given to the work so it can report how far it is. */
    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    /** This process's jobs: a work request carrying another token was left by a process that was stopped. */
    val token: String = UUID.randomUUID().toString()

    private val ids = AtomicLong()
    private val active = ConcurrentHashMap<Long, Job>()
    private val _running = MutableStateFlow<List<Running>>(emptyList())
    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 8)

    val running: StateFlow<List<Running>> = _running.asStateFlow()

    /** Ends of jobs while a screen is showing. Collected only while the main screen is started. */
    val finished: SharedFlow<Finished> = _finished.asSharedFlow()

    /** Whether a Parley screen is showing (it says the end of a job; otherwise a notification does). */
    val inFront: Flow<Boolean> = _finished.subscriptionCount.map { it > 0 }

    /** Whether a job of [kind] is running (to disable a second start of the same export, for example). */
    fun isRunning(kind: Kind): Boolean = _running.value.any { it.kind == kind }

    /** Job [id] of this process while it runs, else null (it ended, or [token] is another process's). */
    fun job(id: Long, token: String): Job? = if (token == this.token) active[id] else null

    /**
     * Starts [work] in the app's scope. Its result is the message to show when it succeeds (null for none); a failure
     * is turned into words by [failure]. [output] is the document the job writes: deleted if the job fails, is
     * cancelled or never finishes. Cancellation (the app closing) is silent.
     */
    fun start(
        kind: Kind,
        label: String,
        failure: (Throwable) -> String,
        output: String? = null,
        work: suspend (Progress) -> String?,
    ): Job = launch(kind, label, failure, output) { p -> work(p)?.let { it to null } }

    /** Like [start], for a job that prepares a file the person then shares or prints by tapping its end message. */
    fun prepare(kind: Kind, label: String, failure: (Throwable) -> String, work: suspend (Progress) -> Ready): Job =
        launch(kind, label, failure, null) { p -> work(p).let { it.message to it.opener } }

    @Suppress("TooGenericExceptionCaught") // A job's failure of any kind is reported to the user, never thrown at the caller.
    private fun launch(
        kind: Kind,
        label: String,
        failure: (Throwable) -> String,
        output: String?,
        work: suspend (Progress) -> Pair<String, Opener?>?,
    ): Job {
        val id = ids.incrementAndGet()
        val first = Running(id, kind, label)
        _running.update { it + first }
        val progress = Progress { done, total -> _running.update { list -> list.map { if (it.id == id) it.copy(done = done, total = total) else it } } }
        // Started once it is registered, so a job that ends at once is never left in [active].
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var ok = false
            val outcome = try {
                work(progress).also { ok = true }?.let { (message, opener) -> Finished(id, kind, message, failed = false, opener = opener) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Whatever went wrong becomes the job's message: the work started from a screen that may be gone.
                Finished(id, kind, failure(e), failed = true)
            } finally {
                // A half-written file is worse than none: it would look like a finished export.
                if (!ok && output != null) catching { host.discard(output) }
                active.remove(id)
                _running.update { list -> list.filterNot { it.id == id } }
                catching { host.ended(id, token) }
            }
            outcome?.let(::announce)
        }
        active[id] = job
        catching { host.keepAlive(first, output, token) }
        job.start()
        return job
    }

    private fun announce(f: Finished) {
        // A screen is listening (Parley is in front): a snackbar there. Otherwise the person left: a notification.
        if (_finished.subscriptionCount.value > 0 && _finished.tryEmit(f)) return
        notify(f)
    }
}
