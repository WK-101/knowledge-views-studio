package app.parley.common.qr

import app.parley.common.qr.ScannedCard.Flag
import app.parley.common.vcard.VCardStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedCardTest {
    private val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Stranger\nN:;Stranger\nTEL:+12125551212\nCATEGORIES:Family,VIP\n" +
        "X-PARLEY-STARRED:1\nX-PARLEY-SEND-TO-VOICEMAIL:1\nX-PARLEY-RINGTONE:content://media/x\nEND:VCARD"

    private fun record() = (QrParser.parse(card) as QrPayload.Contact).records.single()

    @Test fun control_fields_are_found() {
        val r = record()
        assertEquals(setOf(Flag.STARRED, Flag.VOICEMAIL, Flag.RINGTONE, Flag.LABELS), ScannedCard.flags(r))
        assertEquals(listOf("Family", "VIP"), ScannedCard.labels(r))
    }

    @Test fun stripped_by_default() {
        val s = ScannedCard.strip(record(), emptySet())
        assertFalse(s.starred)
        assertFalse(s.sendToVoicemail)
        assertNull(s.customRingtone)
        assertTrue(ScannedCard.flags(s).isEmpty())
        // The details stay.
        assertTrue(s.raws.flatMap { it.rows }.any { it[app.parley.common.record.Col.D1] == "+12125551212" })
    }

    @Test fun only_ticked_flags_are_kept() {
        val s = ScannedCard.strip(record(), setOf(Flag.STARRED))
        assertTrue(s.starred)
        assertFalse(s.sendToVoicemail)
        assertTrue(ScannedCard.labels(s).isEmpty())
    }

    @Test fun categories_starred_counts_as_a_favourite() {
        val r = (QrParser.parse("BEGIN:VCARD\nVERSION:3.0\nFN:A\nCATEGORIES:starred\nEND:VCARD") as QrPayload.Contact).records.single()
        assertTrue(Flag.STARRED in ScannedCard.flags(r))
        assertFalse(ScannedCard.strip(r, emptySet()).starred)
    }

    @Test fun vcard_to_import_drops_what_wasnt_ticked() {
        val records = listOf(record())
        val out = ScannedCard.vcardToImport(records, card, emptySet())
        val back = VCardStream.readAll(out).first.single()
        assertFalse(back.starred)
        assertFalse(back.sendToVoicemail)
        assertNull(back.customRingtone)
        assertTrue(ScannedCard.labels(back).isEmpty())
        assertEquals("Stranger", back.displayName)
        // Everything ticked (or nothing to drop): the card as scanned.
        assertSame(card, ScannedCard.vcardToImport(records, card, Flag.entries.toSet()))
        val plain = "BEGIN:VCARD\nVERSION:3.0\nFN:B\nEND:VCARD"
        assertSame(plain, ScannedCard.vcardToImport((QrParser.parse(plain) as QrPayload.Contact).records, plain, emptySet()))
    }
}
