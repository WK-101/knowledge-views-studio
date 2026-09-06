package com.cairn.reader.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedTest {

    @Test fun `speed steps are the expected preset ladder`() {
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f), ListenSpeeds)
    }

    @Test fun `nextSpeed advances one step`() {
        assertEquals(1.0f, nextSpeed(0.75f))
        assertEquals(1.25f, nextSpeed(1.0f))
        assertEquals(1.5f, nextSpeed(1.25f))
        assertEquals(2.0f, nextSpeed(1.5f))
    }

    @Test fun `nextSpeed wraps around at the top`() {
        assertEquals(0.75f, nextSpeed(2.0f))
    }

    @Test fun `nextSpeed falls back to 1x for an unknown speed`() {
        assertEquals(1.0f, nextSpeed(3.3f))
        assertEquals(1.0f, nextSpeed(0.9f)) // outside the 0.01 tolerance of any preset
    }

    @Test fun `speedLabel drops the decimal for whole speeds`() {
        assertEquals("1×", speedLabel(1.0f))
        assertEquals("2×", speedLabel(2.0f))
    }

    @Test fun `speedLabel keeps the decimal for fractional speeds`() {
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("0.75×", speedLabel(0.75f))
    }
}
