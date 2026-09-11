package com.todocompanion.app

import com.todocompanion.app.domain.NoteRelated
import com.todocompanion.app.domain.NoteRelated.Doc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave T — on-device "related notes" (no cloud, no model). */
class NoteRelatedTest {

    private val docs = listOf(
        Doc("a", "Sourdough starter", "Feeding the #baking starter every morning. See [[Bread schedule]]."),
        Doc("b", "Bread schedule", "A #baking plan: mix, proof, bake. Related to the sourdough starter feeding."),
        Doc("c", "Tax return", "Gather receipts and file the #finance return before April."),
    )

    @Test fun sharedTagsAndVocabularyRankHighest() {
        val hits = NoteRelated.related("a", docs)
        assertTrue(hits.isNotEmpty())
        // b shares #baking + vocabulary + a title-link, so it must outrank the unrelated tax note
        assertEquals("b", hits.first().id)
        val ids = hits.map { it.id }
        if ("c" in ids) assertTrue(hits.first { it.id == "b" }.score > hits.first { it.id == "c" }.score)
    }

    @Test fun targetItselfExcludedAndUnknownIsEmpty() {
        assertTrue(NoteRelated.related("a", docs).none { it.id == "a" })
        assertTrue(NoteRelated.related("zzz", docs).isEmpty())
    }

    @Test fun titleLinkIsStrong() {
        // a note that [[links]] to the target's title should surface even with little shared vocab
        val d = listOf(
            Doc("t", "Project Phoenix", "kickoff notes here"),
            Doc("x", "Random", "totally different words about gardening but see [[Project Phoenix]]"),
        )
        assertEquals("x", NoteRelated.related("t", d).firstOrNull()?.id)
    }
}
