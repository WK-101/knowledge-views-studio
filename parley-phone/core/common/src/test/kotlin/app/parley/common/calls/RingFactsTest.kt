package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingFactsTest {
    private val base = RingFacts(startedAt = 1_000, ringer = RingerMode.NORMAL, dnd = DndState.OFF, ringVolume = 5, ringVolumeMax = 7)

    @Test fun codec_round_trips_and_ignores_garbage() {
        val list = listOf(base, base.copy(startedAt = 2_000, ringtone = RingtoneSource.RULE, ringtoneDetail = "Plumber", outcome = RingOutcome.ANSWERED, answeredRoute = AnswerRoute.BLUETOOTH, answeredDevice = "Car"))
        assertEquals(list, RingFactsCodec.decode(RingFactsCodec.encode(list)))
        assertTrue(RingFactsCodec.decode("not json").isEmpty())
        assertTrue(RingFactsCodec.decode(null).isEmpty())
        // Rows written by a newer version with extra fields still read.
        assertEquals(1, RingFactsCodec.decode("""[{"startedAt":5,"future":"x"}]""").size)
    }

    @Test fun why_no_ring_prefers_parleys_own_reason() {
        assertEquals("Silenced: off hours", RingExplainer.whyNoRing(base.copy(silencedBy = "Off hours")))
        assertEquals("Silenced: off hours", RingExplainer.whyNoRing(null, "Silenced: off hours"))
        assertEquals("Silenced by rule 'Ads'", RingExplainer.whyNoRing(base.copy(silencedBy = "Silenced by rule 'Ads'")))
    }

    @Test fun why_no_ring_from_the_ringer_state() {
        assertEquals("Didn't ring: Do Not Disturb", RingExplainer.whyNoRing(base.copy(dnd = DndState.PRIORITY, dndAllowsCalls = false)))
        assertNull(RingExplainer.whyNoRing(base.copy(dnd = DndState.PRIORITY, dndAllowsCalls = true, ringMillis = 20_000)))
        assertEquals("Didn't ring: phone on silent", RingExplainer.whyNoRing(base.copy(ringer = RingerMode.SILENT)))
        assertEquals("Vibrate only: phone on vibrate", RingExplainer.whyNoRing(base.copy(ringer = RingerMode.VIBRATE)))
        assertEquals("Didn't ring: ring volume at 0", RingExplainer.whyNoRing(base.copy(ringVolume = 0)))
        assertEquals("Rang for 2 s only", RingExplainer.whyNoRing(base.copy(ringMillis = 1_500)))
        assertNull(RingExplainer.whyNoRing(base.copy(ringMillis = 25_000)))
        assertNull(RingExplainer.whyNoRing(null))
    }

    @Test fun audible_only_when_nothing_kept_it_quiet() {
        assertTrue(base.audible)
        assertFalse(base.copy(ringer = RingerMode.VIBRATE).audible)
        assertFalse(base.copy(dnd = DndState.ALARMS).audible)
        assertFalse(base.copy(silencedBy = "Ignored").audible)
        assertFalse(base.copy(ringVolume = 0).audible)
    }

    @Test fun lines_describe_every_fact() {
        val lines = RingExplainer.lines(
            base.copy(vibrate = true, ringtone = RingtoneSource.LABEL, ringtoneDetail = "Family", ringMillis = 8_000, outcome = RingOutcome.ANSWERED, answeredRoute = AnswerRoute.BLUETOOTH, answeredDevice = "Car kit", ringLoud = true),
        )
        assertEquals(
            listOf(
                "Do Not Disturb: off",
                "Ringer: sound · volume 5/7 · raised to full volume",
                "Vibrate for calls: on",
                "Ringtone: label 'Family'",
                "Rang for 8 s",
                "Answered on Car kit",
            ),
            lines,
        )
        assertEquals("Answered on another device", RingExplainer.outcomeText(base.copy(outcome = RingOutcome.ANSWERED_ELSEWHERE)))
    }

    @Test fun matches_the_closest_ring_within_the_window() {
        val all = listOf(base.copy(startedAt = 10_000), base.copy(startedAt = 70_000), base.copy(startedAt = 900_000))
        assertEquals(70_000L, RingExplainer.matchFor(all, 65_000)?.startedAt)
        assertNull(RingExplainer.matchFor(all, 500_000))
    }
}
