package app.parley.common.qr

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class QrParserTest {
    private fun ContactRecord.rows(mime: String) = raws.flatMap { it.rows }.filter { it.mimeType == mime }
    private fun ContactRecord.first(mime: String, col: String = Col.D1) = rows(mime).firstOrNull()?.get(col)

    private inline fun <reified T : QrPayload> parse(text: String): T {
        val p = QrParser.parse(text)
        assertTrue("Expected ${T::class.simpleName} for $text but got $p", p is T)
        return p as T
    }

    // ---------------------------------------------------------------- contacts

    @Test fun vcard30() {
        val c = parse<QrPayload.Contact>("BEGIN:VCARD\nVERSION:3.0\nN:Owen;Sean\nFN:Sean Owen\nTEL;TYPE=CELL:+12125551212\nEMAIL:sean@example.com\nEND:VCARD")
        assertEquals(ContactFormat.VCARD, c.format)
        assertEquals(1, c.records.size)
        val r = c.records[0]
        assertEquals("Sean Owen", r.displayName)
        assertEquals("+12125551212", r.first(Mime.PHONE))
        assertEquals("sean@example.com", r.first(Mime.EMAIL))
        assertEquals("Sean", r.first(Mime.NAME, Col.D2))
        assertEquals("Owen", r.first(Mime.NAME, Col.D3))
    }

    @Test fun vcard21_quoted_printable_utf8() {
        val c = parse<QrPayload.Contact>(
            "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrgen\r\n" +
                "TEL;CELL:+4915112345678\r\nEND:VCARD\r\n",
        )
        assertEquals("Müller", c.records[0].first(Mime.NAME, Col.D3))
        assertEquals("Jürgen", c.records[0].first(Mime.NAME, Col.D2))
    }

    @Test fun vcard40_tel_uri() {
        val c = parse<QrPayload.Contact>("BEGIN:VCARD\nVERSION:4.0\nFN:Ana\nTEL;VALUE=uri;TYPE=cell:tel:+34600111222\nEND:VCARD")
        assertEquals("+34600111222", c.records[0].first(Mime.PHONE))
    }

    @Test fun vcard_bare_cr_line_endings() {
        val c = parse<QrPayload.Contact>("BEGIN:VCARD\rVERSION:3.0\rFN:Cr Only\rTEL:123456\rEND:VCARD")
        assertEquals("Cr Only", c.records[0].displayName)
    }

    @Test fun several_cards_in_one_code() {
        val c = parse<QrPayload.Contact>(
            "BEGIN:VCARD\nVERSION:3.0\nFN:One\nTEL:111111\nEND:VCARD\nBEGIN:VCARD\nVERSION:3.0\nFN:Two\nTEL:222222\nEND:VCARD",
        )
        assertEquals(listOf("One", "Two"), c.records.map { it.displayName })
    }

    @Test fun broken_vcard_is_text() {
        assertTrue(QrParser.parse("BEGIN:VCARD\nthis is not a card") is QrPayload.Text)
    }

    @Test fun mecard_with_escapes() {
        val c = parse<QrPayload.Contact>("MECARD:N:Owen,Sean;TEL:+12125551212;EMAIL:srowen@example.com;NOTE:Likes \\;semicolons\\, and commas;BDAY:19700310;URL:https\\://example.com;;")
        assertEquals(ContactFormat.MECARD, c.format)
        val r = c.records[0]
        assertEquals("Sean", r.first(Mime.NAME, Col.D2))
        assertEquals("Owen", r.first(Mime.NAME, Col.D3))
        assertEquals("+12125551212", r.first(Mime.PHONE))
        assertEquals("Likes ;semicolons, and commas", r.first(Mime.NOTE))
        assertEquals("1970-03-10", r.first(Mime.EVENT))
        assertEquals("https://example.com", r.first(Mime.WEBSITE))
        assertTrue(c.vcard.startsWith("BEGIN:VCARD"))
    }

    @Test fun mecard_address_and_org() {
        val c = parse<QrPayload.Contact>("MECARD:N:Tanaka,Hanako;SOUND:tanaka,hanako;ADR:,,1-2-3 Chiyoda,Tokyo,,100-0001,Japan;ORG:Acme;TEL:0312345678;;")
        val r = c.records[0]
        assertEquals("Tokyo", r.first(Mime.POSTAL, Col.D7))
        assertEquals("Japan", r.first(Mime.POSTAL, Col.D10))
        assertEquals("Acme", r.first(Mime.ORG))
        assertEquals("hanako", r.first(Mime.NAME, Col.D7))
    }

    @Test fun bizcard() {
        val c = parse<QrPayload.Contact>("BIZCARD:N:Sean;X:Owen;T:Software Engineer;C:Google;A:76 9th Avenue, New York, NY 10011;B:+12125551212;E:srowen@google.com;;")
        assertEquals(ContactFormat.BIZCARD, c.format)
        val r = c.records[0]
        assertEquals("Sean Owen", r.displayName)
        assertEquals("Google", r.first(Mime.ORG))
        assertEquals("Software Engineer", r.first(Mime.ORG, Col.D4))
        assertEquals("srowen@google.com", r.first(Mime.EMAIL))
    }

    @Test fun parley_links() {
        assertEquals(ParleyKind.CONTACT, parse<QrPayload.Parley>("parley://qr?v=1&d=abc").kind)
        assertEquals(ParleyKind.SIMPLE, parse<QrPayload.Parley>("PARLEY://simple?d=abc").kind)
        assertEquals("parley://simple?d=abc", parse<QrPayload.Parley>("PARLEY://simple?d=abc").raw)
        assertEquals(ParleyKind.TEMPLATE, parse<QrPayload.Parley>("parley://template?d=x").kind)
        assertTrue(QrParser.parse("parley://unknown") is QrPayload.Text)
    }

    // ---------------------------------------------------------------- tel / sms / mail / geo

    @Test fun tel() {
        val p = parse<QrPayload.Phone>("tel:+1-212-555-1212")
        assertEquals("+1-212-555-1212", p.number)
        assertFalse(p.isMmi)
        assertEquals("+12125551212,42", parse<QrPayload.Phone>("TEL:+12125551212;ext=42").number)
    }

    @Test fun tel_mmi_codes_are_flagged_even_when_encoded() {
        val p = parse<QrPayload.Phone>("tel:%2A%2306%23")
        assertEquals("*#06#", p.number)
        assertTrue(p.isMmi)
        assertTrue(parse<QrPayload.Phone>("tel:*21*123#").isMmi)
    }

    @Test fun tel_strips_bidi_and_control() {
        assertEquals("+123456789", parse<QrPayload.Phone>("tel:+123\u202E456789").number)
    }

    @Test fun sms_all_forms() {
        parse<QrPayload.Sms>("sms:+18005551212").let { assertEquals("+18005551212", it.number); assertNull(it.body) }
        parse<QrPayload.Sms>("sms:+18005551212:Hello there").let { assertEquals("Hello there", it.body) }
        parse<QrPayload.Sms>("SMSTO:+18005551212:Time: 10:30").let { assertEquals("+18005551212", it.number); assertEquals("Time: 10:30", it.body) }
        parse<QrPayload.Sms>("sms:+18005551212?body=Hi%20you").let { assertEquals("Hi you", it.body) }
        parse<QrPayload.Sms>("sms:+111111111,+222222222?body=x").let { assertEquals(2, it.numbers.size) }
    }

    @Test fun mailto_and_matmsg() {
        parse<QrPayload.Email>("mailto:a@example.com?subject=Hi%20there&cc=b@example.com&body=Line").let {
            assertEquals(listOf("a@example.com"), it.to)
            assertEquals(listOf("b@example.com"), it.cc)
            assertEquals("Hi there", it.subject)
            assertEquals("Line", it.body)
        }
        parse<QrPayload.Email>("MATMSG:TO:x@example.com;SUB:Meeting\\; soon;BODY:See you;;").let {
            assertEquals(listOf("x@example.com"), it.to)
            assertEquals("Meeting; soon", it.subject)
            assertEquals("See you", it.body)
        }
        assertEquals(listOf("plain@example.org"), parse<QrPayload.Email>("plain@example.org").to)
    }

    @Test fun geo() {
        parse<QrPayload.Geo>("geo:40.71872,-73.98905,100").let {
            assertEquals(40.71872, it.lat, 1e-9)
            assertEquals(-73.98905, it.lon, 1e-9)
            assertEquals(100.0, it.altitude!!, 1e-9)
        }
        assertEquals("Café Central", parse<QrPayload.Geo>("geo:0,0?q=Caf%C3%A9+Central").query)
        assertTrue(QrParser.parse("geo:200,0") is QrPayload.Text)
    }

    // ---------------------------------------------------------------- Wi-Fi

    @Test fun wifi_wpa_backslash_escapes() {
        val w = parse<QrPayload.Wifi>("WIFI:T:WPA;S:My\\;Net;P:pa\\:ss\\\\word;H:true;;")
        assertEquals("My;Net", w.ssid)
        assertEquals("pa:ss\\word", w.password)
        assertEquals(WifiSecurity.WPA, w.security)
        assertTrue(w.hidden)
    }

    @Test fun wifi_sae_wep_open() {
        assertEquals(WifiSecurity.SAE, parse<QrPayload.Wifi>("WIFI:S:home;T:SAE;P:secret1234;;").security)
        assertEquals(WifiSecurity.WEP, parse<QrPayload.Wifi>("WIFI:T:WEP;S:old;P:abcde;;").security)
        parse<QrPayload.Wifi>("WIFI:T:nopass;S:Cafe;P:;;").let { assertEquals(WifiSecurity.OPEN, it.security); assertNull(it.password) }
        assertEquals(WifiSecurity.OPEN, parse<QrPayload.Wifi>("WIFI:S:Guest;;").security)
    }

    @Test fun wifi_percent_encoding_of_the_wpa3_spec() {
        val w = parse<QrPayload.Wifi>("WIFI:T:WPA;R:1;S:caf%C3%A9%3Bnet;P:a%25b;;")
        assertEquals("café;net", w.ssid)
        assertEquals("a%b", w.password)
    }

    @Test fun wifi_percent_that_isnt_an_escape_stays() {
        assertEquals("50%OFF", parse<QrPayload.Wifi>("WIFI:T:WPA;S:shop;P:50%OFF;;").password)
    }

    @Test fun wifi_eap_and_quoted_names() {
        val w = parse<QrPayload.Wifi>("WIFI:T:WPA2-EAP;S:\"corp\";E:PEAP;PH2:MSCHAPV2;A:anon;I:alice;P:pw;;")
        assertEquals("corp", w.ssid)
        assertEquals(WifiSecurity.EAP, w.security)
        assertEquals("PEAP", w.eapMethod)
        assertEquals("alice", w.identity)
        assertEquals("MSCHAPV2", w.phase2)
    }

    // ---------------------------------------------------------------- calendar

    @Test fun vevent_utc() {
        val e = parse<QrPayload.Event>("BEGIN:VEVENT\nSUMMARY:Team lunch\nDTSTART:20180601T070000Z\nDTEND:20180601T080000Z\nLOCATION:Caf\u00e9\\, upstairs\nEND:VEVENT")
        assertEquals("Team lunch", e.summary)
        assertEquals("Café, upstairs", e.location)
        assertEquals(1527836400000L, e.start!!.toEpochMillis(ZoneOffset.ofHours(5)))
        assertFalse(e.start!!.allDay)
    }

    @Test fun vevent_all_day_in_calendar_wrapper_with_folding() {
        val e = parse<QrPayload.Event>("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Long\r\n  name\r\nDTSTART;VALUE=DATE:20250102\r\nDURATION:P1D\r\nEND:VEVENT\r\nEND:VCALENDAR")
        assertEquals("Long name", e.summary)
        assertTrue(e.start!!.allDay)
        assertEquals(IcsTime(2025, 1, 3), e.end)
    }

    @Test fun vevent_tzid_and_duration() {
        val e = parse<QrPayload.Event>("BEGIN:VEVENT\nSUMMARY:Call\nDTSTART;TZID=Europe/Berlin:20250701T090000\nDURATION:PT1H30M\nEND:VEVENT")
        assertEquals("Europe/Berlin", e.start!!.tzid)
        assertEquals(10, e.end!!.hour)
        assertEquals(30, e.end!!.minute)
        // 09:00 in Berlin (summer) is 07:00 UTC, whatever the phone's zone.
        assertEquals(java.time.Instant.parse("2025-07-01T07:00:00Z").toEpochMilli(), e.start!!.toEpochMillis(ZoneOffset.UTC))
    }

    @Test fun vevent_with_a_date_that_does_not_exist_is_text() {
        assertTrue(QrParser.parse("BEGIN:VEVENT\nSUMMARY:X\nDTSTART:20260231\nEND:VEVENT") is QrPayload.Text)
        assertTrue(QrParser.parse("BEGIN:VEVENT\nSUMMARY:X\nDTSTART:20260431T090000\nEND:VEVENT") is QrPayload.Text)
        assertTrue(QrParser.parse("BEGIN:VEVENT\nSUMMARY:X\nDTSTART:20250229\nEND:VEVENT") is QrPayload.Text)
        assertTrue(QrParser.parse("BEGIN:VEVENT\nSUMMARY:X\nDTSTART:20260101\nDTEND:20260230\nEND:VEVENT") is QrPayload.Text)
        assertNull(QrParser.icsTime("20261301"))
        assertNull(QrParser.icsTime("20260100"))
        assertEquals(IcsTime(2024, 2, 29), QrParser.icsTime("20240229"))
        // Built by hand, a date that doesn't exist gives no time rather than a crash.
        assertNull(IcsTime(2026, 2, 31).toEpochMillis(ZoneOffset.UTC))
        assertNull(IcsTime(2026, 4, 31, 9).toEpochMillis(ZoneOffset.UTC))
    }

    // ---------------------------------------------------------------- URLs and text

    @Test fun url() {
        val u = parse<QrPayload.Url>("https://example.com/path?x=1")
        assertEquals("example.com", u.info.displayHost)
        assertTrue(u.info.warnings.isEmpty())
        assertTrue(parse<QrPayload.Url>("HTTP://EXAMPLE.COM").info.warnings.contains(UrlSafety.Warning.NOT_HTTPS))
    }

    @Test fun dangerous_schemes_are_just_text() {
        listOf("javascript:alert(1)", "intent://x#Intent;scheme=http;end", "file:///sdcard/x", "content://x/y", "market://details?id=x").forEach {
            assertTrue(it, QrParser.parse(it) is QrPayload.Text)
        }
    }

    @Test fun plain_and_huge_text() {
        assertTrue(QrParser.parse("Hello world") is QrPayload.Text)
        assertTrue(QrParser.parse("") is QrPayload.Text)
        val big = QrParser.parse("x".repeat(QrParser.MAX_INPUT + 10)) as QrPayload.Text
        assertTrue(big.truncated)
        assertEquals(QrParser.MAX_INPUT, big.raw.length)
    }

    @Test fun multi_line_link_is_text() {
        assertTrue(QrParser.parse("https://example.com\nand a caption") is QrPayload.Text)
    }
}
