package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pronouns travel as vCard 4.0's PRONOUNS (RFC 9554) and come back into Parley's own row. */
class PronounsVCardTest {
    @Test fun pronouns_round_trip_as_the_rfc_9554_property() {
        val r = record(
            "Alex Kim",
            row(Mime.NAME, Col.D1 to "Alex Kim", Col.D2 to "Alex", Col.D3 to "Kim"),
            row(Mime.PRONOUNS, Col.D1 to "they/them"),
            row(Mime.PHONE, Col.D1 to "+44 7700 900123", Col.D2 to "2"),
        )
        val text = unfolded(r)
        assertTrue(text, text.contains("PRONOUNS:they/them"))
        assertTrue(text, !text.contains("X-ANDROID-CUSTOM"))
        val back = assertLossless(r)
        assertEquals("they/them", back.rows(Mime.PRONOUNS).single()[Col.D1])
    }

    @Test fun a_card_from_another_app_with_pronouns_is_read() {
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Sam Lee\r\nPRONOUNS;LANGUAGE=en;PREF=1:she/her\r\nEND:VCARD\r\n"
        val r = VCardStream.readAll(card).first.single()
        assertEquals("she/her", r.rows(Mime.PRONOUNS).single()[Col.D1])
    }

    @Test fun pronouns_are_not_an_other_field() {
        assertTrue(Mime.PRONOUNS in Mime.CORE)
    }
}
