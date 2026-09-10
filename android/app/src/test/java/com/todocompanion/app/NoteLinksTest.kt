package com.todocompanion.app

import com.todocompanion.app.domain.NoteLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — the pure wiki-link + checkbox parsing that powers Notes' expert connection layer. */
class NoteLinksTest {
    @Test fun extractsDistinctWikiLinkTitles() {
        val body = "See [[Project Alpha]] and [[Reading List]].\nAlso [[Project Alpha]] again, and [[  Trimmed  ]]."
        assertEquals(listOf("Project Alpha", "Reading List", "Trimmed"), NoteLinks.outgoingTitles(body))
    }

    @Test fun ignoresEmptyOrMalformedLinks() {
        assertEquals(emptyList<String>(), NoteLinks.outgoingTitles("no links here; [ ] not a box either; [[]] empty"))
    }

    @Test fun backlinkMatchIsCaseInsensitive() {
        assertTrue(NoteLinks.links("refers to [[daily note]]", "Daily Note"))
        assertFalse(NoteLinks.links("refers to [[something else]]", "Daily Note"))
        assertFalse(NoteLinks.links("plain text", ""))
    }

    @Test fun extractsUncheckedCheckboxesOnly() {
        val body = """
            # Plan
            - [ ] Buy milk
            * [ ] Call the dentist
            + [ ]   Water plants
            - [x] Already done
            - just a bullet
            plain line
        """.trimIndent()
        assertEquals(listOf("Buy milk", "Call the dentist", "Water plants"), NoteLinks.uncheckedCheckboxes(body))
    }
}
