package com.wkhan.hexis

import com.wkhan.hexis.domain.voice.VoiceCommandAnalyzer
import com.wkhan.hexis.domain.voice.VoiceIntent
import com.wkhan.hexis.domain.voice.VoiceProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Phase 2 — the on-device voice analysis brain: transcript -> intent -> structured proposal, built on
 * the existing QuickAddParser. Pure JVM, deterministic clock.
 */
class VoiceCommandAnalyzerTest {

    private val now = LocalDateTime.of(2026, 1, 1, 12, 0)

    @Test fun addTask_parsesTitleDateAndTime() {
        val p = VoiceCommandAnalyzer.analyze("add buy milk tomorrow at 5 pm", now)
        assertTrue(p is VoiceProposal.AddTask)
        p as VoiceProposal.AddTask
        assertEquals("buy milk", p.parsed.title)
        assertEquals(LocalDateTime.of(2026, 1, 2, 17, 0), p.parsed.dateTime)
        assertTrue(p.parsed.hasTime)
        assertFalse(p.needsReview)
    }

    @Test fun remindMeTo_isATaskWithTriggerStripped() {
        val p = VoiceCommandAnalyzer.analyze("remind me to call mom tomorrow", now)
        assertTrue(p is VoiceProposal.AddTask)
        p as VoiceProposal.AddTask
        assertEquals("call mom", p.parsed.title)
        assertEquals(2, p.parsed.dateTime?.dayOfMonth) // tomorrow = Jan 2
        assertFalse(p.parsed.hasTime)
    }

    @Test fun question_isClassifiedAsQuery() {
        val p = VoiceCommandAnalyzer.analyze("what's due today", now)
        assertEquals(VoiceIntent.QUERY, p.intent)
    }

    @Test fun timer_extractsActivity() {
        val p = VoiceCommandAnalyzer.analyze("start a timer for deep work", now)
        assertTrue(p is VoiceProposal.StartTimer)
        assertEquals("deep work", (p as VoiceProposal.StartTimer).activity)
    }

    @Test fun note_stripsTriggerAndKeepsBody() {
        val p = VoiceCommandAnalyzer.analyze("note that the roof leaks", now)
        assertTrue(p is VoiceProposal.AddNote)
        assertEquals("the roof leaks", (p as VoiceProposal.AddNote).text)
    }

    @Test fun tagsAndPriority_rideThroughFromQuickAddParser() {
        val p = VoiceCommandAnalyzer.analyze("add file taxes #finance p1 friday", now)
        assertTrue(p is VoiceProposal.AddTask)
        p as VoiceProposal.AddTask
        assertEquals("file taxes", p.parsed.title)
        assertTrue(p.parsed.tags.contains("finance"))
        assertEquals(com.wkhan.hexis.domain.priority.PriorityLevel.HIGH, p.parsed.priority)
    }

    @Test fun blank_isUnknown() {
        assertEquals(VoiceIntent.UNKNOWN, VoiceCommandAnalyzer.analyze("   ", now).intent)
    }
}
