package app.parley.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import app.parley.common.ux.MenuEntry
import app.parley.ui.common.MenuItems
import app.parley.ui.common.MenuLabel
import app.parley.ui.common.MenuRows
import app.parley.ui.common.ProvideAppKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The kit's parts that open in place and its menus: a header is one TalkBack heading that says its state and what a
 * tap does, and a destructive menu action is set apart by a line, after the others, in a drop-down and in a sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KitExpandAndMenuTest {
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
        scenario.onActivity { it.setContent { MaterialTheme { ProvideAppKit { content() } } } }
        compose.waitForIdle()
    }

    @Test fun an_expandable_header_is_a_heading_that_says_its_state() {
        ExpandableStyle.entries.forEach { style ->
            show {
                // A fresh state for each style (the same composition is reused).
                key(style) {
                    var open by remember { mutableStateOf(false) }
                    Column {
                        ExpandableHeader("Advanced", open, { open = !open }, summary = "3 settings", style = style)
                        ExpandableBody(open) { Text("Inside") }
                    }
                }
            }
            val header = compose.onNodeWithText("Advanced", useUnmergedTree = false)
            fun config() = header.fetchSemanticsNode().config
            assertNotNull("$style: not a heading", config().getOrNull(SemanticsProperties.Heading))
            assertEquals("Collapsed", config().getOrNull(SemanticsProperties.StateDescription))
            assertEquals("Unfold", config().getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label)
            assertEquals(0, compose.onAllNodesWithText("Inside").fetchSemanticsNodes().size)

            header.performClick()
            compose.waitForIdle()
            assertEquals("Expanded", config().getOrNull(SemanticsProperties.StateDescription))
            assertEquals("Fold", config().getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)?.label)
            assertEquals(1, compose.onAllNodesWithText("Inside").fetchSemanticsNodes().size)
        }
    }

    @Test fun a_header_can_say_what_its_tap_does_in_its_own_words() {
        show { ExpandableHeader("Advanced", false, {}, actionLabel = "Show advanced settings") }
        val click = compose.onNodeWithText("Advanced").fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals("Show advanced settings", click?.label)
    }

    @Test fun a_destructive_menu_item_comes_after_a_line() {
        val entries = listOf(MenuEntry.Action(EDIT), MenuEntry.Action(DELETE))
        show { Column { MenuItems(entries, { label(it) }, close = {}, onGroup = {}, onAction = {}) } }
        assertSetApart()
    }

    @Test fun a_destructive_sheet_row_comes_after_a_line() {
        val entries = listOf(MenuEntry.Action(EDIT), MenuEntry.Action(DELETE))
        show { Column { MenuRows(entries, { label(it) }, onGroup = {}, onAction = {}) } }
        assertSetApart()
    }

    @Test fun a_menu_without_a_destructive_item_has_no_line() {
        show { Column { MenuItems(listOf(MenuEntry.Action(EDIT)), { label(it) }, close = {}, onGroup = {}, onAction = {}) } }
        assertEquals(0, compose.onAllNodesWithTag(DESTRUCTIVE_DIVIDER_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun the_overflow_menu_opens_from_a_button_named_more_options() {
        show { OverflowMenu { close -> ParleyMenuItem("Merge labels") { close() } } }
        val button = compose.onNodeWithText("Merge labels", useUnmergedTree = true)
        assertEquals(0, compose.onAllNodesWithText("Merge labels").fetchSemanticsNodes().size)
        compose.onNode(androidx.compose.ui.test.hasContentDescription("More options")).performClick()
        compose.waitForIdle()
        button.assertExists()
    }

    private fun assertSetApart() {
        val line = compose.onNodeWithTag(DESTRUCTIVE_DIVIDER_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val edit = compose.onNodeWithText("Edit").fetchSemanticsNode().boundsInRoot
        val delete = compose.onNodeWithText("Delete").fetchSemanticsNode().boundsInRoot
        assertTrue("the line isn't below Edit", line.top >= edit.bottom - 0.5f)
        assertTrue("Delete isn't below the line", delete.top >= line.bottom - 0.5f)
    }

    private companion object {
        const val EDIT = "edit"
        const val DELETE = "delete"

        @Composable
        fun label(a: String): MenuLabel =
            if (a == DELETE) MenuLabel("Delete", Icons.Rounded.Delete, isDestructive = true) else MenuLabel("Edit", Icons.Rounded.Edit)
    }
}
