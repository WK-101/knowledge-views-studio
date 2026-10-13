package app.parley.telecom.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.parley.common.Verification
import app.parley.telecom.CallState
import app.parley.telecom.CallUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The call screen's bottom half changes with a short scale and fade when its phase changes (answering, the call
 * ending), and once that is done exactly one set of controls is left: never a second End button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ControlsSwapTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(context, ComponentActivity::class.java))
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() = scenario.close()

    private fun call(state: CallState, blocking: Boolean = false) = CallUi(
        id = "c1", state = state, number = "+442079460000", hidden = false, name = "Ada Lovelace", label = "Mobile",
        photoUri = null, contactId = 7L, incoming = true, connectTimeMillis = 0, isConference = false, children = emptyList(),
        canHold = false, canMerge = false, canSwap = false, canMute = true, canSeparate = false, canDisconnectChild = false,
        canRespondViaText = true, accountLabel = null, verification = Verification.NOT_VERIFIED, disconnectReason = null,
        postDialWait = null, silenced = false, isEmergency = false, savedCaller = true, blockingDecline = blocking,
    )

    @Test fun each_state_has_its_phase() {
        assertEquals(ControlsPhase.ENDED, controlsPhase(null))
        assertEquals(ControlsPhase.RINGING, controlsPhase(call(CallState.RINGING)))
        assertEquals(ControlsPhase.BLOCKING, controlsPhase(call(CallState.RINGING, blocking = true)))
        assertEquals(ControlsPhase.SELECT_ACCOUNT, controlsPhase(call(CallState.SELECT_ACCOUNT)))
        assertEquals(ControlsPhase.ONGOING, controlsPhase(call(CallState.ACTIVE)))
        assertEquals(ControlsPhase.ONGOING, controlsPhase(call(CallState.HOLDING)))
    }

    @Test fun answering_leaves_one_set_of_controls() {
        var primary by mutableStateOf<CallUi?>(call(CallState.RINGING))
        scenario.onActivity {
            it.setContent {
                MaterialTheme {
                    ControlsSwap(primary, ::controlsPhase) { shown, phase ->
                        when (phase) {
                            ControlsPhase.RINGING -> Text("Answer")
                            ControlsPhase.ONGOING -> Text("End call ${shown?.state}")
                            else -> Text("Other")
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodesWithText("Answer").fetchSemanticsNodes().size)

        compose.runOnIdle { primary = call(CallState.ACTIVE) }
        compose.waitForIdle()
        // A change within the phase (held) updates the same controls: no second set.
        compose.runOnIdle { primary = call(CallState.HOLDING) }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Answer").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("End call ACTIVE").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("End call HOLDING").fetchSemanticsNodes().size)
    }
}
