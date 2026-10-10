package app.parley.common.ux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun an_update_from_6_3_names_what_became_visible_since() {
        // 6.3 stored only its version code (35).
        assertEquals("6.3", WhatsNew.lastSeenRelease(null, 35))
        val named = WhatsNew.named(seenName = null, seenVersion = 35, currentName = "6.4.0").map { it.key }
        assertEquals(listOf("search_everything", "situations", "help", "archived", "case_files"), named)
        // From 6.2.3 too: the 6.x features that only got their rows in 6.4.
        assertTrue(WhatsNew.named(null, 34, "6.4.0").map { it.key }.containsAll(listOf("situations", "search_everything", "archived")))
    }

    @Test fun the_next_update_names_only_what_came_after_the_version_seen() {
        // Seen on 6.4 (its name stored): an update to 6.5 doesn't repeat 6.4's rows.
        assertEquals("6.4", WhatsNew.lastSeenRelease("6.4.0-debug", 36))
        assertEquals(emptyList<Capability>(), WhatsNew.named("6.4.0", 36, "6.5.0"))
        // From long ago: older rows are named too, the newest visibility after the featured ones, at most five.
        val fromOld = WhatsNew.named(null, 16, "6.4.0")
        assertEquals(5, fromOld.size)
        assertTrue(fromOld.any { it.key == "coming_from" })
    }

    @Test fun nothing_recorded_names_this_releases_rows_only() {
        assertNull(WhatsNew.lastSeenRelease(null, 0))
        assertEquals(CapabilityCatalog.headline("6.4.0", max = 5), WhatsNew.named(null, 0, "6.4.0"))
    }
}
