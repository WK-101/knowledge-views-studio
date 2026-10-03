package app.parley.shortcuts

import android.app.Application
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.common.AppSettings
import app.parley.security.AppLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leaving Parley with a lock delay sets up a widget redraw for when the delay runs out; coming back cancels it. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetLockRefreshTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val away = ArrayList<Long>()
    private var back = 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        AppLock.onAway = { away += it }
        AppLock.onBack = { back++ }
    }

    @After fun tearDown() {
        AppLock.onAway = null
        AppLock.onBack = null
    }

    @Test fun leaving_with_a_delay_asks_for_a_redraw_when_it_runs_out() {
        AppLock.onStop(AppSettings(appLock = true, lockAfterMinutes = 5))
        assertEquals(listOf(5 * 60_000L), away)
        // "Lock immediately" locks at once (the lock redraws the widgets); no app lock: nothing to hide.
        AppLock.onStop(AppSettings(appLock = true, lockAfterMinutes = 0))
        AppLock.onStop(AppSettings(appLock = false, lockAfterMinutes = 5))
        assertEquals(1, away.size)
        AppLock.onStart(AppSettings(appLock = false))
        assertEquals(1, back)
    }

    @Test fun the_redraw_survives_the_process_as_a_one_time_job_and_coming_back_cancels_it() {
        val wm = WorkManager.getInstance(context)
        WidgetLockRefresh.schedule(context, 60_000L)
        val info = wm.getWorkInfosForUniqueWork("parley-widget-lock-refresh").get().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.initialDelayMillis >= 60_000L)
        WidgetLockRefresh.cancel(context)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork("parley-widget-lock-refresh").get().single().state)
    }
}
