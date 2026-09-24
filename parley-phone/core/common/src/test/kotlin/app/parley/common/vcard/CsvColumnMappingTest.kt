package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M12: contact CSVs in other layouts, with guessed and user-chosen column mappings. */
class CsvColumnMappingTest {
    private fun rows(r: ContactRecord, mime: String) = r.raws.flatMap { it.rows }.filter { it.mimeType == mime }
    private fun t(f: CsvField, type: Int? = null) = ColumnTarget(f, type)

    private val google = listOf(
        "Name", "Given Name", "Additional Name", "Family Name", "Nickname", "Birthday", "Notes", "Group Membership",
        "E-mail 1 - Type", "E-mail 1 - Value", "Phone 1 - Type", "Phone 1 - Value", "Phone 2 - Type", "Phone 2 - Value",
        "Organization 1 - Name", "Organization 1 - Title", "Address 1 - Type", "Address 1 - Formatted", "Address 1 - Street",
    )

    @Test fun guesses_google_headers() {
        val m = CsvColumnMapping.guess(google, emptyList())
        assertEquals(
            listOf(
                t(CsvField.FULL_NAME), t(CsvField.GIVEN), t(CsvField.MIDDLE), t(CsvField.FAMILY), t(CsvField.NICKNAME), t(CsvField.BIRTHDAY),
                t(CsvField.NOTES), t(CsvField.LABELS), t(CsvField.EMAIL_LABEL), t(CsvField.EMAIL), t(CsvField.PHONE_LABEL), t(CsvField.PHONE),
                t(CsvField.PHONE_LABEL), t(CsvField.PHONE), t(CsvField.ORG), t(CsvField.TITLE), t(CsvField.IGNORE), t(CsvField.ADDRESS), t(CsvField.IGNORE),
            ),
            m,
        )
        assertEquals(CsvColumnMapping.Layout.GOOGLE, CsvColumnMapping.layout(google))
    }

    @Test fun guesses_new_google_headers_without_mistaking_phonetic_names_for_phones() {
        val h = listOf("First Name", "Middle Name", "Last Name", "Phonetic First Name", "Name Prefix", "Organization Name", "Organization Title", "Labels", "Phone 1 - Label", "Phone 1 - Value")
        assertEquals(
            listOf(t(CsvField.GIVEN), t(CsvField.MIDDLE), t(CsvField.FAMILY), t(CsvField.IGNORE), t(CsvField.PREFIX), t(CsvField.ORG), t(CsvField.TITLE), t(CsvField.LABELS), t(CsvField.PHONE_LABEL), t(CsvField.PHONE)),
            CsvColumnMapping.guess(h, emptyList()),
        )
    }

    @Test fun guesses_outlook_headers() {
        val h = listOf("Title", "First Name", "Middle Name", "Last Name", "Suffix", "Company", "Job Title", "Business Phone", "Home Phone", "Mobile Phone", "Business Fax", "E-mail Address", "E-mail Display Name", "Categories", "Notes")
        assertEquals(
            listOf(
                t(CsvField.PREFIX), t(CsvField.GIVEN), t(CsvField.MIDDLE), t(CsvField.FAMILY), t(CsvField.SUFFIX), t(CsvField.ORG), t(CsvField.TITLE),
                t(CsvField.PHONE, 3), t(CsvField.PHONE, 1), t(CsvField.PHONE, 2), t(CsvField.PHONE, 4), t(CsvField.EMAIL), t(CsvField.IGNORE), t(CsvField.LABELS), t(CsvField.NOTES),
            ),
            CsvColumnMapping.guess(h, emptyList()),
        )
        assertEquals(CsvColumnMapping.Layout.OUTLOOK, CsvColumnMapping.layout(h))
        // Without "Job Title", "Title" is a job title.
        assertEquals(listOf(t(CsvField.FULL_NAME), t(CsvField.TITLE)), CsvColumnMapping.guess(listOf("Name", "Title"), emptyList()))
    }

