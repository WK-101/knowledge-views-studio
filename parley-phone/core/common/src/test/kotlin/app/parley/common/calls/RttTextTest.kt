package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RttTextTest {
    private fun RttTranscript.texts() = bubbles.map { (if (it.side == RttSide.THEM) "T:" else "M:") + it.text + (if (it.open) "…" else "") }

    @Test fun characters_stream_into_one_open_message() {
        var t = RttTranscript()
        "Hel".forEach { t = t.received(it.toString()) }
        t = t.received("lo")
        assertEquals(listOf("T:Hello…"), t.texts())
        t = t.received("\n")
        assertEquals(listOf("T:Hello"), t.texts())
    }

    @Test fun backspace_takes_back_the_last_character() {
        val t = RttTranscript().received("Hepp\b\bllo")
        assertEquals(listOf("T:Hello…"), t.texts())
        // DEL counts as a backspace too.
        assertEquals(listOf("T:Hell…"), t.received("\u007F").texts())
    }

    @Test fun backspace_removes_a_whole_emoji() {
        val t = RttTranscript().received("ok 👍\b")
        assertEquals(listOf("T:ok …"), t.texts())
    }

    @Test fun backspace_after_a_line_break_reopens_the_message() {
        val t = RttTranscript().received("Hi\n\bya")
        assertEquals(listOf("T:Hiya…"), t.texts())
        // Deleting everything and then the line break before it.
        val u = RttTranscript().received("One\nTwo\b\b\b\b!")
        assertEquals(listOf("T:One!…"), u.texts())
    }

    @Test fun backspace_with_nothing_written_is_ignored() {
        assertTrue(RttTranscript().received("\b\b").isEmpty)
        assertEquals(listOf("M:x…"), RttTranscript().sent("x").received("\b").texts())
    }

    @Test fun both_sides_type_at_once() {
        var t = RttTranscript().received("Are you")
        t = t.sent("Yes")
        t = t.received(" there?\n")
        t = t.sent(" I am\n")
        assertEquals(listOf("T:Are you there?", "M:Yes I am"), t.texts())
        // The next message starts a new bubble at the end.
        assertEquals(listOf("T:Are you there?", "M:Yes I am", "T:Good…"), t.received("Good").texts())
    }

    @Test fun line_breaks_of_every_kind_end_a_message() {
        val t = RttTranscript().received("a\r\nb c\rd\n\n")
        assertEquals(listOf("T:a", "T:b", "T:c", "T:d"), t.texts())
    }

    @Test fun controls_and_keep_alives_are_dropped() {
        val t = RttTranscript().received("﻿a\u0007b‮c⁦d\tz")
        assertEquals(listOf("T:abcd z…"), t.texts())
    }

    @Test fun close_all_and_text_for_a_note() {
        val t = RttTranscript().received("Hi\n").sent("Hello  ").closeAll()
        assertEquals(listOf("T:Hi", "M:Hello  "), t.texts())
        assertEquals("Them: Hi\nYou: Hello", t.asText("Them", "You"))
    }

    @Test fun a_long_call_keeps_the_latest_text() {
        var t = RttTranscript()
        repeat(30) { t = t.received("x".repeat(1_000) + "\n") }
        assertTrue(t.bubbles.sumOf { it.text.length } <= RttTranscript.MAX_CHARS)
        assertEquals(20, t.bubbles.size)
    }

    @Test fun typing_sends_only_what_changed() {
        assertEquals("o", RttTyping.diff("Hell", "Hello"))
        assertEquals("\b", RttTyping.diff("Hello", "Hell"))
        assertEquals("", RttTyping.diff("same", "same"))
        // An autocorrect that changes the word.
        assertEquals("\b\bhe ", RttTyping.diff("teh", "the "))
        // An emoji is one character to delete.
        assertEquals("\b", RttTyping.diff("ok 👍", "ok "))
        // Replacing one emoji with another never splits a surrogate pair.
        assertEquals("\b😀", RttTyping.diff("👍", "😀"))
        // Pasted line breaks become spaces.
        assertEquals(" b", RttTyping.diff("a", "a\nb"))
    }

    @Test fun typing_applied_to_a_transcript_matches_the_field() {
        val fields = listOf("H", "He", "Hel", "Help", "Hel", "Hello", "Hello wrold", "Hello world")
        var before = ""
        var t = RttTranscript()
        for (f in fields) {
            RttTyping.characters(RttTyping.diff(before, f)).forEach { t = t.sent(it) }
            before = f
        }
        assertEquals(listOf("M:Hello world…"), t.texts())
        assertEquals(listOf("👍", "a"), RttTyping.characters("👍a"))
    }
}
