package com.todocompanion.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.todocompanion.app.domain.priority.PriorityLevel
import com.todocompanion.app.ui.components.FlagStar
import com.todocompanion.app.ui.components.PriorityCheckbox
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R97 — renders the shared task-row controls on the JVM (Robolectric, no device) and asserts their
 * *accessibility* semantics: the labels TalkBack reads, the checkbox role and toggle state, and that a
 * tap fires the callback. This is the accessibility labelling from the F2 pass, now guarded in CI so a
 * regression that strips a contentDescription or the checkbox role fails the build.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun priorityCheckbox_hasCheckboxRole_offLabel_andToggles() {
        var clicks = 0
        compose.setContent {
            MaterialTheme { PriorityCheckbox(checked = false, level = PriorityLevel.HIGH, onCheckedChange = { clicks++ }) }
        }
        compose.onNodeWithContentDescription("Mark complete.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertEquals(1, clicks)
    }

    @Test fun checkedPriorityCheckbox_announcesCompletion_andIsOn() {
        compose.setContent {
            MaterialTheme { PriorityCheckbox(checked = true, level = PriorityLevel.NONE, onCheckedChange = {}) }
        }
        compose.onNodeWithContentDescription("Completed. Double-tap to mark incomplete.")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
    }

    @Test fun flagStar_labelsBothIcons_andFiresCallbacks() {
        var flag = 0
        var star = 0
        // FlagStar emits two sibling icon-boxes designed to sit in a Row (its callers provide one); a Row
        // here lays them out side by side instead of overlapping in the root box.
        compose.setContent {
            MaterialTheme { Row { FlagStar(flagArgb = null, starred = false, onCycleFlag = { flag++ }, onToggleStar = { star++ }) } }
        }
        compose.onNodeWithContentDescription("Flag").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Star").assertIsDisplayed().performClick()
        assertEquals(1, flag)
        assertEquals(1, star)
    }
}