    @Test fun unknown_headers_are_guessed_from_their_values() {
        val h = listOf("Wer", "Nummer 1", "Kontakt", "Irgendwas")
        val sample = listOf(listOf("Jonas", "+49 151 1234567", "jonas@example.de", "x"), listOf("Mia", "0151 7654321", "mia@example.de", ""))
        assertEquals(listOf(t(CsvField.FULL_NAME), t(CsvField.PHONE), t(CsvField.EMAIL), t(CsvField.IGNORE)), CsvColumnMapping.guess(h, sample).let {
            // "Nummer 1" isn't a known name; "Kontakt" isn't either: both come from their values. "Wer" is the first text column.
            it
        })
    }

    @Test fun parley_header_is_recognised() {
        val own = ContactCsv.header(ContactCsv.Slots(1, 1, 1))
        assertTrue(CsvColumnMapping.isParleyHeader(own))
        assertEquals(CsvColumnMapping.Layout.PARLEY, CsvColumnMapping.layout(own))
        assertFalse(CsvColumnMapping.isParleyHeader(listOf("Name", "Phone")))
        assertFalse(CsvColumnMapping.isParleyHeader(google))
    }

    @Test fun header_detection() {
        assertTrue(CsvColumnMapping.hasHeader(listOf("Name", "Phone")))
        assertTrue(CsvColumnMapping.hasHeader(listOf("Phone")))
        assertFalse(CsvColumnMapping.hasHeader(listOf("Ana", "+351 912 345 678")))
        assertFalse(CsvColumnMapping.hasHeader(listOf("ana@example.com")))
    }

    @Test fun reads_a_google_export() {
        val csv = google.joinToString(",") + "\r\n" +
            "Ana Silva,Ana,,Silva,Aninha,1990-04-03,\"Met at the fair, 2024\",* myContacts ::: Friends ::: Work,* Home,ana@example.com," +
            "Mobile,+351 912 345 678 ::: +351 913 000 000,Work,+351 21 000 0000,ACME,Engineer,Home,\"Rua 1, Lisboa\",Rua 1\r\n"
        val (list, report) = CsvColumnMapping.readAll(csv, CsvColumnMapping.guess(google, emptyList()), hasHeader = true)
        assertEquals(1, list.size)
        val r = list[0]
        val name = rows(r, Mime.NAME).single()
        assertEquals("Ana", name[Col.D2]); assertEquals("Silva", name[Col.D3])
        val phones = rows(r, Mime.PHONE)
        assertEquals(listOf("+351 912 345 678", "+351 913 000 000", "+351 21 000 0000"), phones.map { it[Col.D1] })
        assertEquals(listOf("2", "2", "3"), phones.map { it[Col.D2] })
        assertEquals("ana@example.com", rows(r, Mime.EMAIL).single()[Col.D1])
        assertEquals("1", rows(r, Mime.EMAIL).single()[Col.D2])
        assertEquals(listOf("Friends", "Work"), rows(r, Mime.GROUP).map { it[Col.GROUP_TITLE] })
        assertEquals("ACME", rows(r, Mime.ORG).single()[Col.D1]); assertEquals("Engineer", rows(r, Mime.ORG).single()[Col.D4])
        assertEquals("1990-04-03", rows(r, Mime.EVENT).single()[Col.D1])
        assertEquals("Met at the fair, 2024", rows(r, Mime.NOTE).single()[Col.D1])
        assertEquals("Rua 1, Lisboa", rows(r, Mime.POSTAL).single()[Col.D1])
        assertEquals("Aninha", rows(r, Mime.NICKNAME).single()[Col.D1])
        // The ignored "Address 1 - Street" (and its type) are reported, not silently dropped.
        assertTrue(report.unmappedProperties.keys.any { it.contains("Address 1 - Street") })
    }

