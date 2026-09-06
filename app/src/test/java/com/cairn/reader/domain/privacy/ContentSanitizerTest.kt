package com.cairn.reader.domain.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSanitizerTest {

    private val sanitizer = ContentSanitizer()

    @Test fun `strips scripts and inline event handlers`() {
        val res = sanitizer.sanitize(
            "<p onclick=\"steal()\">Hi</p><script>evil()</script>",
            "https://ex.com",
        )
        assertFalse("no script tag", res.html.contains("<script", ignoreCase = true))
        assertFalse("no inline handler", res.html.contains("onclick", ignoreCase = true))
        assertTrue("keeps the readable text", res.html.contains("Hi"))
        assertTrue("counts what it removed", res.removed >= 1)
    }

    @Test fun `keeps ordinary content links and paragraphs intact`() {
        val res = sanitizer.sanitize(
            "<p>See <a href=\"https://good.com\">this</a>.</p>",
            "https://ex.com",
        )
        assertTrue(res.html.contains("this"))
        assertTrue(res.html.contains("https://good.com"))
    }

    @Test fun `does not crash on empty or malformed html`() {
        sanitizer.sanitize("", "https://ex.com")
        sanitizer.sanitize("<p>unclosed", "https://ex.com")
    }
}
