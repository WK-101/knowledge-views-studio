package app.parley.common.calls

import app.parley.common.calls.AnswerSlide.Outcome
import app.parley.common.calls.AnswerSlide.Tick
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerSlideTest {
    @Test fun position_is_a_share_of_the_travel_and_stays_on_the_track() {
        assertEquals(0.5f, AnswerSlide.position(50f, 100f), 0f)
        assertEquals(-1f, AnswerSlide.position(-250f, 100f), 0f)
        assertEquals(1f, AnswerSlide.position(250f, 100f), 0f)
        // Before the track is measured, the knob rests in the middle.
        assertEquals(0f, AnswerSlide.position(30f, 0f), 0f)
    }

    @Test fun a_short_or_slow_drag_springs_back() {
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(0f))
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(0.59f))
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(-0.59f))
        // A fast brush that has barely moved the knob (a pocket) does nothing.
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(0.1f, 5000f))
    }

    @Test fun past_the_threshold_answers_right_and_declines_left() {
        assertEquals(Outcome.ANSWER, AnswerSlide.release(AnswerSlide.COMMIT))
        assertEquals(Outcome.ANSWER, AnswerSlide.release(1f))
        assertEquals(Outcome.DECLINE, AnswerSlide.release(-AnswerSlide.COMMIT))
        assertEquals(Outcome.DECLINE, AnswerSlide.release(-1f, 3000f))
    }

    @Test fun a_flick_counts_only_when_it_keeps_going_the_same_way() {
        assertEquals(Outcome.ANSWER, AnswerSlide.release(0.35f, AnswerSlide.FLICK_DP_PER_SECOND))
        assertEquals(Outcome.DECLINE, AnswerSlide.release(-0.35f, -AnswerSlide.FLICK_DP_PER_SECOND))
        // Heading back towards the middle is a change of mind, not a flick.
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(0.45f, -2000f))
        assertEquals(Outcome.SPRING_BACK, AnswerSlide.release(0.45f, 800f))
    }

    @Test fun ticks_on_arming_and_backing_off_only() {
        assertEquals(Tick.NONE, AnswerSlide.tick(0f, 0.5f))
        assertEquals(Tick.ARMED, AnswerSlide.tick(0.5f, 0.61f))
        assertEquals(Tick.NONE, AnswerSlide.tick(0.61f, 0.9f))
        assertEquals(Tick.DISARMED, AnswerSlide.tick(0.9f, 0.4f))
        assertEquals(Tick.ARMED, AnswerSlide.tick(-0.2f, -0.7f))
        // Straight from one end to the other (a fast swipe between two frames) still ticks.
        assertEquals(Tick.ARMED, AnswerSlide.tick(0.7f, -0.7f))
    }

    @Test fun the_hint_fades_as_the_knob_moves() {
        assertEquals(1f, AnswerSlide.hintAlpha(0f), 0f)
        assertEquals(0.5f, AnswerSlide.hintAlpha(AnswerSlide.HINT_FADE / 2), 0.001f)
        assertEquals(0f, AnswerSlide.hintAlpha(-AnswerSlide.HINT_FADE), 0f)
        assertEquals(0f, AnswerSlide.hintAlpha(1f), 0f)
    }

    @Test fun each_end_fills_as_the_knob_approaches_it() {
        assertEquals(0f, AnswerSlide.targetFill(0f, 1), 0f)
        assertEquals(0.5f, AnswerSlide.targetFill(AnswerSlide.COMMIT / 2, 1), 0.001f)
        assertEquals(1f, AnswerSlide.targetFill(0.9f, 1), 0f)
        assertEquals(0f, AnswerSlide.targetFill(0.9f, -1), 0f)
        assertEquals(1f, AnswerSlide.targetFill(-0.9f, -1), 0f)
    }
}
