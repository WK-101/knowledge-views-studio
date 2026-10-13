package app.parley.ui.onboarding

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ux.OnboardingStep
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Each onboarding step has its own scroll: a step opens at its top, whatever the previous one was scrolled to. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OnboardingStepsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    @Test fun a_new_step_starts_at_its_top() {
        var step by mutableStateOf(OnboardingStep.PERMISSIONS)
        scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    OnboardingSteps(step) { shown ->
                        // A tall page per step, so it scrolls.
                        (1..LINES).forEach { n -> Text("${shown.name} $n", Modifier.height(80.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(ONBOARDING_SCROLL_TAG).performScrollToNode(hasText("PERMISSIONS $LINES"))
        compose.onNodeWithText("PERMISSIONS $LINES").assertIsDisplayed()

        compose.runOnIdle { step = OnboardingStep.BASICS }
        compose.waitForIdle()
        compose.onNodeWithText("BASICS 1").assertIsDisplayed()

        // Back to the scrolled step: it opens at its top too.
        compose.runOnIdle { step = OnboardingStep.PERMISSIONS }
        compose.waitForIdle()
        compose.onNodeWithText("PERMISSIONS 1").assertIsDisplayed()
    }

    private companion object {
        const val LINES = 40
    }
}
