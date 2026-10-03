package app.parley.common.vcard

import app.parley.common.AltCalendar
import app.parley.common.AltDay
import app.parley.common.CalendarConverter
import java.time.LocalDate
import app.parley.common.people.AddressParts
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Custom fields, RFC 9554's name, address and language fields, social profiles and calendars through vCard 4.0. */
class ContactFieldsVCardTest {
    private val name = row(Mime.NAME, Col.D1 to "Ana García", Col.D2 to "Ana", Col.D3 to "García")

    @Test fun custom_fields_round_trip_as_labelled_grouped_items() {
        val r = record(
            "Ana García", name,
            row(Mime.CUSTOM_FIELD, Col.D1 to "Shoe size", Col.D2 to "38"),
            row(Mime.CUSTOM_FIELD, Col.D1 to "Locker", Col.D2 to "A;12, top\nleft"),
            row(Mime.CUSTOM_FIELD, Col.D2 to "no label"),
        )
        val text = unfolded(r)
        assertTrue(text, Regex("item\\d+\\.X-PARLEY-CUSTOM:38").containsMatchIn(text))
        assertTrue(text, Regex("item\\d+\\.X-ABLabel:Shoe size").containsMatchIn(text))
        val back = assertLossless(r)
        assertEquals(listOf("Shoe size", "Locker", null), back.rows(Mime.CUSTOM_FIELD).map { it[Col.D1] })
        assertEquals("A;12, top\nleft", back.rows(Mime.CUSTOM_FIELD)[1][Col.D2])
    }

    @Test fun googles_user_defined_fields_travel_as_custom_fields() {
        val r = record("Ana García", name, row(Mime.GOOGLE_CUSTOM_FIELD, Col.D1 to "Membership", Col.D2 to "Gold"))
        assertEquals(Mime.CUSTOM_FIELD, VCardMapper.canonical(r).rows().last().mimeType)
        val back = assertLossless(r)
        assertEquals("Gold", back.rows(Mime.CUSTOM_FIELD).single()[Col.D2])
        assertTrue(unfolded(r).contains("X-PARLEY-CUSTOM:Gold"))
    }

