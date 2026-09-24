package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ContactRecord -> vCard -> ContactRecord must give back [VCardMapper.canonical] exactly. */
class VCardRoundTripTest {

    @Test fun rich_record_round_trips_losslessly() {
        val back = assertLossless(Rich.record(), Rich.groupTitles)
        // Spot checks on top of the full equality, so a canonical() bug cannot hide a loss.
        assertEquals("Dr. Jane Quincy Doe-Smith Jr.", back.displayName)
        assertTrue(back.starred)
        assertTrue(back.sendToVoicemail)
        assertEquals("content://media/internal/audio/media/42", back.customRingtone)
        assertEquals("0r1-2A3B4C", back.key)
        assertEquals(9, back.rows(Mime.PHONE).size)
        assertEquals(setOf("Friends, close", "Book club"), back.rows(Mime.GROUP).map { it[Col.GROUP_TITLE] }.toSet())
        assertArrayEquals(Rich.photo, back.rows(Mime.PHOTO).single().blob)
    }

    @Test fun canonical_drops_only_provider_computed_columns() {
        val c = VCardMapper.canonical(Rich.record(), Rich.groupTitles).rows()
        val name = c.single { it.mimeType == Mime.NAME }
        assertNull(name[Col.D10]) // FULL_NAME_STYLE
        assertEquals("ドウ", name[Col.D9])
        assertNull(c.first { it.mimeType == Mime.PHONE }[Col.D4]) // NORMALIZED_NUMBER
        assertNull(c.single { it.mimeType == Mime.PHOTO }[Col.D14]) // PHOTO_FILE_ID
        assertEquals("Building 4", c.first { it.mimeType == Mime.ORG }[Col.D9])
        assertEquals("Jane (Work)", c.first { it.mimeType == Mime.EMAIL && it[Col.D2] == "2" }[Col.D4])
    }

    @Test fun every_row_of_the_rich_record_survives() {
        val original = Rich.record().rows()
        val back = roundTrip(Rich.record(), Rich.groupTitles).rows()
        // Name + photo + groups aside, every row keeps its identity (mimetype + DATA1..DATA14 minus computed columns).
        val comparable = original.filter { it.mimeType !in setOf(Mime.NAME, Mime.PHOTO, Mime.GROUP) }
        assertEquals(comparable.size, back.count { it.mimeType !in setOf(Mime.NAME, Mime.PHOTO, Mime.GROUP) })
        for (r in comparable) {
            val match = back.firstOrNull { b -> b.mimeType == r.mimeType && b[Col.D1] == r[Col.D1] }
            assertTrue("lost ${r.mimeType} ${r.values}", match != null)
        }
    }

    @Test fun multiple_raw_contacts_flatten_into_one_card() {
        val google = RawRecord(
            "com.google", "a@gmail.com",
            rows = listOf(
                row(Mime.NAME, Col.D1 to "Sam Lee", Col.D2 to "Sam", Col.D3 to "Lee"),
                row(Mime.PHONE, Col.D1 to "+44 7700 900123", Col.D2 to "2", primary = true),
                row(Mime.EMAIL, Col.D1 to "sam@example.com", Col.D2 to "1"),
            ),
        )
        val device = RawRecord(
            null, null,
            rows = listOf(
                row(Mime.NAME, Col.D1 to "Sammy", Col.D2 to "Sammy"),
                row(Mime.PHONE, Col.D1 to "+44 7700 900123", Col.D2 to "2"), // same number: merged
                row(Mime.PHONE, Col.D1 to "+44 20 7946 0000", Col.D2 to "3", primary = true),
                row(Mime.NOTE, Col.D1 to "from the device"),
            ),
        )
        val r = ContactRecord("k", "Sam Lee", raws = listOf(google, device))
        val back = assertLossless(r)
        assertEquals(1, back.raws.size)
        assertNull(back.raws.single().accountType)
        assertEquals("Sam", back.rows(Mime.NAME).single()[Col.D2]) // the name row matching the display name wins
        assertEquals(2, back.rows(Mime.PHONE).size)
        assertEquals(1, back.rows(Mime.PHONE).count { it.isPrimary }) // one default per kind
        assertEquals("+44 7700 900123", back.rows(Mime.PHONE).single { it.isPrimary }[Col.D1])
    }

    @Test fun organisation_only_contact_gets_no_invented_name_row() {
        val r = record("ACME Plumbing", row(Mime.ORG, Col.D1 to "ACME Plumbing", Col.D2 to "1"), row(Mime.PHONE, Col.D1 to "0161 496 0000", Col.D2 to "3"))
        val back = assertLossless(r)
        assertTrue(back.rows(Mime.NAME).isEmpty())
        assertEquals("ACME Plumbing", back.displayName)
    }

    @Test fun contact_without_name_row_and_underived_display_name() {
        val r = record("Mum", row(Mime.PHONE, Col.D1 to "+1 555 0100", Col.D2 to "2"))
        val back = assertLossless(r)
        assertTrue(back.rows(Mime.NAME).isEmpty())
        assertEquals("Mum", back.displayName)
    }

    @Test fun number_only_contact_round_trips() {
        val r = record("+1 555 0100", row(Mime.PHONE, Col.D1 to "+1 555 0100", Col.D2 to "2"))
        val back = assertLossless(r)
        assertTrue(back.rows(Mime.NAME).isEmpty())
    }

    @Test fun name_row_with_display_name_only() {
        assertLossless(record("Bob", row(Mime.NAME, Col.D1 to "Bob")))
    }

    @Test fun name_row_whose_display_name_differs_from_contact_display_name() {
        val r = record("Robert", row(Mime.NAME, Col.D1 to "Bob the Builder", Col.D2 to "Bob"))
        val back = assertLossless(r)
        assertEquals("Bob the Builder", back.rows(Mime.NAME).single()[Col.D1])
        assertEquals("Robert", back.displayName)
    }

