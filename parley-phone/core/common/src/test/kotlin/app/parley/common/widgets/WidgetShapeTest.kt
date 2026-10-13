package app.parley.common.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetShapeTest {
    @Test fun each_phone_size_gets_its_shape() {
        // 1×1: portrait is a tile, landscape (wide and low) a strip.
        assertEquals(WidgetShape.TILE, WidgetShape.of(57, 102))
        assertEquals(WidgetShape.STRIP, WidgetShape.of(127, 51))
        // 2×1 and wider, one cell high: a strip.
        assertEquals(WidgetShape.STRIP, WidgetShape.of(130, 102))
        assertEquals(WidgetShape.STRIP, WidgetShape.of(554, 51))
        // 2×2 and up, in either orientation.
        assertEquals(WidgetShape.BLOCK, WidgetShape.of(130, 220))
        assertEquals(WidgetShape.BLOCK, WidgetShape.of(269, 117))
        // One cell across stays a tile however high.
        assertEquals(WidgetShape.TILE, WidgetShape.of(57, 337))
    }

    @Test fun an_unknown_size_counts_as_roomy() {
        assertEquals(WidgetShape.BLOCK, WidgetShape.of(0, 0))
        assertEquals(WidgetShape.STRIP, WidgetShape.of(0, 60))
        assertEquals(WidgetShape.TILE, WidgetShape.of(60, 0))
    }

    @Test fun a_label_shows_only_where_it_fits_under_the_icon() {
        assertTrue(WidgetShape.labelFits(102))
        assertTrue(WidgetShape.labelFits(0))
        assertFalse(WidgetShape.labelFits(51))
    }
}
