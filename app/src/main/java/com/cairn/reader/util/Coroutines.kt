package com.cairn.reader.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but it never swallows coroutine cancellation.
 *
 * `runCatching` catches `Throwable`, which includes `kotlinx.coroutines.CancellationException`.
 * Wrapping a suspend call in a bare `runCatching` therefore breaks structured concurrency: when a
 * job is cancelled (WorkManager stops a sync worker, a constraint like Wi-Fi-only is lost, a
 * timeout fires, or the caller's scope is closed) the cancellation is caught and turned into a
 * `Result.failure`, so the loop keeps running and the cancellation is ignored. It also masks fatal
 * errors like `OutOfMemoryError` as ordinary failures.
 *
 * This helper is `inline` with a non-suspend `block` parameter — exactly like `runCatching` — so it
 * can still be called with suspend calls inside the lambda when inlined into a suspend function, and
 * it returns a [Result] so all the usual `getOrNull`/`getOrDefault`/`onFailure` chaining still works.
 * Prefer it over `runCatching` anywhere inside a coroutine.
 */
inline fun <T> coRunCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
