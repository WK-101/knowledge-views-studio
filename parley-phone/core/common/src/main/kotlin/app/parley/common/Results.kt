package app.parley.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] that is safe in coroutine code: failures become a [Result], but cancellation is rethrown so a
 * cancelled scope stops instead of carrying on (and reporting "failed") as if the work had gone wrong. Errors
 * (out of memory, stack overflow) are not caught either. The detekt rule RunCatchingInSuspend points here.
 */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
