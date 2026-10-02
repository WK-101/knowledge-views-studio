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

    @Test fun a_run_of_four_digits_is_never_kept() {
        // Menu choices, then an account number.
        val p = presses('2' to 3_000, '#' to 8_000, '1' to 12_000, '2' to 12_400, '3' to 12_800, '4' to 13_200, '5' to 13_600)
        assertEquals(2, MenuMemory.secretStart(p))
        assertEquals("2 › #", MenuMemory.label(MenuMemory.record(p, at = 0)!!.steps))
    }

    @Test fun a_four_digit_pin_is_never_kept_with_or_without_hash() {
        // The voicemail PIN that moves on by itself after four digits.
        assertNull(MenuMemory.record(presses('1' to 10_000, '9' to 10_500, '7' to 11_000, '0' to 11_500), at = 0))
        assertNull(MenuMemory.record(presses('4' to 1_000, '4' to 1_300, '1' to 1_600, '2' to 1_900, '#' to 2_200), at = 0))
        // A menu choice straight before the PIN is part of the same run of digits: nothing is kept.
        assertNull(MenuMemory.record(presses('3' to 5_000, '1' to 10_000, '9' to 10_500, '7' to 11_000, '0' to 11_500, '#' to 12_000), at = 0))
    }

    @Test fun slow_typing_is_no_way_round_the_guard() {
        // TalkBack users explore, then double-tap: seconds between digits.
        val slow = (1..4).map { MenuPress(('0' + it), it * 6_000L) }
        assertEquals(0, MenuMemory.secretStart(slow))
        assertNull(MenuMemory.record(slow, at = 0))
        // A pause in the middle of a PIN changes nothing either.
        val split = presses('1' to 1_000, '2' to 1_300, '3' to 1_600, '4' to 20_000, '5' to 20_300, '6' to 20_600, '#' to 21_000)
        assertNull(MenuMemory.record(split, at = 0))
    }

    @Test fun partial_passcodes_are_not_kept() {
        // "Enter the 2nd digit of your passcode, then hash" four times.
        val marked = presses('1' to 3_000, '#' to 4_000, '2' to 9_000, '#' to 9_500, '5' to 15_000, '#' to 15_500, '7' to 21_000, '#' to 21_500)
        assertEquals(0, MenuMemory.secretStart(marked))
        assertNull(MenuMemory.record(marked, at = 0))
        // "2nd, 5th and 6th digit" each answering its own prompt, after two menu choices: one run of five digits.
        val asked = presses('1' to 3_000, '2' to 8_000, '4' to 14_000, '7' to 20_000, '9' to 26_000)
        assertNull(MenuMemory.record(asked, at = 0))
        // Digits ended by a star or a hash count together ("12*34#").
        assertEquals(0, MenuMemory.secretStartOf("12*34#".toList()))
    }

    @Test fun mixed_runs_keep_the_menu_part_only() {
        // Menu keys with # and * in between are kept while they stay short.
        assertNull(MenuMemory.secretStartOf("1#2*3".toList()))
        assertEquals("1 › # › 2 › * › 3", MenuMemory.label(MenuMemory.record(presses('1' to 1, '#' to 2, '2' to 3, '*' to 4, '3' to 5), at = 0)!!.steps))
        // Menu keys, then a PIN: the keys before the PIN stay, nothing from it on.
        assertEquals(2, MenuMemory.secretStartOf("2*98765".toList()))
        // ...unless the keys before it were ended by a mark too: then they may be part of it.
        assertEquals(0, MenuMemory.secretStartOf("2*91234#".toList()))
        // A fourth digit ended by a mark: from the first such digit on.
        assertEquals(1, MenuMemory.secretStartOf("*1#2#3#4#".toList()))
        assertNull(MenuMemory.secretStartOf("*1#2#3#4".toList()))
        assertNull(MenuMemory.secretStartOf("".toList()))
    }

    @Test fun at_most_six_keys_are_kept() {
        val p = "123".map { MenuPress(it, 1_000L) } + (1..17).map { MenuPress(if (it % 2 == 0) '#' else '*', it * 3_000L) }
        assertEquals(MenuMemory.MAX_STEPS, MenuMemory.record(p, at = 0)!!.steps.size)
        assertEquals(6, MenuMemory.MAX_STEPS)
    }

    @Test fun stored_paths_go_through_the_guard_again() {
        val pin = MenuPath(listOf('1', '2', '3', '4').map { MenuStep(it, 3_000) }, at = 1)
        val menu = MenuPath(listOf(MenuStep('2', 3_000), MenuStep('1', 3_000)), at = 1)
        val long = MenuPath("12#**#*#*#**".map { MenuStep(it, 3_000) }, at = 1)
        val old = MenuState(paths = mapOf("pin" to pin, "menu" to menu, "long" to long), optOut = setOf("x"))
        val clean = MenuMemory.sanitize(old)
        assertEquals(setOf("menu", "long"), clean.paths.keys)
        assertEquals(menu, clean.paths.getValue("menu"))
        assertEquals(MenuMemory.MAX_STEPS, clean.paths.getValue("long").steps.size)
        assertEquals(setOf("x"), clean.optOut)
        assertEquals(clean, MenuMemory.sanitize(clean))
        // remember() never keeps what the guard refuses.
        assertTrue(MenuMemory.remember(MenuState(), "k", pin).paths.isEmpty())
        assertTrue(MenuMemory.forgetPaths(clean).paths.isEmpty())
    }

    @Test fun private_names_are_never_suggested() {
        assertEquals("Ana", MenuMemory.shortcutWho("Ana", "+44 20", private = false))
        assertEquals("+44 20", MenuMemory.shortcutWho("Ana", "+44 20", private = true))
        assertEquals("+44 20", MenuMemory.shortcutWho(" ", "+44 20", private = false))
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

    @Test fun replay_stops_in_a_conference_or_behind_another_call() {
        assertTrue(MenuMemory.replayGoesOn(active = true, inConference = false, otherActive = false))
        assertFalse(MenuMemory.replayGoesOn(active = true, inConference = true, otherActive = false))
        assertFalse(MenuMemory.replayGoesOn(active = true, inConference = false, otherActive = true))
        assertFalse(MenuMemory.replayGoesOn(active = false, inConference = false, otherActive = false))
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
        // The backup itself keeps shortcuts and opt-outs, never remembered paths.
        val backup = MenuMemory.forBackup(mine) { it == "+442" }
        assertTrue(backup.paths.isEmpty())
        assertEquals(listOf("a"), backup.shortcuts.map { it.id })
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
