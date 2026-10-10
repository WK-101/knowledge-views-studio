package app.parley.ui

import android.app.Application
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.parley.R
import app.parley.messaging.InternetCallBackDialog
import app.parley.ui.people.whoPickerText
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
 * The automated accessibility checks catch what they promise (no words, a small target, the same words twice), and
 * the screens added with them pass: Call back for an internet call, and what "Who can see your contacts" says about
 * Android 17's picker.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class A11yChecksTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    private fun show(content: @Composable () -> Unit) {
        scenario.onActivity { it.setContent { MaterialTheme { content() } } }
        compose.waitForIdle()
    }

    private fun findings(min: Dp = 48.dp) = A11yChecks.findings(compose.onAllNodes(hasClickAction()), compose.density.run { min.toPx() } - 0.5f, emptySet())

    @Test fun each_kind_of_problem_is_found() {
        show {
            Column {
                // No words at all.
                Box(Modifier.size(48.dp).clickable { })
                // A 24 dp target with words: Compose widens its touch area to 48 dp (what TalkBack and touch use).
                Box(Modifier.size(24.dp).semantics { contentDescription = "Tiny" }.clickable { })
                // Two different buttons that say the same.
                Row {
                    Box(Modifier.size(48.dp).semantics { contentDescription = "Call" }.clickable { })
                    Box(Modifier.size(48.dp).semantics { contentDescription = "Call" }.clickable { })
                }
            }
        }
        val problems = findings().map { it.problem }
        assertEquals(problems.toString(), 1, problems.count { it.startsWith("nothing for TalkBack") })
        assertEquals(problems.toString(), 0, problems.count { it.startsWith("touch target") })
        // Measured, not assumed: against a 56 dp minimum every one of the four 48 dp targets is reported.
        assertEquals(4, findings(56.dp).count { it.problem.startsWith("touch target") })
        assertEquals(problems.toString(), 1, problems.count { it.contains("say the same words") })
    }

    @Test fun material_controls_pass() {
        show {
            Row {
                IconButton({}) { Icon(Icons.Rounded.Call, "Call Ada") }
                IconButton({}) { Icon(Icons.Rounded.Call, "Call Grace") }
            }
        }
        assertTrue(findings().toString(), findings().isEmpty())
    }

    @Test fun call_back_for_an_internet_call_offers_the_app_first_and_the_phone_only_when_chosen() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = "com.whatsapp"
                applicationInfo = ApplicationInfo().apply { packageName = "com.whatsapp"; enabled = true }
            },
        )
        var byPhone = 0
        show { InternetCallBackDialog("+44 20 7946 0000", null, "com.whatsapp", onCallByPhone = { byPhone++ }, onDismiss = {}) }
        compose.onNodeWithText(context.getString(R.string.recents_app_call_back_title, "WhatsApp")).assertExists()
        compose.onNodeWithText(context.getString(R.string.recents_app_call_back_in_app, "WhatsApp")).assertExists()
        assertTrue(findings().toString(), findings().isEmpty())
        assertEquals(0, byPhone)
        compose.onNodeWithText(context.getString(R.string.recents_app_call_by_phone)).performClick()
        assertEquals(1, byPhone)
    }

    @Test fun call_back_when_the_app_is_gone_says_so_and_offers_the_phone() {
        var byPhone = 0
        show { InternetCallBackDialog("+44 20 7946 0000", null, "org.thoughtcrime.securesms", onCallByPhone = { byPhone++ }, onDismiss = {}) }
        compose.onNodeWithText(context.getString(R.string.recents_app_call_back_gone, "Signal")).assertExists()
        assertTrue(findings().toString(), findings().isEmpty())
        assertEquals(0, byPhone)
        compose.onNodeWithText(context.getString(R.string.recents_app_call_by_phone)).performClick()
        assertEquals(1, byPhone)
    }

    @Test fun who_can_see_says_android_17_s_picker_never_shows_private_or_archived_contacts() {
        assertEquals(R.string.who_picker_text, whoPickerText(36))
        assertEquals(R.string.who_picker_text_37, whoPickerText(37))
        val text = context.getString(whoPickerText(37))
        assertTrue(text, text.contains("never shows your private or archived contacts"))
    }
}
