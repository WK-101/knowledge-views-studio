package app.parley.jobs

import android.app.Application
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A running job's notification on Android 16: one segment as long as the job, filled as far as it got. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class JobProgressStyleTest {
    private fun running(done: Int, total: Int) = UserJobs.Running(1, UserJobs.Kind.EXPORT, "Exporting", done, total)

    @Test fun a_known_size_fills_one_segment() {
        val style = JobNotices.progressStyle(running(done = 40, total = 100))
        assertEquals(1, style.progressSegments.size)
        assertEquals(100, style.progressMax)
        assertEquals(40, style.progress)
        assertFalse(style.isProgressIndeterminate)
    }

    @Test fun an_unknown_size_is_a_moving_bar() {
        val style = JobNotices.progressStyle(running(done = 0, total = 0))
        assertTrue(style.isProgressIndeterminate)
        assertEquals(0, style.progress)
    }

    @Test fun progress_never_runs_past_the_end() {
        assertEquals(10, JobNotices.progressStyle(running(done = 12, total = 10)).progress)
    }

    @Test fun before_android_16_the_plain_bar_stays() {
        // Robolectric runs SDK 35 here: the notification keeps setProgress and carries no style.
        val context: Application = ApplicationProvider.getApplicationContext()
        val n = JobNotices.progress(context, running(done = 3, total = 10))
        assertEquals(10, n.extras.getInt(NotificationCompat.EXTRA_PROGRESS_MAX))
        assertEquals(3, n.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertNull(n.extras.getString(NotificationCompat.EXTRA_TEMPLATE))
    }
}
