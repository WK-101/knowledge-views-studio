package app.parley.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every floating button is core/ui's ParleyFab (or ParleyFabMenu): one size, one corner, one place at the bottom end
 * on every screen, so it never changes shape or jumps between tabs. A scan of the app's sources, since a raw Material
 * FAB would compile and look almost the same.
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

    private companion object {
        val RAW = Regex("""(?<![\w.])(?:Small|Medium|Large|Extended|Toggle)?FloatingActionButton\s*\(|(?<![\w.])FloatingActionButtonMenu\s*\(""")
        val SLOT = Regex("""floatingActionButton\s*=\s*\{""")
        val FAB = Regex("""\b(?:ParleyFab|ParleyFabMenu|AddButton)\s*\(""")
    }
}
