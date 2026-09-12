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

    // ── Custom (user-created) templates: JSON round-trip through settings ──

    @Test fun customCodecRoundTrips() {
        val a = NoteTemplates.newCustom("Standup", "☕", "# Standup\n- Yesterday\n- Today\n- Blockers")
        val b = NoteTemplates.newCustom("Recipe", "🍳", "---\nkind: recipe\n---\n# {{date}}\n")
        val json = NoteTemplates.encodeCustom(listOf(a, b))
        assertTrue("non-empty encoding", json.isNotBlank())
        val back = NoteTemplates.parseCustom(json)
        assertEquals(2, back.size)
        assertEquals(a.id, back[0].id)
        assertEquals("Standup", back[0].name)
        assertEquals("☕", back[0].emoji)
        assertEquals(a.body, back[0].body)
        assertTrue("decoded templates are always flagged custom", back.all { it.custom })
    }

    @Test fun emptyOrGarbageParsesToNoTemplates() {
        assertTrue(NoteTemplates.parseCustom("").isEmpty())
        assertTrue(NoteTemplates.parseCustom("   ").isEmpty())
        assertTrue(NoteTemplates.parseCustom("not json at all {[").isEmpty())
    }

    @Test fun newCustomFallsBackForBlankNameAndEmoji() {
        val t = NoteTemplates.newCustom("   ", "", "body")
        assertEquals("My template", t.name)
        assertEquals("📄", t.emoji)
        assertTrue("ids are unique per creation", t.id.isNotBlank())
        assertTrue(t.custom)
    }
}
