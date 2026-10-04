package app.parley.ui.discover

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.core.app.ApplicationProvider
import app.parley.NavEvent
import app.parley.common.StartTab
import app.parley.common.ux.CapabilityCatalog
import app.parley.common.ux.ComingFrom
import app.parley.ui.Destination
import app.parley.ui.Routes
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
 * Every row of "What Parley can do" and every "Coming from…" importer opens a screen that is really in the app's
 * graph (or a tab), and every row has its texts: a renamed or removed screen fails here, not on someone's phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CapabilityRoutesTest {
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

    @Test fun every_row_opens_a_registered_screen_or_a_tab() {
        CapabilityCatalog.rows.forEach { c ->
            when (val e = capabilityEvent(c.target)) {
                is NavEvent.Route -> opens(e.route)
                is NavEvent.Tab -> assertTrue("${c.key} opens a tab", e.tab in listOf(StartTab.CIRCLE, StartTab.KEYPAD))
                else -> error("${c.key} opens $e")
            }
        }
        opens(DiscoverRoutes.Capabilities)
        opens(DiscoverRoutes.ComingFrom)
    }

    @Test fun every_importer_is_in_the_graph() {
        ComingFrom.Importer.entries.forEach { opens(importerRoute(it)) }
    }

    @Test fun every_row_has_its_texts() {
        CapabilityCatalog.rows.forEach { c ->
            assertTrue("${c.key} has strings", CapabilityText.has(c.key))
            val (title, summary) = CapabilityText.of(c)
            // The English texts on screen are the catalog's reference texts, so search and screen agree.
            assertEquals(c.title, context.getString(title))
            assertEquals(c.summary, context.getString(summary))
        }
    }

    /**
     * Tools is the one hub: every screen in the app's graph is opened by a Tools row, or is listed here as part of a
     * feature that is (or as a place that isn't a feature). A new screen fails until it has a row or a reason.
     */
    @Test fun every_screen_is_reached_from_tools_or_explicitly_part_of_another() {
        fun name(route: String?) = route.orEmpty().substringBefore('/').substringBefore('?')
        val fromTools = CapabilityCatalog.rows
            .mapNotNull { c -> (capabilityEvent(c.target) as? NavEvent.Route)?.route?.let { it::class.qualifiedName } }
            .toSet()
        val screens = nav.graph.map { name(it.route) }.filter { it.isNotEmpty() }.toSet()
        val missing = screens - fromTools - PART_OF_ANOTHER.keys
        assertEquals("Screens without a Tools row or a reason", emptySet<String>(), missing)
        // The reasons stay honest: each names a screen that still exists and that Tools doesn't open itself.
        assertEquals(emptySet<String>(), PART_OF_ANOTHER.keys - screens)
        assertEquals(emptySet<String>(), PART_OF_ANOTHER.keys.intersect(fromTools))
    }

    private companion object {
        private const val UI = "app.parley.ui"

        /** Screens Tools doesn't open itself, and why that's right. */
        val PART_OF_ANOTHER: Map<String, String> = mapOf(
            "$UI.Routes.Home" to "the tabs",
            "$UI.Routes.Contact" to "a contact's page",
            "$UI.Routes.Pick" to "Add to a contact, from a number",
            "$UI.Routes.History" to "a number's history, from Recents",
            "$UI.Routes.Vault" to "a private contact's page",
            "$UI.Routes.Versions" to "a contact's Version history",
            "$UI.Routes.Settings" to "Settings",
            "$UI.Routes.Tools" to "an old link to the hub",
            "$UI.discover.DiscoverRoutes.Capabilities" to "Tools itself",
            "$UI.Routes.SyncMarkdown" to "an old link to the notes export, which Export contacts replaced",
            "$UI.Routes.Export" to "Import & export contacts, a setting: Settings › Contacts › Export",
            "$UI.blocking.BlockingRoutes.Rule" to "a rule of Blocking & screening",
            "$UI.contact.ContactPageRoutes.Sections" to "a setting: Settings › Contacts › Contact page sections",
            "$UI.contact.ContactPageRoutes.Timeline" to "a contact's timeline",
            "$UI.extras.ExtrasRoutes.SimpleImport" to "Simple mode's setup from another phone",
            "$UI.family.FamilyRoutes.SafeWords" to "Family safe word, set on a label's page",
            "$UI.history.HistoryRoutes.Settings" to "an old link to Settings › Recents & history",
            "$UI.history.HistoryRoutes.Sim" to "one SIM's plan minutes",
            "$UI.history.HistoryRoutes.Import" to "Coming from another phone? › call history",
            "app.parley.messaging.MessagingRoutes.CsvMapping" to "a contacts import's columns",
            "$UI.people.PeopleRoutes.Diagnostics" to "Settings › About",
            "$UI.people.PeopleRoutes.EditRaw" to "one account's copy of a contact",
            "$UI.people.PeopleRoutes.Label" to "a label's page",
            "$UI.people.PeopleRoutes.MeEdit" to "My card's editor",
            "$UI.people.PeopleRoutes.NewNumber" to "My card › Changed my number",
            "$UI.people.PeopleRoutes.SharedWith" to "My card › Shared with",
            "$UI.people.PeopleRoutes.SimImport" to "Settings › Contacts › Import from SIM card",
            "$UI.sync.shared.SharedLabelRoutes.Join" to "Shared labels › Join",
            "$UI.sync.shared.SharedLabelRoutes.Manage" to "a shared label",
            "$UI.sync.shared.SharedLabelRoutes.Share" to "a label's ⋮ › Share this label…",
            "$UI.sync.shared.SharedLabelRoutes.OpenFile" to "a shared label's Open an update, or a file opened in Parley",
            "$UI.timemachine.WatchRoutes.Restore" to "the sync watchdog's notification",
            "$UI.situations.SituationRoutes.Edit" to "one Situation, a setting: Settings › Calls › Situations",
        )
    }
}
