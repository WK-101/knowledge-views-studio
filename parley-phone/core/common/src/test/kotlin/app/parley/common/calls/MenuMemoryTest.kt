package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuMemoryTest {
    private val region = "GB"
    private val bank = "+44 20 7946 0000"

    private fun presses(vararg p: Pair<Char, Long>) = p.map { MenuPress(it.first, it.second) }

    @Test fun records_the_keys_with_their_waits_since_connect() {
        val path = MenuMemory.record(presses('2' to 4_000, '1' to 9_000, '4' to 12_500), at = 100, number = "$bank,,9")!!
        assertEquals(listOf(MenuStep('2', 4_000), MenuStep('1', 5_000), MenuStep('4', 3_500)), path.steps)
        assertEquals(100L, path.at)
        assertEquals(bank, path.number)
        assertEquals("2 › 1 › 4", MenuMemory.label(path.steps))
    }

    @Test fun keys_before_connect_and_unknown_keys_are_dropped() {
        val path = MenuMemory.record(presses('5' to -300, 'A' to 1_000, '#' to 2_000), at = 0)!!
        assertEquals(listOf(MenuStep('#', 2_000)), path.steps)
        assertNull(MenuMemory.record(emptyList(), at = 0))
    }

    @Test fun a_quick_run_of_six_digits_is_never_kept() {
        // Menu choices, then an account number typed quickly.
        val p = presses('2' to 3_000, '1' to 8_000, '1' to 12_000, '2' to 12_400, '3' to 12_800, '4' to 13_200, '5' to 13_600, '6' to 14_000)
        assertEquals(2, MenuMemory.secretStart(p))
        assertEquals("2 › 1", MenuMemory.label(MenuMemory.record(p, at = 0)!!.steps))
    }

    @Test fun four_quick_digits_and_hash_look_like_a_pin() {
        val p = presses('3' to 5_000, '1' to 10_000, '9' to 10_500, '7' to 11_000, '0' to 11_500, '#' to 12_000)
        assertEquals("3", MenuMemory.label(MenuMemory.record(p, at = 0)!!.steps))
        // The same digits, nothing after: four digits alone are kept (an extension, a menu shortcut).
        val ext = presses('1' to 10_000, '9' to 10_500, '7' to 11_000, '0' to 11_500)
        assertNull(MenuMemory.secretStart(ext))
        // Slow choices are menu steps, however many there are.
        val slow = (1..7).map { MenuPress('1', it * 4_000L) }
        assertNull(MenuMemory.secretStart(slow))
    }

    @Test fun a_secret_at_the_start_keeps_nothing() {
        val p = presses('4' to 1_000, '4' to 1_300, '1' to 1_600, '2' to 1_900, '#' to 2_200)
        assertNull(MenuMemory.record(p, at = 0))
    }

    @Test fun at_most_twelve_keys_are_kept() {
        val p = (1..20).map { MenuPress('1', it * 3_000L) }
        assertEquals(MenuMemory.MAX_STEPS, MenuMemory.record(p, at = 0)!!.steps.size)
    }

    @Test fun never_for_emergency_numbers_or_service_codes() {
        assertFalse(MenuMemory.remembers("112", emergency = false))
        assertFalse(MenuMemory.remembers("911", emergency = false))
        assertFalse(MenuMemory.remembers(bank, emergency = true))
        assertFalse(MenuMemory.remembers("*#06#", emergency = false))
        assertFalse(MenuMemory.remembers("*100#", emergency = false))
        assertFalse(MenuMemory.remembers(null, emergency = false))
        assertTrue(MenuMemory.remembers(bank, emergency = false))
        assertTrue(MenuMemory.remembers("0800 123 456,,2", emergency = false))
    }

    @Test fun the_dial_string_has_pauses_for_the_recorded_waits() {
        val steps = listOf(MenuStep('2', 6_000), MenuStep('1', 3_000), MenuStep('4', 500))
        assertEquals("0800123456,,2,1,4", MenuMemory.dialString("0800123456", steps))
        // A long wait is capped, a dial string's own pauses are not repeated.
        assertEquals("0800123456" + ",".repeat(MenuMemory.MAX_PAUSES) + "#", MenuMemory.dialString("0800123456,9", listOf(MenuStep('#', 600_000))))
    }

    @Test fun replay_waits_for_what_is_left_of_the_first_wait() {
        val steps = listOf(MenuStep('2', 6_000), MenuStep('1', 100), MenuStep('4', 120_000))
        assertEquals(listOf(2_000L, MenuMemory.MIN_REPLAY_GAP_MS, MenuMemory.MAX_REPLAY_GAP_MS), MenuMemory.replayDelays(steps, sinceConnectMs = 4_000))
        assertEquals(0L, MenuMemory.replayDelays(steps, sinceConnectMs = 60_000).first())
    }

    @Test fun remembers_per_number_unless_opted_out() {
        val key = MenuMemory.key(bank, region)
        assertEquals(MenuMemory.key("020 7946 0000,,1", region), key)
        val path = MenuPath(listOf(MenuStep('2', 3_000)), at = 1, number = bank)
        var s = MenuMemory.remember(MenuState(), key, path)
        assertEquals(path, MenuMemory.pathFor(s, key))
        s = MenuMemory.setOptOut(s, key, true)
        assertNull(MenuMemory.pathFor(s, key))
        assertTrue(s.paths.isEmpty())
        assertEquals(s, MenuMemory.remember(s, key, path))
        s = MenuMemory.setOptOut(s, key, false)
        assertEquals(path, MenuMemory.pathFor(MenuMemory.remember(s, key, path), key))
    }

    @Test fun the_oldest_numbers_go_first() {
        var s = MenuState()
        for (i in 0..MenuMemory.MAX_NUMBERS) s = MenuMemory.remember(s, "k$i", MenuPath(listOf(MenuStep('1', 1)), at = i.toLong()))
        assertEquals(MenuMemory.MAX_NUMBERS, s.paths.size)
        assertFalse("k0" in s.paths)
    }

    @Test fun shortcuts_are_added_renamed_found_and_removed() {
        val steps = listOf(MenuStep('2', 3_000), MenuStep('1', 3_000))
        val sc = MenuShortcut("a", "  Bank \n lost card ", "$bank,,7", steps, created = 1)
        var s = MenuMemory.addShortcut(MenuState(), sc, region)
        assertEquals("Bank lost card", s.shortcuts.single().name)
        assertEquals(bank, s.shortcuts.single().number)
        // The same number and keys again: one shortcut, renamed.
        s = MenuMemory.addShortcut(s, sc.copy(id = "b", name = "Bank › cards"), region)
        assertEquals(listOf("a"), s.shortcuts.map { it.id })
        assertEquals("Bank › cards", s.shortcuts.single().name)
        assertEquals(listOf("a"), MenuMemory.shortcutsFor(s, listOf("020 7946 0000"), region).map { it.id })
        assertTrue(MenuMemory.shortcutsFor(s, listOf("+44 20 7946 0001"), region).isEmpty())
        s = MenuMemory.renameShortcut(s, "a", "Card")
        assertEquals("Card", s.shortcuts.single().name)
        assertEquals(s, MenuMemory.renameShortcut(s, "a", "  "))
        assertTrue(MenuMemory.removeShortcut(s, "a").shortcuts.isEmpty())
        // Never a shortcut to an emergency number, without keys or without a name.
        assertEquals(MenuState(), MenuMemory.addShortcut(MenuState(), sc.copy(number = "112"), region))
        assertEquals(MenuState(), MenuMemory.addShortcut(MenuState(), sc.copy(steps = emptyList()), region))
        assertEquals(MenuState(), MenuMemory.addShortcut(MenuState(), sc.copy(name = " "), region))
    }

    @Test fun backup_leaves_private_numbers_out_and_restore_merges() {
        val mine = MenuState(
            paths = mapOf(
                "k1" to MenuPath(listOf(MenuStep('1', 1)), at = 5, number = "+441"),
                "k2" to MenuPath(listOf(MenuStep('2', 1)), at = 5, number = "+442"),
            ),
            shortcuts = listOf(MenuShortcut("a", "A", "+441", listOf(MenuStep('1', 1)), 1)),
        )
        val out = MenuMemory.without(mine) { it == "+442" }
        assertEquals(setOf("k1"), out.paths.keys)
        val restored = MenuState(
            paths = mapOf("k1" to MenuPath(listOf(MenuStep('9', 1)), at = 9, number = "+441"), "k3" to MenuPath(listOf(MenuStep('3', 1)), at = 1)),
            shortcuts = listOf(MenuShortcut("a", "Other", "+441", emptyList(), 1), MenuShortcut("b", "B", "+443", listOf(MenuStep('3', 1)), 1)),
            optOut = setOf("k2"),
        )
        val merged = MenuMemory.merge(mine, restored)
        assertEquals('9', merged.paths.getValue("k1").steps.single().tone)
        assertEquals(setOf("k1", "k3"), merged.paths.keys)
        assertEquals(listOf("A", "B"), merged.shortcuts.map { it.name })
        assertEquals(merged, MenuMemory.decode(MenuMemory.encode(merged)))
        assertEquals(MenuState(), MenuMemory.decode("not json"))
    }

    @Test fun suggested_names_and_cleaning() {
        assertEquals("Bank › 2 › 1", MenuMemory.suggestedName("Bank", listOf(MenuStep('2', 1), MenuStep('1', 1))))
        assertEquals(MenuMemory.MAX_NAME, MenuMemory.cleanName("x".repeat(100))!!.length)
    }
}