    @Test fun an_older_export_of_a_google_field_comes_back_as_a_custom_field() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nX-ANDROID-CUSTOM:${Mime.GOOGLE_CUSTOM_FIELD};Membership;Gold;;;;;;;;;;;;;\r\nEND:VCARD\r\n"
        val r = VCardStream.readAll(card).first.single()
        val f = r.rows(Mime.CUSTOM_FIELD).single()
        assertEquals("Membership", f[Col.D1])
        assertEquals("Gold", f[Col.D2])
    }

    @Test fun phonetic_middle_name_round_trips() {
        val r = record("Ana García", row(Mime.NAME, Col.D1 to "Ana García", Col.D2 to "Ana", Col.D3 to "García", Col.D8 to "Lu"))
        assertTrue(unfolded(r).contains("X-PHONETIC-MIDDLE-NAME:Lu"))
        assertEquals("Lu", assertLossless(r).rows(Mime.NAME).single()[Col.D8])
    }

    @Test fun second_surname_and_generation_are_ns_rfc_9554_components() {
        val r = record(
            "Ana García", name,
            row(Mime.NAME_PARTS, Col.D1 to "López", Col.D2 to "Jr."),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("N:García;Ana;;;;López;Jr."))
        assertFalse(text, text.contains(Rfc9554.PARTS))
        val back = assertLossless(r)
        assertEquals("López", back.rows(Mime.NAME_PARTS).single()[Col.D1])
    }

    @Test fun name_parts_without_a_name_row_make_no_name_row() {
        val r = record("Acme", row(Mime.ORG, Col.D1 to "Acme"), row(Mime.NAME_PARTS, Col.D2 to "III"))
        val back = assertLossless(r)
        assertTrue(back.rows(Mime.NAME).isEmpty())
        assertEquals("III", back.rows(Mime.NAME_PARTS).single()[Col.D2])
    }

    @Test fun address_parts_are_adrs_rfc_9554_components() {
        val parts = AddressParts.encode(
            mapOf(AddressParts.Part.FLOOR to "3", AddressParts.Part.BUILDING to "B", AddressParts.Part.LANDMARK to "Opposite the park"),
        )!!
        val r = record(
            "Ana García", name,
            row(Mime.POSTAL, Col.D4 to "1 Main St", Col.D7 to "Lisbon", Col.D2 to "1", AddressParts.COLUMN to parts),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("ADR;TYPE=home:;;1 Main St;Lisbon;;;;;;3;;;B;;;;Opposite the park;"))
        val back = assertLossless(r)
        assertEquals(parts, back.rows(Mime.POSTAL).single()[AddressParts.COLUMN])
    }

    @Test fun an_address_column_in_another_form_rides_along_unchanged() {
        val r = record("Ana García", name, row(Mime.POSTAL, Col.D4 to "1 Main St", Col.D2 to "1", AddressParts.COLUMN to "oem;value"))
        assertEquals("oem;value", assertLossless(r).rows(Mime.POSTAL).single()[AddressParts.COLUMN])
    }

    @Test fun languages_round_trip_as_lang_and_language() {
        val r = record(
            "Ana García", name,
            row(Mime.LANGUAGE, Col.D1 to "es", Col.D2 to "work", primary = true),
            row(Mime.LANGUAGE, Col.D1 to "pt-BR"),
            row(Mime.LANGUAGE, Col.D1 to "es-MX", Col.D3 to VCardMapper.CARD_LANGUAGE),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("LANG;TYPE=work;PREF=1:es"))
        assertTrue(text, text.contains("LANGUAGE:es-MX"))
        val back = assertLossless(r)
        assertEquals(listOf("es", "pt-BR", "es-MX"), back.rows(Mime.LANGUAGE).map { it[Col.D1] })
    }

    @Test fun a_date_kept_by_another_calendar_stays_gregorian_with_parleys_calendar_mark() {
        val r = record(
            "Ana García", name,
            row(Mime.EVENT, Col.D1 to "1990-02-01", Col.D2 to "3", AltCalendar.COLUMN to AltCalendar.CHINESE.key),
            row(Mime.EVENT, Col.D1 to "2001-09-18", Col.D2 to "1", AltCalendar.COLUMN to AltCalendar.HEBREW.key),
            row(Mime.EVENT, Col.D1 to "2010-11-16", Col.D2 to "0", Col.D3 to "Graduation", AltCalendar.COLUMN to AltCalendar.HIJRI.key),
            row(Mime.EVENT, Col.D1 to "2012-01-01", Col.D2 to "2"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("BDAY;X-PARLEY-CALENDAR=chinese:19900201") || text.contains("BDAY;X-PARLEY-CALENDAR=chinese:1990-02-01"))
        assertTrue(text, text.contains("X-PARLEY-CALENDAR=islamic-umalqura"))
        // CALSCALE would say the value itself is a Chinese or Hijri date.
        assertFalse(text, text.contains("CALSCALE"))
        val back = assertLossless(r)
        assertEquals(listOf("chinese", "hebrew", "islamic-umalqura", null), back.rows(Mime.EVENT).map { it[AltCalendar.COLUMN] })
    }

    @Test fun another_apps_data14_on_a_date_travels_as_it_is() {
        val r = record("Ana García", name, row(Mime.EVENT, Col.D1 to "1990-02-01", Col.D2 to "3", AltCalendar.COLUMN to "persian"))
        assertFalse(unfolded(r).contains("X-PARLEY-CALENDAR"))
        assertEquals("persian", assertLossless(r).rows(Mime.EVENT).single()[AltCalendar.COLUMN])
    }

    /** Knows one Hijri day: 1 Shawwal 1446 is 30 March 2025. */
    private val hijri = object : CalendarConverter {
        override fun toAlt(calendar: AltCalendar, date: LocalDate) = error("not needed")
        override fun toGregorian(calendar: AltCalendar, day: AltDay) =
            if (calendar == AltCalendar.HIJRI && day == AltDay(1446, 10, 1)) LocalDate.of(2025, 3, 30) else null
    }

    private fun card(vararg lines: String) = (listOf("BEGIN:VCARD", "VERSION:4.0", "FN:Ana") + lines + listOf("END:VCARD", "")).joinToString("\r\n")

    @Test fun a_hijri_calscale_date_is_read_as_the_hijri_day_it_names() {
        val (list, report) = VCardStream.readAll(card("BDAY;CALSCALE=islamic-umalqura:14461001"), hijri)
        val e = list.single().rows(Mime.EVENT).single()
        assertEquals("2025-03-30", e[Col.D1])
        assertEquals(AltCalendar.HIJRI.key, e[AltCalendar.COLUMN])
        assertEquals(emptyMap<String, Int>(), report.unmappedProperties)
    }

    @Test fun a_calscale_date_that_cant_be_read_unambiguously_is_kept_as_written_and_reported() {
        // Hebrew month numbers depend on who wrote them; without a converter nothing can be read.
        val (list, report) = VCardStream.readAll(card("BDAY;CALSCALE=hebrew:57500315", "ANNIVERSARY;CALSCALE=islamic-umalqura:14461001"))
        val r = list.single()
        assertTrue(r.rows(Mime.EVENT).isEmpty())
        val kept = r.rows(Mime.CUSTOM_FIELD).map { it[Col.D1] to it[Col.D2] }
        assertEquals(listOf("Birthday (hebrew calendar)" to "5750-03-15", "Anniversary (islamic-umalqura calendar)" to "1446-10-01"), kept)
        assertEquals(
            setOf("BDAY;CALSCALE=hebrew (kept as a custom field)", "ANNIVERSARY;CALSCALE=islamic-umalqura (kept as a custom field)"),
            report.unmappedProperties.keys,
        )
    }

    @Test fun calscale_gregorian_is_an_ordinary_date() {
        val e = VCardStream.readAll(card("BDAY;CALSCALE=gregorian:19900201")).first.single().rows(Mime.EVENT).single()
        assertEquals("1990-02-01", e[Col.D1])
        assertEquals(null, e[AltCalendar.COLUMN])
    }

    @Test fun an_rfc_9554_profile_given_as_a_user_name_becomes_the_profile_address() {
        val r = VCardStream.readAll(card("SOCIALPROFILE;SERVICE-TYPE=Instagram;VALUE=text:ana.lima")).first.single()
        val site = r.rows(Mime.WEBSITE).single()
        assertEquals("https://www.instagram.com/ana.lima/", site[Col.D1])
        assertEquals("Instagram", site[Col.D3])
        // An address with an escaped colon is read as the address.
        val escaped = VCardStream.readAll(card("SOCIALPROFILE;SERVICE-TYPE=LinkedIn:https\\://www.linkedin.com/in/ana")).first.single()
        assertEquals("https://www.linkedin.com/in/ana", escaped.rows(Mime.WEBSITE).single()[Col.D1])
    }

    @Test fun social_profiles_are_written_as_socialprofile() {
        val r = record(
            "Ana García", name,
            row(Mime.WEBSITE, Col.D1 to "https://example.org", Col.D2 to "1"),
            row(Mime.WEBSITE, Col.D1 to "https://www.instagram.com/ana.lima", Col.D2 to "0", Col.D3 to "Instagram"),
            row(Mime.WEBSITE, Col.D1 to "https://mastodon.social/@ana", Col.D2 to "0", Col.D3 to "Mastodon", primary = true),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("SOCIALPROFILE;SERVICE-TYPE=Instagram;USERNAME=ana.lima:https://www.instagram.com/ana.lima"))
        assertFalse(text, text.contains("URL:https://www.instagram.com"))
        assertFalse(text, text.contains("X-ABLabel:Instagram"))
        val back = assertLossless(r)
        assertEquals(3, back.rows(Mime.WEBSITE).size)
    }

    @Test fun profiles_in_the_older_forms_are_still_read() {
        val card = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ana\r\nitem1.URL:https://github.com/ana\r\nitem1.X-ABLabel:GitHub\r\n" +
            "X-SOCIALPROFILE;TYPE=twitter;X-USER=ana_lima:https://twitter.com/ana_lima\r\nEND:VCARD\r\n"
        val r = VCardStream.readAll(card).first.single()
        val sites = r.rows(Mime.WEBSITE)
        assertEquals(listOf("GitHub", "X (Twitter)"), sites.map { it[Col.D3] })
    }

    @Test fun a_card_using_rfc_9554_drops_nothing() {
        val card = listOf(
            "BEGIN:VCARD", "VERSION:4.0", "FN:Ana García López",
            "N:García;Ana;;Dr.;;López;II",
            "ADR;TYPE=work:;;Rua A 1;Porto;;4000-001;Portugal;12;;2;1;Rua A;Torre B;;;Bonfim;;N",
            "LANG;PREF=1:pt", "LANGUAGE:es",
            "SOCIALPROFILE;SERVICE-TYPE=LinkedIn:https://www.linkedin.com/in/ana-garcia",
            "END:VCARD", "",
        ).joinToString("\r\n")
        val (list, report) = VCardStream.readAll(card)
        assertEquals(emptyMap<String, Int>(), report.unmappedProperties)
        val r = list.single()
        assertEquals("López", r.rows(Mime.NAME_PARTS).single()[Col.D1])
        assertEquals("II", r.rows(Mime.NAME_PARTS).single()[Col.D2])
        val parts = AddressParts.decode(r.rows(Mime.POSTAL).single()[AddressParts.COLUMN])
        assertEquals("12", parts[AddressParts.Part.ROOM])
        assertEquals("Torre B", parts[AddressParts.Part.BUILDING])
        assertEquals("N", parts[AddressParts.Part.DIRECTION])
        assertEquals(listOf("pt", "es"), r.rows(Mime.LANGUAGE).map { it[Col.D1] })
        assertEquals("LinkedIn", r.rows(Mime.WEBSITE).single()[Col.D3])
        // And it goes out again as it came.
        assertLossless(r)
    }

    @Test fun the_new_kinds_are_core_kinds() {
        listOf(Mime.CUSTOM_FIELD, Mime.GOOGLE_CUSTOM_FIELD, Mime.NAME_PARTS, Mime.LANGUAGE).forEach { assertTrue(it, it in Mime.CORE) }
    }
}
