package app.parley.calls

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** The missed-call notification waits for the name of a call that just ended, but never for long. */
@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class NetworkNamesSettleTest {
    @Test fun waits_for_a_name_being_written() = runBlocking {
        var written = false
        val job = GlobalScope.launch(Dispatchers.IO) {
            delay(200)
            written = true
        }
        NetworkNames.track(job)
        NetworkNames.settle()
        assertTrue(written)
    }

    @Test fun gives_up_on_a_write_that_hangs() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val job = GlobalScope.launch(Dispatchers.IO) { never.await() }
        NetworkNames.track(job)
        val started = System.nanoTime()
        NetworkNames.settle(timeoutMs = 100)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        job.cancelAndJoin()
    }

    @Test fun nothing_pending_returns_at_once() = runBlocking {
        val started = System.nanoTime()
        NetworkNames.settle()
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
    }
}
