package com.todocompanion.app

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R97 — proves the Compose UI-test harness runs on the JVM under Robolectric (no device). If this is
 * green, the app's real composables can be rendered and their semantics tree asserted in CI, which is how
 * accessibility labels get verified without an emulator.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ComposeHarnessTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersTextAndExposesContentDescription() {
        compose.setContent {
            Text("Hello", modifier = Modifier.semantics { contentDescription = "greeting" })
        }
        compose.onNodeWithText("Hello").assertIsDisplayed()
        compose.onNodeWithContentDescription("greeting").assertIsDisplayed()
    }
}
