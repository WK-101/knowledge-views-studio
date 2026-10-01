package app.parley.common.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoldModeTest {
    private val min = 60_000L

    @Test fun reminders_at_15_and_30_minutes() {
        assertEquals(15 * min, HoldMode.nextReminderAt(0, 0))
        assertEquals(15 * min, HoldMode.nextReminderAt(0, 14 * min))
        assertEquals(30 * min, HoldMode.nextReminderAt(0, 15 * min))
        assertNull(HoldMode.nextReminderAt(0, 30 * min))
        assertEquals(1_000 + 30 * min, HoldMode.nextReminderAt(1_000, 20 * min))
    }
}
