package app.parley.ui.common

import app.parley.common.ux.ContactMenu
import app.parley.common.ux.MenuEntry
import app.parley.common.ux.SelectionMenu
import app.parley.ui.contact.contactMenuIcon
import app.parley.ui.home.selectionMenuIcon
import androidx.compose.ui.graphics.vector.ImageVector
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One icon, one meaning, within a menu: an icon is never repeated among the entries of one ⋮ menu, or among the rows
 * of one of its sheets, whatever facts apply. (A padlock that both locks and unlocks, or a flag for a report and a
 * chapter, read as one action.)
 */
class MenuIconsTest {
    private fun combos(n: Int): List<List<Boolean>> = (0 until (1 shl n)).map { m -> (0 until n).map { m and (1 shl it) != 0 } }

    private fun <A> assertNoRepeats(what: String, entries: List<MenuEntry<A>>, icon: (A) -> ImageVector) {
        val top = entries.map { e ->
            when (e) {
                is MenuEntry.Action -> icon(e.action).name
                is MenuEntry.Group -> e.group.icon.name
            }
        }
        assertEquals("$what: ${top.groupBy { it }.filter { it.value.size > 1 }.keys}", top.size, top.toSet().size)
        entries.filterIsInstance<MenuEntry.Group<A>>().forEach { g ->
            val sheet = g.actions.map { icon(it).name }
            assertEquals("$what ${g.group}: $sheet", sheet.size, sheet.toSet().size)
        }
    }

    @Test fun the_contact_menu_repeats_no_icon() {
        combos(11).forEach { b ->
            val f = ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10])
            assertNoRepeats(f.toString(), ContactMenu.build(f), ::contactMenuIcon)
        }
        // And across all its actions: every one has an icon of its own.
        val all = ContactMenu.Action.entries.map { contactMenuIcon(it).name }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun the_selection_menu_repeats_no_icon() {
        combos(3).forEach { b ->
            val f = SelectionMenu.Facts(b[0], b[1], b[2])
            assertNoRepeats(f.toString(), SelectionMenu.build(f), ::selectionMenuIcon)
        }
    }
}
