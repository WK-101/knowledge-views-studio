package app.parley.common.vcard

import app.parley.common.qr.ScannedCard
import app.parley.common.record.Col
import app.parley.common.record.Mime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader

/** A plain file can't plant a hidden or trusted contact; the first look at a file stays bounded. */
class ImportGuardTest {
    private val planted = """
        BEGIN:VCARD
        VERSION:4.0
        FN:Bank Fraud Team
        TEL:+44 20 7946 0999
        X-PARLEY-ARCHIVED:1
        X-PARLEY-PRIVATE:0
        item1.X-PARLEY-STARRED:1
        X-PARLEY-SEND-TO-VOICEMAIL:1
        X-PARLEY-RINGTONE:content://media/external/audio/media/7
        X-ANDROID-CUSTOM:vnd.android.cursor.item/vnd.com.whatsapp.profile;+447700900999;;;;;;;;;;;;;;
        X-ANDROID-CUSTOM:vnd.com.google.cursor.item/contact_user_defined_field;Shoe size;38;;;;;;;;;;;;;
        END:VCARD
        BEGIN:VCARD
        VERSION:4.0
        FN:Grace
        X-PARLEY-PRIVATE:1
        CATEGORIES:Family,starred
        END:VCARD
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private fun parsed(text: String): List<ParsedCard> = ArrayList<ParsedCard>().also { out ->
        VCardStream.read(text.reader(), ImportReportBuilder()) { out += it }
    }

    @Test fun a_plain_card_loses_its_trust_flags_and_other_apps_rows() {
        val card = parsed(planted).first()
        val notes = card.notes!!.forImport(card.record, fromSealed = false, region = "GB")
        val g = ImportGuard.guard(card.record, notes, fromSealed = false)
        assertFalse(g.record.starred)
        assertFalse(g.record.sendToVoicemail)
        assertNull(g.record.customRingtone)
        assertFalse(g.notes!!.archived)
        val mimes = g.record.rows().map { it.mimeType }
        assertFalse(mimes.toString(), mimes.any { "whatsapp" in it })
        // Android's own kinds, Parley's and Google's custom field stay.
        assertTrue(mimes.toString(), mimes.size > 1 && mimes.all { ImportGuard.allowedKind(it) })
        assertTrue(ImportGuard.allowedKind(Mime.GOOGLE_CUSTOM_FIELD) && ImportGuard.allowedKind(Mime.PRONOUNS))
        assertFalse(ImportGuard.allowedKind("vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"))
        assertEquals("+44 20 7946 0999", g.record.rows(Mime.PHONE).single()[Col.D1])
        assertEquals(ImportGuard.Dropped(starred = 1, voicemail = 1, ringtone = 1, otherApps = 1), g.dropped)
    }

    @Test fun parleys_own_encrypted_file_keeps_them() {
        val card = parsed(planted).first()
        val notes = card.notes!!.forImport(card.record, fromSealed = true, region = "GB")
        val g = ImportGuard.guard(card.record, notes, fromSealed = true)
        assertTrue(g.record.starred && g.record.sendToVoicemail && g.notes!!.archived)
        assertTrue(g.record.rows().any { "whatsapp" in it.mimeType })
        assertTrue(g.dropped.isEmpty)
    }

    @Test fun flags_ticked_on_a_scanned_card_are_kept() {
        val card = parsed(planted).first()
        val g = ImportGuard.guard(card.record, card.notes, fromSealed = false, keep = setOf(ScannedCard.Flag.STARRED))
        assertTrue(g.record.starred)
        assertFalse(g.record.sendToVoicemail)
        assertFalse(g.notes!!.archived)
    }

    @Test fun private_still_keeps_a_card_out_of_the_address_book() {
        val grace = parsed(planted)[1]
        val notes = grace.notes!!.forImport(grace.record, fromSealed = false, region = "GB")
        assertTrue(ImportGuard.guard(grace.record, notes, fromSealed = false).notes!!.private)
    }

    @Test fun the_scan_lists_what_a_plain_import_leaves_out() {
        val scan = ImportGuard.scanVCards(planted.reader())
        assertEquals(2, scan.entries)
        assertEquals(1, scan.private)
        assertFalse(scan.capped)
        assertEquals(ImportGuard.Dropped(archived = 1, starred = 2, voicemail = 1, ringtone = 1, otherApps = 1), scan.dropped)
        assertEquals(ImportGuard.Dropped(archived = 1, voicemail = 1, ringtone = 1, otherApps = 1), scan.dropped.without(setOf(ScannedCard.Flag.STARRED)))
    }

    @Test fun parleys_csv_archived_column_is_counted() {
        val csv = "Name,Phone,Archived\r\nAna,+441,1\r\nBen,+442,\r\n\"Cy\",\"+443\",\"1\"\r\n"
        val scan = ImportGuard.scanLines(csv.reader())
        assertEquals(4, scan.entries)
        assertEquals(1 + 1, scan.dropped.archived)
    }

    /** An endless stream: one line that never ends, or endless short lines. */
    private class Endless(private val c: Char, private val newlineEvery: Int) : Reader() {
        var served = 0L

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            for (i in 0 until len) cbuf[off + i] = if (newlineEvery > 0 && (served + i) % newlineEvery == 0L) '\n' else c
            served += len
            return len
        }

        override fun close() = Unit
    }

    @Test fun an_endless_line_stops_the_scan_without_filling_the_memory() {
        val r = Endless('A', 0)
        val scan = ImportGuard.scanVCards(r)
        assertTrue(scan.capped)
        assertEquals(0, scan.entries)
        // Gave up at the line cap, not at the end of a stream that never ends.
        assertTrue(r.served < 4L shl 20)
    }

    @Test fun endless_short_lines_stop_at_the_character_cap() {
        val r = Endless('B', 80)
        val scan = ImportGuard.scanLines(r, maxChars = 1L shl 20)
        assertTrue(scan.capped)
        assertTrue(r.served < 2L shl 20)
    }
}
