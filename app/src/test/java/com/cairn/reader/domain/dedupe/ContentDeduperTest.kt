package com.cairn.reader.domain.dedupe

import com.cairn.reader.data.db.ItemListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentDeduperTest {

    private var seq = 0
    private fun row(
        url: String,
        title: String,
        starred: Boolean = false,
        read: Boolean = false,
        readLater: Boolean = false,
        cache: String? = null,
        extract: String = "NONE",
        savedAt: Long = (seq++).toLong(),
        simHash: Long = 0L,
    ) = ItemListRow(
        id = "id-${seq}-$url-$title", url = url, title = title, author = null, siteName = null,
        sourceId = null, sourceTitle = null, excerpt = null, leadImage = null,
        publishedAt = null, savedAt = savedAt, readingMinutes = 0, extractStatus = extract,
        type = "ARTICLE", cacheStatus = cache, simHash = simHash, isRead = read, isStarred = starred,
        isReadLater = readLater, isArchived = false,
    )

    @Test fun `collapses tracking and www and trailing-slash URL variants`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://example.com/story", "A"),
                row("https://www.example.com/story/?utm_source=x", "B different title"),
            ),
        )
        assertEquals(1, out.size)
    }

    @Test fun `collapses same story across feeds by title`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://verge.com/a", "Big News Today"),
                row("https://reuters.com/z", "Big News Today"),
            ),
        )
        assertEquals(1, out.size)
    }

    @Test fun `strips trailing site suffix when matching titles`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/1", "The Very Big Announcement - The Verge"),
                row("https://b.com/2", "The Very Big Announcement | Reuters"),
            ),
        )
        assertEquals(1, out.size)
    }

    @Test fun `keeps the starred copy`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/1", "Same", read = true, savedAt = 100),
                row("https://a.com/1", "Same", starred = true, read = true, savedAt = 50),
            ),
        )
        assertEquals(1, out.size)
        assertTrue(out.single().isStarred)
    }

    @Test fun `distinct articles are not collapsed`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/1", "First article"),
                row("https://b.com/2", "Second article"),
                row("https://c.com/3", "Third article"),
            ),
        )
        assertEquals(3, out.size)
    }

    @Test fun `preserves order of kept rows`() {
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/1", "One"),
                row("https://b.com/2", "Two"),
                row("https://a.com/1?utm_medium=rss", "One again"),
                row("https://c.com/3", "Three"),
            ),
        )
        assertEquals(listOf("One", "Two", "Three"), out.map { it.title })
    }

    @Test fun `empty and single-item lists pass through`() {
        assertTrue(ContentDeduper.dedupe(emptyList()).isEmpty())
        assertEquals(1, ContentDeduper.dedupe(listOf(row("https://a.com/1", "Solo"))).size)
    }

    // ── SimHash near-duplicate collapse (Content Engine P4) ──────────────────────

    @Test fun `collapses near-identical bodies from different channels via SimHash`() {
        // The same article arriving as an RSS-summary vs. a full site-extract: different URLs, headline
        // varies, but the body is near-identical — so their fingerprints are within the Hamming bound.
        val base = "The city council approved the new transit plan on Tuesday after months of debate " +
            "over funding and routes. Supporters say it will cut commute times across the east side, " +
            "add protected crossings near schools and clinics, and lower emissions along the busiest " +
            "downtown corridors over the coming decade. Opponents questioned the cost and asked for " +
            "independent audits and firm timelines before the city issues any new transit bonds."
        val h1 = SimHash.compute(base)
        val h2 = SimHash.compute("$base Read the full story with photos and a map on our website.")
        assertTrue("bodies should be near-dups", SimHash.isNearDuplicate(h1, h2))
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://feed.example.com/a?utm_source=rss", "Transit plan approved", simHash = h1),
                row("https://www.example.com/news/transit", "Council approves transit plan", simHash = h2),
            ),
        )
        assertEquals(1, out.size)
    }

    @Test fun `unrelated bodies are not collapsed by SimHash`() {
        val h1 = SimHash.compute("A long-form review of the newest flagship phone and its camera system.")
        val h2 = SimHash.compute("An analysis of central bank policy and its effect on mortgage rates.")
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/phone", "Phone review", simHash = h1),
                row("https://b.com/rates", "Rate analysis", simHash = h2),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test fun `zero fingerprint never matches`() {
        // 0 is the "not computed" sentinel; two un-fingerprinted, otherwise-distinct rows stay separate.
        val out = ContentDeduper.dedupe(
            listOf(
                row("https://a.com/1", "Alpha", simHash = 0L),
                row("https://b.com/2", "Beta", simHash = 0L),
            ),
        )
        assertEquals(2, out.size)
    }
}
