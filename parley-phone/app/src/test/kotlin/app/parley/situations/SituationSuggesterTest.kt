package app.parley.situations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.parley.common.situations.SituationSuggestions
import app.parley.common.situations.SuggestionKind
import app.parley.data.StatePrefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only the answer to a suggestion is kept, and an answer keeps the next one away for a week. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SituationSuggesterTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun not_now_and_never_are_the_only_marks_kept() {
        val now = 1_790_000_000_000L
        SituationSuggester.answer(context, SuggestionKind.CAR, never = false, now = now)
        SituationSuggester.answer(context, SuggestionKind.NIGHT, never = true, now = now + 1)
        val marks = SituationSuggester.marks(context)
        assertEquals(now + 1, marks.answeredAt)
        assertEquals(setOf(SuggestionKind.NIGHT), marks.never)
        // Two keys, and nothing about any call, device or time window.
        assertEquals(setOf("answered_at", "never"), StatePrefs.open(context, "situation_suggestions").all.keys)
    }

    @Test fun nothing_is_read_within_a_week_of_an_answer() = runTest {
        val now = 1_790_000_000_000L
        SituationSuggester.answer(context, SuggestionKind.MEETING, never = false, now = now)
        // The container (and the call history) isn't touched: this test's application has none.
        assertNull(SituationSuggester.find(context, { emptyList() }, now + SituationSuggestions.WEEK_MS - 1))
    }
}
