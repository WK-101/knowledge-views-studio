package com.todocompanion.app

import com.todocompanion.app.domain.RetroLens
import com.todocompanion.app.domain.WeeklyReview
import com.todocompanion.app.domain.WeeklyReviews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Track 2.4 — the swappable retrospective lenses, and their round-trip through the WeeklyReview store. */
class RetroLensTest {

    @Test fun fourLensesEachWithFields() {
        assertEquals(4, RetroLens.ALL.size)
        assertEquals(listOf("ssc", "msg", "4ls", "sailboat"), RetroLens.ALL.map { it.id })
        assertEquals(listOf("start", "stop", "continue"), RetroLens.START_STOP_CONTINUE.fields.map { it.id })
        assertEquals(4, RetroLens.FOUR_LS.fields.size)
        assertEquals(4, RetroLens.SAILBOAT.fields.size)
        assertEquals(listOf("mad", "sad", "glad"), RetroLens.MAD_SAD_GLAD.fields.map { it.id })
    }

    @Test fun byIdResolvesOrNull() {
        assertEquals(RetroLens.SAILBOAT, RetroLens.byId("sailboat"))
        assertNull(RetroLens.byId(""))
        assertNull(RetroLens.byId("nope"))
    }

    @Test fun lensAnswersPersistInWeeklyReviewJson() {
        val review = WeeklyReview(
            isoWeek = "2026-W36",
            lens = "ssc",
            lensAnswers = mapOf("start" to "Deep work mornings", "stop" to "Late scrolling"),
            focusRating = 3,
        )
        val json = WeeklyReviews.upsert("", review)
        val back = WeeklyReviews.forWeek(json, "2026-W36")!!
        assertEquals("ssc", back.lens)
        assertEquals("Deep work mornings", back.lensAnswers["start"])
        assertEquals(3, back.focusRating)
        assertFalse(back.isEmpty)
    }

    @Test fun aReviewThatIsOnlyALensAnswerIsNotEmpty() {
        val r = WeeklyReview(isoWeek = "2026-W37", lens = "msg", lensAnswers = mapOf("glad" to "shipped it"))
        assertFalse(r.isEmpty)
        // An all-blank lens answer with no other content clears it.
        val blank = WeeklyReview(isoWeek = "2026-W37", lens = "msg", lensAnswers = mapOf("glad" to "   "))
        assertTrue(blank.isEmpty)
    }
}
