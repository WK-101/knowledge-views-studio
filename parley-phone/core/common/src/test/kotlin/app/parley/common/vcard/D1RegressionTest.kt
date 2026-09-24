package app.parley.common.vcard

import app.parley.common.people.LifeEvents
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round-2/3 regression list (D1): custom labels, plain-text addresses, one bad card in a file. */
class D1RegressionTest {
    private fun import(text: String) = VCardStream.readAll(text.trimIndent().replace("\n", "\r\n") + "\r\n")

    @Test fun custom_event_labels_survive_export_and_import() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.EVENT, Col.D1 to "1950-02-01", Col.D2 to "3"),
            row(Mime.EVENT, Col.D1 to "2021-11-30", Col.D2 to "0", Col.D3 to LifeEvents.DEATH_LABEL),
            row(Mime.EVENT, Col.D1 to "--06-24", Col.D2 to "0", Col.D3 to "Name day"),
            row(Mime.EVENT, Col.D1 to "2001-01-01", Col.D2 to "2", Col.D3 to null),
        )
        val events = assertLossless(r).rows(Mime.EVENT)
        val custom = events.filter { it[Col.D2] == "0" }.associate { it[Col.D3] to it[Col.D1] }
        assertEquals("2021-11-30", custom[LifeEvents.DEATH_LABEL])
        assertEquals("--06-24", custom["Name day"])
        assertTrue(LifeEvents.isDeath(0, events.first { it[Col.D1] == "2021-11-30" }[Col.D3]))
    }

    @Test fun custom_phone_label_with_special_characters_survives() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.PHONE, Col.D1 to "+44 20 7946 0000", Col.D2 to "0", Col.D3 to "Mum's; work, desk: 2"),
        )
        assertEquals("Mum's; work, desk: 2", assertLossless(r).rows(Mime.PHONE).single()[Col.D3])
    }

    @Test fun plain_text_address_round_trips() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.POSTAL, Col.D1 to "Flat 2, 10 High St\nLondon N1 1AA", Col.D2 to "1"),
        )
        val a = assertLossless(r).rows(Mime.POSTAL).single()
        assertEquals("Flat 2, 10 High St\nLondon N1 1AA", a[Col.D1])
        assertEquals("1", a[Col.D2])
    }

    @Test fun plain_text_address_from_other_apps_is_kept() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Label Only
            ADR;TYPE=home;LABEL="1 Main St\nSpringfield":;;;;;;
            END:VCARD
            """,
        )
        assertTrue(report.failures.isEmpty())
        val rows = list.single().rows(Mime.POSTAL)
        assertEquals(1, rows.size)
        assertTrue(rows.single().values.toString(), rows.single()[Col.D1].orEmpty().contains("Springfield"))
    }

    @Test fun one_bad_vcard_does_not_abort_the_import() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:First
            TEL:111
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN;ENCODING=QUOTED-PRINTABLE;CHARSET=NOT-A-CHARSET:=ZZ=
            BDAY:not a date at all
            PHOTO;ENCODING=b;TYPE=JPEG:!!!not base64!!!
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Last
            TEL:333
            END:VCARD
            """,
        )
        val names = list.map { it.displayName }
        assertTrue(names.toString(), "First" in names && "Last" in names)
        assertTrue(report.failures.isNotEmpty())
    }
}
