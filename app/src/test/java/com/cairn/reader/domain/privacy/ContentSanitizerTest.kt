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

    @Test fun `strips the Facebook tracking pixel (host plus path endpoint)`() {
        val res = sanitizer.sanitize(
            "<p>Body</p><img src=\"https://www.facebook.com/tr?id=123&ev=PageView\"/>",
            "https://ex.com",
        )
        assertFalse("facebook.com/tr pixel removed", res.html.contains("facebook.com/tr"))
        assertTrue("keeps the readable text", res.html.contains("Body"))
        assertTrue(res.removed >= 1)
    }

    @Test fun `strips a tiny beacon image and a known analytics host`() {
        val res = sanitizer.sanitize(
            "<img src=\"https://x.com/p.gif\" width=\"1\" height=\"1\"/>" +
                "<img src=\"https://www.google-analytics.com/collect?v=1\"/><p>Keep</p>",
            "https://ex.com",
        )
        assertFalse(res.html.contains("google-analytics.com"))
        assertTrue(res.html.contains("Keep"))
    }

    @Test fun `keeps a legitimate image from a general host`() {
        val res = sanitizer.sanitize(
            "<p>Photo</p><img src=\"https://cdn.example.com/photo.jpg\" width=\"800\" height=\"600\"/>",
            "https://ex.com",
        )
        assertTrue("real content image survives", res.html.contains("cdn.example.com/photo.jpg"))
    }
}
