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
    private fun contactFacts(b: List<Boolean>) = ContactMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10])

    @Test fun the_contact_page_menu_has_at_most_seven_items() {
        combos(11).forEach { b ->
            val f = contactFacts(b)
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
        val contact = combos(11).flatMap { b -> actions(ContactMenu.build(contactFacts(b))) }
        assertEquals(ContactMenu.Action.entries.toSet(), contact.toSet())
        val selection = combos(3).flatMap { b -> actions(SelectionMenu.build(SelectionMenu.Facts(b[0], b[1], b[2]))) }
        assertEquals(SelectionMenu.Action.entries.toSet(), selection.toSet())
        val recent = combos(6).flatMap { b ->
            val f = RecentMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5])
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
        // No Privacy… on a contact's page: its "Kept as" row says Visible, Private or Archived (and changes it).
        assertEquals(listOf(MenuGroup.SHARE, MenuGroup.MORE), top.filterIsInstance<MenuEntry.Group<*>>().map { it.group })
        assertEquals(5, top.size)
        val more = top.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().single { it.group == MenuGroup.MORE }
        assertTrue(ContactMenu.Action.VERSION_HISTORY in more.actions)
        // A person's case file waits under More…; a company's is at the top.
        assertTrue(ContactMenu.Action.CASE_FILE in more.actions)
        val company = ContactMenu.build(ContactMenu.Facts(linked = true, isCompany = true))
        assertEquals(MenuEntry.Action(ContactMenu.Action.CASE_FILE), company[3])
        val companyMore = company.filterIsInstance<MenuEntry.Group<ContactMenu.Action>>().single { it.group == MenuGroup.MORE }
        assertTrue(ContactMenu.Action.CASE_FILE !in companyMore.actions)
        // With a case file on the page, its card opens it.
        assertTrue(ContactMenu.Action.CASE_FILE !in actions(ContactMenu.build(ContactMenu.Facts(caseShown = true, isCompany = true))))
        // A blocked number: Unblock in Block's place.
        assertEquals(MenuEntry.Action(ContactMenu.Action.UNBLOCK_NUMBERS), ContactMenu.build(ContactMenu.Facts(blocked = true))[2])
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
        // Everything at once: 11 actions in 6 entries (it was 10 entries with "Introduce myself…", now a Tools row).
        // Edit… (bulk edit, with Add to label inside it) comes first.
        val all = SelectionMenu.build(SelectionMenu.Facts(hasDevice = true, hasPrivate = true, canMerge = true))
        assertEquals(6, all.size)
        assertEquals(MenuEntry.Action(SelectionMenu.Action.EDIT), all.first())
        assertEquals(SelectionMenu.Action.entries.toSet(), actions(all).toSet())
    }

    @Test fun a_recent_calls_sheet_has_a_row_of_buttons_and_at_most_seven_rows() {
        combos(6).forEach { b ->
            val f = RecentMenu.Facts(b[0], b[1], b[2], b[3], b[4], b[5])
            val rows = RecentMenu.build(f)
            assertTrue("$f: ${rows.size} rows", rows.size <= MENU_LIMIT)
            assertTrue(RecentMenu.quick(f).size <= 4)
            assertEquals(MenuEntry.Action(RecentMenu.Action.DELETE_FROM_HISTORY), rows.last())
            val all = actions(rows)
            assertEquals(f.hasNumber, RecentMenu.Action.REMIND_TO_CALL in all)
            assertEquals(f.hasNumber && f.blocked && !f.emergency, RecentMenu.Action.UNBLOCK in all)
            assertEquals(f.hasNumber && !f.blocked && !f.emergency, RecentMenu.Action.BLOCK in all)
            // An outgoing call never rang: no "Why did this ring?".
            assertEquals(f.hasNumber && f.rang, RecentMenu.Action.WHY_IT_RANG in all)
            // Remind me to call is a row of its own, never inside a group.
            if (f.hasNumber) assertTrue(MenuEntry.Action(RecentMenu.Action.REMIND_TO_CALL) in rows)
            rows.filterIsInstance<MenuEntry.Group<RecentMenu.Action>>().forEach { g -> assertTrue("$f: ${g.group}", g.actions.size <= MENU_LIMIT) }
        }
        val unknown = RecentMenu.Facts(hasNumber = true, saved = false, salesLine = true)
        assertEquals(
            listOf(RecentMenu.Action.CALL, RecentMenu.Action.MESSAGE, RecentMenu.Action.MESSAGE_OR_CALL_ON, RecentMenu.Action.COPY_NUMBER),
            RecentMenu.quick(unknown),
        )
        val rows = RecentMenu.build(unknown)
        assertEquals(
            listOf(RecentMenu.Action.CREATE_CONTACT, RecentMenu.Action.ADD_TO_CONTACT, RecentMenu.Action.REMIND_TO_CALL, RecentMenu.Action.BLOCK)
                .map { MenuEntry.Action(it) },
            rows.take(4),
        )
        // The screening rows sit under one "Allow, report…" entry: why it rang, the test, the sales line, allow and report.
        val groups = rows.filterIsInstance<MenuEntry.Group<RecentMenu.Action>>()
        val screening = groups.single { it.group == MenuGroup.WHY_IT_RANG }
        assertEquals(
            listOf(
                RecentMenu.Action.WHY_IT_RANG, RecentMenu.Action.TEST_A_CALL, RecentMenu.Action.SALES_LINE,
                RecentMenu.Action.ALWAYS_ALLOW, RecentMenu.Action.ALLOW_24H, RecentMenu.Action.REPORT,
            ),
            screening.actions,
        )
        // Edit before call and Search the web under More….
        assertEquals(listOf(RecentMenu.Action.EDIT_BEFORE_CALL, RecentMenu.Action.SEARCH_WEB), groups.single { it.group == MenuGroup.MORE }.actions)
        // An outgoing call to a saved number: the test alone, as its own row.
        val savedOut = RecentMenu.build(RecentMenu.Facts(hasNumber = true, saved = true, rang = false))
        assertTrue(MenuEntry.Action(RecentMenu.Action.TEST_A_CALL) in savedOut)
        assertTrue(RecentMenu.Action.WHY_IT_RANG !in actions(savedOut))
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

    /** One Share for a selection: the vCard to another app is in Share… with the clipboard and the file. */
    @Test fun a_selection_has_one_share() {
        val share = SelectionMenu.build(SelectionMenu.Facts()).filterIsInstance<MenuEntry.Group<SelectionMenu.Action>>().single { it.group == MenuGroup.SHARE }
        assertEquals(listOf(SelectionMenu.Action.SHARE_FILE, SelectionMenu.Action.COPY_AS_TEXT, SelectionMenu.Action.EXPORT_VCF), share.actions)
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
