package app.parley.situations

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.ParleyApp
import app.parley.common.situations.SituationKind
import app.parley.container
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.testing.awaitMain
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Situations widget turns a Situation on and off through the controller the app and the tile use, never while
 * the phone is locked, and is drawn again whenever a Situation changes, whatever changed it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class SituationsWidgetTest {
    private val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private val manager get() = shadowOf(AppWidgetManager.getInstance(app))
    private val widgetId = 57

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
        val provider = ComponentName(app, SituationsWidget::class.java)
        manager.addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = provider })
        manager.bindAppWidgetId(widgetId, provider)
    }

    @After fun tearDown() {
        app.container.scope.cancel()
    }

    private fun locked(on: Boolean) = shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(on)

    @Test fun a_tap_turns_a_situation_on_then_off_but_never_on_a_locked_phone() {
        val sit = app.container.situations
        val night = sit.list.value.first { it.kind == SituationKind.NIGHT }
        val tap = SituationsWidget.toggleTap(app, widgetId, sit.list.value.indexOf(night), night)
        // Android delivers the explicit tap to the manifest receiver whatever its filter; Robolectric matches filters, so
        // the receiver is registered here for the tap's action and scheme.
        val filter = IntentFilter(SituationsWidget.ACTION_TOGGLE).apply { addDataScheme("parley-widget") }
        app.registerReceiver(SituationsWidget(), filter, Context.RECEIVER_NOT_EXPORTED)

        locked(true)
        tap.send()
        awaitMain("the widget drawn") { manager.getViewFor(widgetId) != null }
        Thread.sleep(300)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(null, sit.state.value.activeId)

        locked(false)
        tap.send()
        awaitMain("Night on") { sit.state.value.activeId == night.id }
        tap.send()
        awaitMain("Night off") { sit.state.value.activeId == null }
    }

    @Test fun a_change_made_elsewhere_redraws_the_widget_and_counts_for_the_single_toggle() {
        val sit = app.container.situations
        val meeting = sit.list.value.first { it.kind == SituationKind.MEETING }
        kotlinx.coroutines.runBlocking { sit.turnOn(meeting.id) }
        // The app's change listener (or this call, in a process where it isn't wired yet) redraws the widget.
        SituationsWidget.update(app)
        awaitMain("the widget drawn") { manager.getViewFor(widgetId) != null }
        val used = app.getSharedPreferences("local_state", 0)
        awaitMain("Meeting counted") { used.getInt("situation_widgets.used.${meeting.id}", 0) == 1 }
        kotlinx.coroutines.runBlocking { sit.turnOff() }
    }
}