    @Test fun reads_semicolon_name_phone_without_header() {
        val csv = "Ana;+351 912 345 678\nBruno;0912 000 111\n;\n"
        val first = ContactCsv.parse(csv.reader(), ';').first()
        assertFalse(CsvColumnMapping.hasHeader(first))
        val sample = ContactCsv.parse(csv.reader(), ';').toList()
        val m = CsvColumnMapping.guess(null, sample)
        assertEquals(listOf(t(CsvField.FULL_NAME), t(CsvField.PHONE)), m)
        val (list, report) = CsvColumnMapping.readAll(csv, m, hasHeader = false, delimiter = ';')
        assertEquals(2, list.size)
        assertEquals("Ana", rows(list[0], Mime.NAME).single()[Col.D1])
        assertEquals("0912 000 111", rows(list[1], Mime.PHONE).single()[Col.D1])
        assertEquals("2", rows(list[1], Mime.PHONE).single()[Col.D2])
        assertEquals(0, report.failures.size)
    }

    @Test fun reads_tab_separated_with_user_mapping_and_custom_phone_labels() {
        val csv = "who\tkind\tnum\tmail\nZoe\tSatellite\t+1 555 0100\tz@example.com\n"
        assertEquals('\t', ContactCsv.detectDelimiter(csv.substringBefore('\n')))
        val m = listOf(t(CsvField.FULL_NAME), t(CsvField.PHONE_LABEL), t(CsvField.PHONE), t(CsvField.EMAIL, 2))
        val (list, _) = CsvColumnMapping.readAll(csv, m, hasHeader = true)
        val p = rows(list.single(), Mime.PHONE).single()
        assertEquals("0", p[Col.D2]); assertEquals("Satellite", p[Col.D3])
        assertEquals("2", rows(list.single(), Mime.EMAIL).single()[Col.D2])
    }

    @Test fun single_column_of_numbers() {
        val csv = "Phone\n+44 7700 900123\n07700 900456\n"
        val all = ContactCsv.parse(csv.reader(), ',').toList()
        assertTrue(CsvColumnMapping.hasHeader(all.first()))
        val m = CsvColumnMapping.guess(all.first(), all.drop(1))
        assertEquals(listOf(t(CsvField.PHONE)), m)
        val (list, _) = CsvColumnMapping.readAll(csv, m, hasHeader = true)
        assertEquals(2, list.size)
    }

    @Test fun lines_with_nothing_mapped_are_reported_and_formulas_unescaped() {
        val csv = "Name,Phone,Extra\n'=cmd,+1 555 0100,x\n,,only extra\n"
        val m = listOf(t(CsvField.FULL_NAME), t(CsvField.PHONE), t(CsvField.IGNORE))
        val (list, report) = CsvColumnMapping.readAll(csv, m, hasHeader = true)
        assertEquals(1, list.size)
        assertEquals("=cmd", rows(list[0], Mime.NAME).single()[Col.D1])
        assertEquals(1, report.failures.size)
        assertEquals(2, report.unmappedProperties[ImportReport.csvColumn("Extra")])
    }

    @Test fun phone_and_email_type_labels() {
        assertEquals(2 to null, CsvColumnMapping.phoneType("* Mobile"))
        assertEquals(3 to null, CsvColumnMapping.phoneType("work"))
        assertEquals(2 to null, CsvColumnMapping.phoneType(""))
        assertEquals(0 to "Boat", CsvColumnMapping.phoneType("Boat"))
        assertEquals(2 to null, CsvColumnMapping.emailType("Work"))
        assertEquals(3 to null, CsvColumnMapping.emailType(""))
    }

    @Test fun describe_for_preview() {
        val (list, _) = CsvColumnMapping.readAll("Name,Phone,Labels\nAna,+1 555 0100,Family;Friends\n", listOf(t(CsvField.FULL_NAME), t(CsvField.PHONE), t(CsvField.LABELS)), hasHeader = true)
        assertEquals("Ana · +1 555 0100 · Labels: Family, Friends", CsvColumnMapping.describe(list.single()))
    }
}
