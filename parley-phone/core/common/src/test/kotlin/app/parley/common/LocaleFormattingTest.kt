package app.parley.common

import app.parley.common.history.CallExport
import app.parley.common.record.Col
import app.parley.common.record.Mime
import app.parley.common.vcard.VCardStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** Stored, exported and exchanged values never follow the app language (Arabic writes non-ASCII digits). */
class LocaleFormattingTest {
    private lateinit var saved: Locale

    @Before fun arabic() {
        saved = Locale.getDefault()
        // Arabic with Arabic-Indic digits, as Android formats it (the JVM's plain "ar" may use Latin digits).
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        // The default locale must really localise digits, or these tests prove nothing.
        assertFalse("%d".format(12).all { it in '0'..'9' })
    }

    @After fun restore() = Locale.setDefault(saved)

    private fun ascii(s: String) = assertTrue("non-ASCII digits in \"$s\"", s.all { it.code < 128 })

    @Test fun event_dates_are_ascii() {
        assertEquals("1990-03-12", EventDate(1990, 3, 12).format())
        assertEquals("--03-12", EventDate(null, 3, 12).format())
    }

    @Test fun export_durations_are_ascii() {
        assertEquals("1:02:03", CallExport.hms(3723))
    }

    @Test fun vcard_import_dates_are_ascii() {
        val list = VCardStream.readAll("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Old\r\nBDAY:1970-01-02\r\nEND:VCARD\r\n").first
        val bday = list.single().raws.flatMap { it.rows }.single { it.mimeType == Mime.EVENT }[Col.D1]
        assertEquals("1970-01-02", bday)
    }

    @Test fun file_names_and_fingerprints_are_ascii() {
        ascii(app.parley.common.calls.VoicemailFiles.shareName(2026, 9, 24, 14, 32, "audio/amr"))
        assertEquals("voicemail-2026-09-24-1432.amr", app.parley.common.calls.VoicemailFiles.shareName(2026, 9, 24, 14, 32, "audio/amr"))
        ascii(app.parley.common.spam.ListPack.sha256Hex(byteArrayOf(1, 2, 3)))
        ascii(app.parley.common.backup.RetentionDecider.fileName(java.time.Instant.ofEpochSecond(1_790_000_000), java.time.ZoneOffset.UTC))
    }
}
