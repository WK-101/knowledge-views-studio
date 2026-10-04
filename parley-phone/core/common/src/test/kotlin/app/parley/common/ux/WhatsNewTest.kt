package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewTest {
    @Test fun whats_new_once_per_update_and_an_introduction_once_on_a_fresh_install() {
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(seenVersion = 4, currentVersion = 5, freshInstall = false))
        // Updated from a version that didn't record anything yet.
        assertEquals(WhatsNew.Decision.SHOW, WhatsNew.decide(0, 5, freshInstall = false))
        // A fresh install gets the short introduction instead, until it is dismissed (that records the version).
        assertEquals(WhatsNew.Decision.INTRO, WhatsNew.decide(0, 5, freshInstall = true))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(5, 5, freshInstall = true))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(5, 5, freshInstall = false))
        assertEquals(WhatsNew.Decision.NOTHING, WhatsNew.decide(6, 5, freshInstall = false))
    }
}
