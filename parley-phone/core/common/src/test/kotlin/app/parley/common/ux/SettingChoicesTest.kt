package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingChoicesTest {
    @Test fun sales_lines_read_and_write_the_two_stored_switches() {
        assertEquals(SalesLines.OFF, SalesLines.of(learn = false, silence = false))
        // A silence rule left on while learning was off never acted: it reads as Off.
        assertEquals(SalesLines.OFF, SalesLines.of(learn = false, silence = true))
        assertEquals(SalesLines.TAG, SalesLines.of(learn = true, silence = false))
        assertEquals(SalesLines.TAG_AND_SILENCE, SalesLines.of(learn = true, silence = true))
        SalesLines.entries.forEach { assertEquals(it, SalesLines.of(it.learn, it.silence)) }
    }

    @Test fun call_vibration_reads_and_writes_the_two_stored_switches() {
        // The defaults (both on) read as the fullest choice, so nothing changes for anyone who never touched them.
        assertEquals(CallVibration.CHANGES_AND_ANSWER, CallVibration.of(haptics = true, onConnect = true))
        assertEquals(CallVibration.CHANGES, CallVibration.of(haptics = true, onConnect = false))
        assertEquals(CallVibration.OFF, CallVibration.of(haptics = false, onConnect = true))
        CallVibration.entries.forEach { assertEquals(it, CallVibration.of(it.haptics, it.onConnect)) }
    }
}
