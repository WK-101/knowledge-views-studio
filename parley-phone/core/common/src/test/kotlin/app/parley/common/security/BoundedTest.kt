package app.parley.common.security

import app.parley.common.backup.BackupArchiveReader
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.spam.ListPack
import app.parley.common.spam.PackException
import app.parley.common.templates.RuleTemplates
import app.parley.common.templates.TemplateException
import app.parley.common.vcard.ContactCsv
import app.parley.common.vcard.ImportReportBuilder
import app.parley.common.vcard.VCardStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.util.Base64
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundedTest {
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    /** An endless stream of one byte, to prove a reader stops on its own. */
    private fun endless(b: Int = 'a'.code) = object : InputStream() {
        override fun read(): Int = b
        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            buf.fill(b.toByte(), off, off + len)
            return len
        }
    }

    private fun endlessText(c: Char) = object : Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            cbuf.fill(c, off, off + len)
            return len
        }
        override fun close() = Unit
    }

    @Test fun reads_up_to_the_cap_and_refuses_more() {
        assertArrayEquals(ByteArray(100) { 1 }, Bounded.readBytes(ByteArray(100) { 1 }.inputStream(), 100))
        assertThrows(LimitExceededException::class.java) { Bounded.readBytes(ByteArray(101).inputStream(), 100) }
        assertThrows(LimitExceededException::class.java) { Bounded.readBytes(endless(), 1L shl 20) }
    }

    @Test fun limited_stream_throws_past_the_cap() {
        val s = Bounded.stream(endless(), 1000)
        assertThrows(LimitExceededException::class.java) { s.readBytes() }
    }

    @Test fun gunzip_bomb_is_refused() {
        // 50 MB of zeros compresses to about 50 KB: far beyond both the cap and the ratio.
        val bomb = gzip(ByteArray(50 shl 20))
        assertThrows(LimitExceededException::class.java) { Bounded.gunzip(bomb, Bounded.Caps.QR_GUNZIP) }
        // Within the cap but past the ratio (1 MB from a few KB).
        assertThrows(LimitExceededException::class.java) { Bounded.gunzip(gzip(ByteArray(1 shl 20)), 8L shl 20) }
        val text = "Ada Lovelace +44 20 7946 0000".encodeToByteArray()
        assertArrayEquals(text, Bounded.gunzip(gzip(text), Bounded.Caps.QR_GUNZIP))
        assertThrows(IOException::class.java) { Bounded.gunzip(byteArrayOf(1, 2, 3), 1000) }
    }

    @Test fun unzip_caps_entries_sizes_and_ratio() {
        val two = zip("a.txt" to "hello".encodeToByteArray(), "dir/b.txt" to "x".encodeToByteArray())
        val ok = Bounded.unzip(two.inputStream(), { it == "a.txt" }, 10, 1000, 1000)
        assertEquals(setOf("a.txt"), ok.keys)
        val many = zip(*Array(20) { "f$it" to ByteArray(1) })
        assertThrows(LimitExceededException::class.java) { Bounded.unzip(many.inputStream(), { true }, 10, 1000, 1000) }
        val big = zip("a" to ByteArray(2000) { it.toByte() })
        assertThrows(LimitExceededException::class.java) { Bounded.unzip(big.inputStream(), { true }, 10, 1000, 10_000) }
        val bomb = zip("a" to ByteArray(20 shl 20))
        assertThrows(LimitExceededException::class.java) { Bounded.unzip(bomb.inputStream(), { true }, 10, 64L shl 20, 64L shl 20) }
    }

    @Test fun line_reader_splits_like_readLine_and_caps_line_length() {
        val r = Bounded.LineReader("a\r\nb\rc\n\nd".reader())
        assertEquals(listOf("a", "b", "c", "", "d"), r.lineSequence().toList())
        assertNull(Bounded.LineReader("".reader()).readLine())
        assertThrows(LimitExceededException::class.java) { Bounded.LineReader(endlessText('x'), maxLine = 10_000).readLine() }
        assertThrows(LimitExceededException::class.java) { Bounded.LineReader("1\n2\n3\n".reader(), maxLines = 2).lineSequence().toList() }
    }

    @Test fun vcard_reader_refuses_an_endless_line_or_card() {
        assertThrows(LimitExceededException::class.java) { VCardStream.read(endlessText('x'), ImportReportBuilder()) {} }
        val card = object : Reader() {
            var sent = false
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                val text = if (!sent) "BEGIN:VCARD\r\n".also { sent = true } else "NOTE:" + "y".repeat(1000) + "\r\n"
                val n = minOf(len, text.length)
                text.toCharArray(0, n).copyInto(cbuf, off)
                return n
            }
            override fun close() = Unit
        }
        assertThrows(LimitExceededException::class.java) { VCardStream.read(card, ImportReportBuilder()) {} }
        // An ordinary file still reads.
        val (records, _) = VCardStream.readAll("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Ada\r\nTEL:+441234567890\r\nEND:VCARD\r\n")
        assertEquals(1, records.size)
    }

    @Test fun csv_parser_refuses_an_endless_quoted_cell() {
        val endlessQuoted = object : Reader() {
            var first = true
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (first) { first = false; cbuf[off] = '"'; return 1 }
                cbuf.fill('z', off, off + len)
                return len
            }
            override fun close() = Unit
        }
        assertThrows(LimitExceededException::class.java) { ContactCsv.parse(endlessQuoted).toList() }
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), ContactCsv.parse("a,b\nc,d\n".reader()).toList())
    }

    @Test fun pack_zip_bomb_is_refused() {
        val bomb = zip(ListPack.NUMBERS to ByteArray(100 shl 20), ListPack.MANIFEST to "{}".encodeToByteArray())
        val e = assertThrows(PackException::class.java) { ListPack.parse(bomb) }
        assertTrue(e.message!!.contains("too large"))
        assertThrows(PackException::class.java) { ListPack.parse(ByteArray(10) { 7 }) }
    }

    @Test fun backup_zip_bomb_is_refused() {
        val bomb = zip("contacts.jsonl" to ByteArray(200 shl 20))
        assertThrows(BackupIntegrityException::class.java) { BackupArchiveReader.open(bomb) }
    }

    @Test fun template_link_bomb_is_refused() {
        val zipped = gzip(ByteArray(8 shl 20))
        val link = "parley://template?d=" + Base64.getUrlEncoder().withoutPadding().encodeToString(zipped)
        assertThrows(TemplateException::class.java) { RuleTemplates.fromLink(link) }
    }
}
