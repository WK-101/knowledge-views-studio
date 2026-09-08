package com.cairn.reader.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class EpubExporterTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val zis = ZipInputStream(ByteArrayInputStream(bytes))
        var e = zis.nextEntry
        while (e != null) {
            if (!e.isDirectory) map[e.name] = zis.readBytes().toString(Charsets.UTF_8)
            e = zis.nextEntry
        }
        return map
    }

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

    @Test fun `every XML document is well-formed with no whitespace before the prolog`() {
        val files = entries(build())
        val xmlDocs = listOf(
            "META-INF/container.xml", "OEBPS/content.opf", "OEBPS/toc.ncx",
            "OEBPS/nav.xhtml", "OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml",
        )
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for (name in xmlDocs) {
            val doc = files[name] ?: error("missing $name")
            // The prolog must be the very first thing — a leading space is fatal per the XML spec
            // (and EPUBCheck). This is the exact regression this test guards.
            assertTrue("$name must start with the XML declaration, no leading whitespace", doc.startsWith("<?xml"))
            // And it must actually parse — "content is not allowed in prolog" would throw here.
            factory.newDocumentBuilder().parse(ByteArrayInputStream(doc.toByteArray(Charsets.UTF_8)))
        }
    }
}
