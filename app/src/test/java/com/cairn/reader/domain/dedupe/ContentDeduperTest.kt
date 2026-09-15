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
    ) = ItemListRow(
        id = "id-${seq}-$url-$title", url = url, title = title, author = null, siteName = null,
        sourceId = null, sourceTitle = null, excerpt = null, leadImage = null,
        publishedAt = null, savedAt = savedAt, readingMinutes = 0, extractStatus = extract,
        type = "ARTICLE", cacheStatus = cache, isRead = read, isStarred = starred,
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
}
