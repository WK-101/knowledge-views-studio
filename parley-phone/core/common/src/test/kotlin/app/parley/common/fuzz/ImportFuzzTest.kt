package app.parley.common.fuzz

import app.parley.common.people.PasteParser
import app.parley.common.record.ContactRecord
import app.parley.common.security.LimitExceededException
import app.parley.common.vcard.ContactCsv
import app.parley.common.vcard.CsvColumnMapping
import app.parley.common.vcard.VCardMapper
import app.parley.common.vcard.VCardStream
import ezvcard.io.text.VCardReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzes what people import from files and the clipboard: vCards, CSV files (Parley's own and any other, with the
 * column mapping) and pasted contact details. Each must finish quickly, never throw past its documented failure,
 * keep its output bounded by its input, and round-trip what it read.
 */
class ImportFuzzTest {
    private fun text(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test fun vCardImportNeverCrashesAndRoundTrips() {
        val target = Fuzz.Target(
            "vcard", iterations = 1_500,
            dictionary = listOf(
                "BEGIN:VCARD\r\n", "END:VCARD\r\n", "VERSION:4.0\r\n", "VERSION:2.1\r\n", "\r\n ", ";TYPE=", ";CHARSET=UTF-8",
                ";ENCODING=QUOTED-PRINTABLE:", "=C3=", ";ENCODING=b:", "item1.", "X-ABLabel:", "TEL;VALUE=uri:tel:", "BDAY:--", "ADR:;;;;;;",
                "N:;;;;", "ORG:;;;", "\\n", "\\,", "\\;", "PHOTO;ENCODING=BASE64:", "RELATED;TYPE=", "IMPP:", "X-", "GEO:geo:",
            ),
            allowed = setOf(LimitExceededException::class.java),
        )
        Fuzz.run(target) { input ->
            val t = text(input)
            val (records, report) = VCardStream.readAll(t)
            val begins = Regex("BEGIN:VCARD", RegexOption.IGNORE_CASE).findAll(t).count()
            assertTrue("more contacts than cards", records.size <= begins)
            assertTrue("more failures than cards", report.failures.size <= begins + 1)
            // What was read writes out and reads back as the same contacts, never gaining rows. Junk values may still
            // settle on the second pass (a phone number that is only "tel:" reads back empty and is dropped), and an
            // IM handle with characters a URI can't hold ("[") is percent-encoded once more on each export.
            val written = VCardStream.writeAll(records)
            val (again, _) = VCardStream.readAll(written)
            assertEquals("contacts lost writing them out", records.size, again.size)
            assertTrue("rows grew on a second pass", rowCount(again) <= rowCount(records))
            val third = VCardStream.readAll(VCardStream.writeAll(again)).first
            assertEquals("rows changed on a third pass", rowCount(again), rowCount(third))
        }
    }

    @Test fun vCardMapperAcceptsAnythingTheParserReads() {
        // The mapper's own code, below ez-vcard: any card ez-vcard returns must map without an exception.
        val target = Fuzz.Target(
            "vcard", iterations = 1_000,
            dictionary = listOf(";TYPE=pref", ";PREF=1", ";LABEL=\"x\"", "item2.", "X-ABDATE:", "X-ANDROID-CUSTOM:", "KIND:org", "MEMBER:", "N:a;b;c;d;e;f;g"),
        )
        Fuzz.run(target) { input ->
            val cards = try {
                VCardReader(text(input)).use { it.readAll() }
            } catch (_: Exception) {
                // Malformed for ez-vcard itself: the import reports it as a card it couldn't read.
                emptyList()
            }
            cards.forEach { VCardMapper.fromVCard(it, LinkedHashMap()) }
        }
    }

    @Test fun csvImportNeverCrashesAndRoundTrips() {
        val target = Fuzz.Target(
            "csv", iterations = 1_500,
            dictionary = listOf("\"", "\"\"", ",", ";", "\t", "\r\n", "\n", "=", "+", "@", "First Name", "Phone 1 - Value", "E-mail Address", "Birthday", "﻿"),
            allowed = setOf(LimitExceededException::class.java),
        )
        Fuzz.run(target) { input ->
            val t = text(input)
            // A lone CR ends a line too.
            val lines = t.split("\r\n", "\n", "\r").size
            val (records, _) = ContactCsv.readAll(t)
            assertTrue("more contacts than lines", records.size <= lines)
            val written = ContactCsv.writeAll(records)
            assertEquals("contacts lost writing them out", records.size, ContactCsv.readAll(written).first.size)

            // Any other CSV, mapped by the guessed columns as the import screen does.
            val delimiter = ContactCsv.detectDelimiter(t.substringBefore('\n'))
            val rows = ContactCsv.parse(t.reader(), delimiter).take(SAMPLE_ROWS).toList()
            val hasHeader = rows.firstOrNull()?.let(CsvColumnMapping::hasHeader) == true
            val mapping = CsvColumnMapping.guess(rows.firstOrNull()?.takeIf { hasHeader }, rows.drop(if (hasHeader) 1 else 0))
            val (mapped, _) = CsvColumnMapping.readAll(t, mapping, hasHeader, delimiter)
            assertTrue("more mapped contacts than lines", mapped.size <= lines)
            mapped.forEach { CsvColumnMapping.describe(it) }
        }
    }

    @Test fun pasteParsingNeverCrashesAndStaysBounded() {
        val target = Fuzz.Target(
            "paste", iterations = 1_500,
            dictionary = listOf(
                "\n", "Name: ", "Phone: ", "+44 ", "(555) ", "ext. ", "@", ".com", "https://", "Birthday ", "1 January ", " St", ", ",
                "‪", "‏", " ", "Dr. ", " Jr.", "Rua ", "-", "/",
            ),
        )
        Fuzz.run(target) { input ->
            val t = text(input)
            for (region in listOf("GB", "US", null)) {
                val cards = PasteParser.parse(t, region)
                val fields = cards.sumOf { it.fields.size }
                assertTrue("more fields than characters", fields <= t.length)
                cards.flatMap { it.fields }.forEach { f -> assertTrue("a field longer than the text", f.value.length <= t.length + FIELD_SLACK) }
                PasteParser.worthOffering(t, region)
            }
            // Hints from the system's text classifier may point anywhere, even past the text.
            val hints = listOf(
                PasteParser.Hint(-3, 5, PasteParser.HintType.entries.first()),
                PasteParser.Hint(2, t.length + 10, PasteParser.HintType.entries.last()),
            )
            PasteParser.parse(t, "GB", hints)
        }
    }

    private fun rowCount(records: List<ContactRecord>) = records.sumOf { r -> r.raws.sumOf { it.rows.size } }

    private companion object {
        const val SAMPLE_ROWS = 20

        /** A field may gain a little when it is normalised (a country code, "https://"). */
        const val FIELD_SLACK = 16
    }
}
