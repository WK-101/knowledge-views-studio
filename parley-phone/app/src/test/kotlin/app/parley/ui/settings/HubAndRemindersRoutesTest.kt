package app.parley.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import app.parley.common.SettingPlace
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory
import app.parley.ui.Destination
import app.parley.ui.Routes
import app.parley.ui.discover.DiscoverRoutes
import app.parley.ui.parleyGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tools is one hub and Reminders one page: search and the old links (Tools, a reminder setting on its category page)
 * still open a screen in the graph, and every searchable setting has its texts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HubAndRemindersRoutesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        nav = NavHostController(context).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
        }
        nav.graph = nav.createGraph(startDestination = Routes.Home) {
            composable<Routes.Home> {}
            parleyGraph(nav)
        }
    }

    private fun opens(d: Destination) {
        nav.navigate(d)
        val here = nav.currentBackStackEntry?.destination
        assertNotNull(here)
        assertTrue("$d is registered", here!!.hasRoute(d::class))
    }

    @Test fun tools_search_entries_open_the_hub_and_the_old_route_still_works() {
        assertEquals(DiscoverRoutes.Capabilities, settingRoute(SettingsCatalog["what_parley_can_do"]))
        opens(DiscoverRoutes.Capabilities)
        // A back stack saved by an older version may still hold the old Tools page.
        opens(Routes.Tools)
    }

    @Test fun every_reminder_opens_the_reminders_page_scrolled_to_it() {
        val reminders = SettingsCatalog.entries.filter { it.place == SettingPlace.REMINDERS }
        assertTrue(reminders.size >= 9)
        reminders.forEach { e ->
            val route = settingRoute(e)
            assertEquals(RemindersRoutes.Page(e.key), route)
            opens(route)
        }
        // Circle ⋮ › Circle settings and other old links to the Contacts page still land (the page hands over to Reminders).
        opens(Routes.settingsPage(SettingsCategory.CONTACTS, "circle_delivery"))
    }

    @Test fun the_privacy_dashboard_lives_under_privacy() {
        assertEquals(SettingsCategory.PRIVACY, SettingsCatalog["privacy_dashboard"].category)
        assertEquals(null, SettingsCatalog["privacy_dashboard"].place)
        opens(Routes.Privacy)
    }

    @Test fun every_setting_has_its_texts() {
        // Throws on a key without string resources; the English title stays searchable either way.
        val localized = SettingsText.localizedCatalog(context)
        assertEquals(SettingsCatalog.entries.map { it.key }, localized.map { it.key })
        assertEquals("Reminders", localized.single { it.key == "reminders" }.title)
        assertEquals("Tools", localized.single { it.key == "what_parley_can_do" }.title)
    }
}
