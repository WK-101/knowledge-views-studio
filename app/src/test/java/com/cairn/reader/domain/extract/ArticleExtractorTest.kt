package com.cairn.reader.domain.extract

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleExtractorTest {

    private val extractor = ArticleExtractor()

    @Test fun `extracts the main article body and computes reading metadata`() = runTest {
        val body = (1..12).joinToString("\n") {
            "<p>This is a reasonably long paragraph number $it with enough words to be counted as real readable content by the readability algorithm, well beyond boilerplate navigation chrome.</p>"
        }
        val html = """
            <html><head><title>Great Article</title></head>
            <body>
              <nav>home about contact</nav>
              <article><h1>Great Article</h1>$body</article>
              <footer>copyright junk</footer>
            </body></html>
        """.trimIndent()

        val result = extractor.extract("https://example.com/post", html)
        assertNotNull("should extract a body", result)
        result!!
        assertTrue("word count is measured", result.wordCount > 100)
        assertTrue("reading time at least a minute", result.readingMinutes >= 1)
        assertTrue("body carries the prose", result.plainText.contains("readable content"))
    }

    @Test fun `returns null for content-free markup`() = runTest {
        val result = extractor.extract("https://example.com/empty", "<html><body></body></html>")
        // Nothing meaningful to extract -> null rather than a crash.
        assertTrue(result == null || result.wordCount == 0)
    }
}
