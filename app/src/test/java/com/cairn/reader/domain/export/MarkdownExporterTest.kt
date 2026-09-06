package com.cairn.reader.domain.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownExporterTest {

    private val meta = MarkdownExporter.Meta(
        title = "The Title: A Study",
        url = "https://example.com/a",
        author = "Ada Lovelace",
        siteName = "Example",
        publishedAt = 1_700_000_000_000L,
        savedAt = 1_710_000_000_000L,
        tags = listOf("science", "deep dive"),
    )

    @Test fun `frontmatter is well-formed YAML with quoted risky values and slugged tags`() {
        val fm = MarkdownExporter.frontmatter(meta)
        assertTrue(fm.startsWith("---\n"))
        assertTrue(fm.trimEnd().endsWith("---"))
        assertTrue("title with colon is quoted", fm.contains("title: \"The Title: A Study\""))
        assertTrue("author kept", fm.contains("author: Ada Lovelace"))
        assertTrue("spaces in a tag become a hyphen", fm.contains("deep-dive"))
    }

    @Test fun `filename strips characters that break files or Obsidian links`() {
        val name = MarkdownExporter.filename(meta.copy(title = "a/b:c*?\"<>|#^[]d"))
        assertTrue(name.endsWith(".md"))
        val stem = name.removeSuffix(".md")
        listOf("/", ":", "*", "?", "\"", "<", ">", "|", "#", "^", "[", "]").forEach {
            assertFalse("must not contain $it", stem.contains(it))
        }
    }

    @Test fun `html body converts to markdown constructs`() {
        val md = MarkdownExporter.htmlToMarkdown(
            "<h2>Head</h2><p>Some <strong>bold</strong> and <a href=\"https://x.com\">link</a>.</p>" +
                "<ul><li>one</li><li>two</li></ul><blockquote>quote</blockquote>",
        )
        assertTrue(md.contains("## Head"))
        assertTrue(md.contains("**bold**"))
        assertTrue(md.contains("[link](https://x.com)"))
        assertTrue(md.contains("- one"))
        assertTrue(md.contains("> quote"))
    }

    @Test fun `document includes title, source link, body and highlights`() {
        val doc = MarkdownExporter.document(
            meta,
            html = "<p>Body text here.</p>",
            highlights = listOf(MarkdownExporter.Highlight("A quote", "my note", 1_710_000_000_000L)),
        )
        assertEquals("The Title A Study.md", doc.filename)
        assertTrue(doc.content.contains("# The Title: A Study"))
        assertTrue(doc.content.contains("[Read the original](https://example.com/a)"))
        assertTrue(doc.content.contains("Body text here."))
        assertTrue(doc.content.contains("## Highlights"))
        assertTrue(doc.content.contains("> A quote"))
        assertTrue(doc.content.contains("my note"))
    }

    @Test fun `missing body falls back to a note, not a crash`() {
        val doc = MarkdownExporter.document(meta, html = null)
        assertTrue(doc.content.contains("No saved article text"))
    }
}
