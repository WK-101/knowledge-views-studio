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
import java.io.File
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
        // Old links to a reminder on its former category page hand over to Reminders, on that row.
        assertEquals(SettingsPageTarget.Reminders("circle_delivery"), settingsPageTarget(SettingsCategory.CONTACTS.name, "circle_delivery"))
        assertEquals(SettingsPageTarget.Reminders("missed_realert"), settingsPageTarget(SettingsCategory.CALLS.name, "missed_realert"))
        opens(Routes.settingsPage(SettingsCategory.CONTACTS, "circle_delivery"))
        // Circle ⋮ › Circle settings opens Contacts at the Circle's own setting (which links to Reminders).
        assertEquals(
            SettingsPageTarget.Category(SettingsCategory.CONTACTS, "log_prompts"),
            settingsPageTarget(SettingsCategory.CONTACTS.name, "log_prompts"),
        )
    }

    @Test fun old_calls_links_hand_over_to_the_page_holding_the_row() {
        SettingsCatalog.entries.forEach { e ->
            val page = e.place?.let { CallsSubPage.at(it) } ?: return@forEach
            assertEquals(e.key, SettingsPageTarget.Calls(page, e.key), settingsPageTarget(SettingsCategory.CALLS.name, e.key))
        }
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.DURING, "proximity_sensor"), settingsPageTarget("CALLS", "proximity_sensor"))
        // Rows still on Calls itself, a page without focus, and another page with a row of the same key stay put.
        assertEquals(SettingsPageTarget.Category(SettingsCategory.CALLS, "default_dialer"), settingsPageTarget("CALLS", "default_dialer"))
        assertEquals(SettingsPageTarget.Category(SettingsCategory.CALLS, null), settingsPageTarget("CALLS", null))
        // The Call time category went: its old links open Calls › During calls, and its SIMs row the SIMs page.
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.DURING, "call_time"), settingsPageTarget("CALL_TIME", "call_time"))
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.DURING, "call_time"), settingsPageTarget("CALL_TIME", null))
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.SIMS, "sims"), settingsPageTarget("CALL_TIME", "sims"))
        // Keypad and Messaging went too: their links open Calls › Keypad & dialling and Answering.
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.KEYPAD, "speed_dial"), settingsPageTarget("KEYPAD", "speed_dial"))
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.KEYPAD, "keypad_tones"), settingsPageTarget("KEYPAD", null))
        assertEquals(SettingsPageTarget.Calls(CallsSubPage.ANSWERING, "quick_replies"), settingsPageTarget("MESSAGING", null))
        // Blocking & spam has no page: its links open the screen.
        assertEquals(SettingsPageTarget.Blocking, settingsPageTarget("BLOCKING", "repeat_callers"))
        assertEquals(SettingsPageTarget.Blocking, settingsPageTarget("BLOCKING", null))
        // An unknown page name (an old link) opens Appearance.
        assertEquals(SettingsPageTarget.Category(SettingsCategory.APPEARANCE, null), settingsPageTarget("GONE", null))
    }

    /**
     * Search scrolls to a row by its key, so each setting that lives on Reminders or one of Calls' pages must be a row
     * keyed so on that page. Checked in the sources that build each page: a renamed row key fails here.
     */
    @Test fun every_moved_setting_is_a_keyed_row_on_its_page() {
        val dir = listOf("src/main/kotlin/app/parley/ui/settings", "app/src/main/kotlin/app/parley/ui/settings").map(::File).first { it.isDirectory }
        fun sources(vararg names: String) = names.joinToString("\n") { File(dir, it).readText() }
        val pages = mapOf(
            SettingPlace.REMINDERS to sources("RemindersScreen.kt", "CircleSettings.kt"),
            SettingPlace.CALLS_ANSWERING to sources("CallsPages.kt", "AutoAnswerSettings.kt", "RttSettings.kt"),
            SettingPlace.CALLS_DURING to sources("CallsPages.kt", "CallExtrasSettings.kt", "CircleSettings.kt"),
            SettingPlace.CALLS_KEYPAD to sources("CallsPages.kt"),
            SettingPlace.CALLS_SIMS to sources("CallsPages.kt"),
            SettingPlace.CALLS_SITUATIONS to sources("CallsPages.kt"),
        )
        pages.forEach { (place, source) ->
            val keys = SettingsCatalog.entries.filter { it.place == place }.map { it.key }
            assertTrue("$place has settings", keys.isNotEmpty())
            keys.forEach { k ->
                val row = Regex("""\b(item|blended|switchRow|linkRow|menuRow|choiceRow)\(\s*"$k"""")
                assertTrue("$k is a row on $place", row.containsMatchIn(source))
            }
        }
    }

    /** Settings holds no launcher rows: a tool found by Settings search opens the tool itself, which is in the graph. */
    @Test fun every_tool_found_by_settings_search_opens_its_screen() {
        val tools = SettingsCatalog.entries.filter { it.place == SettingPlace.TOOLS }
        assertEquals(tools.map { it.key }.toSet(), toolRoutes.keys)
        tools.forEach { e ->
            val route = settingRoute(e)
            assertEquals(e.key, toolRoutes.getValue(e.key), route)
            opens(route)
        }
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
