package com.cairn.reader.domain.export

import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSnapshotExporterTest {

    private val meta = HtmlSnapshotExporter.Meta(
        title = "Snapshot & Co",
        url = "https://ex.com/p",
        author = "Ada",
        siteName = "Example",
        savedAt = 1_710_000_000_000L,
    )

    @Test fun `produces a self-contained document with doctype, title and theme support`() {
        val html = HtmlSnapshotExporter.snapshot(meta, "<p>Body <b>text</b></p>")
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.contains("<title>Snapshot &amp; Co</title>"))
        assertTrue("carries the body", html.contains("Body"))
        assertTrue("links back to source", html.contains("https://ex.com/p"))
        assertTrue("color-scheme for theme support", html.contains("color-scheme"))
    }

    @Test fun `strips scripts from the snapshot`() {
        val html = HtmlSnapshotExporter.snapshot(meta, "<p>Hi</p><script>evil()</script>")
        assertTrue(!html.contains("evil()"))
    }

    @Test fun `falls back gracefully when there is no body`() {
        val html = HtmlSnapshotExporter.snapshot(meta, null)
        assertTrue(html.contains("No saved article text"))
    }
}
