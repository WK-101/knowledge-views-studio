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
        combos(12).forEach { b ->
            val f = ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11])
            val top = ContactMenu.build(f)
            assertTrue("$f: ${top.size} items", top.size <= MENU_LIMIT)
            // Nothing is lost in the regrouping: every action that applies is somewhere.
            val all = actions(top)
            assertTrue(ContactMenu.Action.DELETE in all)
            assertEquals(f.hasNumbers, ContactMenu.Action.REMIND_TO_CALL in all)
            assertEquals(f.hasNumbers && !f.onlyEmergency && !f.blocked, ContactMenu.Action.BLOCK_NUMBERS in all)
            assertEquals(f.hasNumbers && !f.onlyEmergency && f.blocked, ContactMenu.Action.UNBLOCK_NUMBERS in all)
            assertEquals(f.linked, ContactMenu.Action.SEPARATE in all)
            assertEquals(f.canSeeVersions, ContactMenu.Action.VERSION_HISTORY in all)
            assertEquals(f.hasNumbers && !f.caseShown, ContactMenu.Action.CASE_FILE in all)
            // Every sheet a group opens keeps to seven too.
            top.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().forEach { g -> assertTrue("$f: ${g.group}", g.actions.size <= MENU_LIMIT) }
            assertEquals(all.size, all.toSet().size)
        }
    }

    /** Every action of every menu is reachable with some facts: none was lost when the menus were regrouped. */
    @Test fun no_action_is_lost() {
        val contact = combos(12).flatMap { b ->
            actions(ContactMenu.build(ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11])))
        }
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
        // 17 actions (with Remind me to call, Keep a case file and Archive) in 7 entries; Version history is under More….
        assertEquals(7, top.size)
        assertEquals(17, actions(top).size)
        assertEquals(MenuEntry.Action(ContactMenu.Action.CASE_FILE), top[4])
        // With a case file on the page, its card opens it: 16 actions in 6 entries.
        assertEquals(6, ContactMenu.build(ContactMenu.Facts(linked = true, caseShown = true)).size)
        val more = top.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().single { it.group == MenuGroup.MORE }
        assertTrue(ContactMenu.Action.VERSION_HISTORY in more.actions)
        // A blocked number: Unblock in Block's place.
        assertEquals(MenuEntry.Action(ContactMenu.Action.UNBLOCK_NUMBERS), ContactMenu.build(ContactMenu.Facts(blocked = true))[2])
        // Archive is under Privacy….
        assertTrue(ContactMenu.Action.ARCHIVE in top.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().single { it.group == MenuGroup.PRIVACY }.actions)
        // A private contact is archived inside the vault (it stays private); an archived one isn't offered it again.
        assertTrue(ContactMenu.Action.ARCHIVE in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true))))
        assertTrue(ContactMenu.Action.ARCHIVE !in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true, archived = true))))
        // Archiving dropped its expiry and it is kept until Unarchive: no "Delete automatically" for it either.
        assertTrue(ContactMenu.Action.DELETE_AUTOMATICALLY !in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true, archived = true))))
        assertTrue(ContactMenu.Action.MAKE_VISIBLE in actions(ContactMenu.build(ContactMenu.Facts(isPrivate = true, archived = true))))
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

    @Test fun an_emergency_number_offers_neither_block_nor_unblock() {
        for (blocked in listOf(false, true)) {
            val recent = actions(RecentMenu.build(RecentMenu.Facts(hasNumber = true, blocked = blocked, emergency = true)))
            assertTrue(recent.none { it == RecentMenu.Action.BLOCK || it == RecentMenu.Action.UNBLOCK || it == RecentMenu.Action.REPORT })
            val contact = actions(ContactMenu.build(ContactMenu.Facts(blocked = blocked, onlyEmergency = true)))
            assertTrue(contact.none { it == ContactMenu.Action.BLOCK_NUMBERS || it == ContactMenu.Action.UNBLOCK_NUMBERS })
        }
        assertTrue(RecentMenu.Action.REMIND_TO_CALL in actions(RecentMenu.build(RecentMenu.Facts(hasNumber = true, emergency = true))))
    }

    @Test fun a_group_of_one_is_the_action_itself() {
        // A private-only selection has nothing to share: no empty "Share…".
        val top = SelectionMenu.build(SelectionMenu.Facts(hasDevice = false, hasPrivate = true))
        assertTrue(top.none { it is MenuEntry.Group && it.group == MenuGroup.SHARE })
        assertTrue(top.filterIsInstance<MenuEntry.Group<*>>().all { it.actions.size > 1 })
    }

    @Test fun a_selection_can_be_archived_from_privacy() {
        for (b in combos(3)) {
            val privacy = SelectionMenu.build(SelectionMenu.Facts(b[0], b[1], b[2])).filterIsInstance<MenuEntry.Group<SelectionMenu.Action>>()
                .single { it.group == MenuGroup.PRIVACY }
            assertEquals(SelectionMenu.Action.ARCHIVE, privacy.actions.last())
        }
    }
}
