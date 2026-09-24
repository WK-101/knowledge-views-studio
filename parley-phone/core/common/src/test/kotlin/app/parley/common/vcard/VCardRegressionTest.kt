package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per vCard bug found in other open-source contacts apps (docs/COMPETITIVE_ANALYSIS.md §2),
 * plus the fidelity complaints users report most (§3, #4).
 */
class VCardRegressionTest {

    private fun import(text: String) = VCardStream.readAll(text.trimIndent().replace("\n", "\r\n") + "\r\n")

    @Test fun title_and_organization_are_not_swapped() {
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.ORG, Col.D1 to "ACME Corp", Col.D4 to "Engineer", Col.D5 to "Rockets"))
        val text = unfolded(r)
        assertTrue(text, text.contains("\r\nORG:ACME Corp;Rockets\r\n"))
        assertTrue(text, text.contains("\r\nTITLE:Engineer\r\n"))
        val org = assertLossless(r).rows(Mime.ORG).single()
        assertEquals("ACME Corp", org[Col.D1])
        assertEquals("Engineer", org[Col.D4])
        assertEquals("Rockets", org[Col.D5])

        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Bo
            TITLE:Chef
            ORG:Diner
            END:VCARD
            """,
        )
        val imported = list.single().rows(Mime.ORG).single()
        assertEquals("Diner", imported[Col.D1])
        assertEquals("Chef", imported[Col.D4])
    }

    @Test fun title_without_company_stays_a_title() {
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.ORG, Col.D4 to "Freelancer"))
        val org = assertLossless(r).rows(Mime.ORG).single()
        assertNull(org[Col.D1])
        assertEquals("Freelancer", org[Col.D4])
    }

    @Test fun two_jobs_keep_their_titles_paired() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.ORG, Col.D1 to "Day Job", Col.D4 to "Clerk"),
            row(Mime.ORG, Col.D1 to "Night Job", Col.D4 to "DJ"),
        )
        val orgs = assertLossless(r).rows(Mime.ORG)
        assertEquals(listOf("Day Job" to "Clerk", "Night Job" to "DJ"), orgs.map { it[Col.D1] to it[Col.D4] })
    }

    @Test fun birthday_and_anniversary_are_not_swapped() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.EVENT, Col.D1 to "2015-09-05", Col.D2 to "1"),
            row(Mime.EVENT, Col.D1 to "1990-05-17", Col.D2 to "3"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("\r\nBDAY:19900517\r\n"))
        assertTrue(text, text.contains("\r\nANNIVERSARY:20150905\r\n"))
        val events = assertLossless(r).rows(Mime.EVENT).associate { it[Col.D2] to it[Col.D1] }
        assertEquals("1990-05-17", events["3"])
        assertEquals("2015-09-05", events["1"])

        // Legacy 3.0 cards (Evolution, KDE) use X-ANNIVERSARY.
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Old
            X-ANNIVERSARY:2001-02-03
            BDAY:1970-01-02
            END:VCARD
            """,
        )
        val imported = list.single().rows(Mime.EVENT).associate { it[Col.D2] to it[Col.D1] }
        assertEquals("1970-01-02", imported["3"])
        assertEquals("2001-02-03", imported["1"])
    }

    @Test fun yearless_birthday_is_preserved() {
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.EVENT, Col.D1 to "--05-17", Col.D2 to "3"))
        val text = unfolded(r)
        assertTrue(text, text.contains("\r\nBDAY:--0517\r\n"))
        assertEquals("--05-17", assertLossless(r).rows(Mime.EVENT).single()[Col.D1])
    }

    @Test fun yearless_birthday_from_other_apps() {
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Apple
            BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-12
            item1.X-ABDATE;X-APPLE-OMIT-YEAR=1604:1604-11-30
            item1.X-ABLabel:_${'$'}!<Anniversary>!${'$'}_
            END:VCARD
            BEGIN:VCARD
            VERSION:4.0
            FN:Four
            BDAY:--0412
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Dashes
            BDAY:--04-13
            END:VCARD
            """,
        )
        val dates = list.map { r -> r.rows(Mime.EVENT).map { it[Col.D2] to it[Col.D1] } }
        assertEquals(listOf("3" to "--03-12", "1" to "--11-30"), dates[0])
        assertEquals(listOf("3" to "--04-12"), dates[1])
        assertEquals(listOf("3" to "--04-13"), dates[2])
        // Android's own "1604-..." convention is normalised the same way.
        assertEquals("--03-12", VCardMapper.normalizeDate("1604-03-12"))
    }

    @Test fun free_text_dates_are_kept_verbatim() {
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.EVENT, Col.D1 to "circa spring 1950", Col.D2 to "3"))
        assertEquals("circa spring 1950", assertLossless(r).rows(Mime.EVENT).single()[Col.D1])
    }

    @Test fun structured_address_is_preserved() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(
                Mime.POSTAL, Col.D2 to "2", Col.D4 to "10 Downing St, Flat 1", Col.D5 to "PO Box 10", Col.D6 to "Westminster",
                Col.D7 to "London", Col.D8 to "Greater London", Col.D9 to "SW1A 2AA", Col.D10 to "United Kingdom",
            ),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("ADR;TYPE=work:PO Box 10;Westminster;10 Downing St\\, Flat 1;London;Greater London;SW1A 2AA;United Kingdom"))
        val a = assertLossless(r).rows(Mime.POSTAL).single()
        assertEquals("10 Downing St, Flat 1", a[Col.D4])
        assertEquals("PO Box 10", a[Col.D5])
        assertEquals("Westminster", a[Col.D6])
        assertEquals("London", a[Col.D7])
        assertEquals("Greater London", a[Col.D8])
        assertEquals("SW1A 2AA", a[Col.D9])
        assertEquals("United Kingdom", a[Col.D10])
        assertEquals("2", a[Col.D2])
    }

    @Test fun websites_are_preserved() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.WEBSITE, Col.D1 to "https://ann.example", Col.D2 to "1"),
            row(Mime.WEBSITE, Col.D1 to "https://work.example/ann", Col.D2 to "5"),
            row(Mime.WEBSITE, Col.D1 to "https://example.org/?q=a,b;c", Col.D2 to "7"),
        )
        val sites = assertLossless(r).rows(Mime.WEBSITE)
        assertEquals(listOf("https://ann.example", "https://work.example/ann", "https://example.org/?q=a,b;c"), sites.map { it[Col.D1] })
        assertEquals(listOf("1", "5", "7"), sites.map { it[Col.D2] })
    }

    @Test fun multiple_phones_with_custom_labels_are_preserved() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.PHONE, Col.D1 to "111 111 1111", Col.D2 to "0", Col.D3 to "Boat"),
            row(Mime.PHONE, Col.D1 to "222 222 2222", Col.D2 to "0", Col.D3 to "Grandma's house"),
            row(Mime.PHONE, Col.D1 to "333 333 3333", Col.D2 to "2"),
            row(Mime.PHONE, Col.D1 to "444 444 4444", Col.D2 to "0", Col.D3 to "Boat"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("item1.X-ABLabel:Boat"))
        val phones = assertLossless(r).rows(Mime.PHONE)
        assertEquals(listOf("Boat", "Grandma's house", null, "Boat"), phones.map { it[Col.D3] })
        assertEquals(listOf("0", "0", "2", "0"), phones.map { it[Col.D2] })
    }

    @Test fun every_android_phone_type_survives() {
        val rows = (1..20).map { t -> row(Mime.PHONE, Col.D1 to "555 01%02d".format(t), Col.D2 to t.toString()) }
        val r = record("Types", row(Mime.NAME, Col.D1 to "Types"), *rows.toTypedArray())
        assertEquals((1..20).map { it.toString() }, assertLossless(r).rows(Mime.PHONE).map { it[Col.D2] })
    }

    @Test fun every_android_relation_type_survives() {
        val rows = (1..14).map { t -> row(Mime.RELATION, Col.D1 to "Person $t", Col.D2 to t.toString()) }
        val r = record("Rel", row(Mime.NAME, Col.D1 to "Rel"), *rows.toTypedArray())
        assertEquals((1..14).map { it.toString() }, assertLossless(r).rows(Mime.RELATION).map { it[Col.D2] })
    }

    @Test fun every_android_im_protocol_survives() {
        val rows = (0..8).map { p -> row(Mime.IM, Col.D1 to "user$p", Col.D2 to "3", Col.D5 to p.toString()) }
        val r = record("Im", row(Mime.NAME, Col.D1 to "Im"), *rows.toTypedArray())
        assertEquals((0..8).map { it.toString() }, assertLossless(r).rows(Mime.IM).map { it[Col.D5] })
    }

    @Test fun primary_number_is_preserved() {
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row(Mime.PHONE, Col.D1 to "111", Col.D2 to "2"),
            row(Mime.PHONE, Col.D1 to "222", Col.D2 to "3", primary = true),
            row(Mime.PHONE, Col.D1 to "333", Col.D2 to "1"),
        )
        val text = unfolded(r)
        assertTrue(text, Regex("TEL;[^\r\n]*PREF=1[^\r\n]*:222\r\n").containsMatchIn(text))
        val phones = assertLossless(r).rows(Mime.PHONE)
        assertEquals(listOf(false, true, false), phones.map { it.isPrimary })
        assertEquals(listOf(false, true, false), phones.map { it.isSuperPrimary })

        // vCard 2.1 / 3.0 mark the default with TYPE=PREF.
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Old
            TEL;TYPE=CELL:111
            TEL;TYPE=HOME,PREF:222
            END:VCARD
            """,
        )
        assertEquals(listOf(false, true), list.single().rows(Mime.PHONE).map { it.isPrimary })
    }

    @Test fun lowest_pref_wins_when_several_are_preferred() {
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:X
            TEL;PREF=3:111
            TEL;PREF=1:222
            EMAIL;PREF=2:a@b.c
            END:VCARD
            """,
        )
        val rows = list.single().rows()
        assertEquals(listOf(false, true), rows.filter { it.mimeType == Mime.PHONE }.map { it.isPrimary })
        assertTrue(rows.single { it.mimeType == Mime.EMAIL }.isPrimary)
    }

    @Test fun custom_and_unknown_mimetypes_are_preserved() {
        val blob = byteArrayOf(9, 8, 7, 0, -1)
        val r = record(
            "Ann",
            row(Mime.NAME, Col.D1 to "Ann"),
            row("vnd.android.cursor.item/vnd.com.whatsapp.profile", Col.D1 to "4915112345678@s.whatsapp.net", Col.D3 to "Message +49 151 12345678"),
            row("vnd.android.cursor.item/vnd.example.pets", Col.D1 to "Rex", Col.D2 to "dog; good boy", Col.D7 to "x,y", primary = true, blob = blob),
            row(Mime.IDENTITY, Col.D1 to "ann", Col.D2 to "org.example"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("X-ANDROID-CUSTOM:vnd.android.cursor.item/vnd.com.whatsapp.profile;4915112345678@s.whatsapp.net;;Message +49 151 12345678;"))
        val back = assertLossless(r)
        val pets = back.rows("vnd.android.cursor.item/vnd.example.pets").single()
        assertEquals("dog; good boy", pets[Col.D2])
        assertEquals("x,y", pets[Col.D7])
        assertTrue(pets.isPrimary)
        assertArrayEquals(blob, pets.blob)
    }

    @Test fun android_composer_x_android_custom_is_understood() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            N:Doe;Jane;;;
            FN:Jane Doe
            X-ANDROID-CUSTOM:vnd.android.cursor.item/nickname;JD;1;;;;;;;;;;;;;
            X-ANDROID-CUSTOM:vnd.android.cursor.item/relation;Mum\, dearest;8;;;;;;;;;;;;;
            X-ANDROID-CUSTOM:vnd.android.cursor.item/contact_event;2010-10-10;0;Adoption day;;;;;;;;;;;;
            END:VCARD
            """,
        )
        assertTrue(report.unmappedProperties.isEmpty())
        val r = list.single()
        assertEquals("JD", r.rows(Mime.NICKNAME).single()[Col.D1])
        assertEquals("Mum, dearest", r.rows(Mime.RELATION).single()[Col.D1])
        assertEquals("8", r.rows(Mime.RELATION).single()[Col.D2])
        val ev = r.rows(Mime.EVENT).single()
        assertEquals(listOf("2010-10-10", "0", "Adoption day"), listOf(ev[Col.D1], ev[Col.D2], ev[Col.D3]))
    }

    @Test fun photo_is_preserved_byte_exact_at_full_resolution() {
        val big = fakeJpeg(700_000, seed = 3)
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.PHOTO, blob = big))
        val back = assertLossless(r)
        assertArrayEquals(big, back.rows(Mime.PHOTO).single().blob)

        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val text = unfolded(record("P", row(Mime.PHOTO, blob = png)))
        assertTrue(text, text.contains("PHOTO:data:image/png;base64,"))
    }

    @Test fun photo_link_is_reported_not_silently_dropped() {
        val (_, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Linked
            PHOTO;VALUE=uri:https://example.com/a.jpg
            END:VCARD
            """,
        )
        assertEquals(mapOf("PHOTO (link)" to 1), report.unmappedProperties)
    }

    @Test fun starred_and_groups() {
        val r = record("Ann", row(Mime.NAME, Col.D1 to "Ann"), row(Mime.GROUP, Col.D1 to "5"), row(Mime.GROUP, Col.D1 to "6"))
            .copy(starred = true)
        val back = assertLossless(r, mapOf(5L to "Family", 6L to "Work"))
        assertTrue(back.starred)
        assertEquals(listOf("Family", "Work"), back.rows(Mime.GROUP).map { it[Col.GROUP_TITLE] })
        // A membership whose group we cannot name (a system group) does not travel.
        assertTrue(VCardMapper.canonical(r).rows(Mime.GROUP).isEmpty())
    }
}
