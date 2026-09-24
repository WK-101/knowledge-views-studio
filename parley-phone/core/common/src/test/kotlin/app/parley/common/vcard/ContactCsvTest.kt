package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactCsvTest {

    private val ann = record(
        "Dr. Ann Lee",
        row(Mime.NAME, Col.D1 to "Dr. Ann Lee", Col.D2 to "Ann", Col.D3 to "Lee", Col.D4 to "Dr.", Col.D5 to "May", Col.D6 to "III"),
        row(Mime.NICKNAME, Col.D1 to "Annie"),
        row(Mime.ORG, Col.D1 to "ACME, Inc.", Col.D4 to "CTO"),
        row(Mime.PHONE, Col.D1 to "+1 555 0100", Col.D2 to "2"),
        row(Mime.PHONE, Col.D1 to "555 0101", Col.D2 to "0", Col.D3 to "Boat"),
        row(Mime.EMAIL, Col.D1 to "ann@example.com", Col.D2 to "2"),
        row(Mime.POSTAL, Col.D2 to "1", Col.D4 to "1 Main St, Apt 2", Col.D7 to "Springfield", Col.D9 to "62701", Col.D10 to "USA"),
        row(Mime.EVENT, Col.D1 to "--05-17", Col.D2 to "3"),
        row(Mime.NOTE, Col.D1 to "Line one\nLine \"two\""),
        row(Mime.GROUP, Col.GROUP_TITLE to "Friends"),
        row(Mime.GROUP, Col.D1 to "7"),
    )

    @Test fun header_has_the_fixed_columns() {
        val h = ContactCsv.header(ContactCsv.Slots(2, 1, 1))
        assertEquals(listOf("Prefix", "Given", "Middle", "Family", "Suffix", "Nickname", "Organization", "Title"), h.take(8))
        assertEquals(listOf("Phone 1 Type", "Phone 1 Value", "Phone 2 Type", "Phone 2 Value", "Email 1 Type", "Email 1 Value"), h.subList(8, 14))
        assertEquals(listOf("Birthday", "Notes", "Groups"), h.takeLast(3))
    }

    @Test fun export_then_import_keeps_every_csv_field() {
        val csv = ContactCsv.writeAll(listOf(ann), mapOf(7L to "Book club"))
        assertTrue(csv.startsWith("\uFEFFPrefix,Given,"))
        val (list, report) = ContactCsv.readAll(csv)
        assertTrue(report.failures.isEmpty())
        val r = list.single()
        val name = r.rows(Mime.NAME).single()
        assertEquals(listOf("Dr.", "Ann", "May", "Lee", "III"), listOf(Col.D4, Col.D2, Col.D5, Col.D3, Col.D6).map { name[it] })
        assertEquals("Dr. Ann May Lee III", r.displayName)
        assertEquals("Annie", r.rows(Mime.NICKNAME).single()[Col.D1])
        assertEquals("ACME, Inc." to "CTO", r.rows(Mime.ORG).single().let { it[Col.D1] to it[Col.D4] })
        assertEquals(listOf(Triple("+1 555 0100", "2", null), Triple("555 0101", "0", "Boat")), r.rows(Mime.PHONE).map { Triple(it[Col.D1], it[Col.D2], it[Col.D3]) })
        assertEquals("2", r.rows(Mime.EMAIL).single()[Col.D2])
        val a = r.rows(Mime.POSTAL).single()
        assertEquals(listOf("1 Main St, Apt 2", "Springfield", "62701", "USA", "1"), listOf(Col.D4, Col.D7, Col.D9, Col.D10, Col.D2).map { a[it] })
        assertEquals("--05-17", r.rows(Mime.EVENT).single()[Col.D1])
        assertEquals("Line one\nLine \"two\"", r.rows(Mime.NOTE).single()[Col.D1])
        assertEquals(listOf("Friends", "Book club"), r.rows(Mime.GROUP).map { it[Col.GROUP_TITLE] })
    }

    @Test fun formulas_are_neutralised_and_restored() {
        val evil = record(
            "=HYPERLINK(\"http://x\")",
            row(Mime.NAME, Col.D2 to "=cmd|' /C calc'!A0", Col.D3 to "@SUM(A1)"),
            row(Mime.ORG, Col.D1 to "-2+3", Col.D4 to "+1+cmd|x"),
            row(Mime.PHONE, Col.D1 to "+44 20 7946 0000", Col.D2 to "2"),
            row(Mime.NOTE, Col.D1 to "'quoted"),
        )
        val csv = ContactCsv.writeAll(listOf(evil))
        val line = csv.lines()[1]
        for (cell in listOf("'=cmd", "'@SUM", "'-2+3", "'+1+cmd", "''quoted")) assertTrue("$cell in $line", line.contains(cell))
        assertTrue("plain numbers stay readable: $line", line.contains(",+44 20 7946 0000,"))
        assertFalse(line.contains(",=") || line.contains(",@") || line.contains(",-2"))

        val r = ContactCsv.readAll(csv).first.single()
        val name = r.rows(Mime.NAME).single()
        assertEquals("=cmd|' /C calc'!A0" to "@SUM(A1)", name[Col.D2] to name[Col.D3])
        assertEquals("-2+3" to "+1+cmd|x", r.rows(Mime.ORG).single().let { it[Col.D1] to it[Col.D4] })
        assertEquals("'quoted", r.rows(Mime.NOTE).single()[Col.D1])
    }

    @Test fun columns_are_found_by_name_in_any_order() {
        val csv = "Family,Phone 1 Value,Given,Shoe size,Phone 1 Type,Email 1 Value\r\n" +
            "Lee,555 1234,Ann,38,Work,ann@x.example\r\n" +
            "\"O'Brien, Jr\",\"555\n9999\",,,Mobile,\r\n" +
            ",,,,,\r\n"
        val (list, report) = ContactCsv.readAll(csv)
        assertEquals(2, list.size)
        assertEquals("Ann Lee", list[0].displayName)
        assertEquals("3", list[0].rows(Mime.PHONE).single()[Col.D2])
        assertEquals("O'Brien, Jr", list[1].rows(Mime.NAME).single()[Col.D3])
        assertEquals("555\n9999", list[1].rows(Mime.PHONE).single()[Col.D1])
        assertEquals("2", list[1].rows(Mime.PHONE).single()[Col.D2])
        assertEquals(mapOf("CSV column “Shoe size”" to 1), report.unmappedProperties)
    }

    @Test fun parser_handles_rfc4180_quoting() {
        val rows = ContactCsv.parse("a,\"b,c\",\"d\"\"e\"\r\n\"multi\nline\",,x\n".reader()).toList()
        assertEquals(listOf(listOf("a", "b,c", "d\"e"), listOf("multi\nline", "", "x")), rows)
    }

    @Test fun free_form_address_goes_to_street() {
        val r = record("A", row(Mime.NAME, Col.D1 to "A"), row(Mime.POSTAL, Col.D1 to "Somewhere 5, Town", Col.D2 to "2"))
        val back = ContactCsv.readAll(ContactCsv.writeAll(listOf(r))).first.single()
        assertEquals("Somewhere 5, Town", back.rows(Mime.POSTAL).single()[Col.D4])
    }
}
