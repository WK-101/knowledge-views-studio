package com.todocompanion.app

import com.todocompanion.app.domain.NotePredicate
import com.todocompanion.app.domain.NoteSmartViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wave D — the Smart View predicate engine (serialization + evaluation). */
class NotePredicateTest {
    private fun ctx(
        pinned: Boolean = false, favorite: Boolean = false, archived: Boolean = false,
        title: String = "", body: String = "", tagIds: Set<String> = emptySet(), updatedAt: Long = 0L, now: Long = 0L,
    ) = NoteSmartViews.Ctx(pinned, favorite, archived, false, title, body, "note", updatedAt, tagIds, now)

    @Test fun jsonRoundTrips() {
        val p = NotePredicate.Group(any = false, children = listOf(
            NotePredicate.Cond("pinned"), NotePredicate.Cond("titleContains", "alpha"),
        ))
        assertEquals(p, NoteSmartViews.decode(NoteSmartViews.encode(p)))
    }

    @Test fun andRequiresAll() {
        val p = NotePredicate.Group(false, listOf(NotePredicate.Cond("pinned"), NotePredicate.Cond("favorite")))
        assertTrue(NoteSmartViews.matches(p, ctx(pinned = true, favorite = true)))
        assertFalse(NoteSmartViews.matches(p, ctx(pinned = true, favorite = false)))
    }

    @Test fun orRequiresAny() {
        val p = NotePredicate.Group(true, listOf(NotePredicate.Cond("pinned"), NotePredicate.Cond("favorite")))
        assertTrue(NoteSmartViews.matches(p, ctx(favorite = true)))
        assertFalse(NoteSmartViews.matches(p, ctx()))
    }

    @Test fun emptyAllMatchesEverything() {
        assertTrue(NoteSmartViews.matches(NoteSmartViews.ALL, ctx()))
    }

    @Test fun conditionsEvaluate() {
        assertTrue(NoteSmartViews.matches(NotePredicate.Cond("untagged"), ctx(tagIds = emptySet())))
        assertFalse(NoteSmartViews.matches(NotePredicate.Cond("untagged"), ctx(tagIds = setOf("t"))))
        assertTrue(NoteSmartViews.matches(NotePredicate.Cond("hasTag", "t2"), ctx(tagIds = setOf("t1", "t2"))))
        assertTrue(NoteSmartViews.matches(NotePredicate.Cond("bodyContains", "milk"), ctx(body = "buy MILK")))
        val old = NotePredicate.Cond("olderThanDays", "30")
        assertTrue(NoteSmartViews.matches(old, ctx(updatedAt = 0L, now = 40L * 86_400_000L)))
        assertFalse(NoteSmartViews.matches(old, ctx(updatedAt = 0L, now = 10L * 86_400_000L)))
    }
}
