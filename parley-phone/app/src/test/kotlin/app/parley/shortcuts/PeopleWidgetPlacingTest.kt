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
import app.parley.situations.SituationsWidget
import app.parley.data.testing.FakeAndroidKeyStore
import app.parley.data.testing.FakeContactsProvider
import app.parley.testing.awaitMain
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 * sends no update while it asks). The old Circle widget's entry is gone (the People widget's Circle mode replaces it),
 * and every widget can be made as small as one cell.
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

    @Test fun the_widget_list_is_people_direct_dial_and_situations_and_the_old_circle_entry_is_gone() {
        val update = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(app.packageName)
        val providers = app.packageManager.queryBroadcastReceivers(update, PackageManager.GET_META_DATA).map { it.activityInfo.name }.toSet()
        assertEquals(
            setOf(FavoritesWidget::class.java.name, DialWidget::class.java.name, SituationsWidget::class.java.name),
            providers,
        )
    }

    @Test fun every_widget_resizes_down_to_one_cell() {
        listOf(R.xml.widget_favorites_info, R.xml.widget_dial_info, R.xml.widget_situations_info).forEach { res ->
            val xml = app.resources.getXml(res)
            while (xml.next() != XmlPullParser.START_TAG) Unit
            val ns = "http://schemas.android.com/apk/res/android"
            val resize = xml.getAttributeIntValue(ns, "resizeMode", 0)
            assertEquals(AppWidgetProviderInfo.RESIZE_BOTH, resize and AppWidgetProviderInfo.RESIZE_BOTH)
            // At most about one cell (40 dp) either way, so a 1×1 is allowed.
            assertTrue(app.resources.getXml(res).let { minResize(it, "minResizeWidth") } <= 40f)
            assertTrue(app.resources.getXml(res).let { minResize(it, "minResizeHeight") } <= 40f)
        }
    }

    private fun minResize(xml: android.content.res.XmlResourceParser, name: String): Float {
        while (xml.next() != XmlPullParser.START_TAG) Unit
        val value = xml.getAttributeValue("http://schemas.android.com/apk/res/android", name) ?: return Float.MAX_VALUE
        return value.removeSuffix("dip").removeSuffix("dp").toFloat()
    }
}
