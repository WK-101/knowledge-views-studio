package com.todocompanion.app

import com.todocompanion.app.domain.nlp.QuickAddParser
import com.todocompanion.app.domain.nlp.QuickTokens
import com.todocompanion.app.domain.priority.PriorityLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coherence audit — the two capture surfaces (the quick-add sheet and the Momentum omnibox) now
 * funnel through the same token handling, so an identical string produces an identical task from
 * either box. These tests pin that contract at the pure layer (QuickTokens + QuickAddParser); the
 * ViewModel's submitQuickAdd merges the two exactly as asserted here.
 */
class CaptureCoherenceTests {

    // The task funnel calls QuickTokens with handleActivity = false so "@name" survives for the
    // context parser instead of being eaten as a time-tracking activity. This is the fix that stops
    // "call mom @home" losing its context when typed into the omnibox.
    @Test fun taskFunnelLeavesAtForContext() {
        val tok = QuickTokens.parse("call mom @home #t25 !!", handleActivity = false)
        // estimate + priority are captured…
        assertEquals(25, tok.estimateMin)
        assertEquals(2, tok.priorityLevel)
        assertNull(tok.activity)
        // …and "@home" is still in the residual text.
        assertTrue(tok.text.contains("@home"))
        // which QuickAddParser then reads as a context.
        val parsed = QuickAddParser.parse(tok.text)
        assertEquals(listOf("home"), parsed.contexts)
        assertEquals("call mom", parsed.title)
    }

    // The omnibox's activity path still consumes a bare "@name" (default handleActivity = true).
    @Test fun omniboxActivityStillConsumesAt() {
        val tok = QuickTokens.parse("@exercise")
        assertEquals("exercise", tok.activity)
        assertTrue(tok.text.isBlank())
    }

    // Priority mapping is identical between the inline token and the parser: !!! High, !! Medium, ! Low.
    @Test fun priorityTokenAndParserAgree() {
        assertEquals(3, QuickTokens.parse("x !!!").priorityLevel)   // → HIGH
        assertEquals(PriorityLevel.HIGH, QuickAddParser.parse("x !!!").priority)
        assertEquals(2, QuickTokens.parse("x !!").priorityLevel)    // → MEDIUM
        assertEquals(PriorityLevel.MEDIUM, QuickAddParser.parse("x !!").priority)
        assertEquals(1, QuickTokens.parse("x !").priorityLevel)     // → LOW
        assertEquals(PriorityLevel.LOW, QuickAddParser.parse("x !").priority)
    }

    // The "!30m" reminder shortcut must survive the token pass (it is NOT a priority) so the parser
    // can still read it — both boxes keep lead-time reminders.
    @Test fun reminderShortcutSurvivesTokenPass() {
        val tok = QuickTokens.parse("submit !30m tomorrow", handleActivity = false)
        assertNull(tok.priorityLevel)                 // "!30m" is not swallowed as a bang-priority
        assertTrue(tok.text.contains("!30m"))
        assertEquals(30, QuickAddParser.parse(tok.text).reminderOffsetMin)
    }
}
