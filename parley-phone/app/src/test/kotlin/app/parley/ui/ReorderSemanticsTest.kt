package app.parley.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.parley.ParleyApp
import app.parley.R
import app.parley.common.NavTabs
import app.parley.ui.contact.ContactPageSettingsScreen
import app.parley.ui.home.FavoritesTab
import app.parley.ui.home.ReorderFavoritesSheet
import app.parley.ui.settings.NavTabsEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every drag-to-reorder list gives TalkBack the same way to move an item: "earlier" and "later" actions, only those
 * that can move it, each moving it one place. Switch rows tick on and off like the system's switches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class ReorderSemanticsTest : AppComposeHarness() {
    private val reorderable = SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)

    private fun rows(): List<SemanticsNode> = compose.onAllNodes(reorderable).fetchSemanticsNodes()

    private fun SemanticsNode.actions(): List<String> = config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    /** The row's text, its own or its children's. */
    private fun SemanticsNode.text(): String =
        (config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.map { it.text() }).joinToString(" ").trim()

    private fun run(node: SemanticsNode, label: String) {
        val action = node.config[SemanticsActions.CustomActions].single { it.label == label }
        compose.runOnIdle { action.action() }
        compose.waitForIdle()
    }

    /** The first row offers only [later], the last only [earlier]; [later] on the first row moves it down one place. */
    private fun checkMoves(earlier: String, later: String, firstText: () -> String) {
        val all = rows()
        assertEquals(listOf(later), all.first().actions())
        assertEquals(listOf(earlier), all.last().actions())
        if (all.size > 2) assertEquals(listOf(earlier, later), all[1].actions())
        val before = firstText()
        run(all.first(), later)
        compose.waitUntil(10_000) { firstText() != before }
        compose.waitForIdle()
        assertNotEquals(before, firstText())
    }

    /**
     * Wait until the reorderable rows stop changing. The favourites lists are derived from several flows that settle
     * off the main thread (contacts, call counts, Do-Not-Disturb stars), each re-syncing the shown order as it lands.
     * Draining them to a steady list before a move means a straggler arriving just after the move, under load, can't
     * re-sync the list back over it — the row the move produced then stays put instead of flickering back.
     */
    private fun waitForStableRows() {
        var seen: List<String>? = null
        var steady = 0
        compose.waitUntil(15_000) {
            val now = rows().map { it.text() }
            steady = if (now.size >= 2 && now == seen) steady + 1 else 0
            seen = now
            steady >= 3
        }
        compose.waitForIdle()
    }

    private val up get() = app.getString(R.string.action_move_up)
    private val down get() = app.getString(R.string.action_move_down)
    private val earlier get() = app.getString(R.string.fav_move_earlier)
    private val later get() = app.getString(R.string.fav_move_later)

    @Test fun home_tabs_move_up_and_down() {
        show { _ ->
            var tabs by remember { mutableStateOf(NavTabs()) }
            NavTabsEditor(tabs) { tabs = it }
        }
        checkMoves(up, down) { rows().first().text() }
    }

    @Test fun contact_page_sections_move_up_and_down() {
        show { vm -> ContactPageSettingsScreen(vm) {} }
        checkMoves(up, down) { rows().first().text() }
    }

    @Test fun favourites_in_the_reorder_sheet_move_earlier_and_later() {
        show { vm -> ReorderFavoritesSheet(vm) {} }
        compose.waitUntil(10_000) { rows().size == 2 }
        waitForStableRows()
        checkMoves(earlier, later) { rows().first().text() }
    }

    @Test fun favourite_tiles_move_earlier_and_later_while_reordering() {
        show { vm -> FavoritesTab(vm, {}) }
        waitForText("Bob Babbage")
        compose.onNodeWithText(app.getString(R.string.fav_reorder)).performClick()
        compose.waitUntil(10_000) { rows().size == 2 }
        waitForStableRows()
        checkMoves(earlier, later) { rows().first().text() }
    }

    @Test fun switch_rows_tick_on_and_off() {
        show { _ ->
            var on by remember { mutableStateOf(false) }
            var split by remember { mutableStateOf(true) }
            Column {
                SwitchRow("Plain switch", null, on) { on = it }
                SplitSwitchRow("Split switch", null, split, switchLabel = "Split switch on", openLabel = "Open", onOpen = {}) { split = it }
            }
        }
        compose.onNode(hasText("Plain switch")).performClick()
        compose.onNode(hasText("Plain switch")).performClick()
        assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), haptics.toList())
        haptics.clear()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Split switch on"))).performClick()
        assertEquals(listOf(HapticFeedbackType.ToggleOff), haptics.toList())
    }
}
