package com.todocompanion.app

import com.todocompanion.app.data.entity.NoteEntity
import com.todocompanion.app.util.NoteMarkdownFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave K — `.md`-per-note interop: front-matter round-trip fidelity + tolerant import. */
class NoteMarkdownFileTest {

    private fun note(
        id: String = "n1", title: String = "Title", body: String = "Body",
        tags: List<String> = emptyList(), kind: String = "note",
        pinned: Boolean = false, favorite: Boolean = false, color: Long? = null,
        emoji: String? = null, created: Long = 0L, updated: Long = 0L, day: Long? = null,
    ) = Pair(
        NoteEntity(
            id = id, title = title, body = body, kind = kind, pinned = pinned, favorite = favorite,
            colorArgb = color, coverEmoji = emoji, createdAt = created, updatedAt = updated, dayEpoch = day,
        ),
        tags,
    )

    private fun roundTrip(pair: Pair<NoteEntity, List<String>>): NoteMarkdownFile.Parsed {
        val (n, tags) = pair
        val text = NoteMarkdownFile.serialize(n, tags)
        return NoteMarkdownFile.parse("whatever.md", text)
    }

    @Test fun preservesEverything() {
        val (n, tags) = note(
            id = "abc", title = "Weekly review", body = "## Wins\n- shipped\n\n> quote",
            tags = listOf("work", "review"), kind = "journal", pinned = true, favorite = true,
            color = 4294901760L, emoji = "🗒️", created = 111L, updated = 222L, day = 20000L,
        )
        val p = roundTrip(n to tags)
        assertEquals("abc", p.id)
        assertEquals("Weekly review", p.title)
        assertEquals("## Wins\n- shipped\n\n> quote", p.body)   // body byte-for-byte
        assertEquals(listOf("work", "review"), p.tags)
        assertEquals("journal", p.kind)
        assertTrue(p.pinned); assertTrue(p.favorite)
        assertEquals(4294901760L, p.colorArgb)
        assertEquals("🗒️", p.coverEmoji)
        assertEquals(111L, p.createdAt); assertEquals(222L, p.updatedAt)
        assertEquals(20000L, p.dayEpoch)
    }

    @Test fun titleAndTagsWithPunctuationRoundTrip() {
        // A colon, quotes and a bracket in the title/tags must survive the tiny YAML codec.
        val p = roundTrip(note(title = "Re: \"big\" [plan]", tags = listOf("a: b", "c, d")))
        assertEquals("Re: \"big\" [plan]", p.title)
        assertEquals(listOf("a: b", "c, d"), p.tags)
    }

    @Test fun bodyWithFenceLineIsNotTruncated() {
        // A note whose body itself contains a "---" line (a horizontal rule) must not be cut at it.
        val body = "before\n\n---\n\nafter"
        val p = roundTrip(note(body = body))
        assertEquals(body, p.body)
    }

    @Test fun emptyBodyRoundTrips() {
        val p = roundTrip(note(body = ""))
        assertEquals("", p.body)
    }

    @Test fun bareMarkdownWithoutHeaderImports() {
        val p = NoteMarkdownFile.parse("Grocery list.md", "# Shopping\n- milk\n- eggs")
        assertNull(p.id)                       // no id ⇒ import creates a fresh note
        assertEquals("Shopping", p.title)      // title from the first H1
        assertEquals("# Shopping\n- milk\n- eggs", p.body)
        assertFalse(p.pinned)
    }

    @Test fun bareMarkdownFallsBackToFileNameTitle() {
        val p = NoteMarkdownFile.parse("my_first-note.md", "just some text")
        assertEquals("my first note", p.title)
        assertEquals("just some text", p.body)
    }

    @Test fun fileNamesAreCollisionFreeWithinABatch() {
        val taken = mutableSetOf<String>()
        val (a, _) = note(title = "Plan")
        val (b, _) = note(title = "Plan")
        val (c, _) = note(title = "")
        assertEquals("Plan.md", NoteMarkdownFile.fileName(a, taken))
        assertEquals("Plan (2).md", NoteMarkdownFile.fileName(b, taken))
        assertEquals("note.md", NoteMarkdownFile.fileName(c, taken))
    }

    @Test fun illegalFileNameCharsAreStripped() {
        val taken = mutableSetOf<String>()
        val (n, _) = note(title = "a/b:c*d?e")
        val name = NoteMarkdownFile.fileName(n, taken)
        assertFalse(name.contains('/')); assertFalse(name.contains(':')); assertTrue(name.endsWith(".md"))
    }

    // ── P3c — the extra app-specific fields must round-trip (were silently dropped before). ──
    @Test fun losslessExtrasRoundTrip() {
        val n = NoteEntity(
            id = "x", title = "T", body = "B",
            readonly = true, archived = true, sortOrder = 12.5,
            notebookId = "nb1", folderId = "fd1", linkedTaskId = "tk1", linkedEventId = "ev1",
            reminderAt = 999L, reminderRrule = "FREQ=DAILY", reminderExtra = "1;2;3", reminderKeep = true,
            sealedUntil = 4242L,
        )
        val p = NoteMarkdownFile.parse("x.md", NoteMarkdownFile.serialize(n))
        assertTrue(p.readonly); assertTrue(p.archived)
        assertEquals(12.5, p.sortOrder!!, 0.0001)
        assertEquals("nb1", p.notebookId); assertEquals("fd1", p.folderId)
        assertEquals("tk1", p.linkedTaskId); assertEquals("ev1", p.linkedEventId)
        assertEquals(999L, p.reminderAt); assertEquals("FREQ=DAILY", p.reminderRrule)
        assertEquals("1;2;3", p.reminderExtra); assertTrue(p.reminderKeep)
        assertEquals(4242L, p.sealedUntil)
    }

    @Test fun plainNoteEmitsNoExtraKeys() {
        // A note with all-default extras must not clutter the header with them.
        val text = NoteMarkdownFile.serialize(note().first)
        assertFalse(text.contains("readonly")); assertFalse(text.contains("archived"))
        assertFalse(text.contains("sortOrder")); assertFalse(text.contains("reminder"))
        assertFalse(text.contains("sealedUntil"))
    }

    @Test fun readsBlockStyleYamlTags() {
        // Obsidian writes tags as a block sequence; we must read it, not just the flow form.
        val md = "---\nid: \"z\"\ntitle: \"Note\"\ntags:\n  - work\n  - deep-focus\n---\n\nbody here"
        val p = NoteMarkdownFile.parse("z.md", md)
        assertEquals("z", p.id)
        assertEquals(listOf("work", "deep-focus"), p.tags)
        assertEquals("body here", p.body)
    }
}
