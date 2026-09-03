package com.todocompanion.app

import com.todocompanion.app.data.entity.ListEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.port.Export
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * R103 — per-field export redaction (privacy). The shareable, human-readable exports (Markdown, CSV,
 * iCalendar) must be able to leave the free-text note out while still emitting titles, dates and tags,
 * so a plan can be shared without leaking private notes. The full JSON backup is intentionally unaffected
 * and is covered elsewhere (BackupRoundTripTest).
 */
class ExportRedactionTest {
    private val zone = ZoneId.of("UTC")
    private val secret = "PRIVATE-NOTE-DO-NOT-LEAK"
    private val lists = listOf(ListEntity(id = "l", name = "Inbox"))
    private val tasks = listOf(
        TaskEntity(
            id = "a", listId = "l", title = "Buy milk", note = secret,
            dueDate = 1_760_000_000_000L, createdAt = 0L, updatedAt = 0L,
        ),
    )

    @Test fun markdown_includesNote_whenNotRedacted_omitsWhenRedacted() {
        val plain = Export.toMarkdown(tasks, lists, emptyList(), emptyList(), includeCompleted = true, redactNotes = false, zone = zone)
        val redacted = Export.toMarkdown(tasks, lists, emptyList(), emptyList(), includeCompleted = true, redactNotes = true, zone = zone)
        assertTrue("note should be present in a normal export", plain.contains(secret))
        assertFalse("note must be stripped when redacted", redacted.contains(secret))
        assertTrue("the task title must still export when redacted", redacted.contains("Buy milk"))
    }

    @Test fun csv_blanksNoteColumn_whenRedacted() {
        val plain = Export.toCsv(tasks, lists, emptyList(), emptyList(), includeCompleted = true, redactNotes = false, zone = zone)
        val redacted = Export.toCsv(tasks, lists, emptyList(), emptyList(), includeCompleted = true, redactNotes = true, zone = zone)
        assertTrue(plain.contains(secret))
        assertFalse(redacted.contains(secret))
        assertTrue("the title column must still export when redacted", redacted.contains("Buy milk"))
    }

    @Test fun ics_dropsDescription_whenRedacted() {
        val plain = Export.toIcs(tasks, includeCompleted = true, redactNotes = false, zone = zone, now = 1L)
        val redacted = Export.toIcs(tasks, includeCompleted = true, redactNotes = true, zone = zone, now = 1L)
        assertTrue(plain.contains(secret))
        assertFalse("VEVENT DESCRIPTION must not carry the note when redacted", redacted.contains(secret))
        assertTrue("the event summary must still export when redacted", redacted.contains("Buy milk"))
    }
}
