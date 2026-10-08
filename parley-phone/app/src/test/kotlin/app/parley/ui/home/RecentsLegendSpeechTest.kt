package app.parley.ui.home

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A legend line's mark is only a picture: TalkBack reads the line's words once, not the mark's own words first. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RecentsLegendSpeechTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        // The empty activity isn't in the app's manifest: registered for this test.
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    private fun show(content: @Composable () -> Unit) {
        scenario.onActivity { it.setContent { MaterialTheme { content() } } }
        compose.waitForIdle()
    }

    @Test fun the_private_and_network_marks_say_nothing_of_their_own() {
        show {
            androidx.compose.foundation.layout.Column {
                LegendGlyph(RecentsLegend.Entry.Mark(RecentsMark.PRIVATE, rich = true))
                LegendGlyph(RecentsLegend.Entry.Mark(RecentsMark.NETWORK_NAME, rich = true))
            }
        }
        val privateContact = context.getString(app.parley.ui.R.string.ui_private_contact)
        val fromNetwork = context.getString(R.string.network_name_tag)
        assertEquals(0, compose.onAllNodesWithContentDescription(privateContact).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText(fromNetwork).fetchSemanticsNodes().size)
    }

    @Test fun outside_the_legend_the_badge_still_speaks() {
        show { app.parley.ui.PrivateBadge() }
        val privateContact = context.getString(app.parley.ui.R.string.ui_private_contact)
        assertEquals(1, compose.onAllNodesWithContentDescription(privateContact, useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
