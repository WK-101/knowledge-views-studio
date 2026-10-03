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
        PrivateCallLogSweep.clear(context, setOf(9_000L))
        assertEquals(5_000L, PrivateCallLogSweep.markedAt(context))
        PrivateCallLogSweep.clear(context, setOf(5_000L))
        assertNull(PrivateCallLogSweep.markedAt(context))
    }

    @Test fun two_calls_ending_close_together_each_keep_their_mark() {
        // Call A ends, then call B; A's window ends first and clears only A.
        PrivateCallLogSweep.mark(context, 1_000L)
        PrivateCallLogSweep.mark(context, 20_000L)
        PrivateCallLogSweep.clear(context, setOf(1_000L))
        // B's mark is still there: if the process dies before B's last sweep, the next start sweeps it.
        assertEquals(setOf(20_000L), PrivateCallLogSweep.marks(context))
        assertEquals(20_000L, PrivateCallLogSweep.markedAt(context))
    }

    @Test fun the_single_mark_of_an_earlier_version_is_still_read() {
        context.getSharedPreferences("private_call_sweep", android.content.Context.MODE_PRIVATE).edit().putLong("ended_at", 7_000L).commit()
        assertEquals(7_000L, PrivateCallLogSweep.markedAt(context))
        PrivateCallLogSweep.mark(context, 8_000L)
        assertEquals(setOf(7_000L, 8_000L), PrivateCallLogSweep.marks(context))
        PrivateCallLogSweep.clear(context, setOf(7_000L, 8_000L))
        assertNull(PrivateCallLogSweep.markedAt(context))
    }
}
