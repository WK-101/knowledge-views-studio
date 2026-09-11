package com.todocompanion.app

import com.todocompanion.app.domain.NoteProperties
import com.todocompanion.app.domain.NoteTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave U — cross-module note templates that pull the rest of the app in live. */
class NoteTemplatesTest {

    @Test fun catalogIsWellFormed() {
        assertTrue(NoteTemplates.ALL.isNotEmpty())
        assertEquals("ids unique", NoteTemplates.ALL.size, NoteTemplates.ALL.map { it.id }.toSet().size)
        assertNotNull(NoteTemplates.byId("daily"))
    }

    @Test fun dailyResolvesDateOnceButKeepsLiveTokens() {
        val (_, body) = NoteTemplates.apply(NoteTemplates.byId("daily")!!)
        assertFalse("one-shot {{date}} resolved at apply time", body.contains("{{date}}"))
        // live transclusion tokens survive for the read view to expand
        assertTrue(body.contains("{{today:agenda}}"))
        assertTrue(body.contains("{{events:today}}"))
        assertTrue(body.contains("{{habits:due}}"))
    }

    @Test fun meetingCarriesFrontmatterProperties() {
        val (_, body) = NoteTemplates.apply(NoteTemplates.byId("meeting")!!)
        assertTrue(NoteProperties.has(body))
        assertEquals("meeting", NoteProperties.parse(body)["kind"])
    }
}
