package app.parley.common.ux

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallGlanceTest {
    private fun call(id: Long, number: String, date: Long, type: CallType, duration: Long = 0, hidden: Boolean = false) =
        CallEntry(id, number, null, type, date, duration, null, false, hidden)

    private val key = { n: String -> n.filter(Char::isDigit).takeLast(9) }

    // ---------------------------------------------------------------- R4 call classes

    @Test fun call_class_mapping() {
        assertEquals(CallClass.MISSED, CallClass.of(CallType.MISSED, 0))
        assertEquals(CallClass.DECLINED, CallClass.of(CallType.REJECTED, 0))
        assertEquals(CallClass.INCOMING, CallClass.of(CallType.INCOMING, 30))
        assertEquals(CallClass.ANSWERED_ELSEWHERE, CallClass.of(CallType.ANSWERED_EXTERNALLY, 30))
        assertEquals(CallClass.VOICEMAIL, CallClass.of(CallType.VOICEMAIL, 12))
        assertEquals(CallClass.OUTGOING, CallClass.of(CallType.OUTGOING, 1))
        assertEquals(CallClass.NO_ANSWER, CallClass.of(CallType.OUTGOING, 0))
        assertEquals(CallClass.BLOCKED, CallClass.of(CallType.BLOCKED, 0))
        assertEquals(CallClass.UNKNOWN, CallClass.of(CallType.UNKNOWN, 0))
        // The class keeps the U3 colour family of its type.
        CallType.entries.forEach { t -> assertEquals(CallHue.of(t), CallClass.of(t, 5).hue) }
    }

    @Test fun every_class_is_told_apart_without_colour() {
        // Fill, form and glyph alone (no hue) are different for every class: colour-blind safe.
        val shapes = CallClass.entries.map { Triple(it.fill, it.form, it.glyph) }
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test fun only_talked_calls_get_a_duration_bar() {
        assertEquals(setOf(CallClass.INCOMING, CallClass.OUTGOING), CallClass.entries.filter { it.answered }.toSet())
    }

    // ---------------------------------------------------------------- R4 sequence dots

    @Test fun sequence_dots_are_oldest_first_and_capped() {
        val calls = listOf(
            call(6, "1", 600, CallType.OUTGOING, 20), call(5, "1", 500, CallType.MISSED), call(4, "1", 400, CallType.MISSED),
            call(3, "1", 300, CallType.REJECTED), call(2, "1", 200, CallType.INCOMING, 5), call(1, "1", 100, CallType.OUTGOING),
        )
        assertEquals(
            listOf(CallClass.DECLINED, CallClass.MISSED, CallClass.MISSED, CallClass.OUTGOING),
            CallGlance.sequence(calls),
        )
        assertTrue(CallGlance.sequence(calls.take(1)).isEmpty())
        assertEquals(listOf(CallClass.MISSED, CallClass.OUTGOING), CallGlance.sequence(calls.take(2)))
    }

    // ---------------------------------------------------------------- R4 duration bar

    @Test fun duration_bar_grows_with_the_call() {
        assertEquals(0f, CallGlance.durationFraction(0), 0f)
        val short = CallGlance.durationFraction(20)
        val medium = CallGlance.durationFraction(120)
        val long = CallGlance.durationFraction(1800)
        assertTrue(short in 0.08f..medium && medium < long && long < 1f)
        assertEquals(1f, CallGlance.durationFraction(3 * 3600), 0f)
        assertTrue(CallGlance.durationFraction(1) in 0.08f..0.1f)
    }

    // ---------------------------------------------------------------- R4 unreturned missed calls

    @Test fun missed_call_is_unreturned_until_called_back() {
        val calls = listOf(
            call(5, "+33 6 12 34 56 78", 500, CallType.MISSED),
            call(4, "0612345678", 400, CallType.MISSED),
            call(2, "222", 350, CallType.OUTGOING, 0), // tried calling back (no answer): returned
            call(3, "222", 300, CallType.MISSED),
            call(1, "333", 100, CallType.MISSED),
        )
        // Only the latest missed call per number; 222 was called back; 333 still waits.
        assertEquals(setOf(5L, 1L), CallGlance.unreturnedMissed(calls, key, now = 1000))
        assertEquals(2, CallGlance.unreturnedCount(calls, key, now = 1000))
    }

    @Test fun answered_call_from_them_returns_it_but_declined_or_hidden_do_not() {
        val calls = listOf(
            call(4, "444", 400, CallType.INCOMING, 60),
            call(3, "444", 300, CallType.MISSED),
            call(2, "555", 250, CallType.REJECTED),
            call(1, "555", 200, CallType.MISSED),
            call(0, "", 100, CallType.MISSED, hidden = true),
        )
        assertEquals(setOf(1L), CallGlance.unreturnedMissed(calls, key, now = 1000))
    }

    @Test fun old_blocked_spam_and_withheld_missed_calls_do_not_count() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val calls = listOf(
            call(6, "666", now - day, CallType.MISSED),
            call(5, "Private", now - day, CallType.MISSED),
            call(4, "777", now - 2 * day, CallType.MISSED), // blocked since
            call(3, "888", now - 6 * day, CallType.MISSED),
            call(2, "999", now - 8 * day, CallType.MISSED), // over a week ago
            call(1, "888", now - 9 * day, CallType.OUTGOING, 30),
        )
        val blocked = { n: String -> n == "777" }
        assertEquals(setOf(6L, 3L), CallGlance.unreturnedMissed(calls, key, now, excluded = blocked))
        assertEquals(7 * day, CallGlance.UNRETURNED_MAX_AGE_MS)
        // An older missed call from a blocked number doesn't come back either.
        val again = listOf(call(2, "777", now - day, CallType.MISSED), call(1, "777", now - 3 * day, CallType.MISSED))
        assertTrue(CallGlance.unreturnedMissed(again, key, now, excluded = blocked).isEmpty())
    }

    @Test fun recents_style_is_a_searchable_setting() {
        SettingsCatalog["recents_style"]
    }
}
