package app.parley

import android.app.Application
import android.os.Trace
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two application starts whose warm-ups overlap trace two sections, never one begun twice (which crashes the trace). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WarmTraceTest {
    @Test fun overlapping_warm_ups_trace_separate_sections() {
        val earlier = nextWarmCookie()
        val later = nextWarmCookie()
        assertNotEquals(earlier, later)
        // The earlier start's section is still open as the later one begins its own.
        Trace.beginAsyncSection(TRACE_WARM_SECTION, earlier)
        Trace.beginAsyncSection(TRACE_WARM_SECTION, later)
        Trace.endAsyncSection(TRACE_WARM_SECTION, later)
        Trace.endAsyncSection(TRACE_WARM_SECTION, earlier)
    }
}
