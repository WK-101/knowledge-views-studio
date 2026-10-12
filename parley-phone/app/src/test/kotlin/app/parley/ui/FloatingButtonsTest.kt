package app.parley.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every floating button is core/ui's ParleyFab (or ParleyFabMenu): one size, one corner, one place at the bottom end
 * on every screen, so it never changes shape or jumps between tabs, and everything else gives way to it by one shared
 * reserve. A scan of the app's sources, since a raw Material FAB would compile and look almost the same.
 */
class FloatingButtonsTest {
    private val root = File("..").canonicalFile
    private val app: Map<String, String> = File(root, "app/src/main/kotlin").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }

    @Test fun no_screen_draws_a_raw_material_floating_button() {
        assertTrue("the scan found nothing: wrong folder?", app.size > 100)
        val raw = app.filterValues { RAW.containsMatchIn(it) }.keys
        assertTrue("raw floating buttons in $raw: use ParleyFab or ParleyFabMenu", raw.isEmpty())
    }

    @Test fun every_floating_button_slot_holds_a_parley_fab() {
        val slots = app.flatMap { (name, text) ->
            SLOT.findAll(text).map { name to text.substring(it.range.last, (it.range.last + 400).coerceAtMost(text.length)) }.toList()
        }
        assertTrue("no floating button slots found", slots.isNotEmpty())
        slots.forEach { (name, body) -> assertTrue("$name: the floating button slot doesn't hold a ParleyFab", FAB.containsMatchIn(body)) }
    }

    @Test fun the_screens_with_a_floating_button_use_parley_fab() {
        // Contacts' add button, the docked keypad's button in Recents and the contact page's "Log a chat or visit".
        mapOf("HomeScreen.kt" to "ParleyFabMenu(", "CombinedSurfaces.kt" to "ParleyFab(", "ContactDetailScreen.kt" to "ParleyFab(").forEach { (file, call) ->
            assertTrue("$file doesn't use $call", app.getValue(file).contains(call))
        }
    }

    @Test fun buttons_placed_by_hand_sit_where_the_scaffold_puts_them() {
        // In a pane (or over the docked keypad) a button is placed by hand: at the bottom end, the same margin.
        val keypad = app.getValue("KeypadTab.kt")
        assertTrue(keypad.contains("DockedKeypadButton(") && keypad.contains("align(Alignment.BottomEnd).padding(ParleyFabDefaults.Margin)"))
        assertTrue(app.getValue("HomeScreen.kt").contains("align(Alignment.BottomEnd).padding(ParleyFabDefaults.Margin)) { AddButton"))
        // And never moves for what's around it (the number actions make room instead).
        assertEquals(false, keypad.contains("bottom = 16.dp + if (showNumberActions)"))
    }

    @Test fun the_button_area_is_one_shared_reserve() {
        // The button and a margin above and below it, 16 dp from the end: the one number every screen gives way by.
        assertEquals(ParleyFabDefaults.Size + ParleyFabDefaults.Margin * 2, ParleyFabDefaults.Reserve)
        assertEquals(16.dp, ParleyFabDefaults.Margin)
        assertEquals(ParleyFabDefaults.Reserve, ParleyFabDefaults.listPadding().calculateBottomPadding())
        assertEquals(ParleyFabDefaults.Reserve + 8.dp, ParleyFabDefaults.listPadding(8.dp).calculateBottomPadding())
        // The A–Z index ends above the button's area, so the two never overlap or compete for a touch.
        assertEquals(ParleyFabDefaults.Reserve, AlphabetIndexDefaults.BottomReserve)
        val rail = File(root, "core/ui/src/main/kotlin/app/parley/ui/AlphabetIndexRail.kt").readText()
        assertEquals(2, Regex("""AlphabetIndex\.usable\(""").findAll(rail).count())
    }

    @Test fun every_screen_with_a_floating_button_gives_way_by_the_reserve() {
        // Lists under a button end with its reserve, so their last row scrolls clear of it.
        assertTrue(app.getValue("ContactsTab.kt").contains("contentPadding = ParleyFabDefaults.listPadding()"))
        assertTrue(app.getValue("ContactDetailScreen.kt").contains("padding.calculateBottomPadding() + ParleyFabDefaults.Reserve"))
        assertTrue(app.getValue("CombinedSurfaces.kt").contains("bottomPadding = if (expanded) 0.dp else ParleyFabDefaults.Reserve"))
        assertTrue(app.getValue("KeypadSections.kt").contains("ParleyFabDefaults.Reserve"))
        // A button placed by hand lifts the snackbar by the same reserve (the Scaffold does it for its own button).
        assertTrue(app.getValue("HomeScreen.kt").contains("ScreenSnackbarHost(Modifier.clearOfFab(fabByHand))"))
        // No screen with a button keeps its own clearance number instead.
        val withFab = app.filterValues { FAB.containsMatchIn(it) || it.contains("DockedKeypadButton(") }
        val magic = withFab.filterValues { CLEARANCE.containsMatchIn(it) }.keys
        assertTrue("hand-made clearance in $magic: use ParleyFabDefaults.Reserve", magic.isEmpty())
    }

    private companion object {
        val CLEARANCE = Regex("""bottom\s*=\s*(?:7[2-9]|[89]\d|1\d\d)\.dp""")
        val RAW = Regex("""(?<![\w.])(?:Small|Medium|Large|Extended|Toggle)?FloatingActionButton\s*\(|(?<![\w.])FloatingActionButtonMenu\s*\(""")
        val SLOT = Regex("""floatingActionButton\s*=\s*\{""")
        val FAB = Regex("""\b(?:ParleyFab|ParleyFabMenu|AddButton)\s*\(""")
    }
}
