package app.parley.common.calls

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.DialText
import app.parley.common.PhoneNumbers
import app.parley.common.SettingsCatalog
import app.parley.common.calltime.Ussd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.2 phone items (P1–P9). */
class PhoneV32Test {
    private fun call(id: Long, number: String, day: Long, type: CallType = CallType.INCOMING, hidden: Boolean = false) =
        CallEntry(id, number, null, type, day * 86_400_000L + id, 0, null, false, hidden)

    // ---- P8: call-log layout

    private val calls = listOf(
        call(9, "111", 2), call(8, "111", 2), call(7, "222", 2), call(6, "111", 2),
        call(5, "111", 1), call(4, "333", 1),
    )

    private fun rows(layout: RecentsLayout) = RecentsGrouping.group(calls, layout, { it.number }, { it.date / 86_400_000L }).map { r -> r.map { it.id } }

    @Test fun grouped_merges_only_consecutive_calls_on_the_same_day() {
        assertEquals(listOf(listOf(9L, 8L), listOf(7L), listOf(6L), listOf(5L), listOf(4L)), rows(RecentsLayout.GROUPED))
    }

    @Test fun chronological_gives_every_call_its_own_row() {
        assertEquals(calls.map { listOf(it.id) }, rows(RecentsLayout.CHRONOLOGICAL))
    }

    @Test fun by_day_gives_one_row_per_number_per_day() {
        // 111 called three times on day 2 (with 222 in between): one row, placed at its newest call.
        assertEquals(listOf(listOf(9L, 8L, 6L), listOf(7L), listOf(5L), listOf(4L)), rows(RecentsLayout.BY_DAY))
    }

    @Test fun recents_layout_is_a_searchable_setting() {
        listOf("recents_layout", "clear_history", "connect_haptic", "default_dialer_help").forEach { SettingsCatalog[it] }
    }

    // ---- P5: clear call history

