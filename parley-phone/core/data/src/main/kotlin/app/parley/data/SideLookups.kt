package app.parley.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlin.coroutines.CoroutineContext

/**
 * Lookups that may turn out not to be needed, started early so they run alongside the ones that decide whether they
 * are. They run outside the caller's scope, so a caller that ends without them never waits for them: a provider query
 * or a file read can't be interrupted half way, and `coroutineScope` would otherwise join it. [drop] cancels what is
 * still running (its answer is never read); [owner] ending drops them too. A failure surfaces only to [Deferred.await].
 */
internal class SideLookups(context: CoroutineContext, owner: Job?) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(context + job)

    init {
        owner?.invokeOnCompletion { job.cancel() }
    }

    fun <T> start(block: suspend () -> T): Deferred<T> = scope.async { block() }

    fun drop() = job.cancel()
}