    @Test fun values_with_every_special_character_survive() {
        val nasty = "a;b,c\\d:e\"f\ng\r\nh\ti^j'k<>&é中文🙂"
        val r = record(
            nasty,
            row(Mime.NAME, Col.D1 to nasty, Col.D2 to nasty, Col.D3 to nasty, Col.D4 to nasty, Col.D5 to nasty, Col.D6 to nasty, Col.D7 to nasty),
            row(Mime.NICKNAME, Col.D1 to nasty),
            row(Mime.PHONE, Col.D1 to "12345", Col.D2 to "0", Col.D3 to nasty),
            row(Mime.POSTAL, Col.D1 to nasty, Col.D4 to nasty, Col.D7 to nasty, Col.D2 to "1"),
            row(Mime.ORG, Col.D1 to nasty, Col.D4 to nasty, Col.D5 to nasty, Col.D6 to nasty),
            row(Mime.NOTE, Col.D1 to nasty),
            row(Mime.RELATION, Col.D1 to nasty, Col.D2 to "0", Col.D3 to nasty),
            row(Mime.EVENT, Col.D1 to "1999-12-31", Col.D2 to "0", Col.D3 to nasty),
            row(Mime.IM, Col.D1 to nasty, Col.D5 to "-1", Col.D6 to "Wire"),
            row(Mime.GROUP, Col.GROUP_TITLE to nasty),
            row("vnd.android.cursor.item/vnd.example.x", Col.D1 to nasty, Col.D14 to nasty),
        ).copy(customRingtone = nasty)
        // \r\n and \r inside values are normalised to \n by every vCard reader; compare after that.
        val expected = VCardMapper.canonical(r)
        val back = roundTrip(r)
        fun norm(t: String) = t.replace("\r\n", "\n").replace('\r', '\n')
        assertEquals(norm(describe(expected)), norm(describe(back)))
    }

    @Test fun empty_and_blank_rows_are_not_invented() {
        val r = record(
            "Kim",
            row(Mime.NAME, Col.D1 to "Kim", Col.D2 to "Kim"),
            row(Mime.PHONE, Col.D1 to "", Col.D2 to "2"),
            row(Mime.EMAIL, Col.D1 to null),
            row(Mime.NOTE, Col.D1 to ""),
            row(Mime.PHOTO),
        )
        val back = assertLossless(r)
        assertEquals(listOf(Mime.NAME), back.rows().map { it.mimeType })
    }

    @Test fun type_defaults_are_normalised_consistently() {
        val r = record(
            "T",
            row(Mime.NAME, Col.D1 to "T"),
            row(Mime.PHONE, Col.D1 to "1111111"), // no type -> Other
            row(Mime.PHONE, Col.D1 to "2222222", Col.D2 to "0"), // custom without label -> Other
            row(Mime.EMAIL, Col.D1 to "a@b.c", Col.D2 to "2", Col.D3 to "ignored label"), // label only matters for custom
            row(Mime.NICKNAME, Col.D1 to "Nick", Col.D2 to "1"), // default nickname type
            row(Mime.EVENT, Col.D1 to "2000-02-29"), // no type -> Other
        )
        val c = VCardMapper.canonical(r).rows()
        assertEquals(listOf("7", "7"), c.filter { it.mimeType == Mime.PHONE }.map { it[Col.D2] })
        assertNull(c.single { it.mimeType == Mime.EMAIL }[Col.D3])
        assertNull(c.single { it.mimeType == Mime.NICKNAME }[Col.D2])
        assertEquals("2", c.single { it.mimeType == Mime.EVENT }[Col.D2])
        assertLossless(r)
    }

    @Test fun unknown_type_codes_and_extra_columns_travel_as_parameters() {
        val r = record(
            "OEM",
            row(Mime.NAME, Col.D1 to "OEM"),
            row(Mime.PHONE, Col.D1 to "555 0000", Col.D2 to "42", Col.D3 to "Samsung special", Col.D5 to "extra", Col.D13 to "x;y"),
            row(Mime.EVENT, Col.D1 to "2020-02-02", Col.D2 to "9", Col.D3 to "Custom OEM"),
            row(Mime.IM, Col.D1 to "handle", Col.D5 to "Threema"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("X-PARLEY-DATA2=42"))
        val back = assertLossless(r)
        val phone = back.rows(Mime.PHONE).single()
        assertEquals("42", phone[Col.D2])
        assertEquals("Samsung special", phone[Col.D3])
        assertEquals("x;y", phone[Col.D13])
        assertEquals("Threema", back.rows(Mime.IM).single()[Col.D5])
    }

    @Test fun many_contacts_in_one_file() {
        val records = (1..250).map { i ->
            record("Person $i", row(Mime.NAME, Col.D1 to "Person $i", Col.D2 to "Person", Col.D3 to "$i"), row(Mime.PHONE, Col.D1 to "+1 555 ${1000 + i}", Col.D2 to "2"), key = "k$i")
        }
        val (back, report) = VCardStream.readAll(VCardStream.writeAll(records))
        assertEquals(250, report.cardsParsed)
        assertEquals(records.map { VCardMapper.canonical(it) }, back)
    }

    @Test fun output_is_vcard_4() {
        val text = VCardStream.writeAll(listOf(Rich.record()), Rich.groupTitles)
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:4.0\r\n"))
        assertTrue(text.contains("PRODID:${VCardStream.PRODID}"))
        assertTrue(text.contains("UID:0r1-2A3B4C"))
        assertTrue(text.contains("X-PARLEY-STARRED:1"))
        assertFalse("starred must not leak into labels", text.contains("CATEGORIES:starred"))
    }
}
