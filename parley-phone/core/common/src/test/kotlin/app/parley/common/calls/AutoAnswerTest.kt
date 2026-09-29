package app.parley.common.calls

import app.parley.common.calls.AutoAnswer.Facts
import app.parley.common.calls.AutoAnswer.Reason
import app.parley.common.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAnswerTest {
    private val all = CallExtrasConfig(autoAnswerHeadset = true, autoAnswerSimple = true, autoAnswerChosen = true)
    private val known = Facts(knownCaller = true, headsetConnected = true, simpleMode = true, chosen = true)

    @Test fun off_by_default() {
        val d = CallExtrasConfig()
        assertFalse(AutoAnswer.enabled(d))
        assertEquals(AutoAnswer.DEFAULT_SECONDS, d.autoAnswerSeconds)
        assertNull(AutoAnswer.reason(d, known))
    }

    @Test fun each_situation_on_its_own() {
        assertEquals(Reason.HEADSET, AutoAnswer.reason(CallExtrasConfig(autoAnswerHeadset = true), known))
        assertNull(AutoAnswer.reason(CallExtrasConfig(autoAnswerHeadset = true), known.copy(headsetConnected = false)))
        assertEquals(Reason.SIMPLE_MODE, AutoAnswer.reason(CallExtrasConfig(autoAnswerSimple = true), known))
        assertNull(AutoAnswer.reason(CallExtrasConfig(autoAnswerSimple = true), known.copy(simpleMode = false)))
        assertEquals(Reason.CHOSEN, AutoAnswer.reason(CallExtrasConfig(autoAnswerChosen = true), known))
        assertNull(AutoAnswer.reason(CallExtrasConfig(autoAnswerChosen = true), known.copy(chosen = false)))
        // A chosen person is named as the reason first.
        assertEquals(Reason.CHOSEN, AutoAnswer.reason(all, known))
    }

    @Test fun never_for_unknown_hidden_blocked_spam_emergency_or_during_another_call() {
        assertNull(AutoAnswer.reason(all, known.copy(knownCaller = false)))
        assertNull(AutoAnswer.reason(all, known.copy(hidden = true)))
        assertNull(AutoAnswer.reason(all, known.copy(blockedOrSpam = true)))
        assertNull(AutoAnswer.reason(all, known.copy(otherCall = true)))
        assertNull(AutoAnswer.reason(all, known.copy(emergency = true)))
    }

    @Test fun seconds_are_normalised_and_counted_down() {
        assertEquals(AutoAnswer.DEFAULT_SECONDS, CallExtrasConfig.decode("""{"autoAnswerSeconds":4}""").autoAnswerSeconds)
        assertEquals(10, CallExtrasConfig.decode("""{"autoAnswerSeconds":10,"autoAnswerChosen":true}""").autoAnswerSeconds)
        val c = CallExtrasConfig(autoAnswerHeadset = true, autoAnswerSeconds = 15)
        assertEquals(c, CallExtrasConfig.decode(CallExtrasConfig.encode(c)))
        assertEquals(5, AutoAnswer.secondsLeft(10_000, 5_000))
        assertEquals(1, AutoAnswer.secondsLeft(10_000, 9_990))
        assertEquals(0, AutoAnswer.secondsLeft(10_000, 12_000))
    }

    @Test fun settings_are_searchable() {
        fun keys(q: String) = SettingsSearch.search(q).map { it.key }
        assertTrue("auto_answer" in keys("auto answer"))
        assertTrue("auto_answer" in keys("bluetooth"))
        assertTrue("caller_vibration" in keys("vibration pattern"))
        assertTrue("recents_remember_filter" in keys("unknown callers"))
    }
}
