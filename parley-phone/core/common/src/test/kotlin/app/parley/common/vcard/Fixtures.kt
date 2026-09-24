package app.parley.common.vcard

import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.DataRow
import app.parley.common.record.Mime
import app.parley.common.record.RawRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.random.Random

internal fun row(mime: String, vararg pairs: Pair<String, String?>, primary: Boolean = false, blob: ByteArray? = null) =
    DataRow(mime, linkedMapOf(*pairs), blob, isPrimary = primary, isSuperPrimary = primary)

internal fun record(displayName: String, vararg rows: DataRow, key: String = "lookup-key-1") =
    ContactRecord(key = key, displayName = displayName, raws = listOf(RawRecord("com.google", "me@example.com", rows = rows.toList())))

internal fun ContactRecord.rows(): List<DataRow> = raws.flatMap { it.rows }

internal fun ContactRecord.rows(mime: String): List<DataRow> = rows().filter { it.mimeType == mime }

/** A JPEG-looking blob of [size] random bytes. */
internal fun fakeJpeg(size: Int, seed: Int = 7): ByteArray {
    val b = Random(seed).nextBytes(size)
    b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte()
    return b
}

/** vCard text with folded lines joined again, for `contains` checks. */
internal fun unfolded(r: ContactRecord, titles: Map<Long, String> = emptyMap()) = VCardStream.writeAll(listOf(r), titles).replace("\r\n ", "")

internal fun roundTrip(r: ContactRecord, titles: Map<Long, String> = emptyMap()): ContactRecord {
    val text = VCardStream.writeAll(listOf(r), titles)
    val (list, report) = VCardStream.readAll(text)
    assertTrue("failures: ${report.failures}\n$text", report.failures.isEmpty())
    assertEquals("unmapped: ${report.unmappedProperties}\n$text", emptyMap<String, Int>(), report.unmappedProperties)
    assertEquals(1, list.size)
    return list.single()
}

internal fun assertLossless(r: ContactRecord, titles: Map<Long, String> = emptyMap()): ContactRecord {
    val expected = VCardMapper.canonical(r, titles)
    val actual = roundTrip(r, titles)
    assertEquals(describe(expected), describe(actual))
    assertEquals(expected, actual)
    return actual
}

/** Readable dump (blobs as size + hash) so assertion failures show a useful diff. */
internal fun describe(r: ContactRecord): String = buildString {
    appendLine("key=${r.key} name=${r.displayName} starred=${r.starred} ringtone=${r.customRingtone} vm=${r.sendToVoicemail}")
    r.raws.forEach { raw ->
        appendLine("raw ${raw.accountType}/${raw.accountName}")
        raw.rows.forEach { d ->
            appendLine("  ${d.mimeType.substringAfterLast('/')} ${d.values.toSortedMap()} p=${d.isPrimary}/${d.isSuperPrimary}" + (d.blob?.let { " blob=${it.size}#${it.contentHashCode()}" } ?: ""))
        }
    }
}

internal object Rich {
    val photo = fakeJpeg(48_000)
    val customBlob = byteArrayOf(0, 1, 2, 3, 127, -128, -1)
    val groupTitles = mapOf(12L to "Friends, close")

