package app.parley.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A contact's call is screened without waiting for the lookups only an unknown caller needs. */
class SideLookupsTest {
    @Test fun a_dropped_lookup_is_never_waited_for() = runBlocking {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val answer = coroutineScope {
            val side = SideLookups(Dispatchers.IO, coroutineContext.job)
            // A blocking read that ignores cancellation, like a provider query or parsing a pack index.
            side.start { release.await(5, TimeUnit.SECONDS) }
            side.drop()
            "contact"
        }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        release.countDown()
        assertEquals("contact", answer)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test fun a_needed_lookup_is_awaited_and_its_failure_reaches_only_the_caller() = runBlocking {
        val result = coroutineScope {
            val side = SideLookups(Dispatchers.IO, coroutineContext.job)
            val ok = side.start { 42 }
            val bad = side.start { error("provider failed") }
            val failed = try {
                bad.await()
                false
            } catch (e: IllegalStateException) {
                e.message == "provider failed"
            }
            ok.await() to failed
        }
        assertEquals(42 to true, result)
    }
}
