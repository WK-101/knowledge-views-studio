package app.parley.ui.home

import androidx.activity.BackEventCompat
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.parley.ParleyApp
import app.parley.common.StartTab
import app.parley.ui.AppComposeHarness
import app.parley.ui.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's shell: each tab keeps its place while another is open, the back gesture previews closing the search and the
 * page beside a list (a cancelled swipe changes nothing, a finished one steps back once), and dialpad keys tick like
 * the system's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ParleyApp::class)
class HomeShellTest : AppComposeHarness() {
    override val extraContacts = 40

    private val opened = mutableListOf<Destination>()

    private fun home(tab: StartTab) = show { vm -> HomeScreen(vm, tabRequest = null, onTabRequestHandled = {}, initialTab = tab, open = { opened += it }) }

    private fun barItem(label: String) = compose.onAllNodes(hasText(label) and isSelectable())[0]

    private fun swipe(progress: Float) = BackEventCompat(0f, 400f, progress, BackEventCompat.EDGE_LEFT)

    private fun cancelledBackSwipe() {
        val back = activity.onBackPressedDispatcher
        back.dispatchOnBackStarted(swipe(0f))
        back.dispatchOnBackProgressed(swipe(0.6f))
        back.dispatchOnBackCancelled()
        compose.waitForIdle()
    }

    private fun finishedBackSwipe() {
        val back = activity.onBackPressedDispatcher
        back.dispatchOnBackStarted(swipe(0f))
        back.dispatchOnBackProgressed(swipe(0.8f))
        back.onBackPressed()
        compose.waitForIdle()
    }

    @Config(qualifiers = PHONE)
    @Test
    fun contacts_keep_their_scroll_position_across_a_tab_switch() {
        home(StartTab.CONTACTS)
        waitForText("Ada Lovelace")
        // The whole list, not the first screenful kept from last time.
        compose.waitUntil(10_000) { vm.people.listing.value != null }
        compose.waitForIdle()
        compose.onNode(contactList).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1500f) }
        compose.waitForIdle()
        val scrolled = listOffset()
        assertTrue("the list didn't scroll", scrolled > 0f)

        barItem("Recents").performClick()
        compose.waitForIdle()
        barItem("Contacts").performClick()
        compose.waitForIdle()

        // Back where it was, not at the top of the list.
        assertEquals(scrolled, listOffset(), 1f)
    }

    // The contact list: the one list here that scrolls up and down (the chips scroll sideways).
    private val contactList = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun listOffset(): Float =
        compose.onNode(contactList).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Config(qualifiers = PHONE)
    @Test
    fun a_back_swipe_closes_the_search_only_when_finished() {
        home(StartTab.CONTACTS)
        waitForText("Ada Lovelace")
        compose.onNodeWithContentDescription("Search contacts").performClick()
        compose.waitForIdle()
        assertTrue(searchOpen())

        cancelledBackSwipe()
        assertTrue(searchOpen())

        finishedBackSwipe()
        assertFalse(searchOpen())
        // Home itself stays: nothing was opened or left.
        assertTrue(opened.isEmpty())
    }

    private fun searchOpen() = compose.onAllNodesWithContentDescription("Close search").fetchSemanticsNodes().isNotEmpty()

    @Config(qualifiers = TABLET)
    @Test
    fun a_back_swipe_over_the_page_beside_the_list_steps_back_once() {
        home(StartTab.CONTACTS)
        waitForText("Choose someone to see their page here")
        waitForText("Ada Lovelace")
        compose.onAllNodes(hasText("Ada Lovelace"))[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty() }

        // A cancelled swipe leaves the page where it is.
        cancelledBackSwipe()
        assertTrue(compose.onAllNodesWithContentDescription("Edit").fetchSemanticsNodes().isNotEmpty())
        assertFalse(textShown("Choose someone to see their page here"))

        // A finished one closes it, and only it: the list stays, nothing opened over Home.
        finishedBackSwipe()
        waitForText("Choose someone to see their page here")
        assertTrue(textShown("Ada Lovelace"))
        assertTrue(opened.isEmpty())
    }

    @Config(qualifiers = PHONE)
    @Test
    fun dialpad_keys_tick_like_the_system_keyboard() {
        home(StartTab.KEYPAD)
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("5, J K L").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("5, J K L").performClick()
        compose.waitForIdle()
        assertTrue(haptics.toString(), HapticFeedbackType.VirtualKey in haptics)
        assertFalse(HapticFeedbackType.TextHandleMove in haptics)
    }
}
