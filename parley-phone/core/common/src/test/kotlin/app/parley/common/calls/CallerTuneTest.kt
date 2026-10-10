package app.parley.common.calls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CallerTuneTest {
    private val names = listOf("Ana", "Ana Lima", "Bob", "Zoë", "José", "李小龙", "محمد", "Dr. Who", "", "0800 123", "A", "Mum", "Dad")

    @Test fun same_name_and_variant_give_the_same_tune_and_samples() {
        names.forEach { n ->
            assertEquals(CallerTune.compose(n, 2), CallerTune.compose(n, 2))
            assertArrayEquals(CallerTune.render(CallerTune.compose(n)), CallerTune.render(CallerTune.compose(n)))
        }
        // Case, accents and spacing don't make another person.
        assertEquals(CallerTune.compose("ana"), CallerTune.compose("  Ana "))
        assertEquals(CallerTune.compose("Jose"), CallerTune.compose("José"))
        assertEquals(CallerTune.compose("Ana").lengthMs * CallerTune.SAMPLE_RATE / 1000, CallerTune.render(CallerTune.compose("Ana")).size.toLong())
    }

    @Test fun every_tune_lasts_three_to_five_seconds() {
        (names + (1..300).map { "Person $it" }).forEach { n ->
            for (v in 0..3) {
                val t = CallerTune.compose(n, v)
                assertTrue("$n/$v ${t.lengthMs}", t.lengthMs in 3000..5000)
                // The length in samples, at a low rate: the full rate makes the same length and took most of this suite.
                assertEquals(t.lengthMs * LOW_RATE / 1000, CallerTune.render(t, LOW_RATE).size.toLong())
                // Every note ends before the quiet last beat.
                assertTrue(t.notes.all { it.startBeat + it.beats <= CallerTune.TOTAL_BEATS - 1 })
            }
        }
    }

    @Test fun samples_never_clip_are_loud_enough_and_start_and_end_silent() {
        // At the full rate, so a few names stand for all (each render is four seconds of audio).
        names.forEach { n ->
            val s = CallerTune.render(CallerTune.compose(n, n.length % 3))
            val peak = s.maxOf { abs(it.toInt()) }
            val limit = (CallerTune.PEAK * Short.MAX_VALUE).toInt()
            assertTrue("$n peak $peak", peak in (limit - 2)..(limit + 1))
            assertTrue(peak < Short.MAX_VALUE)
            assertEquals(0, s.first().toInt())
            assertTrue(abs(s.last().toInt()) < 50)
        }
    }

    @Test fun notes_stay_in_the_pentatonic_scale_and_end_on_the_home_note() {
        val pentatonic = setOf(0, 2, 4, 7, 9)
        (names + (1..200).map { "N$it x" }).forEach { n ->
            val t = CallerTune.compose(n, 1)
            assertTrue(t.notes.all { Math.floorMod(it.midi - t.rootMidi, 12) in pentatonic })
            assertEquals(0, Math.floorMod(t.notes.last().midi - t.rootMidi, 12))
            assertTrue(t.bpm in CallerTune.MIN_BPM..CallerTune.MAX_BPM)
        }
    }

    @Test fun other_variants_and_other_names_sound_different() {
        names.forEach { n ->
            val tunes = (0..4).map { CallerTune.compose(n, it) }.toSet()
            assertTrue("$n: ${tunes.size}", tunes.size >= 4)
        }
        val people = (1..200).map { CallerTune.compose("Friend number $it") }.toSet()
        assertTrue(people.size >= 190)
        assertNotEquals(CallerTune.compose("Ana"), CallerTune.compose("Bob"))
    }

    @Test fun wav_is_a_plain_small_pcm_file() {
        val s = CallerTune.render(CallerTune.compose("Ana"))
        val w = CallerTune.wav(s)
        fun ascii(at: Int) = String(w.copyOfRange(at, at + 4), Charsets.US_ASCII)
        fun le32(at: Int) = (0..3).sumOf { (w[at + it].toInt() and 0xff) shl (8 * it) }
        fun le16(at: Int) = (w[at].toInt() and 0xff) or ((w[at + 1].toInt() and 0xff) shl 8)
        assertEquals("RIFF", ascii(0)); assertEquals("WAVE", ascii(8)); assertEquals("fmt ", ascii(12)); assertEquals("data", ascii(36))
        assertEquals(w.size - 8, le32(4))
        assertEquals(1, le16(20)); assertEquals(1, le16(22)); assertEquals(CallerTune.SAMPLE_RATE, le32(24)); assertEquals(16, le16(34))
        assertEquals(s.size * 2, le32(40))
        assertEquals(s[1000], le16(44 + 2000).toShort())
        assertTrue("${w.size}", w.size < 250_000)
    }

    @Test fun file_names_never_carry_the_name() {
        val f = CallerTune.fileName("Ana Lima", 0)
        assertTrue(f, f.matches(Regex("parley-tune-[0-9a-f]{16}\\.wav")))
        assertTrue(!f.contains("ana", ignoreCase = true))
        assertEquals(f, CallerTune.fileName("ana lima", 0))
        assertNotEquals(f, CallerTune.fileName("Ana Lima", 1))
    }

    @Test fun names_with_the_same_short_hash_get_their_own_files() {
        // "aan" and "ac0" share String.hashCode, so the old 32-bit names collided and one got the other's tune.
        assertEquals("aan".hashCode(), "ac0".hashCode())
        assertNotEquals(CallerTune.fileName("Aan", 0), CallerTune.fileName("Ac0", 0))
        // Nor do a name and its variant meet another's (the old name was hash × 31 + variant).
        val names = (0 until 2_000).map { "Person $it" }
        val files = names.flatMap { n -> (0..3).map { v -> CallerTune.fileName(n, v) } }
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun unused_tune_files_are_found() {
        val ana = CallerTune.fileName("Ana", 0)
        val bo = CallerTune.fileName("Bo", 1)
        val old = "parley-tune-0a1b2c3d.wav"
        val files = listOf(ana, bo, old, "notes.txt", "$ana.tmp")
        val inUse = listOf("content://app.parley.files/tunes/$ana", "content://media/internal/audio/media/12")
        assertEquals(listOf(bo, old), CallerTune.unused(files, inUse))
        assertTrue(CallerTune.isTuneFile(old))
        assertFalse(CallerTune.isTuneFile("$ana.tmp"))
    }

    @Test fun envelope_rises_and_falls_to_silence() {
        CallerTune.Instrument.entries.forEach { i ->
            assertEquals(0.0, CallerTune.envelope(i, 0.0, 0.5), 1e-9)
            assertTrue(CallerTune.envelope(i, i.attackS, 0.5) > 0.6)
            assertEquals(0.0, CallerTune.envelope(i, 0.5 + i.releaseS + 0.001, 0.5), 1e-9)
        }
    }

    private companion object {
        const val LOW_RATE = 1_000
    }
}
