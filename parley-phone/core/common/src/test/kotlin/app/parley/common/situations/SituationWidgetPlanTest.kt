package app.parley.common.situations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SituationWidgetPlanTest {
    private val ids = listOf("driving", "meeting", "night", "travelling", "gym")

    @Test fun one_cell_is_a_single_toggle_wider_is_chips() {
        assertNull(SituationWidgetPlan.fit(57, 102))
        // 1×1 in landscape: one chip; lower than a chip, the single toggle.
        assertEquals(SituationWidgetPlan.Fit(title = false, perRow = 1, rows = 1), SituationWidgetPlan.fit(127, 51))
        assertNull(SituationWidgetPlan.fit(127, 40))
        // 2×1: a row of two; 4×1: four.
        assertEquals(SituationWidgetPlan.Fit(title = false, perRow = 2, rows = 1), SituationWidgetPlan.fit(130, 102))
        assertEquals(SituationWidgetPlan.Fit(title = false, perRow = 4, rows = 1), SituationWidgetPlan.fit(276, 102))
        // 2×2: the line on top, then rows.
        assertEquals(SituationWidgetPlan.Fit(title = true, perRow = 2, rows = 4), SituationWidgetPlan.fit(130, 220))
    }

    @Test fun chips_always_fit_inside_the_widget() {
        for (w in 40..600 step 11) for (h in 40..600 step 11) {
            val f = SituationWidgetPlan.fit(w, h) ?: continue
            val used = SituationWidgetPlan.PADDING_DP + (if (f.title) SituationWidgetPlan.TITLE_DP else 0) + f.rows * SituationWidgetPlan.CHIP_MIN_HEIGHT_DP
            assertTrue("$w×$h: $f", used <= h)
            assertTrue("$w×$h: $f", f.perRow * SituationWidgetPlan.CHIP_WIDTH_DP <= w || f.perRow == 1)
        }
    }

    @Test fun the_single_toggle_is_for_the_one_on_else_the_most_used() {
        assertEquals("night", SituationWidgetPlan.favourite(ids, "night", mapOf("meeting" to 9)))
        assertEquals("meeting", SituationWidgetPlan.favourite(ids, null, mapOf("meeting" to 9, "night" to 2)))
        assertEquals("driving", SituationWidgetPlan.favourite(ids, null, emptyMap()))
        // One deleted since: not offered.
        assertEquals("driving", SituationWidgetPlan.favourite(ids, "gone", emptyMap()))
        assertNull(SituationWidgetPlan.favourite(emptyList(), null, emptyMap()))
    }

    @Test fun too_many_chips_keep_the_one_on_and_the_most_used_in_list_order() {
        assertEquals(ids, SituationWidgetPlan.chips(ids, null, emptyMap(), 8))
        assertEquals(listOf("meeting", "gym"), SituationWidgetPlan.chips(ids, "gym", mapOf("meeting" to 3, "night" to 1), 2))
        assertEquals(listOf("driving", "meeting"), SituationWidgetPlan.chips(ids, null, emptyMap(), 2))
    }

    @Test fun every_turn_on_counts_once_whatever_turned_it_on() {
        assertEquals(mapOf("night" to 1), SituationWidgetPlan.counted(emptyMap(), null, "night"))
        assertEquals(mapOf("night" to 2), SituationWidgetPlan.counted(mapOf("night" to 1), "meeting", "night"))
        // Still on, or turned off: nothing new.
        assertNull(SituationWidgetPlan.counted(mapOf("night" to 1), "night", "night"))
        assertNull(SituationWidgetPlan.counted(mapOf("night" to 1), "night", null))
    }
}
