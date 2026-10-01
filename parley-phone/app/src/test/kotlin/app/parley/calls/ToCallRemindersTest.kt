package app.parley.calls

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.common.NotificationIds
import app.parley.common.calls.ToCall
import app.parley.data.DataContainer
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.data.vault.VaultCrypto
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The To call reminder's worker never replaces (cancels) itself. A due item is posted once and marked shown; the
 * run ends SUCCEEDED, not CANCELLED, and nothing is queued to run again for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ToCallRemindersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var c: DataContainer
    private lateinit var wm: WorkManager

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        shadowOf(context).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS)
        VaultCrypto.appContext = context
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        wm = WorkManager.getInstance(context)
        c = DataContainer(context)
        ToCallReminders.containerOf = { c }
    }

    @After fun tearDown() {
        c.scope.cancel()
    }

    private fun infos(): List<WorkInfo> = wm.getWorkInfosForUniqueWork("to_call_reminder").get()

    private fun posted(): Int = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size

    /** Lets every waiting run start and waits until none is running (the worker is a coroutine on its own threads). */
    private fun runAll() {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        repeat(5) {
            infos().filter { it.state == WorkInfo.State.ENQUEUED }.forEach { runCatching { driver.setInitialDelayMet(it.id) } }
            val until = System.currentTimeMillis() + 10_000
            while (infos().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } && System.currentTimeMillis() < until) {
                Thread.sleep(20)
                shadowOf(android.os.Looper.getMainLooper()).idle()
                infos().filter { it.state == WorkInfo.State.ENQUEUED }.forEach { runCatching { driver.setInitialDelayMet(it.id) } }
            }
        }
    }

    @Test fun a_due_item_is_notified_exactly_once() = runBlocking {
        val now = System.currentTimeMillis()
        assertTrue(ToCallReminders.remind(context, "+1 202 555 0100", null, at = now - 60_000, now = now - 120_000))
        assertEquals(1, infos().size)

        runAll()

        val runs = infos()
        assertTrue("never cancelled by its own reschedule: $runs", runs.none { it.state == WorkInfo.State.CANCELLED })
        assertTrue(runs.all { it.state == WorkInfo.State.SUCCEEDED })
        assertEquals(1, posted())
        val item = c.toCall.state.value.items.single()
        assertTrue(item.notified)
        assertEquals(null, ToCall.nextAlarm(c.toCall.state.value, System.currentTimeMillis()))

        // Seen and swiped away: nothing brings it back on its own.
        context.getSystemService(NotificationManager::class.java).cancel(NotificationIds.TAG_TO_CALL, NotificationIds.TO_CALL_ID)
        runAll()
        assertEquals(0, posted())
    }

    @Test fun a_change_that_changes_nothing_schedules_nothing() = runBlocking {
        val now = System.currentTimeMillis()
        ToCallReminders.remind(context, "+1 202 555 0100", null, at = now + 3_600_000, now = now)
        val first = infos().single().id
        ToCallReminders.update(context) { it }
        assertEquals(listOf(first), infos().map { it.id })
    }
}
