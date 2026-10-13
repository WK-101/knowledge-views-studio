package app.parley.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Back and open from a page are taken once: a quick second Back while the first pop animates doesn't pop the page
 * beneath (and never Home, which would leave a blank host), and a double tap on a row opens one page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class NavigationGuardTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var nav: NavHostController

    @Before fun setUp() {
        // The empty activity isn't in the app's manifest: registered for this test.
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity {
            it.setContent {
                nav = rememberNavController()
                NavHost(nav, startDestination = Routes.Home) {
                    composable<Routes.Home> { Text("Home") }
                    composable<Routes.Settings> { Text("Settings") }
                    composable<Routes.Temporary> { Text("Temporary") }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun tearDown() = scenario.close()

    private fun routes() = nav.currentBackStack.value.mapNotNull { e ->
        when {
            e.destination.hasRoute<Routes.Home>() -> "Home"
            e.destination.hasRoute<Routes.Settings>() -> "Settings"
            e.destination.hasRoute<Routes.Temporary>() -> "Temporary"
            else -> null
        }
    }

    private fun <T> onMain(block: () -> T): T = compose.runOnIdle(block)

    @Test fun two_backs_in_the_same_frame_pop_once_and_never_home() {
        onMain { nav.navigate(Routes.Settings) }
        compose.waitForIdle()
        val taken = onMain { listOf(nav.backOnce(), nav.backOnce()) }
        assertEquals(listOf(true, false), taken)
        compose.waitForIdle()
        assertEquals(listOf("Home"), routes())
        // On Home, Back from a page's bar does nothing (the system's Back leaves the app instead).
        assertFalse(onMain { nav.backOnce() })
        assertEquals(listOf("Home"), routes())
    }

    @Test fun a_quick_second_back_from_a_second_level_page_keeps_the_first() {
        onMain { nav.navigate(Routes.Settings) }
        compose.waitForIdle()
        onMain { nav.navigate(Routes.Temporary) }
        compose.waitForIdle()
        onMain { nav.backOnce(); nav.backOnce() }
        compose.waitForIdle()
        assertEquals(listOf("Home", "Settings"), routes())
        // Once that page has settled, Back works again.
        assertTrue(onMain { nav.backOnce() })
        compose.waitForIdle()
        assertEquals(listOf("Home"), routes())
    }

    @Test fun a_double_tap_on_a_row_opens_one_page() {
        val taken = onMain { listOf(nav.openOnce(Routes.Settings), nav.openOnce(Routes.Settings)) }
        assertEquals(listOf(true, false), taken)
        compose.waitForIdle()
        assertEquals(listOf("Home", "Settings"), routes())
        // A different page right after is still opened.
        onMain { nav.openOnce(Routes.Temporary) }
        compose.waitForIdle()
        assertEquals(listOf("Home", "Settings", "Temporary"), routes())
    }

    @Test fun every_graph_and_the_root_use_the_guarded_back_and_open() {
        val ui = File("src/main/kotlin/app/parley").absoluteFile
        val sources = ui.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it to it.readText() }.toList()
        assertTrue("the scan found nothing: wrong folder?", sources.isNotEmpty())
        val raw = sources.filter { (_, t) -> "{ nav.popBackStack() }" in t || Regex("""\{ (r|route) -> nav\.navigate\((r|route)\) }""").containsMatchIn(t) }
            .map { it.first.name }
        assertEquals("Unguarded back/open lambdas in $raw; use nav.parleyBack() / nav.parleyOpen()", emptyList<String>(), raw)
        // The host's page transitions come from the theme's motion, not Compose's defaults.
        val root = sources.single { it.first.name == "ParleyRoot.kt" }.second
        for (spec in listOf("navEnter()", "navExit()", "navPopEnter()", "navPopExit()")) assertTrue("ParleyRoot doesn't read ParleyMotion.$spec", "ParleyMotion.$spec" in root)
        assertFalse("ParleyRoot slides pages with a default spec", "slideInHorizontally" in root || "slideOutHorizontally" in root)
    }
}
