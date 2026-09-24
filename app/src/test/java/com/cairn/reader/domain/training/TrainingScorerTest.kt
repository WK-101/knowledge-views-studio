package com.cairn.reader.domain.training

import com.cairn.reader.data.db.ItemListRow
import com.cairn.reader.data.db.TrainerEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingScorerTest {

    private fun row(
        author: String? = null,
        sourceId: String? = null,
        title: String = "A title",
        tagNames: String? = null,
    ) = ItemListRow(
        id = "i", url = "https://x/y", title = title, author = author, siteName = "Site",
        sourceId = sourceId, sourceTitle = "Feed", excerpt = null, leadImage = null,
        publishedAt = null, savedAt = 0L, readingMinutes = 1, extractStatus = "OK", type = "ARTICLE",
        cacheStatus = null, simHash = 0L, isRead = false, isStarred = false, isReadLater = false,
        isArchived = false, tagNames = tagNames, collectionCount = 0,
    )

    private fun trainer(kind: TrainerKind, value: String, sentiment: Int) =
        TrainerEntity(id = "${kind.name}:$value", kind = kind.name, value = value, sentiment = sentiment, createdAt = 0L)

    @Test fun noTrainers_scoresZero() {
        assertEquals(0, TrainingScorer.score(row(author = "Jane"), emptyList()))
    }

    @Test fun likedAuthor_isFocus() {
        val trainers = listOf(trainer(TrainerKind.AUTHOR, "jane doe", 1))
        val s = TrainingScorer.score(row(author = "Jane Doe"), trainers) // case-insensitive match
        assertEquals(1, s)
        assertEquals(Tier.FOCUS, TrainingScorer.tier(s))
    }

    @Test fun mutedFeed_isHidden() {
        val trainers = listOf(trainer(TrainerKind.FEED, "feed-123", -1))
        val s = TrainingScorer.score(row(sourceId = "feed-123"), trainers)
        assertEquals(-1, s)
        assertEquals(Tier.HIDDEN, TrainingScorer.tier(s))
    }

    @Test fun likedAuthorPlusMutedTag_netsOut() {
        val trainers = listOf(
            trainer(TrainerKind.AUTHOR, "jane doe", 1),
            trainer(TrainerKind.TAG, "sports", -1),
        )
        // Tag names arrive 0x1F-joined on the row projection.
        val s = TrainingScorer.score(row(author = "Jane Doe", tagNames = "news\u001Fsports"), trainers)
        assertEquals(0, s)
        assertEquals(Tier.NEUTRAL, TrainingScorer.tier(s))
    }

    @Test fun titleKeyword_matchesCaseInsensitively() {
        val trainers = listOf(trainer(TrainerKind.TITLE, "bitcoin", -1))
        assertEquals(-1, TrainingScorer.score(row(title = "Why Bitcoin fell today"), trainers))
        assertEquals(0, TrainingScorer.score(row(title = "A calm morning"), trainers))
    }

    @Test fun feedMatchIsCaseSensitive() {
        // Feed ids are opaque; a different-cased id must NOT match.
        val trainers = listOf(trainer(TrainerKind.FEED, "Feed-123", 1))
        assertEquals(0, TrainingScorer.score(row(sourceId = "feed-123"), trainers))
    }
}
