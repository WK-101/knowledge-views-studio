package com.todocompanion.app

import com.todocompanion.app.util.NoteTransclusion
import com.todocompanion.app.util.NoteTransclusion.Token
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave P (moat · N1) — the dynamic note: token grammar + expansion. */
class NoteTransclusionTest {

    @Test fun expandsKnownScopesAndLeavesTheRest() {
        val body = "Agenda:\n{{today:agenda}}\n\nMisc {{unknown:x}} and {{date}}"
        val out = NoteTransclusion.expand(body) { scope, _ -> if (scope == "today") "- [ ] Ship it" else null }
        assertTrue(out.contains("- [ ] Ship it"))
        assertFalse(out.contains("{{today:agenda}}"))
        assertTrue(out.contains("{{unknown:x}}"))   // unknown scope: not matched, left verbatim
        assertTrue(out.contains("{{date}}"))         // date/time belong to NoteTokens, not here
    }

    @Test fun tokensAndDetection() {
        assertTrue(NoteTransclusion.hasTokens("x {{tasks:overdue}} y"))
        assertFalse(NoteTransclusion.hasTokens("x {{date}} y"))
        assertEquals(
            listOf(Token("tasks", "overdue"), Token("note", "My Plan")),
            NoteTransclusion.tokens("{{tasks:overdue}} then {{note:My Plan}}"),
        )
    }

    @Test fun nullProviderLeavesTokenVerbatim() {
        assertEquals("{{note:X}}", NoteTransclusion.expand("{{note:X}}") { _, _ -> null })
    }
}
