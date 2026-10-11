package app.parley.work

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.test.core.app.ApplicationProvider
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.ParleyApp
import app.parley.common.NotificationChannels
import app.parley.container
import app.parley.data.ContactDetails
import app.parley.data.DataItem
import app.parley.data.EventItem
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
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
import java.time.LocalDate

/** The two daily workers: the upkeep never fails its run, and a birthday is announced once, only when asked for. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class WorkersTest {
    private val app: ParleyApp = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG, Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    @After fun tearDown() = app.container.scope.cancel()

    private inline fun <reified W : CoroutineWorker> run(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<W>(app).build().doWork() }

    private fun reminders(): List<Notification> =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.filter { it.channelId == NotificationChannels.REMINDERS }

    private fun adaWithBirthdayToday() = runBlocking {
        val today = LocalDate.now()
        // Without a year, so any day of the year works (29 February too).
        val born = "--%02d-%02d".format(java.util.Locale.ROOT, today.monthValue, today.dayOfMonth)
        app.container.contacts.save(
            null,
            ContactDetails(
                given = "Ada", family = "Lovelace", phones = listOf(DataItem(null, "+44 20 7946 0000", Phone.TYPE_MOBILE)),
                events = listOf(EventItem(null, born, Event.TYPE_BIRTHDAY)),
            ),
            null, null, false,
        )
    }

    @Test fun the_daily_upkeep_always_finishes_its_run() {
        assertEquals(ListenableWorker.Result.success(), run<MaintenanceWorker>())
    }

    @Test fun scheduling_the_upkeep_replaces_the_old_daily_jobs() {
        val wm = WorkManager.getInstance(app)
        MaintenanceWorker.schedule(app)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork("parley-maintenance").get().single().state)
        assertTrue(wm.getWorkInfosForUniqueWork("parley-history").get().none { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test fun a_birthday_today_is_announced_once() {
        runBlocking { app.container.settings.update { it.copy(birthdayReminders = true) } }
        adaWithBirthdayToday()
        assertEquals(ListenableWorker.Result.success(), run<RemindersWorker>())
        val first = reminders()
        assertEquals(1, first.size)
        assertTrue(first.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("Ada"))
        // Its public version names nobody.
        assertTrue("Ada" !in first.single().publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        run<RemindersWorker>()
        assertEquals(1, reminders().size)
    }

    @Test fun no_birthday_notice_when_reminders_are_off() {
        runBlocking { app.container.settings.update { it.copy(birthdayReminders = false, reachOutNudges = false) } }
        adaWithBirthdayToday()
        assertEquals(ListenableWorker.Result.success(), run<RemindersWorker>())
        assertTrue(reminders().isEmpty())
    }
}
