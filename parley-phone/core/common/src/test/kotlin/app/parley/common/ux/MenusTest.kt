package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MenusTest {
    private fun <A> actions(entries: List<MenuEntry<A>>): List<A> = entries.flatMap {
        when (it) {
            is MenuEntry.Action -> listOf(it.action)
            is MenuEntry.Group -> it.actions
        }
    }

    /** Every combination of [n] facts, as bit masks. */
    private fun combos(n: Int): List<List<Boolean>> = (0 until (1 shl n)).map { m -> (0 until n).map { m and (1 shl it) != 0 } }

    /** Every combination of facts: the top of each menu stays at seven items or fewer, whatever applies. */
    @Test fun the_contact_page_menu_has_at_most_seven_items() {
        combos(10).forEach { b ->
            val f = ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9])
            val top = ContactMenu.build(f)
            assertTrue("$f: ${top.size} items", top.size <= MENU_LIMIT)
            // Nothing is lost in the regrouping: every action that applies is somewhere.
            val all = actions(top)
            assertTrue(ContactMenu.Action.DELETE in all)
            assertEquals(f.hasNumbers, ContactMenu.Action.REMIND_TO_CALL in all)
            assertEquals(f.hasNumbers && !f.blocked, ContactMenu.Action.BLOCK_NUMBERS in all)
            assertEquals(f.hasNumbers && f.blocked, ContactMenu.Action.UNBLOCK_NUMBERS in all)
            assertEquals(f.linked, ContactMenu.Action.SEPARATE in all)
            assertEquals(f.canSeeVersions, ContactMenu.Action.VERSION_HISTORY in all)
            assertEquals(all.size, all.toSet().size)
        }
    }

    /** Every action of every menu is reachable with some facts: none was lost when the menus were regrouped. */
    @Test fun no_action_is_lost() {
        val contact = combos(10).flatMap { b -> actions(ContactMenu.build(ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9]))) }
        assertEquals(ContactMenu.Action.entries.toSet(), contact.toSet())
        val selection = combos(3).flatMap { b -> actions(SelectionMenu.build(SelectionMenu.Facts(b[0], b[1], b[2]))) }
        assertEquals(SelectionMenu.Action.entries.toSet(), selection.toSet())
        val recent = combos(4).flatMap { b ->
            val f = RecentMenu.Facts(b[0], b[1], b[2], b[3])
            RecentMenu.quick(f) + actions(RecentMenu.build(f))
        }
        assertEquals(RecentMenu.Action.entries.toSet(), recent.toSet())
    }

    @Test fun the_contact_page_menu_puts_the_most_used_first_and_groups_the_rest() {
        val top = ContactMenu.build(ContactMenu.Facts(linked = true))
        assertEquals(MenuEntry.Action(ContactMenu.Action.REMIND_TO_CALL), top.first())
        assertEquals(MenuGroup.SHARE, (top[1] as MenuEntry.Group).group)
        assertEquals(MenuEntry.Action(ContactMenu.Action.BLOCK_NUMBERS), top[2])
        assertEquals(MenuEntry.Action(ContactMenu.Action.DELETE), top.last())
        assertEquals(listOf(MenuGroup.SHARE, MenuGroup.PRIVACY, MenuGroup.MORE), top.filterIsInstance<MenuEntry.Group<*>>().map { it.group })
        // 15 actions (with Remind me to call) in 6 entries; Version history is under More….
        assertEquals(6, top.size)
        assertEquals(15, actions(top).size)
        val more = top.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().single { it.group == MenuGroup.MORE }
        assertTrue(ContactMenu.Action.VERSION_HISTORY in more.actions)
        // A blocked number: Unblock in Block's place.
        assertEquals(MenuEntry.Action(ContactMenu.Action.UNBLOCK_NUMBERS), ContactMenu.build(ContactMenu.Facts(blocked = true))[2])
        // A private contact offers Make visible instead of Make private.
        assertTrue(ContactMenu.Action.MAKE_VISIBLE in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true))))
        assertTrue(ContactMenu.Action.MAKE_PRIVATE !in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true))))
    }

    @Test fun the_selection_menu_has_at_most_seven_items() {
        combos(3).forEach { b ->
            val f = SelectionMenu.Facts(b[0], b[1], b[2])
            val top = SelectionMenu.build(f)
            assertTrue("$f: ${top.size} items", top.size <= MENU_LIMIT)
            assertEquals(MenuEntry.Action(SelectionMenu.Action.DELETE), top.last())
            assertEquals(f.hasPrivate, SelectionMenu.Action.MAKE_VISIBLE in actions(top))
            assertEquals(f.hasDevice, SelectionMenu.Action.MAKE_PRIVATE in actions(top))
        }
        // Everything at once: 9 actions in 6 entries (it was 10 entries with "Introduce myself…", now a Tools row).
        // Edit… (bulk edit, with Add to label inside it) comes first.
        val all = SelectionMenu.build(SelectionMenu.Facts(hasDevice = true, hasPrivate = true, canMerge = true))
        assertEquals(6, all.size)
        assertEquals(MenuEntry.Action(SelectionMenu.Action.EDIT), all.first())
        assertEquals(SelectionMenu.Action.entries.toSet(), actions(all).toSet())
    }

    @Test fun a_recent_calls_sheet_has_a_row_of_buttons_and_at_most_six_rows() {
        combos(4).forEach { b ->
            val f = RecentMenu.Facts(b[0], b[1], b[2], b[3])
            val rows = RecentMenu.build(f)
            assertTrue("$f: ${rows.size} rows", rows.size <= MENU_LIMIT - 1)
            assertTrue(RecentMenu.quick(f).size <= 4)
            assertEquals(MenuEntry.Action(RecentMenu.Action.DELETE_FROM_HISTORY), rows.last())
            val all = actions(rows)
            assertEquals(f.hasNumber, RecentMenu.Action.REMIND_TO_CALL in all)
            assertEquals(f.hasNumber && f.blocked, RecentMenu.Action.UNBLOCK in all)
            assertEquals(f.hasNumber && !f.blocked, RecentMenu.Action.BLOCK in all)
        }
        val unknown = RecentMenu.Facts(hasNumber = true, saved = false, salesLine = true)
        assertEquals(
            listOf(RecentMenu.Action.CALL, RecentMenu.Action.MESSAGE, RecentMenu.Action.MESSAGE_OR_CALL_ON, RecentMenu.Action.COPY_NUMBER),
            RecentMenu.quick(unknown),
        )
        // The screening rows (about seven) sit under one "Why it rang…" entry.
        val groups = RecentMenu.build(unknown).filterIsInstance<MenuEntry.Group<RecentMenu.Action>>()
        val why = groups.single { it.group == MenuGroup.WHY_IT_RANG }
        assertEquals(7, why.actions.size)
        // Edit before call and Remind me to call under More….
        assertEquals(listOf(RecentMenu.Action.EDIT_BEFORE_CALL, RecentMenu.Action.REMIND_TO_CALL), groups.single { it.group == MenuGroup.MORE }.actions)
        // A hidden number can only be deleted.
        assertEquals(listOf(MenuEntry.Action(RecentMenu.Action.DELETE_FROM_HISTORY)), RecentMenu.build(RecentMenu.Facts(hasNumber = false)))
    }

    @Test fun a_group_of_one_is_the_action_itself() {
        // A private-only selection has nothing to share: no empty "Share…".
        val top = SelectionMenu.build(SelectionMenu.Facts(hasDevice = false, hasPrivate = true))
        assertTrue(top.none { it is MenuEntry.Group && it.group == MenuGroup.SHARE })
        assertTrue(top.filterIsInstance<MenuEntry.Group<*>>().all { it.actions.size > 1 })
    }
}
