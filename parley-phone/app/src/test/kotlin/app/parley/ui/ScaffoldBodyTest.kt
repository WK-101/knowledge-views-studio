package app.parley.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A list screen built with [scaffoldBody] and [scaffoldBottom] is edge to edge: the list draws under a 48 dp
 * navigation bar, and its last row still scrolls clear above it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ScaffoldBodyTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun the_list_runs_under_the_navigation_bar_and_its_last_row_clears_it() {
        scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    val barTint = TopAppBarDefaults.pinnedScrollBehavior()
                    ParleyScaffold(
                        Modifier.fillMaxSize(),
                        topBar = { ParleyTopBar("Rows", onBack = null, scrollBehavior = barTint) },
                        contentWindowInsets = WindowInsets(bottom = NAV_BAR),
                    ) { p ->
                        LazyColumn(Modifier.scaffoldBody(p, barTint).testTag("list"), contentPadding = scaffoldBottom(p)) {
                            items((1..ROWS).toList()) { n -> Text("Row $n", Modifier.height(56.dp)) }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        // Under the bar: the list reaches the bottom of the window, not the top of the navigation bar.
        assertEquals(screen.bottom.value, list.bottom.value, 0.5f)

        compose.onNodeWithTag("list").performScrollToIndex(ROWS - 1)
        compose.waitForIdle()
        val last = compose.onNodeWithText("Row $ROWS").getUnclippedBoundsInRoot()
        assertTrue("the last row ends above the navigation bar (${last.bottom} vs ${screen.bottom - NAV_BAR})", last.bottom <= screen.bottom - NAV_BAR)
    }

    private companion object {
        val NAV_BAR = 48.dp
        const val ROWS = 60
    }
}
