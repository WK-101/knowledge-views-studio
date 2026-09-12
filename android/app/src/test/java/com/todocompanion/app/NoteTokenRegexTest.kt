package com.todocompanion.app

import com.todocompanion.app.domain.NoteTokens
import com.todocompanion.app.util.NoteTransclusion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Guards the two `{{…}}` token regexes. They previously ended in a literal, unescaped `}}` — which the
 * JVM regex engine accepts but Android's ICU engine rejects as a syntax error, so the class threw
 * ExceptionInInitializerError on-device (crashing the note reading view) while every JVM/Robolectric test
 * passed. These tests at least confirm the patterns compile and match their intended tokens; the fix that
 * matters on-device is the escaped `\}\}`.
 */
class NoteTokenRegexTest {
    @Test fun transclusionMatchesLiveTokens() {
        assertTrue(NoteTransclusion.hasTokens("Morning brief:\n{{today:agenda}}\ndone"))
        val toks = NoteTransclusion.tokens("{{tasks:overdue}} and {{note:Ideas#Later}}")
        assertEquals(2, toks.size)
        assertEquals("tasks", toks[0].scope); assertEquals("overdue", toks[0].arg)
        assertEquals("note", toks[1].scope); assertEquals("Ideas#Later", toks[1].arg)
    }

    @Test fun transclusionExpandsAndLeavesUnknownVerbatim() {
        val out = NoteTransclusion.expand("x {{tasks:today}} y") { scope, arg -> if (scope == "tasks") "[$arg]" else null }
        assertEquals("x [today] y", out)
        // An unmatched provider result keeps the token verbatim (never vanishes).
        val kept = NoteTransclusion.expand("keep {{note:none}}") { _, _ -> null }
        assertEquals("keep {{note:none}}", kept)
    }

    @Test fun dateTimeTokensExpand() {
        val at = LocalDateTime.of(2026, 1, 2, 9, 5)
        val out = NoteTokens.expand("d={{date}} t={{time}}", at)
        assertTrue("expanded: $out", !out.contains("{{date}}") && !out.contains("{{time}}"))
    }
}
