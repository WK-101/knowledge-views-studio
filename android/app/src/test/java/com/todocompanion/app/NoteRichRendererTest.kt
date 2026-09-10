package com.todocompanion.app

import com.todocompanion.app.util.NoteRichRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave L — the offline rich canvas: content gating, document assembly, image rewriting. */
class NoteRichRendererTest {

    private val theme = NoteRichRenderer.Theme(
        bg = "#FFFFFF", fg = "#000000", muted = "#666666", accent = "#0000FF",
        codeBg = "#EEEEEE", border = "#CCCCCC", quoteBar = "#999999", dark = false,
    )

    @Test fun detectsMath() {
        assertTrue(NoteRichRenderer.hasMath("energy is \$E = mc^2\$ today"))
        assertTrue(NoteRichRenderer.hasMath("block:\n$$\\int_0^1 x\\,dx$$\n"))
        assertTrue(NoteRichRenderer.hasMath("paren \\(a+b\\) form"))
        assertFalse(NoteRichRenderer.hasMath("a plain note with no math at all"))
        assertFalse(NoteRichRenderer.hasMath("costs \$5 today"))   // lone dollar, not a pair
    }

    @Test fun detectsMermaidAndCode() {
        assertTrue(NoteRichRenderer.hasMermaid("```mermaid\ngraph TD; A-->B\n```"))
        assertFalse(NoteRichRenderer.hasMermaid("```kotlin\nval x = 1\n```"))
        assertTrue(NoteRichRenderer.hasCode("```kotlin\nval x = 1\n```"))
        assertFalse(NoteRichRenderer.hasCode("```mermaid\ngraph TD; A-->B\n```"))  // diagram, not code
        assertFalse(NoteRichRenderer.hasCode("no fences here"))
    }

    @Test fun scriptsIncludedOnlyWhenNeeded() {
        val plain = NoteRichRenderer.buildDocument("# Hello\n\nJust text.", theme)
        assertFalse(plain.contains("katex.min.js"))
        assertFalse(plain.contains("mermaid.min.js"))
        assertFalse(plain.contains("prism-core.min.js"))

        val mathDoc = NoteRichRenderer.buildDocument("value \$x^2\$ end", theme)
        assertTrue(mathDoc.contains("katex.min.js"))
        assertTrue(mathDoc.contains("renderMathInElement"))

        val codeDoc = NoteRichRenderer.buildDocument("```python\nprint(1)\n```", theme)
        assertTrue(codeDoc.contains("prism-core.min.js"))
        assertTrue(codeDoc.contains("Prism.highlightAll"))

        val diagramDoc = NoteRichRenderer.buildDocument("```mermaid\ngraph TD; A-->B\n```", theme)
        assertTrue(diagramDoc.contains("mermaid.min.js"))
    }

    @Test fun documentIsThemedAndSelfContained() {
        val doc = NoteRichRenderer.buildDocument("hello", theme)
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("#FFFFFF"))         // theme background applied
        assertTrue(doc.contains("callout"))         // callout transformer always present
        assertFalse(doc.contains("cdn"))            // no CDN reference anywhere
        assertFalse(doc.contains("http://") || doc.contains("https://"))  // no absolute URLs
    }

    @Test fun localImagesResolvedRemoteBlocked() {
        val local = NoteRichRenderer.buildDocument("![pic](photo.png)", theme, mapOf("photo.png" to "file:///data/photo.png"))
        assertTrue(local.contains("file:///data/photo.png"))
        assertFalse(local.contains("<span class=\"img-blocked\""))

        val remote = NoteRichRenderer.buildDocument("![x](https://evil.example/x.png)", theme)
        assertTrue(remote.contains("<span class=\"img-blocked\""))
        assertFalse(remote.contains("evil.example"))

        val missing = NoteRichRenderer.buildDocument("![y](unknown.png)", theme)
        assertTrue(missing.contains("<span class=\"img-missing\""))
    }

    @Test fun bodyRendersGfm() {
        val html = NoteRichRenderer.bodyHtml("| a | b |\n|---|---|\n| 1 | 2 |")
        assertTrue(html.contains("<table"))
        assertTrue(NoteRichRenderer.bodyHtml("~~gone~~").contains("<del"))
    }
}
