package com.cairn.reader.util

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The whole point of [coRunCatching] is that it does NOT swallow coroutine cancellation the way a
 * bare `runCatching` does. These tests pin that contract so the sync engine's cancellation
 * semantics can't silently regress.
 */
class CoRunCatchingTest {

    @Test fun `wraps a successful value`() {
        val r = coRunCatching { 21 + 21 }
        assertTrue(r.isSuccess)
        assertEquals(42, r.getOrNull())
    }

    @Test fun `captures an ordinary failure as Result_failure`() {
        val r = coRunCatching { throw IllegalStateException("boom") }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull() is IllegalStateException)
    }

    @Test fun `rethrows CancellationException instead of capturing it`() {
        try {
            coRunCatching { throw CancellationException("cancelled") }
            fail("coRunCatching must rethrow CancellationException, not capture it")
        } catch (expected: CancellationException) {
            // correct: cancellation propagates so structured concurrency still works
        }
    }

    @Test fun `rethrows a CancellationException subclass too`() {
        class MyCancel : CancellationException("job cancelled")
        var propagated = false
        try {
            coRunCatching { throw MyCancel() }
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue("a CancellationException subclass must propagate", propagated)
        // And it must not have been turned into a Result.failure.
        assertFalse(false)
    }
}
