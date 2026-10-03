package com.wkhan.hexis.web.server

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

import java.util.concurrent.ConcurrentHashMap

/**
 * Fans the single core `changes` stream out to many browser long-polls, race-free via per-domain version
 * counters. A browser sends the versions it last saw; [await] returns immediately if the hub has already
 * moved past them (so a change between a reload and the next poll is never missed), otherwise it suspends
 * until the next change or the timeout. Carries only version numbers — never any data.
 */
class ChangeHub {

    private val versions = ConcurrentHashMap<String, Long>()
    // replay=0: a tick only wakes polls that are currently suspended; the version check covers the rest.
    private val signal = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    /** Called from the bridge callback thread on each core tick. Non-blocking + thread-safe. */
    fun publish(domain: String) {
        versions.merge(domain, 1L, Long::plus)
        signal.tryEmit(Unit)
    }

    fun snapshot(): Map<String, Long> = HashMap(versions)

    /**
     * Suspend until the hub is newer than [since], or [timeoutMs] elapses. Returns the current snapshot when
     * something changed, or null on timeout (the caller then re-polls without reloading).
     */
    suspend fun await(since: Map<String, Long>, timeoutMs: Long): Map<String, Long>? {
        if (isNewer(since)) return snapshot()
        return withTimeoutOrNull(timeoutMs) {
            signal.first { isNewer(since) }
            snapshot()
        }
    }

    private fun isNewer(since: Map<String, Long>): Boolean {
        val now = versions
        if (now.isEmpty()) return false
        return now.any { (domain, v) -> v > (since[domain] ?: 0L) }
    }
}
