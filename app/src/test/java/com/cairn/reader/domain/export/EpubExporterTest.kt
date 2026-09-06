package com.cairn.reader.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class EpubExporterTest {

    private fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        EpubExporter.write(
            out,
            bookTitle = "My Library",
            chapters = listOf(
                EpubExporter.Chapter("First & Foremost", "Ada", "<p>Hello <b>world</b></p>", "https://x.com/1"),
                EpubExporter.Chapter("Second", null, "<h2>Sub</h2><p>Body</p>", null),
            ),
            author = "Cairn",
        )
        return out.toByteArray()
    }

    @Test fun `mimetype is the first entry, stored uncompressed, with the exact media type`() {
        val zis = ZipInputStream(ByteArrayInputStream(build()))
        val first = zis.nextEntry!!
        assertEquals("mimetype", first.name)
        assertEquals("first entry must be STORED", ZipEntry.STORED.toLong(), first.method.toLong())
        assertEquals("application/epub+zip", zis.readBytes().toString(Charsets.US_ASCII))
    }

    @Test fun `archive contains all required OCF and content documents`() {
        val names = mutableListOf<String>()
        val zis = ZipInputStream(ByteArrayInputStream(build()))
        var e = zis.nextEntry
        while (e != null) { names += e.name; e = zis.nextEntry }
        assertTrue(names.contains("META-INF/container.xml"))
        assertTrue(names.contains("OEBPS/content.opf"))
        assertTrue(names.contains("OEBPS/nav.xhtml"))
        assertTrue(names.contains("OEBPS/toc.ncx"))
        assertTrue(names.contains("OEBPS/ch1.xhtml"))
        assertTrue(names.contains("OEBPS/ch2.xhtml"))
    }

    @Test fun `chapter xhtml escapes special characters in the title`() {
        val zis = ZipInputStream(ByteArrayInputStream(build()))
        var e = zis.nextEntry
        var ch1 = ""
        while (e != null) {
            if (e.name == "OEBPS/ch1.xhtml") ch1 = zis.readBytes().toString(Charsets.UTF_8)
            e = zis.nextEntry
        }
        assertTrue("ampersand escaped", ch1.contains("First &amp; Foremost"))
        assertTrue("well-formed xhtml root", ch1.contains("<html xmlns=\"http://www.w3.org/1999/xhtml\""))
    }
}
