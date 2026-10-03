package com.wkhan.hexis

import com.wkhan.hexis.domain.nlp.QuickAddParser
import com.wkhan.hexis.domain.voice.VoiceCommandAnalyzer
import com.wkhan.hexis.domain.voice.VoiceIntent
import com.wkhan.hexis.domain.voice.VoiceProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Phase 2 — the on-device voice router: transcript -> intent + stripped payload text. Date/tag/priority
 * parsing lives in QuickAddParser (run by the review sheet), so here we assert the router's job and that
 * the stripped payload parses correctly through that same parser. Pure JVM, deterministic clock.
 */
class VoiceCommandAnalyzerTest {

    private val now = LocalDateTime.of(2026, 1, 1, 12, 0)

    @Test fun addTask_stripsTriggerAndPayloadParsesDateAndTime() {
        val p = VoiceCommandAnalyzer.analyze("add buy milk tomorrow at 5 pm")
        assertTrue(p is VoiceProposal.AddTask)
        val parsed = QuickAddParser.parse((p as VoiceProposal.AddTask).quickAddText, now)
        assertEquals("buy milk", parsed.title)
        assertEquals(LocalDateTime.of(2026, 1, 2, 17, 0), parsed.dateTime)
        assertTrue(parsed.hasTime)
    }

    @Test fun remindMeTo_isATaskWithTriggerStripped() {
        val p = VoiceCommandAnalyzer.analyze("remind me to call mom tomorrow")
        assertTrue(p is VoiceProposal.AddTask)
        val parsed = QuickAddParser.parse((p as VoiceProposal.AddTask).quickAddText, now)
        assertEquals("call mom", parsed.title)
        assertEquals(2, parsed.dateTime?.dayOfMonth) // tomorrow = Jan 2
        assertFalse(parsed.hasTime)
    }

    @Test fun question_isClassifiedAsQuery() {
        val p = VoiceCommandAnalyzer.analyze("what's due today")
        assertEquals(VoiceIntent.QUERY, p.intent)
    }

    @Test fun timer_extractsActivity() {
        val p = VoiceCommandAnalyzer.analyze("start a timer for deep work")
        assertTrue(p is VoiceProposal.StartTimer)
        assertEquals("deep work", (p as VoiceProposal.StartTimer).activity)
    }

    @Test fun note_stripsTriggerAndKeepsBody() {
        val p = VoiceCommandAnalyzer.analyze("note that the roof leaks")
        assertTrue(p is VoiceProposal.AddNote)
        assertEquals("the roof leaks", (p as VoiceProposal.AddNote).text)
    }

    @Test fun tagsAndPriority_rideThroughFromQuickAddParser() {
        val p = VoiceCommandAnalyzer.analyze("add file taxes #finance p1 friday")
        assertTrue(p is VoiceProposal.AddTask)
        val parsed = QuickAddParser.parse((p as VoiceProposal.AddTask).quickAddText, now)
        assertEquals("file taxes", parsed.title)
        assertTrue(parsed.tags.contains("finance"))
        assertEquals(com.wkhan.hexis.domain.priority.PriorityLevel.HIGH, parsed.priority)
    }

    @Test fun blank_isUnknown() {
        assertEquals(VoiceIntent.UNKNOWN, VoiceCommandAnalyzer.analyze("   ").intent)
    }
}
