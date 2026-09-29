package app.parley.common.calls

import app.parley.common.calls.CallerHaptics.Pattern
import app.parley.common.calls.CallerHaptics.Preset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerHapticsTest {
    private fun LongArray.pulses() = filterIndexed { i, _ -> i % 2 == 1 }
    private fun LongArray.gaps() = filterIndexed { i, _ -> i > 0 && i % 2 == 0 }

    @Test fun spec_round_trips_and_rejects_nonsense() {
        Preset.entries.forEach { p ->
            val pattern = Pattern(p, if (p == Preset.GENERATED) -12345 else 0)
            assertEquals(pattern, CallerHaptics.decode(CallerHaptics.encode(pattern)))
        }
        assertNull(CallerHaptics.decode(null))
        assertNull(CallerHaptics.decode(""))
        assertNull(CallerHaptics.decode("tango"))
        assertNull(CallerHaptics.decode("generated:notanumber"))
        assertEquals(Pattern(Preset.HEARTBEAT), CallerHaptics.decode(" heartbeat "))
    }

    @Test fun generated_rhythms_are_stable_fit_in_a_second_and_can_be_felt() {
        repeat(2000) { seed ->
            val t = CallerHaptics.generated(seed * 7919)
            assertArrayEquals(t, CallerHaptics.generated(seed * 7919))
            assertEquals(0L, t[0])
            assertTrue("seed $seed: ${t.toList()}", CallerHaptics.length(t) <= CallerHaptics.RHYTHM_MS)
            assertTrue(t.pulses().size in 2..4)
            assertTrue(t.pulses().all { it >= CallerHaptics.MIN_PULSE_MS })
            assertTrue(t.gaps().all { it >= CallerHaptics.MIN_PULSE_MS })
        }
    }

    @Test fun different_people_mostly_get_different_rhythms() {
        val names = (1..200).map { "contact-key-$it" }
        val distinct = names.map { CallerHaptics.timings(CallerHaptics.generatedFor(it)).toList() }.toSet()
        // A few collisions are fine (there are only so many rhythms), but most people must feel different.
        assertTrue("only ${distinct.size} distinct", distinct.size > 60)
        // The same key (whatever its case) gives the same pattern.
        assertEquals(CallerHaptics.generatedFor("Sam"), CallerHaptics.generatedFor(" sam"))
    }

    @Test fun morse_initial_taps_the_first_letter() {
        val u = CallerHaptics.MORSE_UNIT_MS
        // S = ...
        assertArrayEquals(longArrayOf(0, u, u, u, u, u), CallerHaptics.morse("Sam"))
        // Accents are dropped: É = E = .
        assertArrayEquals(longArrayOf(0, u), CallerHaptics.morse("Émile"))
        assertEquals('E', CallerHaptics.morseLetter("Émile"))
        assertNull(CallerHaptics.morse("李"))
        assertNull(CallerHaptics.morse("  "))
        // Every letter fits in one rhythm.
        ('A'..'Z').forEach { c -> assertTrue("$c", CallerHaptics.length(CallerHaptics.morse(c.toString())!!) <= CallerHaptics.RHYTHM_MS) }
        // A name without a Latin letter still gets a rhythm.
        assertTrue(CallerHaptics.timings(Pattern(Preset.MORSE), "李").size >= 3)
    }

    @Test fun presets_fit_and_repeat_with_a_pause() {
        listOf(Preset.HEARTBEAT, Preset.DOUBLE, Preset.LONG).forEach { p ->
            val t = CallerHaptics.timings(Pattern(p))
            assertTrue(CallerHaptics.length(t) <= CallerHaptics.RHYTHM_MS)
            val r = CallerHaptics.repeating(Pattern(p))
            assertEquals(CallerHaptics.PAUSE_MS, r.last())
            // Waveform timings alternate off/on from index 0: the pause lands on an "off" index, so the size is odd.
            assertEquals(1, r.size % 2)
        }
    }

    @Test fun own_pattern_wins_over_labels_then_first_label_alphabetically() {
        val labels = mapOf("Work" to "double", "Family" to "heartbeat", "Broken" to "nope")
        assertEquals("long", CallerHaptics.resolve("long", setOf("Work"), labels))
        assertEquals("heartbeat", CallerHaptics.resolve(null, setOf("Work", "Family"), labels))
        assertEquals("double", CallerHaptics.resolve("junk", setOf("Work", "Broken"), labels))
        assertNull(CallerHaptics.resolve(null, setOf("Friends"), labels))
        assertFalse(CallerHaptics.resolve(null, emptySet(), labels) != null)
    }
}
