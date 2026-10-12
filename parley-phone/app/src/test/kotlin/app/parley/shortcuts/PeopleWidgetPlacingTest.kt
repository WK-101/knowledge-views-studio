package app.parley.shortcuts

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.parley.ParleyApp
import app.parley.R
import app.parley.container
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.testing.awaitMain
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * A People widget being placed is drawn while its settings are open, so Back leaves a working widget (the system
 * sends no update while it asks); the old Circle widget's entry is hidden from the list, never disabled, since Android
 * removes the placed widgets of a disabled provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class PeopleWidgetPlacingTest {
    private val app: ParleyApp = ApplicationProvider.getApplicationContext()
    private val manager get() = shadowOf(AppWidgetManager.getInstance(app))

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        FakeAndroidKeyStore.install()
        FakeContactsProvider.install()
    }

    @After fun tearDown() {
        app.container.scope.cancel()
    }

    @Test fun backing_out_of_the_settings_leaves_a_drawn_widget() {
        val provider = ComponentName(app, FavoritesWidget::class.java)
        manager.addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = provider })
        val id = 41
        manager.bindAppWidgetId(id, provider)
        val intent = Intent(app, FavoritesWidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        val controller = Robolectric.buildActivity(FavoritesWidgetConfigActivity::class.java, intent).setup()
        awaitMain("the widget drawn") { manager.getViewFor(id) != null }
        // Back: the launcher keeps the widget, which shows Favourites.
        controller.get().onBackPressedDispatcher.onBackPressed()
        assertEquals(Activity.RESULT_OK, shadowOf(controller.get()).resultCode)
        controller.pause().stop().destroy()
    }

    @Test fun the_old_circle_entry_is_hidden_from_the_list_never_disabled() {
        PeopleWidget.placed(app)
        val circle = ComponentName(app, CircleWidget::class.java)
        assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, app.packageManager.getComponentEnabledSetting(circle))
        val xml = app.resources.getXml(R.xml.widget_circle_info)
        while (xml.next() != XmlPullParser.START_TAG) Unit
        val features = xml.getAttributeIntValue("http://schemas.android.com/apk/res/android", "widgetFeatures", 0)
        assertEquals(AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER, features and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER)
    }
}
