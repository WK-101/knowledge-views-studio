package app.parley.calls

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The mark that makes the next start sweep a private call a process couldn't finish with. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrivateCallLogSweepTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before fun clean() = PrivateCallLogSweep.clear(context)

    @Test fun the_oldest_waiting_call_is_kept() {
        assertNull(PrivateCallLogSweep.markedAt(context))
        PrivateCallLogSweep.mark(context, 5_000L)
        PrivateCallLogSweep.mark(context, 9_000L)
        assertEquals(5_000L, PrivateCallLogSweep.markedAt(context))
        PrivateCallLogSweep.mark(context, 2_000L)
        assertEquals(2_000L, PrivateCallLogSweep.markedAt(context))
    }

    @Test fun a_finished_sweep_clears_only_its_own_mark() {
        PrivateCallLogSweep.mark(context, 5_000L)
        // A newer call's sweep ending doesn't clear an older call's mark (that one's start may not have run).
        PrivateCallLogSweep.clear(context, ifAt = 9_000L)
        assertEquals(5_000L, PrivateCallLogSweep.markedAt(context))
        PrivateCallLogSweep.clear(context, ifAt = 5_000L)
        assertNull(PrivateCallLogSweep.markedAt(context))
    }
}
