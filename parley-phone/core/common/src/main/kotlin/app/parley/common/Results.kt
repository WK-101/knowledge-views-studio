package app.parley.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: failures become a [Result], but cancellation is rethrown so a cancelled
 * scope stops instead of carrying on (and reporting "failed") as if the work had gone wrong.
 */
inline fun <T> suspendRunCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
