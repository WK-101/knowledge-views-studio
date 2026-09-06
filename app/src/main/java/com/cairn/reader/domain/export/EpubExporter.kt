package com.cairn.reader.domain.export

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a valid EPUB 3 (with an EPUB 2 NCX fallback for wide e-reader support) from saved articles,
 * using only java.util.zip and jsoup — no library, no network. The result is a portable .epub the
 * user can send to Kindle, Kobo, or any reader: your reading, in the open standard for books.
 */
object EpubExporter {

    /** One chapter of the book: a title and its cleaned article HTML. */
    data class Chapter(val title: String, val author: String?, val html: String?, val url: String?)

    private fun utcStamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    /** Write a complete EPUB containing [chapters] to [out]. */
    fun write(out: OutputStream, bookTitle: String, chapters: List<Chapter>, author: String? = null) {
        val uid = "urn:uuid:" + UUID.randomUUID()
        val modified = utcStamp()
        ZipOutputStream(out).use { zos ->
            // 1) The mimetype entry MUST be first and stored uncompressed, per the EPUB OCF spec.
            writeStored(zos, "mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
            zos.setMethod(ZipOutputStream.DEFLATED)

            // 2) OCF container pointing at the package document.
            writeText(zos, "META-INF/container.xml", CONTAINER)

            // 3) The chapters as XHTML.
            val files = chapters.mapIndexed { i, ch -> "ch${i + 1}.xhtml" to chapterXhtml(ch) }
            files.forEach { (name, xhtml) -> writeText(zos, "OEBPS/$name", xhtml) }
            writeText(zos, "OEBPS/style.css", CSS)

            // 4) Navigation: EPUB3 nav + EPUB2 ncx.
            writeText(zos, "OEBPS/nav.xhtml", navXhtml(chapters))
            writeText(zos, "OEBPS/toc.ncx", ncx(uid, bookTitle, chapters))

            // 5) The package manifest + spine, tying it all together.
            writeText(zos, "OEBPS/content.opf", opf(uid, bookTitle, author, modified, chapters.size))
        }
    }

    // -- entry writers ---------------------------------------------------------

    private fun writeStored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        zos.putNextEntry(entry)
        zos.write(data)
        zos.closeEntry()
    }

    private fun writeText(zos: ZipOutputStream, name: String, text: String) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(text.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    // -- documents -------------------------------------------------------------

    private val CONTAINER = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val CSS = """
        body { font-family: Georgia, 'Times New Roman', serif; line-height: 1.6; margin: 1em; }
        h1, h2, h3 { line-height: 1.25; }
        .cairn-src { color: #666; font-size: 0.85em; margin-bottom: 1.5em; }
        blockquote { border-left: 3px solid #ccc; margin: 1em 0; padding-left: 1em; color: #333; }
        img { max-width: 100%; height: auto; }
        pre { white-space: pre-wrap; word-wrap: break-word; background: #f4f4f4; padding: 0.5em; }
    """.trimIndent()

    private fun opf(uid: String, title: String, author: String?, modified: String, chapters: Int): String {
        val items = (1..chapters).joinToString("\n") {
            """    <item id="ch$it" href="ch$it.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = (1..chapters).joinToString("\n") { """    <itemref idref="ch$it"/>""" }
        val creator = if (!author.isNullOrBlank()) "\n    <dc:creator>${esc(author)}</dc:creator>" else ""
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="en">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="bookid">$uid</dc:identifier>
                <dc:title>${esc(title)}</dc:title>
                <dc:language>en</dc:language>$creator
                <meta property="dcterms:modified">$modified</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="css" href="style.css" media-type="text/css"/>
            $items
              </manifest>
              <spine toc="ncx">
            $spine
              </spine>
            </package>
        """.trimIndent()
    }

    private fun navXhtml(chapters: List<Chapter>): String {
        val items = chapters.mapIndexed { i, ch ->
            """      <li><a href="ch${i + 1}.xhtml">${esc(ch.title.ifBlank { "Untitled" })}</a></li>"""
        }.joinToString("\n")
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="en">
            <head><title>Contents</title><meta charset="utf-8"/></head>
            <body>
              <nav epub:type="toc" id="toc"><h1>Contents</h1><ol>
            $items
              </ol></nav>
            </body>
            </html>
        """.trimIndent()
    }

    private fun ncx(uid: String, title: String, chapters: List<Chapter>): String {
        val points = chapters.mapIndexed { i, ch ->
            """    <navPoint id="np${i + 1}" playOrder="${i + 1}">
      <navLabel><text>${esc(ch.title.ifBlank { "Untitled" })}</text></navLabel>
      <content src="ch${i + 1}.xhtml"/>
    </navPoint>"""
        }.joinToString("\n")
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="$uid"/></head>
              <docTitle><text>${esc(title)}</text></docTitle>
              <navMap>
            $points
              </navMap>
            </ncx>
        """.trimIndent()
    }

    /** Wrap one article's cleaned body in a standalone XHTML chapter document. */
    private fun chapterXhtml(ch: Chapter): String {
        val bodyInner = sanitizeToXhtml(ch.html)
        val srcBits = buildList {
            ch.author?.takeIf { it.isNotBlank() }?.let { add("by ${esc(it)}") }
            ch.url?.takeIf { it.isNotBlank() }?.let { add("""<a href="${esc(it)}">source</a>""") }
        }
        val src = if (srcBits.isNotEmpty()) """<p class="cairn-src">${srcBits.joinToString(" · ")}</p>""" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en">
<head><title>${esc(ch.title.ifBlank { "Untitled" })}</title><meta charset="utf-8"/><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
<h1>${esc(ch.title.ifBlank { "Untitled" })}</h1>
$src
$bodyInner
</body>
</html>"""
    }

    /** Clean article HTML into well-formed XHTML body content jsoup can serialize; drop scripts,
     *  interactive elements, and images (kept text-clean for reliable e-reader rendering). */
    private fun sanitizeToXhtml(html: String?): String {
        if (html.isNullOrBlank()) return "<p><em>No saved article text.</em></p>"
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, iframe, form, button, svg, link, meta, img, picture, source, video, audio").remove()
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)  // well-formed, self-closing tags
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset("UTF-8")
            .prettyPrint(false)
        val inner = doc.body().html().trim()
        return inner.ifBlank { "<p><em>No saved article text.</em></p>" }
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
