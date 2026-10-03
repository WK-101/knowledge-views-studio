package app.parley.common.fuzz

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackException
import app.parley.common.vcard.VCardStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the parser fuzzing found, pinned as plain tests. */
class FuzzFindingsTest {
    @Test fun aMalformedPrefParameterKeepsTheCard() {
        val text = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nTEL;TYPE=WORK;PREF=1E:020 7946 0001\r\nEND:VCARD\r\n"
        val (records, report) = VCardStream.readAll(text)
        assertEquals(report.failures.toString(), 1, records.size)
        val phone = records.single().raws.single().rows.single { it.mimeType == Mime.PHONE }
        assertEquals("020 7946 0001", phone.values[Col.D1])
    }

    @Test fun anImpossibleDateWithoutAYearStillExports() {
        val record = ContactRecord(
            key = "k", displayName = "Grace",
            raws = listOf(RawRecord(null, null, rows = listOf(DataRow(Mime.EVENT, linkedMapOf(Col.D1 to "--43-15", Col.D2 to "1"))))),
        )
        val text = VCardStream.writeAll(listOf(record))
        assertTrue(text, text.contains("--43-15"))
        assertEquals(1, VCardStream.readAll(text).first.size)
    }

    @Test fun aListWithAnUnreadableEntryNameIsDamagedNotAnError() {
        // ZipInputStream throws IllegalArgumentException for a name that isn't UTF-8; it reads as a damaged list.
        val zip = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry() }
        }.toByteArray()
        val at = String(zip, Charsets.ISO_8859_1).indexOf("manifest.json")
        zip[at] = 0xC3.toByte()
        zip[at + 1] = 0x28
        val e = assertThrows(PackException::class.java) { ListPack.parse(zip) }
        assertEquals("Not a Parley list: the file is damaged", e.message)
    }
}
