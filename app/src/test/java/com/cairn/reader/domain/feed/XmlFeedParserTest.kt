package com.cairn.reader.domain.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fixture tests for the RSS/Atom parser — the highest-bug-density input path in a feed reader,
 * previously untested. Runs under Robolectric so the platform XmlPullParser is available on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class XmlFeedParserTest {

    private val parser = XmlFeedParser()

    @Test fun `parses RSS 2_0 with content-encoded, enclosure and comments`() {
        val xml = """
            <?xml version="1.0"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
              <channel>
                <title>Example Feed</title>
                <link>https://example.com</link>
                <item>
                  <title>First Post</title>
                  <link>https://example.com/first</link>
                  <guid>guid-1</guid>
                  <pubDate>Mon, 06 Sep 2021 12:00:00 GMT</pubDate>
                  <description>Summary text</description>
                  <content:encoded><![CDATA[<p>Full <b>HTML</b> body</p>]]></content:encoded>
                  <comments>https://example.com/first#comments</comments>
                  <enclosure url="https://example.com/ep.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = parser.parse(xml, "https://example.com/feed")
        assertNotNull(feed)
        assertEquals("Example Feed", feed!!.title)
        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("First Post", item.title)
        assertEquals("https://example.com/first", item.link)
        assertEquals("guid-1", item.guid)
        assertNotNull("RFC-822 pubDate should parse", item.publishedAt)
        // content:encoded is richer than description and must win for the body.
        assertTrue(item.contentHtml!!.contains("Full"))
        assertEquals("Summary text", item.summary)
        assertEquals("https://example.com/first#comments", item.commentsUrl)
        assertEquals("https://example.com/ep.mp3", item.audioUrl)
    }

    @Test fun `parses Atom entries`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Atom Example</title>
              <link rel="alternate" href="https://atom.example.com"/>
              <entry>
                <title>Atom Entry</title>
                <link rel="alternate" href="https://atom.example.com/e1"/>
                <id>tag:atom,1</id>
                <published>2021-09-06T12:00:00Z</published>
                <content type="html">&lt;p&gt;hi&lt;/p&gt;</content>
              </entry>
            </feed>
        """.trimIndent()

        val feed = parser.parse(xml, "https://atom.example.com/feed")
        assertNotNull(feed)
        assertEquals("Atom Example", feed!!.title)
        assertEquals(1, feed.items.size)
        val item = feed.items.first()
        assertEquals("Atom Entry", item.title)
        assertEquals("https://atom.example.com/e1", item.link)
        assertNotNull("ISO-8601 published should parse", item.publishedAt)
    }

    @Test fun `returns null for non-xml input`() {
        assertNull(parser.parse("this is not xml at all", "https://x.example.com"))
        assertNull(parser.parse("", "https://x.example.com"))
    }

    @Test fun `does not crash on malformed xml`() {
        // An unterminated tag must not throw out of parse(); at worst it returns null.
        val result = parser.parse("<rss><channel><title>x</title><item><title>oops", "https://x.example.com")
        // Either null, or a lenient partial parse — the contract is only "no exception escapes".
        if (result != null) assertTrue(result.items.size <= 1)
    }
}
