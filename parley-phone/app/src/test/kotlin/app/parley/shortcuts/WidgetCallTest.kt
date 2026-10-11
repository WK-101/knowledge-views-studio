package app.parley.shortcuts

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.ParleyApp
import app.parley.container
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeCallLogProvider
import app.parley.data.testing.FakeContactsProvider
import app.parley.messaging.NumberActionActivity
import app.parley.testing.awaitMain
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Calls from widgets, shortcuts and reminders with "Confirm before calling" on ask first, with the person's name, and
 * never touch the missed calls; only the missed-call notification's own "Call back" marks them seen, once the call
 * is placed. Cancelling leaves everything as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class WidgetCallTest {
    private val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private lateinit var calls: FakeCallLogProvider
    private val controllers = ArrayList<ActivityController<*>>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        calls = FakeCallLogProvider.install()
        shadowOf(app).grantPermissions(
            Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.CALL_PHONE, Manifest.permission.HIDE_OVERLAY_WINDOWS,
        )
        // Two missed calls from Ben, not yet seen.
        repeat(2) { i ->
            app.contentResolver.insert(
                CallLog.Calls.CONTENT_URI,
                ContentValues().apply {
                    put(CallLog.Calls.NUMBER, "+44 20 7946 0022")
                    put(CallLog.Calls.DATE, System.currentTimeMillis() - (i + 1) * 60_000L)
                    put(CallLog.Calls.TYPE, CallLog.Calls.MISSED_TYPE)
                    put(CallLog.Calls.NEW, 1)
                    put(CallLog.Calls.IS_READ, 0)
                },
            )
        }
    }

    @After fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        app.container.scope.cancel()
    }

    private fun confirm(on: Boolean) = runBlocking { app.container.settings.update { it.copy(confirmBeforeCall = on) } }

    private fun unseenMissed(): Int = calls.rows().count { it["type"] == CallLog.Calls.MISSED_TYPE.toString() && it["new"] == "1" }

    private fun placed(): Int = shadowOf(app.getSystemService(TelecomManager::class.java)).allOutgoingCalls.size

    private fun until(what: String, check: () -> Boolean) = awaitMain(what, check)

    /** Lets the app's background work run for a moment (nothing should change in it). */
    private fun settle() = repeat(40) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    private fun <T : android.app.Activity> start(type: Class<T>, intent: Intent): T =
        Robolectric.buildActivity(type, intent).also { controllers += it }.setup().get()

    @Test fun a_widget_call_with_confirm_on_asks_with_the_name_and_leaves_missed_calls_alone() {
        confirm(true)
        val shortcut = start(ShortcutActivity::class.java, Shortcuts.intent(app, Shortcuts.Kind.CALL, "+44 20 7946 0011", 11, "Ana"))
        until("the question to be asked") { shadowOf(shortcut).peekNextStartedActivity() != null }
        val asked = shadowOf(shortcut).nextStartedActivity
        assertEquals(NumberActionActivity.ACTION_CALL_CONFIRM, asked.action)
        assertEquals("Ana", asked.getStringExtra(NumberActionActivity.EXTRA_NAME))

        val sheet = start(NumberActionActivity::class.java, asked)
        until("the confirm question") { sheet.pendingCall != null }
        assertEquals("Ana", sheet.pendingCall?.name)
        settle()
        assertEquals("nothing marked seen while it asks", 2, unseenMissed())
        // Cancel: nothing placed, the missed calls still unseen.
        sheet.finish()
        settle()
        assertEquals(0, placed())
        assertEquals(2, unseenMissed())
    }

    @Test fun a_widget_call_without_confirm_places_the_call_and_leaves_missed_calls_alone() {
        confirm(false)
        start(NumberActionActivity::class.java, NumberActionActivity.confirmCallIntent(app, "+44 20 7946 0011", "Ana"))
        until("the call") { placed() == 1 }
        settle()
        assertEquals(2, unseenMissed())
    }

    @Test fun call_back_from_the_missed_call_notification_marks_them_seen_once_placed() {
        confirm(false)
        start(NumberActionActivity::class.java, NumberActionActivity.callBackIntent(app, "+44 20 7946 0022"))
        until("the call") { placed() == 1 }
        until("the missed calls to be seen") { unseenMissed() == 0 }
    }

    @Test fun call_back_cancelled_at_the_question_leaves_the_missed_calls_unseen() {
        confirm(true)
        val sheet = start(NumberActionActivity::class.java, NumberActionActivity.callBackIntent(app, "+44 20 7946 0022"))
        until("the confirm question") { sheet.pendingCall != null }
        assertNotNull(sheet.pendingCall)
        sheet.finish()
        settle()
        assertEquals(0, placed())
        assertEquals(2, unseenMissed())
    }
}