    /** A contact using every kind Android has, with awkward characters everywhere. */
    fun record(): ContactRecord = ContactRecord(
        key = "0r1-2A3B4C",
        displayName = "Dr. Jane Quincy Doe-Smith Jr.",
        starred = true,
        customRingtone = "content://media/internal/audio/media/42",
        sendToVoicemail = true,
        raws = listOf(
            RawRecord(
                "com.google", "jane@gmail.com", dataSet = null, sourceId = "abc123",
                rows = listOf(
                    row(
                        Mime.NAME, Col.D1 to "Dr. Jane Quincy Doe-Smith Jr.", Col.D2 to "Jane", Col.D3 to "Doe-Smith",
                        Col.D4 to "Dr.", Col.D5 to "Quincy Anne", Col.D6 to "Jr.", Col.D7 to "ジェーン", Col.D8 to "キュー",
                        Col.D9 to "ドウ", Col.D10 to "40", Col.D11 to "3",
                    ),
                    row(Mime.NICKNAME, Col.D1 to "JJ", Col.D2 to "1"),
                    row(Mime.NICKNAME, Col.D1 to "Janie", Col.D2 to "4"),
                    row(Mime.NICKNAME, Col.D1 to "Smithy, the great", Col.D2 to "0", Col.D3 to "Old school"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0100", Col.D2 to "2", Col.D4 to "+15550100", primary = true),
                    row(Mime.PHONE, Col.D1 to "+1 555 0101", Col.D2 to "3"),
                    row(Mime.PHONE, Col.D1 to "+881 1234 5678", Col.D2 to "0", Col.D3 to "Satellite; roof"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0110", Col.D2 to "10"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0116", Col.D2 to "16"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0117", Col.D2 to "17"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0120", Col.D2 to "20"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0108,123#", Col.D2 to "8"),
                    row(Mime.PHONE, Col.D1 to "+1 555 0199", Col.D2 to "101", Col.D3 to "Walkie"),
                    row(Mime.EMAIL, Col.D1 to "jane@home.example", Col.D2 to "1"),
                    row(Mime.EMAIL, Col.D1 to "jane@work.example", Col.D2 to "2", Col.D4 to "Jane (Work)", primary = true),
                    row(Mime.EMAIL, Col.D1 to "jane@uni.example", Col.D2 to "0", Col.D3 to "Uni"),
                    row(Mime.EMAIL, Col.D1 to "jane@phone.example", Col.D2 to "4"),
                    row(
                        Mime.POSTAL, Col.D1 to "PO Box 7, 12 Main St\nSpringfield, IL 62701\nUSA \"the big one\"", Col.D2 to "1",
                        Col.D4 to "12 Main St, Apt 4", Col.D5 to "PO Box 7", Col.D6 to "Old Town", Col.D7 to "Springfield",
                        Col.D8 to "IL", Col.D9 to "62701", Col.D10 to "USA",
                    ),
                    row(Mime.POSTAL, Col.D1 to "Unit 5; Industrial Park, Leeds", Col.D2 to "2"),
                    row(Mime.POSTAL, Col.D4 to "1 Beach Rd", Col.D7 to "Brighton", Col.D2 to "0", Col.D3 to "Holiday flat"),
                    row(
                        Mime.ORG, Col.D1 to "ACME, Inc.", Col.D2 to "1", Col.D4 to "Chief Widget Officer", Col.D5 to "R&D; Labs",
                        Col.D6 to "Makes widgets", Col.D7 to "ACME", Col.D8 to "アクメ", Col.D9 to "Building 4", Col.D10 to "4",
                    ),
                    row(Mime.ORG, Col.D1 to "Side Gig LLC", Col.D2 to "2", Col.D4 to "Founder"),
                    row(Mime.WEBSITE, Col.D1 to "https://jane.example.com", Col.D2 to "1"),
                    row(Mime.WEBSITE, Col.D1 to "https://blog.example.com/?a=1&b=2", Col.D2 to "2"),
                    row(Mime.WEBSITE, Col.D1 to "https://portfolio.example.com", Col.D2 to "0", Col.D3 to "Portfolio"),
                    row(Mime.WEBSITE, Col.D1 to "ftp://files.example.com", Col.D2 to "6"),
                    row(Mime.EVENT, Col.D1 to "1990-05-17", Col.D2 to "3"),
                    row(Mime.EVENT, Col.D1 to "--06-12", Col.D2 to "1"),
                    row(Mime.EVENT, Col.D1 to "2001-01-01", Col.D2 to "2"),
                    row(Mime.EVENT, Col.D1 to "2012-07-01", Col.D2 to "0", Col.D3 to "Graduation"),
                    row(Mime.IM, Col.D1 to "jane.doe", Col.D2 to "1", Col.D5 to "3"),
                    row(Mime.IM, Col.D1 to "jane@jabber.example", Col.D2 to "2", Col.D5 to "7"),
                    row(Mime.IM, Col.D1 to "jane@gmail.com", Col.D2 to "3", Col.D5 to "5"),
                    row(Mime.IM, Col.D1 to "+1 555 0100", Col.D2 to "0", Col.D3 to "Private", Col.D5 to "-1", Col.D6 to "Signal"),
                    row(Mime.IM, Col.D1 to "12345 678", Col.D2 to "3", Col.D5 to "4"),
                    row(Mime.RELATION, Col.D1 to "John Doe", Col.D2 to "14"),
                    row(Mime.RELATION, Col.D1 to "Bob Doe", Col.D2 to "2"),
                    row(Mime.RELATION, Col.D1 to "Mary Doe", Col.D2 to "8"),
                    row(Mime.RELATION, Col.D1 to "Ada, Countess", Col.D2 to "0", Col.D3 to "Mentor"),
                    row(Mime.SIP, Col.D1 to "jane@sip.example.com", Col.D2 to "2"),
                    row(Mime.NOTE, Col.D1 to "First note"),
                    row(Mime.NOTE, Col.D1 to "Line 1\nLine 2; with, punctuation \\ and a backslash"),
                    row(Mime.GROUP, Col.D1 to "12"),
                    row(Mime.GROUP, Col.GROUP_TITLE to "Book club"),
                    row(Mime.PHOTO, Col.D14 to "9001", blob = photo),
                    row(
                        "vnd.android.cursor.item/vnd.com.example.custom", Col.D1 to "x;y", Col.D2 to "a,b", Col.D5 to "line\nbreak \\ back",
                        blob = customBlob,
                    ),
                    row(Mime.IDENTITY, Col.D1 to "jane-id", Col.D2 to "com.example.ns"),
                ),
            ),
        ),
    )
}
