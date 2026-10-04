package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parley's notes in an open export: written as its own properties, read back exactly, invisible to the contact's fields. */
class CardNotesTest {
    private val ana = record(
        "Ana Lima", row(Mime.NAME, Col.D1 to "Ana Lima", Col.D2 to "Ana", Col.D3 to "Lima"), row(Mime.PHONE, Col.D1 to "+351 912 345 678", Col.D2 to "2"),
    )
    private val notes = CardNotes(
        private = true,
        forCalls = "Ask about the move;\nshe starts in May, ok?",
        context = "Neighbour, flat 2",
        keepInTouchDays = 30,
        callNotes = listOf(CardNotes.CallNote("+351912345678", 1_759_572_000_123L, "Dentist: Tue 10:00, bring X-ray")),
        moments = listOf(CardNotes.Moment("meet", 1_759_000_000_000L, "Coffee at the bay"), CardNotes.Moment("video", 1_758_000_000_000L, null)),
        promises = listOf("Send the photos"),
    )
    private val words = CardNotes.Words(
        heading = "Parley", forCalls = "For calls", context = "Who is this", keepInTouch = { "Every $it days" }, callNote = "Call note",
        moment = { it.replaceFirstChar(Char::uppercase) }, promises = "Promises", date = { "d$it" },
    )

    private fun readBack(text: String): ParsedCard {
        val cards = ArrayList<ParsedCard>()
        val report = ImportReportBuilder()
        VCardStream.read(text.reader(), report) { cards += it }
        val r = report.build()
        assertTrue(r.failures.toString(), r.failures.isEmpty())
        assertEquals("unmapped: ${r.unmappedProperties}", emptyMap<String, Int>(), r.unmappedProperties)
        return cards.single()
    }

    private fun write(n: CardNotes?, summary: String? = null): String {
        val sw = java.io.StringWriter()
        VCardStream.CardWriter(sw).use { it.write(ana, notes = n, summary = summary) }
        return sw.toString()
    }

    @Test fun notes_come_back_exactly_and_stay_out_of_the_contact() {
        val text = write(notes, notes.summary(words))
        assertTrue(text, text.contains("X-PARLEY-PRIVATE:1"))
        val card = readBack(text)
        assertEquals(notes, card.notes)
        // The readable summary is for other apps: it never becomes the contact's own note.
        assertTrue(card.record.rows(Mime.NOTE).isEmpty())
        assertEquals(roundTrip(ana).rows().toSet(), card.record.rows().toSet())
    }

    @Test fun a_card_without_notes_has_none() {
        val card = readBack(write(null))
        assertNull(card.notes)
        assertFalse(write(null).contains("X-PARLEY-"))
    }

    @Test fun the_summary_reads_newest_first() {
        val s = notes.summary(words)
        assertEquals(
            listOf(
                "Parley", "For calls: Ask about the move;\nshe starts in May, ok?", "Who is this: Neighbour, flat 2", "Every 30 days",
                "Promises: Send the photos",
                "d1759572000123 · Call note: Dentist: Tue 10:00, bring X-ray", "d1759000000000 · Meet: Coffee at the bay", "d1758000000000 · Video",
            ).joinToString("\n"),
            s,
        )
        assertEquals("", CardNotes().summary(words))
    }

    @Test fun another_apps_notes_and_odd_values_are_handled() {
        val text = """
            BEGIN:VCARD
            VERSION:4.0
            FN:Bo
            NOTE:Bo's own note
            X-PARLEY-KEEP-IN-TOUCH:99999
            X-PARLEY-CALL-NOTE;X-WHEN=yesterday;X-LINE=123:lost
            X-PARLEY-CALL-NOTE;X-WHEN="2025-10-04T10:00:00Z";X-LINE=+44 20:kept
            X-PARLEY-MOMENT;X-WHEN="2025-10-04T10:00:00Z";X-KIND=party:x
            END:VCARD
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
        val card = readBack(text)
        val n = card.notes!!
        assertNull(n.keepInTouchDays)
        assertEquals(listOf(CardNotes.CallNote("+4420", 1_759_572_000_000L, "kept")), n.callNotes)
        assertEquals("other", n.moments.single().kind)
        assertFalse(n.private)
        assertEquals("Bo's own note", card.record.rows(Mime.NOTE).single()[Col.D1])
    }
}
