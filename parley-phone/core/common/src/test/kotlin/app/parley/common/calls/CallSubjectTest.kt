package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSubjectTest {
    @Test fun blank_is_nothing() {
        assertNull(CallSubject.clean(null))
        assertNull(CallSubject.clean("   "))
        assertNull(CallSubject.clean("‮‏"))
    }

    @Test fun lines_and_spaces_collapse() {
        assertEquals("About the delivery at 3", CallSubject.clean("  About the\n delivery\tat   3 "))
    }

    @Test fun control_and_bidi_characters_are_dropped() {
        // A right-to-left override would make the rest read backwards.
        assertEquals("invoice gnp.exe", CallSubject.clean("invoice ‮gnp.exe‬"))
        assertEquals("ab", CallSubject.clean("a\u0000\u0007b"))
        assertEquals("ab", CallSubject.clean("a⁦b⁩"))
    }

    @Test fun long_subjects_are_cut_without_splitting_an_emoji() {
        val long = "x".repeat(200)
        val cut = CallSubject.clean(long)!!
        assertEquals(CallSubject.MAX_LENGTH, cut.length)
        assertTrue(cut.endsWith("…"))
        val emoji = "a".repeat(CallSubject.MAX_LENGTH - 2) + "😀" + "b".repeat(10)
        val e = CallSubject.clean(emoji)!!
        assertTrue(e.none { Character.isSurrogate(it) } || e.count { Character.isHighSurrogate(it) } == e.count { Character.isLowSurrogate(it) })
    }

    @Test fun non_latin_text_stays() {
        assertEquals("موعد الطبيب", CallSubject.clean("موعد الطبيب"))
    }
}
