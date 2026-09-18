package com.cairn.reader.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveResolverTest {

    private val url = "https://www.example.com/news/story"

    @Test fun `archive today newest appends the raw url`() {
        assertEquals("https://archive.ph/newest/$url", ArchiveResolver.archiveTodayNewest(url))
    }

    @Test fun `archive today search appends the raw url`() {
        assertEquals("https://archive.ph/$url", ArchiveResolver.archiveTodaySearch(url))
    }

    @Test fun `wayback newest uses the far-future latest trick and id_ raw page`() {
        assertEquals("https://web.archive.org/web/2999id_/$url", ArchiveResolver.waybackNewest(url))
    }

    @Test fun `urls are trimmed`() {
        assertEquals("https://archive.ph/newest/$url", ArchiveResolver.archiveTodayNewest("  $url  "))
    }

    @Test fun `a thin body is treated as a paywall`() {
        assertTrue(ArchiveResolver.looksPaywalled("A short teaser sentence.", wordCount = 12))
    }

    @Test fun `a short body with a paywall phrase is flagged`() {
        assertTrue(
            ArchiveResolver.looksPaywalled(
                "Here is the intro. Subscribe to continue reading this exclusive report.",
                wordCount = 200,
            ),
        )
    }

    @Test fun `a long body is never flagged even if it mentions subscribing`() {
        assertFalse(ArchiveResolver.looksPaywalled("subscribe to continue".repeat(50), wordCount = 900))
    }

    @Test fun `a normal medium body without markers is not flagged`() {
        assertFalse(ArchiveResolver.looksPaywalled("A perfectly ordinary article body.", wordCount = 400))
    }

    @Test fun `zero word count is not flagged as paywall`() {
        // 0 words means "not extracted at all" — that's a different state, handled by extractStatus.
        assertFalse(ArchiveResolver.looksPaywalled(null, wordCount = 0))
    }
}
