package com.todocompanion.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.todocompanion.app.ui.components.EmptyState
import com.todocompanion.app.ui.components.SmallCheck
import com.todocompanion.app.ui.components.TipBanner
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R103 — extends the JVM Compose-UI layer (Robolectric, no device) to more shared components: the
 * matrix/compact checkbox's accessibility toggle, the empty-state guidance block, and the discoverability
 * tip banner. Guards the labels TalkBack reads and that the action/dismiss callbacks fire, so a regression
 * that strips a control's semantics or wires a button to nothing fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ComponentSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun smallCheck_unchecked_hasCheckboxRole_offLabel_andToggles() {
        var clicks = 0
        compose.setContent {
            MaterialTheme { SmallCheck(checked = false, color = Color.Red, onToggle = { clicks++ }) }
        }
        compose.onNodeWithContentDescription("Mark complete.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(1, clicks)
    }

    @Test fun smallCheck_checked_announcesCompletion_andIsOn() {
        compose.setContent {
            MaterialTheme { SmallCheck(checked = true, color = Color.Red, onToggle = {}) }
        }
        compose.onNodeWithContentDescription("Completed. Double-tap to mark incomplete.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test fun emptyState_showsTitleAndBody_andActionFires() {
        var acted = 0
        compose.setContent {
            MaterialTheme {
                EmptyState(
                    emoji = "📭",
                    title = "No tasks yet",
                    body = "Add your first task to get rolling.",
                    actionLabel = "Add task",
                    onAction = { acted++ },
                )
            }
        }
        compose.onNodeWithText("No tasks yet").assertIsDisplayed()
        compose.onNodeWithText("Add your first task to get rolling.").assertIsDisplayed()
        compose.onNodeWithText("Add task").assertIsDisplayed().performClick()
        assertEquals(1, acted)
    }

    @Test fun tipBanner_showsText_andBothActionsFire() {
        var dismissed = 0
        var acted = 0
        compose.setContent {
            MaterialTheme {
                TipBanner(
                    text = "Swipe a task to reveal its actions.",
                    onDismiss = { dismissed++ },
                    onAction = { acted++ },
                    actionLabel = "Show me",
                )
            }
        }
        compose.onNodeWithText("Swipe a task to reveal its actions.").assertIsDisplayed()
        compose.onNodeWithText("Show me").assertIsDisplayed().performClick()
        compose.onNodeWithText("Got it").assertIsDisplayed().performClick()
        assertEquals(1, acted)
        assertEquals(1, dismissed)
    }
}
