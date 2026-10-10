package app.parley.testing

import android.os.Looper
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.locks.LockSupport

/**
 * Runs the main looper until [check] holds, while the app's background threads do their part; fails after [timeoutMs]
 * naming [what]. The one wait the Robolectric tests share, instead of a sleep loop in each.
 */
fun awaitMain(what: () -> String, timeoutMs: Long = 10_000, check: () -> Boolean) {
    val looper = shadowOf(Looper.getMainLooper())
    val end = System.nanoTime() + timeoutMs * NANOS_PER_MS
    while (true) {
        looper.idle()
        if (check()) break
        if (System.nanoTime() > end) fail("Timed out waiting for ${what()}")
        // Hands the CPU to the background work for a moment instead of sleeping a set time.
        LockSupport.parkNanos(POLL_NANOS)
    }
    looper.idle()
}

/** [awaitMain] with a fixed description. */
fun awaitMain(what: String, check: () -> Boolean) = awaitMain({ what }, check = check)

private const val NANOS_PER_MS = 1_000_000L
private const val POLL_NANOS = 500_000L