    @Test fun clear_history_scopes() {
        val list = listOf(
            call(1, "111", 1), call(2, "999", 1, CallType.MISSED), call(3, "", 1, hidden = true),
            call(-4, "555", 1), call(5, "111", 1, CallType.REJECTED),
        )
        val known = { n: String -> n == "111" }
        assertEquals(listOf(1L, 2L, 3L, 5L), ClearHistory.select(list, ClearScope.ALL, known).map { it.id })
        // Private numbers count as unknown; private-contact calls (negative ids) are never touched.
        assertEquals(listOf(2L, 3L), ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, known).map { it.id })
        assertEquals(listOf(2L, 5L), ClearHistory.select(list, ClearScope.MISSED, known).map { it.id })
        assertEquals(listOf(5L), ClearHistory.select(list, ClearScope.SHOWN, known, setOf(5L, -4L)).map { it.id })
    }

    @Test fun clear_history_never_takes_a_private_contacts_call_not_yet_moved_to_the_vault() {
        val list = listOf(call(1, "111", 1), call(2, "777", 1, CallType.MISSED), call(3, "999", 1), call(4, "", 1, CallType.MISSED, hidden = true))
        val known = { n: String -> n == "111" }
        val private = { n: String -> n == "777" }
        assertEquals(listOf(1L, 3L, 4L), ClearHistory.select(list, ClearScope.ALL, known, isPrivate = private).map { it.id })
        assertEquals(listOf(4L), ClearHistory.select(list, ClearScope.MISSED, known, isPrivate = private).map { it.id })
        assertEquals(listOf(3L, 4L), ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, known, isPrivate = private).map { it.id })
        assertEquals(listOf(1L), ClearHistory.select(list, ClearScope.SHOWN, known, setOf(1L, 2L), isPrivate = private).map { it.id })
    }

    @Test fun clear_unknown_numbers_needs_the_contacts() {
        val list = listOf(call(1, "111", 1), call(2, "999", 1))
        // Contacts not loaded or not permitted: every number would look unknown, so nothing is picked.
        assertTrue(ClearHistory.select(list, ClearScope.UNKNOWN_NUMBERS, { false }, contactsReady = false).isEmpty())
        assertFalse(ClearHistory.available(ClearScope.UNKNOWN_NUMBERS, contactsReady = false))
        // The other scopes don't depend on them.
        assertTrue(ClearHistory.available(ClearScope.ALL, contactsReady = false))
        assertEquals(listOf(1L, 2L), ClearHistory.select(list, ClearScope.ALL, { false }, contactsReady = false).map { it.id })
    }

    // ---- P6: call failure banner

    private fun facts(
        code: EndCode? = EndCode.ERROR, outgoing: Boolean = true, connected: Boolean = false, picker: Boolean = false,
        user: Boolean = false, airplane: Boolean = false, emergency: Boolean = false,
    ) = EndFacts(outgoing, connected, code, picker, user, airplane, emergency, hasNumber = true)

    @Test fun failure_only_for_outgoing_calls_that_never_connected() {
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts()))
        assertNull(CallFailure.classify(facts(outgoing = false)))
        assertNull(CallFailure.classify(facts(connected = true)))
        assertNull(CallFailure.classify(facts(emergency = true)))
        // The user hung up or cancelled: no banner.
        assertNull(CallFailure.classify(facts(code = EndCode.LOCAL, user = true)))
        // The other side declined.
        assertNull(CallFailure.classify(facts(code = EndCode.REMOTE)))
        assertNull(CallFailure.classify(facts(code = EndCode.REJECTED)))
    }

    @Test fun failure_reasons() {
        assertEquals(FailureKind.AIRPLANE_MODE, CallFailure.classify(facts(airplane = true)))
        assertEquals(FailureKind.NO_SIM_SELECTED, CallFailure.classify(facts(code = EndCode.ERROR, picker = true)))
        assertEquals(FailureKind.BUSY, CallFailure.classify(facts(code = EndCode.BUSY)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.RESTRICTED)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.OTHER)))
        assertEquals(FailureKind.OTHER, CallFailure.classify(facts(code = EndCode.UNKNOWN)))
    }

    @Test fun a_hang_up_from_outside_parley_is_never_a_failure() {
        // Power button, Bluetooth headset or car kit, a watch: Telecom reports LOCAL (or CANCELED) without Parley knowing.
        listOf(EndCode.LOCAL, EndCode.CANCELED).forEach { code ->
            assertNull(CallFailure.classify(facts(code = code)))
            assertNull(CallFailure.classify(facts(code = code, airplane = true)))
            assertNull(CallFailure.classify(facts(code = code, picker = true)))
        }
        assertNull(CallFailure.classify(facts(code = EndCode.MISSED)))
        assertNull(CallFailure.classify(facts(code = null)))
    }

    // ---- P9: '#' in dialled numbers and the Call button

    @Test fun hash_codes_survive_every_step_before_dialling() {
        listOf("*100#", "#123*1#", "*#06#", "**21*+4915112345678#", "#31#0612345678", "*43#").forEach { code ->
            assertEquals(code, DialText.sanitize(code))
            if ('+' !in code) assertEquals(code, PhoneNumbers.clean(code))
            assertEquals(code, DialTarget.pick(code, topMatch = "+15550100"))
        }
        // A pasted number keeps a trailing '#' (voicemail PINs, extensions).
        assertEquals("0612345678,1234#", DialText.sanitize("06 12 34 56 78,1234#"))
    }

    @Test fun ussd_and_mmi_codes() {
        assertTrue(Ussd.isUssd("*100#"))
        assertTrue(Ussd.isUssd("#123*1#"))
        assertTrue(Ussd.isUssd("*123*2*1#"))
        // Supplementary services (forwarding, waiting, caller ID, IMEI) are dialled like a call, '#' included.
        assertFalse(Ussd.isUssd("**21*+4915112345678#"))
        assertFalse(Ussd.isUssd("*43#"))
        assertFalse(Ussd.isUssd("*#06#"))
        assertFalse(Ussd.isUssd("#31#0612345678"))
        assertTrue(PhoneNumbers.isServiceCode("*#06#"))
        assertEquals("4636", DialCodes.secretCode("*#*#4636#*#*"))
        assertNull(DialCodes.secretCode("*#06#"))
        assertFalse(Ussd.isUssd("*#*#4636#*#*"))
    }

    @Test fun call_button_dials_the_typed_number_not_the_top_match() {
        // "555" matches Ana's +1 555 0100 first: the Call button still dials 555.
        assertEquals("555", DialTarget.pick("555", topMatch = "+15550100"))
        assertEquals("0612", DialTarget.pick(" 0612 ", topMatch = "+33612345678"))
        assertEquals("+33612345678;42", DialTarget.pick("+33612345678;42", topMatch = "+33612345678"))
        // Only a name typed on a hardware keyboard calls the best match.
        assertEquals("+15550100", DialTarget.pick("ana", topMatch = "+15550100"))
        assertNull(DialTarget.pick("ana", topMatch = null))
        assertNull(DialTarget.pick("  ", topMatch = "+15550100"))
    }

    // ---- P9 / A1: call waiting

    private data class C(val id: String, val s: LiveCallState, val sim: Boolean = false)

    private fun slots(vararg c: C) = CallWaiting.slots(c.toList()) { it.s }

    @Test fun a_second_ringing_call_is_call_waiting() {
        val s = slots(C("a", LiveCallState.ACTIVE), C("b", LiveCallState.RINGING))
        assertEquals("b", s.primary?.id)
        assertEquals("a", s.current?.id)
        assertTrue(s.waiting)
    }

    @Test fun a_ringing_call_with_only_a_held_call_is_call_waiting_too() {
        val s = slots(C("a", LiveCallState.HOLDING), C("b", LiveCallState.RINGING))
        assertTrue(s.waiting)
        assertEquals("a", s.current?.id)
        assertEquals(listOf("a"), s.held.map { it.id })
    }

    @Test fun a_lone_ringing_call_is_a_normal_incoming_call() {
        assertFalse(slots(C("b", LiveCallState.RINGING)).waiting)
        assertFalse(slots(C("a", LiveCallState.ACTIVE), C("c", LiveCallState.DIALING)).waiting)
        assertEquals("a", slots(C("c", LiveCallState.DIALING), C("a", LiveCallState.ACTIVE)).primary?.id)
        assertNull(slots().primary)
    }

    // ---- P1: picture-in-picture

    @Test fun pip_never_for_ringing_calls_or_the_sim_picker() {
        val pip = { l: List<C> -> CallWaiting.pipAllowed(l, { it.s }, { it.sim }) }
        assertTrue(pip(listOf(C("a", LiveCallState.ACTIVE))))
        assertTrue(pip(listOf(C("a", LiveCallState.DIALING))))
        assertFalse(pip(emptyList()))
        assertFalse(pip(listOf(C("a", LiveCallState.ACTIVE), C("b", LiveCallState.RINGING))))
        assertFalse(pip(listOf(C("a", LiveCallState.OTHER, sim = true))))
    }

    // ---- P4: default-dialer rescue

    @Test fun rescue_only_when_android_refused_without_asking() {
        assertTrue(RoleRescue.silentlyRefused(granted = false, elapsedMs = 40))
        assertFalse(RoleRescue.silentlyRefused(granted = false, elapsedMs = 1_800)) // Cancel on a real dialog
        assertFalse(RoleRescue.silentlyRefused(granted = true, elapsedMs = 40))
        assertEquals(RoleRescue.Variant.ANDROID_10_11, RoleRescue.variant(29))
        assertEquals(RoleRescue.Variant.ANDROID_12, RoleRescue.variant(32))
        assertEquals(RoleRescue.Variant.ANDROID_13_PLUS, RoleRescue.variant(36))
    }
}
