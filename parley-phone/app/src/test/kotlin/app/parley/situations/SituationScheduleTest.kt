package app.parley.situations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every process start, a ringing call's included, schedules the Situations window job (PERFORMANCE B13). With no
 * Situation on a window, WorkManager (its database and scheduler) is touched once to cancel a job from before, and
 * never again after that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SituationScheduleTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var opened = 0
    private val workManager: () -> WorkManager? = { opened++; null }
    private val real: () -> WorkManager? = { opened++; WorkManager.getInstance(context) }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("situation_triggers", 0).edit().clear().commit()
    }

    @Test fun no_window_leaves_workmanager_alone_after_the_first_cancel() {
        // Updated from a version that didn't record it: one cancel, in case a job is queued.
        SituationTriggers.schedule(context, next = null, fromWorker = false, workManager = real)
        assertEquals(1, opened)
        repeat(3) { SituationTriggers.schedule(context, next = null, fromWorker = false, workManager = workManager) }
        assertEquals("later starts don't open WorkManager", 1, opened)
    }

    @Test fun a_window_is_scheduled_and_then_cancelled_once() {
        SituationTriggers.schedule(context, next = System.currentTimeMillis() + 60_000, fromWorker = false, workManager = real)
        SituationTriggers.schedule(context, next = null, fromWorker = false, workManager = real)
        SituationTriggers.schedule(context, next = null, fromWorker = false, workManager = workManager)
        assertEquals(2, opened)
    }
}
