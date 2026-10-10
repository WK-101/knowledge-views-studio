package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Archived contacts in open exports: a vCard flag read back as archived, Parley's CSV column, Google's label. */
class ArchivedExportTest {
    private val ana = record("Ana Lima", row(Mime.NAME, Col.D1 to "Ana Lima", Col.D2 to "Ana", Col.D3 to "Lima"), key = "parley-archived:3")
    private val ben = record("Ben Ruiz", row(Mime.NAME, Col.D1 to "Ben Ruiz", Col.D2 to "Ben", Col.D3 to "Ruiz"), key = "lk-ben")

    @Test fun a_vcard_says_archived_and_reads_back_archived() {
        val sw = java.io.StringWriter()
        VCardStream.CardWriter(sw).use { it.write(ana, notes = CardNotes(archived = true)) }
        val text = sw.toString()
        assertTrue(text, text.contains("X-PARLEY-ARCHIVED:1"))
        val cards = ArrayList<ParsedCard>()
        val report = ImportReportBuilder()
        VCardStream.read(text.reader(), report) { cards += it }
        assertEquals(emptyMap<String, Int>(), report.build().unmappedProperties)
        val notes = cards.single().notes!!
        assertTrue(notes.archived)
        assertFalse(notes.isEmpty)
        // Only Parley's own encrypted file archives it again: a plain card can't plant a hidden, trusted contact.
        assertFalse(notes.forImport(cards.single().record, fromSealed = false, region = "GB").archived)
        assertTrue(notes.forImport(cards.single().record, fromSealed = true, region = "GB").archived)
    }

    @Test fun parleys_csv_has_an_archived_column_only_when_needed_and_reads_it_back_quietly() {
        val plain = CsvExports.writeAll(CsvFormat.PARLEY, listOf(ana, ben))
        assertFalse(plain.lineSequence().first().contains(ContactCsv.ARCHIVED))
        val sb = StringBuilder()
        CsvExports.write(CsvFormat.PARLEY, listOf(ana, ben), sb, withBom = false, archived = setOf(ana.key))
        val lines = sb.lines().filter { it.isNotBlank() }
        assertTrue(lines[0].endsWith(",Archived"))
        assertTrue(lines[1].endsWith(",1"))
        assertFalse(lines[2].endsWith(",1"))
        val (records, report) = ContactCsv.readAll(sb.toString())
        assertEquals(2, records.size)
        assertTrue(report.unmappedProperties.toString(), report.unmappedProperties.isEmpty())
    }

    @Test fun parleys_csv_archived_flag_reads_back_as_archived() {
        val sb = StringBuilder()
        CsvExports.write(CsvFormat.PARLEY, listOf(ana, ben), sb, withBom = false, archived = setOf(ana.key))
        val cards = ArrayList<ParsedCard>()
        ContactCsv.read(sb.toString().reader(), ImportReportBuilder()) { cards += it }
        assertEquals(2, cards.size)
        // The column reads back, but a plain file doesn't archive anyone on import (ImportGuard).
        assertTrue(cards[0].notes!!.archived)
        assertFalse(cards[0].notes!!.forImport(cards[0].record, fromSealed = false, region = "GB").archived)
        assertEquals(null, cards[1].notes)
    }

    @Test fun googles_csv_labels_an_archived_contact() {
        val sb = StringBuilder()
        CsvExports.write(CsvFormat.GOOGLE, listOf(ana, ben), sb, withBom = false, archived = setOf(ana.key))
        val lines = sb.lines().filter { it.isNotBlank() }
        assertTrue(lines[1], lines[1].contains("Archived"))
        assertFalse(lines[2], lines[2].contains("Archived"))
    }
}
