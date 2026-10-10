package app.parley.common.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Parley PIN and the duress PIN: hashing, verification, the stored form and the backoff. */
class AppPinTest {
    // A small scrypt cost keeps the tests fast; the app uses PinHasher.LOG2N.
    private fun record(pin: String = "246810") = PinHasher.create(pin, log2N = 10, r = 1, p = 1)

    @Test fun pins_are_four_to_twelve_digits() {
        assertTrue(PinRules.valid("1234"))
        assertTrue(PinRules.valid("123456789012"))
        assertFalse(PinRules.valid("123"))
        assertFalse(PinRules.valid("1234567890123"))
        assertFalse(PinRules.valid("12a4"))
        assertFalse(PinRules.valid(""))
    }

    @Test fun typed_text_keeps_digits_and_reads_native_digits() {
        assertEquals("1234", PinRules.normalize("١٢٣٤"))
        assertEquals("1234", PinRules.normalize("12 3-4"))
        assertEquals(PinRules.MAX_LENGTH, PinRules.normalize("1".repeat(20)).length)
    }

    @Test fun only_hashes_are_stored() {
        val r = record("246810")
        assertFalse(r.encode().contains("246810"))
        assertNotEquals(record("246810").salt, r.salt)
        assertNotEquals(record("246810").pin, r.pin)
    }

    @Test fun verify_tells_the_two_pins_apart_and_rejects_others() {
        val r = PinHasher.withDuress(record("246810"), "1357")
        assertEquals(PinVerdict.NORMAL, PinHasher.verify(r, "246810"))
        assertEquals(PinVerdict.DURESS, PinHasher.verify(r, "1357"))
        assertEquals(PinVerdict.WRONG, PinHasher.verify(r, "246811"))
        assertEquals(PinVerdict.WRONG, PinHasher.verify(r, "12"))
    }

    @Test fun without_a_duress_pin_nothing_is_duress() {
        val r = record("246810")
        assertEquals(PinVerdict.WRONG, PinHasher.verify(r, "1357"))
        assertFalse(r.hasDuress)
    }

    @Test fun changing_the_pin_keeps_the_duress_pin() {
        val r = PinHasher.withPin(PinHasher.withDuress(record("246810"), "1357"), "9999")
        assertEquals(PinVerdict.NORMAL, PinHasher.verify(r, "9999"))
        assertEquals(PinVerdict.WRONG, PinHasher.verify(r, "246810"))
        assertEquals(PinVerdict.DURESS, PinHasher.verify(r, "1357"))
        assertEquals(PinVerdict.WRONG, PinHasher.verify(PinHasher.withDuress(r, null), "1357"))
    }

    @Test fun the_duress_pin_must_differ_from_the_pin() {
        val r = record("246810")
        assertEquals(PinProblem.SAME_AS_PIN, PinRules.duressProblem(r, "246810"))
        assertEquals(PinProblem.INVALID, PinRules.duressProblem(r, "12"))
        assertNull(PinRules.duressProblem(r, "1357"))
    }

    @Test fun records_round_trip_and_damaged_ones_are_refused() {
        val r = PinHasher.withDuress(record(), "1357").copy(lockVaultOnDuress = false, failures = 6, lastFailureAt = 12_345L)
        assertEquals(r, PinRecord.decode(r.encode()))
        assertNull(PinRecord.decode(null))
        assertNull(PinRecord.decode(""))
        assertNull(PinRecord.decode("p2;10;1;1;a;b;;1;0;0"))
        assertNull(PinRecord.decode(r.encode().replace(";10;", ";40;")))
        assertNull(PinRecord.decode(r.encode().substringBeforeLast(';')))
    }

    @Test fun five_free_tries_then_doubling_waits_up_to_an_hour() {
        assertEquals(0L, PinBackoff.waitAfter(4))
        assertEquals(30_000L, PinBackoff.waitAfter(5))
        assertEquals(60_000L, PinBackoff.waitAfter(6))
        assertEquals(120_000L, PinBackoff.waitAfter(7))
        assertEquals(PinBackoff.MAX_WAIT_MS, PinBackoff.waitAfter(12))
        assertEquals(PinBackoff.MAX_WAIT_MS, PinBackoff.waitAfter(10_000))
    }

    @Test fun the_wait_counts_down_and_restarts_after_a_reboot() {
        var r = record()
        repeat(5) { r = PinBackoff.after(r, PinVerdict.WRONG, 1_000_000L) }
        assertEquals(30_000L, PinBackoff.remaining(r, 1_000_000L))
        assertEquals(10_000L, PinBackoff.remaining(r, 1_020_000L))
        assertEquals(0L, PinBackoff.remaining(r, 1_030_000L))
        // Elapsed time restarted (the phone rebooted): the full wait again, counted from now once rebased.
        assertEquals(30_000L, PinBackoff.remaining(r, 5_000L))
        val rebased = PinBackoff.rebased(r, 5_000L)
        assertEquals(20_000L, PinBackoff.remaining(rebased, 15_000L))
    }

    @Test fun either_right_pin_clears_the_count() {
        var r = record()
        repeat(7) { r = PinBackoff.after(r, PinVerdict.WRONG, 10L) }
        assertEquals(0, PinBackoff.after(r, PinVerdict.DURESS, 20L).failures)
        assertEquals(0, PinBackoff.after(r, PinVerdict.NORMAL, 20L).failures)
        assertEquals(0L, PinBackoff.remaining(PinBackoff.after(r, PinVerdict.NORMAL, 20L), 21L))
    }
}
