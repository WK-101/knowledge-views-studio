package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class ResultsTest {
    @Test fun success_and_failure_become_results() {
        assertEquals(3, catching { 1 + 2 }.getOrNull())
        val failed = catching<Int> { throw IllegalStateException("disk full") }
        assertTrue(failed.exceptionOrNull() is IllegalStateException)
    }

    @Test(expected = CancellationException::class)
    fun cancellation_is_rethrown() {
        catching { throw CancellationException("left the screen") }
    }

    @Test fun errors_are_not_caught() {
        val thrown = runCatching { catching { throw StackOverflowError() } }.exceptionOrNull()
        assertTrue(thrown is StackOverflowError)
    }
}

class CatchingTest {
    @Test fun failures_become_results_and_cancellation_does_not() {
        assertEquals("ok", catching { "ok" }.getOrNull())
        assertTrue(catching { error("broken") }.isFailure)
        val thrown = runCatching { catching { throw CancellationException("left the screen") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }

    @Test fun the_older_name_behaves_the_same() {
        val thrown = runCatching { catching { throw CancellationException("stop") } }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
    }
}
