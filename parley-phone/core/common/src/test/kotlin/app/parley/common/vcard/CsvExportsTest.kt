package app.parley.common.vcard

import app.parley.common.people.OtherFields
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Google's and Outlook's CSV layouts (and Parley's, with Department) against golden files in
 * src/test/resources/csv: the exact header and every cell, so a change to either layout is a deliberate one.
 */
class CsvExportsTest {
    private val ana = record(
        "Dr. Ana María García",
        row(
            Mime.NAME, Col.D1 to "Dr. Ana María García", Col.D2 to "Ana", Col.D3 to "García", Col.D4 to "Dr.", Col.D5 to "María",
            Col.D7 to "Ana", Col.D9 to "Garsia",
        ),
        row(Mime.NICKNAME, Col.D1 to "Anita"),
        row(OtherFields.GOOGLE_FILE_AS, Col.D1 to "García, Ana"),
        row(Mime.ORG, Col.D1 to "Acme, Inc.", Col.D4 to "Engineer", Col.D5 to "Research", Col.D9 to "Building 4"),
        row(Mime.PHONE, Col.D1 to "+1 555 0100", Col.D2 to "2", primary = true),
        row(Mime.PHONE, Col.D1 to "+1 555 0101", Col.D2 to "3"),
        row(Mime.PHONE, Col.D1 to "+1 555 0102", Col.D2 to "19"),
        row(Mime.PHONE, Col.D1 to "+1 555 0103", Col.D2 to "0", Col.D3 to "Boat"),
        row(Mime.EMAIL, Col.D1 to "ana@example.com", Col.D2 to "1"),
        row(Mime.EMAIL, Col.D1 to "ana@acme.example", Col.D2 to "2"),
        row(
            Mime.POSTAL, Col.D2 to "1", Col.D1 to "1 Main St\nSpringfield, IL 62701\nUSA", Col.D4 to "1 Main St", Col.D7 to "Springfield",
            Col.D8 to "IL", Col.D9 to "62701", Col.D10 to "USA",
        ),
        row(Mime.POSTAL, Col.D2 to "2", Col.D4 to "9 Work Rd", Col.D7 to "Chicago", Col.D10 to "USA"),
        row(Mime.WEBSITE, Col.D1 to "https://ana.example", Col.D2 to "1"),
        row(Mime.WEBSITE, Col.D1 to "https://acme.example/ana", Col.D2 to "5"),
        row(Mime.EVENT, Col.D1 to "1990-03-12", Col.D2 to "3"),
        row(Mime.EVENT, Col.D1 to "2015-06-20", Col.D2 to "1"),
        row(Mime.EVENT, Col.D1 to "--09-01", Col.D2 to "0", Col.D3 to "Name day"),
        row(Mime.RELATION, Col.D1 to "Luis", Col.D2 to "14"),
        row(Mime.RELATION, Col.D1 to "Marta", Col.D2 to "7"),
        row(Mime.IM, Col.D1 to "ana@xmpp.example", Col.D5 to "7"),
        row(Mime.NOTE, Col.D1 to "Met at the \"Book club\""),
        row(Mime.CUSTOM_FIELD, Col.D1 to "Shoe size", Col.D2 to "38"),
        row(Mime.GROUP, Col.GROUP_TITLE to "Friends"),
    ).copy(starred = true)

    private val bo = record("Bo", row(Mime.NAME, Col.D1 to "Bo", Col.D2 to "Bo"), row(Mime.PHONE, Col.D1 to "07700 900123", Col.D2 to "2"))

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/csv/$name")) { "missing golden csv/$name" }.readBytes().toString(Charsets.UTF_8)

    private fun check(format: CsvFormat, file: String) {
        val out = CsvExports.writeAll(format, listOf(ana, bo))
        assertEquals("$file:\n$out", golden(file), out)
    }

    @Test fun google_csv_matches_its_golden_file() = check(CsvFormat.GOOGLE, "google.csv")

    @Test fun outlook_csv_matches_its_golden_file() = check(CsvFormat.OUTLOOK, "outlook.csv")

    @Test fun parley_csv_matches_its_golden_file() = check(CsvFormat.PARLEY, "parley.csv")

    @Test fun googles_header_starts_with_its_fixed_columns() {
        val header = CsvExports.writeAll(CsvFormat.GOOGLE, listOf(bo)).lines().first()
        assertEquals(
            "First Name,Middle Name,Last Name,Phonetic First Name,Phonetic Middle Name,Phonetic Last Name,Name Prefix,Name Suffix,Nickname," +
                "File As,Organization Name,Organization Title,Organization Department,Birthday,Notes,Photo,Labels,E-mail 1 - Label,E-mail 1 - Value," +
                "Phone 1 - Label,Phone 1 - Value,Address 1 - Label,Address 1 - Formatted,Address 1 - Street,Address 1 - City,Address 1 - PO Box," +
                "Address 1 - Region,Address 1 - Postal Code,Address 1 - Country,Address 1 - Extended Address",
            header,
        )
    }

    @Test fun outlooks_header_is_its_fixed_set() {
        val header = ContactCsv.parse(CsvExports.writeAll(CsvFormat.OUTLOOK, listOf(bo)).reader()).first()
        assertEquals(61, header.size)
        assertEquals(CsvExports.Outlook.HEADER, header)
        assertEquals(listOf("First Name", "Middle Name", "Last Name", "Title"), header.take(4))
        assertEquals(listOf("Birthday", "Anniversary", "Notes"), header.takeLast(3))
    }

    @Test fun both_layouts_import_back_through_the_column_mapping() {
        for (format in listOf(CsvFormat.GOOGLE, CsvFormat.OUTLOOK)) {
            val text = CsvExports.writeAll(format, listOf(ana))
            val rows = ContactCsv.parse(text.reader()).toList()
            val mapping = CsvColumnMapping.guess(rows.first(), rows.drop(1))
            val r = CsvColumnMapping.readAll(text, mapping, hasHeader = true).first.single()
            val org = r.rows(Mime.ORG).single()
            assertEquals("$format", "Research", org[Col.D5])
            assertEquals("$format", "Acme, Inc.", org[Col.D1])
            assertTrue("$format", r.rows(Mime.PHONE).any { it[Col.D1] == "+1 555 0100" })
        }
    }

    @Test fun department_survives_parleys_own_csv() {
        val back = ContactCsv.readAll(ContactCsv.writeAll(listOf(ana))).first.single()
        assertEquals("Research", back.rows(Mime.ORG).single()[Col.D5])
    }

    @Test fun outlook_dates_are_month_day_year_and_yearless_ones_are_left_out() {
        assertEquals("3/12/1990", CsvExports.Outlook.date("1990-03-12"))
        assertEquals(null, CsvExports.Outlook.date("--09-01"))
    }
}
