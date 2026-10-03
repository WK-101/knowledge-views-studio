package app.parley.jobs

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import app.parley.common.NotificationIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The work request that keeps a job running: it waits for its job, and after Parley was stopped it says so. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UserJobWorkerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = UserJobs(scope) { }

    @Before fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        UserJobWorker.jobsOf = { jobs }
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun worker(id: Long, token: String) = TestListenableWorkerBuilder<UserJobWorker>(context)
        .setInputData(
            workDataOf(UserJobWorker.K_ID to id, UserJobWorker.K_TOKEN to token, UserJobWorker.K_KIND to "EXPORT", UserJobWorker.K_LABEL to "Exporting…"),
        )
        .build()

    private fun jobNotices() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        .filter { it.channelId == app.parley.common.NotificationChannels.JOBS }

    @Test fun it_waits_for_its_job_to_end() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        jobs.start(UserJobs.Kind.EXPORT, "Exporting…", { "failed" }) { gate.await(); null }
        val run = scope.async { worker(1, jobs.token).doWork() }
        Thread.sleep(100)
        assertFalse(run.isCompleted)
        gate.complete(Unit)
        assertEquals(ListenableWorker.Result.success(), run.await())
        assertTrue(jobNotices().isEmpty())
    }

    @Test fun a_job_from_a_stopped_process_is_said_not_to_have_finished() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker(1, "a stopped process").doWork())
        val n = jobNotices().single()
        assertEquals(context.getString(app.parley.R.string.job_failed_title), n.extras.getString("android.title"))
        // Its own id, apart from this process's jobs (which start at 1).
        val active = shadowOf(context.getSystemService(NotificationManager::class.java)).activeNotifications
        assertTrue(active.none { it.tag == NotificationIds.TAG_JOBS && it.id == 1 })
    }

    @Test fun a_job_that_already_ended_here_needs_nothing() = runBlocking {
        assertEquals(ListenableWorker.Result.success(), worker(7, jobs.token).doWork())
        assertTrue(jobNotices().isEmpty())
    }
}
