package com.todocompanion.app

import com.todocompanion.app.util.NoteRichRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 — pin the Markdown engine's correctness across the full CommonMark + GFM feature set. The renderer is
 * a pure commonmark-java pipeline (no Android), so a plain JVM test proves headings, emphasis, tables,
 * task lists, code, quotes, nested lists, links and rules all render — plus the app's own extensions
 * (content gating, CSV→table, offline image blocking) that layer on top.
 */
class NoteRichRendererTest {

    private fun html(md: String) = NoteRichRenderer.bodyHtml(md)

    @Test fun headingsAllSixLevels() {
        val h = html("# a\n\n## b\n\n### c\n\n#### d\n\n##### e\n\n###### f")
        listOf("<h1>", "<h2>", "<h3>", "<h4>", "<h5>", "<h6>").forEach {
            assertTrue("missing $it", h.contains(it))
        }
    }

    @Test fun emphasisBoldItalicStrikethrough() {
        val h = html("*i* **b** ~~s~~")
        assertTrue(h.contains("<em>i</em>"))
        assertTrue(h.contains("<strong>b</strong>"))
        assertTrue(h.contains("<del>s</del>"))   // GFM strikethrough extension
    }

    @Test fun inlineAndFencedCodeWithLanguage() {
        assertTrue(html("`x`").contains("<code>x</code>"))
        val fenced = html("```kotlin\nval x = 1\n```")
        assertTrue("fenced block", fenced.contains("<pre>"))
        assertTrue("language class", fenced.contains("language-kotlin"))
    }

    @Test fun gfmTableRenders() {
        val h = html("| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertTrue(h.contains("<table>"))
        assertTrue(h.contains("<thead>"))
        assertTrue(h.contains("<td>1</td>"))
    }

    @Test fun taskListCheckboxes() {
        val h = html("- [ ] open\n- [x] done")
        assertTrue("checkbox inputs", h.contains("<input") && h.contains("type=\"checkbox\""))
        assertTrue("one is checked", h.contains("checked"))
    }

    @Test fun blockquoteAndNestedListsAndRuleAndLink() {
        assertTrue(html("> quoted").contains("<blockquote>"))
        val nested = html("- a\n    - a1\n    - a2\n- b")
        // an outer <ul> containing an inner <ul>
        assertTrue("nested list", nested.indexOf("<ul>") != nested.lastIndexOf("<ul>"))
        assertTrue(html("---").contains("<hr"))
        // The renderer runs with sanitizeUrls(true), so a link from imported/shared Markdown is emitted as
        // `<a rel="nofollow" href="…">` — the href is present but not necessarily the first attribute. Assert
        // it order-independently, and pin the rel="nofollow" hardening so a config change that drops it (and
        // starts passing link equity to an attacker's URL) is caught here.
        val link = html("[t](https://x.dev)")
        assertTrue(link, link.contains("href=\"https://x.dev\""))
        assertTrue("links from untrusted Markdown must carry rel=nofollow", link.contains("rel=\"nofollow\""))
    }

    @Test fun malformedMarkdownNeverThrows() {
        // Unbalanced marks and a lone frontmatter fence — must render, not crash.
        val h = html("**bold that never closes\n| broken | table\n```\nunterminated fence")
        assertTrue(h.isNotBlank())
    }

    // ── App-specific extensions layered on CommonMark ──

    @Test fun contentGatingDetectsMathMermaidCode() {
        assertTrue(NoteRichRenderer.hasMath("euler \$e^{i\\pi}+1=0\$"))
        assertTrue(NoteRichRenderer.hasMath("\$\$\\int_0^1 x\\,dx\$\$"))
        assertTrue(NoteRichRenderer.hasMermaid("```mermaid\ngraph TD; A-->B\n```"))
        assertTrue(NoteRichRenderer.hasCode("```python\nprint(1)\n```"))
        // plain prose triggers none of the heavy scripts
        val plain = "just some words, no code or math here"
        assertFalse(NoteRichRenderer.hasMath(plain))
        assertFalse(NoteRichRenderer.hasMermaid(plain))
        assertFalse(NoteRichRenderer.hasCode(plain))
        // a mermaid fence is a diagram, not highlighted code
        assertFalse("mermaid is not 'code'", NoteRichRenderer.hasCode("```mermaid\ngraph TD;A-->B\n```"))
    }

    @Test fun csvFenceBecomesGfmTable() {
        val out = NoteRichRenderer.csvToTables("```csv\nName,Score\nAda,10\nGrace,9\n```")
        assertTrue("header row", out.contains("| Name | Score |"))
        assertTrue("separator", out.contains("| --- | --- |"))
        assertTrue("data escaped/aligned", out.contains("| Ada | 10 |"))
        // and it parses into a real HTML table
        assertTrue(html(out).contains("<table>"))
    }

    @Test fun offlineImageBlockingInFullDocument() {
        val theme = NoteRichRenderer.Theme(
            bg = "#fff", fg = "#000", muted = "#888", accent = "#36c",
            codeBg = "#eee", border = "#ccc", quoteBar = "#ccc", dark = false,
        )
        val doc = NoteRichRenderer.buildDocument(
            source = "![a](https://evil.example/x.png)\n\n![b](local.png)",
            theme = theme,
            images = mapOf("local.png" to "file:///data/local.png"),
        )
        assertTrue("remote image blocked", doc.contains("remote image blocked"))
        assertTrue("mapped local image kept", doc.contains("file:///data/local.png"))
        assertFalse("no live remote src", doc.contains("src=\"https://evil.example/x.png\""))
    }

    @Test fun documentGatesHeavyScriptsToNeed() {
        val theme = NoteRichRenderer.Theme("#fff", "#000", "#888", "#36c", "#eee", "#ccc", "#ccc", false)
        val plain = NoteRichRenderer.buildDocument("just words", theme)
        assertFalse("no katex for plain note", plain.contains("katex.min.js"))
        assertFalse("no prism for plain note", plain.contains("prism-core"))
        val mathy = NoteRichRenderer.buildDocument("\$\$x^2\$\$", theme)
        assertTrue("katex included when math present", mathy.contains("katex.min.js"))
    }
}
