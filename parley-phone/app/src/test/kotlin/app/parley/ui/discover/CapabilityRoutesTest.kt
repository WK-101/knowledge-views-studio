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
}
