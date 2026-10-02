package app.parley.common.calls

import app.parley.common.calls.CallAnnouncements.Phase
import app.parley.common.calls.CallAnnouncements.Say
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallAnnouncementsTest {
    @Test fun answered_and_dialled_calls_say_connected() {
        assertEquals(Say.CONNECTED, CallAnnouncements.on(Phase.RINGING, Phase.ACTIVE))
        assertEquals(Say.CONNECTED, CallAnnouncements.on(Phase.DIALLING, Phase.ACTIVE))
        assertEquals(Say.CONNECTED, CallAnnouncements.on(Phase.OTHER, Phase.ACTIVE))
    }

    @Test fun hold_resume_and_end_are_spoken() {
        assertEquals(Say.ON_HOLD, CallAnnouncements.on(Phase.ACTIVE, Phase.HOLDING))
        assertEquals(Say.RESUMED, CallAnnouncements.on(Phase.HOLDING, Phase.ACTIVE))
        assertEquals(Say.ENDED, CallAnnouncements.on(Phase.ACTIVE, Phase.ENDED))
        assertEquals(Say.ENDED, CallAnnouncements.on(Phase.RINGING, Phase.ENDED))
        assertEquals(Say.ENDED, CallAnnouncements.on(Phase.HOLDING, Phase.ENDED))
    }

    @Test fun nothing_on_first_sight_or_without_a_change() {
        Phase.entries.forEach { p ->
            assertNull(CallAnnouncements.on(null, p))
            assertNull(CallAnnouncements.on(p, p))
        }
        assertNull(CallAnnouncements.on(Phase.RINGING, Phase.DIALLING))
        assertNull(CallAnnouncements.on(Phase.ENDED, Phase.ACTIVE))
        assertNull(CallAnnouncements.on(Phase.ACTIVE, Phase.OTHER))
    }
}
