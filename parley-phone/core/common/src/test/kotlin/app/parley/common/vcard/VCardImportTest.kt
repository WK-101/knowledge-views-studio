package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Real-world dialects: Apple, Google, Android's 2.1 composer, and broken input. */
class VCardImportTest {

    private fun crlf(text: String) = text.trimIndent().replace("\n", "\r\n") + "\r\n"

    private fun import(text: String) = VCardStream.readAll(crlf(text))

    private val d = '$'

    @Test fun apple_x_ablabel_card() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            PRODID:-//Apple Inc.//iPhone OS 17.0//EN
            N:Appleseed;Johnny;;;
            FN:Johnny Appleseed
            ORG:Apple Inc.;
            TITLE:Farmer
            item1.EMAIL;type=INTERNET;type=pref:johnny@example.com
            item1.X-ABLabel:_$d!<Other>!${d}_
            EMAIL;type=INTERNET;type=HOME:home@example.com
            item2.TEL:+1 (555) 010-0100
            item2.X-ABLabel:Orchard line
            TEL;type=CELL;type=VOICE;type=pref:+1 (555) 010-0101
            TEL;type=IPHONE;type=CELL;type=VOICE:+1 555 010 0102
            item7.TEL:+1 555 010 0103
            item7.X-ABLabel:_$d!<HomeFAX>!${d}_
            item3.ADR;type=HOME;type=pref:;;1 Infinite Loop;Cupertino;CA;95014;United States
            item3.X-ABADR:us
            item4.URL;type=pref:https://example.com
            item4.X-ABLabel:_$d!<HomePage>!${d}_
            item5.X-ABDATE;type=pref:2004-06-06
            item5.X-ABLabel:_$d!<Anniversary>!${d}_
            item6.X-ABRELATEDNAMES;type=pref:Jane Appleseed
            item6.X-ABLabel:_$d!<Spouse>!${d}_
            item8.X-ABRELATEDNAMES:Kid Appleseed
            item8.X-ABLabel:_$d!<Child>!${d}_
            item9.X-ABRELATEDNAMES:Coach Carter
            item9.X-ABLabel:Coach
            IMPP;X-SERVICE-TYPE=Skype;type=HOME;type=pref:skype:johnny.apple
            IMPP;X-SERVICE-TYPE=Signal:x-apple:+15550100
            BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-12
            X-PHONETIC-FIRST-NAME:Jonny
            X-PHONETIC-LAST-NAME:Apple Seed
            X-SOCIALPROFILE;type=twitter:https://twitter.com/johnny
            END:VCARD
            """,
        )
        assertTrue(report.failures.isEmpty())
        assertEquals(mapOf("X-SOCIALPROFILE" to 1), report.unmappedProperties)
        val r = list.single()
        assertEquals("Johnny Appleseed", r.displayName)
        val name = r.rows(Mime.NAME).single()
        assertEquals(listOf("Johnny", "Appleseed", "Jonny", "Apple Seed"), listOf(name[Col.D2], name[Col.D3], name[Col.D7], name[Col.D9]))

        val emails = r.rows(Mime.EMAIL)
        assertEquals(listOf("3" to true, "1" to false), emails.map { it[Col.D2] to it.isPrimary })

        val phones = r.rows(Mime.PHONE)
        assertEquals(listOf("+1 (555) 010-0100", "+1 (555) 010-0101", "+1 555 010 0102", "+1 555 010 0103"), phones.map { it[Col.D1] })
        assertEquals(listOf("0", "2", "2", "5"), phones.map { it[Col.D2] })
        assertEquals("Orchard line", phones[0][Col.D3])
        assertEquals(listOf(false, true, false, false), phones.map { it.isPrimary })

        val adr = r.rows(Mime.POSTAL).single()
        assertEquals(listOf("1 Infinite Loop", "Cupertino", "CA", "95014", "United States", "1"), listOf(Col.D4, Col.D7, Col.D8, Col.D9, Col.D10, Col.D2).map { adr[it] })

        val org = r.rows(Mime.ORG).single()
        assertEquals("Apple Inc." to "Farmer", org[Col.D1] to org[Col.D4])
        assertEquals("1", r.rows(Mime.WEBSITE).single()[Col.D2])
        assertEquals(setOf("3" to "--03-12", "1" to "2004-06-06"), r.rows(Mime.EVENT).map { it[Col.D2] to it[Col.D1] }.toSet())
        assertEquals(listOf("14" to null, "3" to null, "0" to "Coach"), r.rows(Mime.RELATION).map { it[Col.D2] to it[Col.D3] })

        val im = r.rows(Mime.IM)
        assertEquals(listOf("3", "-1"), im.map { it[Col.D5] })
        assertEquals("johnny.apple", im[0][Col.D1])
        assertEquals("1", im[0][Col.D2])
        assertEquals("Signal" to "+15550100", im[1][Col.D6] to im[1][Col.D1])
    }

    @Test fun google_contacts_vcard_3_export() {
        val photo = fakeJpeg(2_000)
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Jane Doe
            N:Doe;Jane;Marie;Ms.;PhD
            NICKNAME:JD
            EMAIL;TYPE=INTERNET;TYPE=HOME:jane@example.com
            EMAIL;TYPE=INTERNET;TYPE=WORK:jane@work.example
            TEL;TYPE=CELL:+1 555-0100
            TEL;TYPE=WORK:+1 555-0101
            item1.TEL:+1 555-0102
            item1.X-ABLabel:Satellite
            ADR;TYPE=HOME:;;1 Main St;Springfield;IL;62701;USA
            item2.ADR:;;2 Side St;Shelbyville;;;
            item2.X-ABLabel:Cottage
            ORG:Example Corp;Engineering
            TITLE:Staff Engineer
            BDAY:1985-02-03
            item3.X-ABDATE:2010-06-12
            item3.X-ABLabel:_$d!<Anniversary>!${d}_
            item4.URL:https\://jane.example.com
            item4.X-ABLabel:_$d!<HomePage>!${d}_
            item5.X-ABRELATEDNAMES:John Doe
            item5.X-ABLabel:_$d!<Spouse>!${d}_
            IMPP:xmpp:jane@jabber.example
            NOTE:Met at the conference\, 2019\nSecond line
            CATEGORIES:myContacts,starred,Friends
            PHOTO;ENCODING=b;TYPE=JPEG:${Base64.getEncoder().encodeToString(photo)}
            END:VCARD
            """,
        )
        assertTrue(report.failures.isEmpty())
        assertTrue(report.unmappedProperties.toString(), report.unmappedProperties.isEmpty())
        val r = list.single()
        assertTrue(r.starred)
        val name = r.rows(Mime.NAME).single()
        assertEquals(listOf("Jane Doe", "Jane", "Doe", "Ms.", "Marie", "PhD"), listOf(Col.D1, Col.D2, Col.D3, Col.D4, Col.D5, Col.D6).map { name[it] })
        assertEquals("JD", r.rows(Mime.NICKNAME).single()[Col.D1])
        assertEquals(listOf("1", "2"), r.rows(Mime.EMAIL).map { it[Col.D2] })
        assertEquals(listOf("2", "3", "0"), r.rows(Mime.PHONE).map { it[Col.D2] })
        assertEquals("Satellite", r.rows(Mime.PHONE)[2][Col.D3])
        assertEquals(listOf("1" to null, "0" to "Cottage"), r.rows(Mime.POSTAL).map { it[Col.D2] to it[Col.D3] })
        val org = r.rows(Mime.ORG).single()
        assertEquals(listOf("Example Corp", "Engineering", "Staff Engineer"), listOf(org[Col.D1], org[Col.D5], org[Col.D4]))
        assertEquals(listOf("3" to "1985-02-03", "1" to "2010-06-12"), r.rows(Mime.EVENT).map { it[Col.D2] to it[Col.D1] })
        assertEquals("https://jane.example.com", r.rows(Mime.WEBSITE).single()[Col.D1])
        assertEquals("14", r.rows(Mime.RELATION).single()[Col.D2])
        assertEquals("7", r.rows(Mime.IM).single()[Col.D5])
        assertEquals("Met at the conference, 2019\nSecond line", r.rows(Mime.NOTE).single()[Col.D1])
        assertEquals(listOf("Friends"), r.rows(Mime.GROUP).map { it[Col.GROUP_TITLE] })
        assertArrayEquals(photo, r.rows(Mime.PHOTO).single().blob)
    }

    @Test fun vcard_2_1_quoted_printable_utf8_names() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:=C3=96zt=C3=BCrk;J=C3=BCrgen;;;
            FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:J=C3=BCrgen =C3=96zt=C3=BCrk
            TEL;CELL;PREF:+49 151 1234567
            TEL;HOME;VOICE:+49 30 123456
            TEL;WORK;FAX:+49 30 654321
            EMAIL;INTERNET:j@example.de
            ADR;HOME;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:;;Stra=C3=9Fe 1;M=C3=BCnchen;;80331;Deutschland
            NOTE;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Gr=C3=BC=C3=9Fe aus M=C3=BCnchen, eine sehr lange Notiz, die umgebro=
            chen wird
            X-ANDROID-CUSTOM;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:vnd.android.cursor.item/nickname;J=C3=BCrgi;1;;;;;;;;;;;;;
            END:VCARD
            BEGIN:VCARD
            VERSION:2.1
            N;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:M=FCller;J=F6rg
            FN;CHARSET=ISO-8859-1;ENCODING=QUOTED-PRINTABLE:J=F6rg M=FCller
            END:VCARD
            BEGIN:VCARD
            VERSION:2.1
            N;ENCODING=QUOTED-PRINTABLE:=E6=9D=8E;=E5=B0=8F=E9=BE=99
            END:VCARD
            """,
        )
        assertTrue(report.failures.isEmpty())
        assertEquals(3, list.size)
        val r = list[0]
        assertEquals("Jürgen Öztürk", r.displayName)
        val name = r.rows(Mime.NAME).single()
        assertEquals("Jürgen" to "Öztürk", name[Col.D2] to name[Col.D3])
        assertEquals(listOf("2" to true, "1" to false, "4" to false), r.rows(Mime.PHONE).map { it[Col.D2] to it.isPrimary })
        assertEquals("Straße 1" to "München", r.rows(Mime.POSTAL).single().let { it[Col.D4] to it[Col.D7] })
        assertEquals("Grüße aus München, eine sehr lange Notiz, die umgebrochen wird", r.rows(Mime.NOTE).single()[Col.D1])
        assertEquals("Jürgi", r.rows(Mime.NICKNAME).single()[Col.D1])

        assertEquals("Jörg Müller", list[1].displayName)
        assertEquals("小龙", list[2].rows(Mime.NAME).single()[Col.D2]) // no CHARSET: UTF-8 is assumed
        assertEquals("小龙 李", list[2].displayName)
    }

    @Test fun legacy_im_and_sip_properties() {
        val (list, report) = import(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Legacy
            X-AIM;TYPE=WORK:aimuser
            X-SKYPE-USERNAME:skypeuser
            X-JABBER:jab@example.org
            X-SIP:sip:legacy@sip.example
            IMPP;TYPE=work:sip:modern@sip.example
            END:VCARD
            """,
        )
        assertTrue(report.unmappedProperties.isEmpty())
        val r = list.single()
        assertEquals(listOf("0" to "2", "3" to "3", "7" to "3"), r.rows(Mime.IM).map { it[Col.D5] to it[Col.D2] })
        assertEquals(listOf("legacy@sip.example" to "3", "modern@sip.example" to "2"), r.rows(Mime.SIP).map { it[Col.D1] to it[Col.D2] })
    }

    @Test fun report_lists_failed_cards_and_unmapped_properties() {
        val text = crlf(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Good One
            GEO:geo:37.386013,-122.082932
            GENDER:F
            END:VCARD
            BEGIN:VCARD
            VERSION:4.0
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Second Good
            TZ:-05:00
            X-CUSTOM-THING:whatever
            END:VCARD
            BEGIN:VCARD
            VERSION:3.0
            FN:Cut off
            TEL:123
            """,
        )
        val (list, report) = VCardStream.readAll(text)
        assertEquals(listOf("Good One", "Second Good"), list.map { it.displayName })
        assertEquals(2, report.cardsParsed)
        assertEquals(listOf(2, 4), report.failures.map { it.index })
        assertEquals("The card is empty.", report.failures[0].reason)
        assertTrue(report.failures[1].reason.contains("END:VCARD"))
        assertTrue(report.failures[1].snippet.contains("FN:Cut off"))
        assertEquals(mapOf("GEO" to 1, "GENDER" to 1, "TZ" to 1, "X-CUSTOM-THING" to 1), report.unmappedProperties)
        assertTrue(report.summary(), report.summary().contains("2 failed"))
    }

    @Test fun not_a_vcard_file_is_reported() {
        val (list, report) = VCardStream.readAll("name,phone\r\nBob,123\r\n")
        assertTrue(list.isEmpty())
        assertEquals(1, report.failures.size)
    }

    @Test fun utf8_bom_and_lf_line_endings() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "BEGIN:VCARD\nVERSION:3.0\nFN:Zoë\nEND:VCARD\n".toByteArray()
        val report = ImportReportBuilder()
        val names = ArrayList<String>()
        VCardStream.read(VCardStream.reader(bytes.inputStream()), report) { names += it.record.displayName }
        assertEquals(listOf("Zoë"), names)
    }

    @Test fun fn_only_card_gets_a_name_row() {
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:4.0
            FN:Only Formatted
            TEL:123
            END:VCARD
            """,
        )
        assertEquals("Only Formatted", list.single().rows(Mime.NAME).single()[Col.D1])
    }

    @Test fun uid_becomes_the_key_and_missing_uid_is_empty() {
        val (list, _) = import(
            """
            BEGIN:VCARD
            VERSION:4.0
            UID:urn:uuid:4fbe8971-0bc3-424c-9c26-36c3e1eff6b1
            FN:A
            END:VCARD
            BEGIN:VCARD
            VERSION:4.0
            FN:B
            END:VCARD
            """,
        )
        assertEquals("urn:uuid:4fbe8971-0bc3-424c-9c26-36c3e1eff6b1", list[0].key)
        assertEquals("", list[1].key)
        assertFalse(list[1].starred)
        assertNull(list[1].customRingtone)
    }
}
