package app.parley.common.ux

import app.parley.common.SettingsCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpTopicTest {
    @Test fun about_a_dozen_task_pages_each_leading_to_a_place_or_saying_where() {
        val pages = HelpTopic.troubleshooting
        assertTrue("about a dozen pages", pages.size in 8..14)
        // Each page opens the place that fixes it, or (a few, like why calls aren't recorded) explains where in its text.
        pages.forEach { t -> assertTrue("$t opens a place or says where", t.target != null || t in HelpTopic.textOnly) }
        // The text-only pages are only the ones meant to be: Android-feature pointers with no in-app place of their own.
        assertEquals(listOf(HelpTopic.WHY_NO_RECORDING, HelpTopic.FLASH_ON_RING), HelpTopic.textOnly)
        // Every setting a page opens is one Settings search knows.
        val keys = HelpTopic.entries.mapNotNull { (it.target as? CapabilityTarget.Setting)?.key }
        assertEquals(emptyList<String>(), keys.filter { k -> SettingsCatalog.entries.none { it.key == k } })
    }

    @Test fun the_7_2_help_pages_are_listed() {
        listOf(HelpTopic.WHY_NO_RECORDING, HelpTopic.FLASH_ON_RING, HelpTopic.CHECK_DATA)
            .forEach { assertTrue("$it is listed in Help", it in HelpTopic.troubleshooting) }
        // The self-check page opens the self-check screen; the two Android-feature pages only explain.
        assertEquals(CapabilityTarget.Screen(AppScreen.CHECK_DATA), HelpTopic.CHECK_DATA.target)
        assertNull(HelpTopic.WHY_NO_RECORDING.target)
        assertNull(HelpTopic.FLASH_ON_RING.target)
    }

    @Test fun the_questions_people_ask_are_answered() {
        // PRODUCT D4: the call screen, a Situation, why it rang, battery and "my contact vanished after Archive".
        assertEquals(CapabilityTarget.Setting("default_dialer"), HelpTopic.CALL_SCREEN.target)
        assertEquals(CapabilityTarget.Screen(AppScreen.TEST_A_CALL), HelpTopic.DID_NOT_RING.target)
        assertEquals(CapabilityTarget.Setting("situations"), HelpTopic.SITUATION_QUIET.target)
        assertEquals(CapabilityTarget.Setting("battery"), HelpTopic.LATE.target)
        assertEquals(CapabilityTarget.Screen(AppScreen.ARCHIVED), HelpTopic.ARCHIVED.target)
    }

    @Test fun feature_pages_are_opened_from_their_rows_not_listed() {
        val features = HelpTopic.entries.filter { it.explainsFeature }
        assertTrue(features.none { it in HelpTopic.troubleshooting })
        val fromRows = CapabilityCatalog.rows.mapNotNull { (it.target as? CapabilityTarget.Help)?.topic }.toSet()
        assertEquals(features.toSet(), fromRows)
    }

    @Test fun pages_are_found_by_their_stable_key() {
        HelpTopic.entries.forEach { assertEquals(it, HelpTopic.of(it.key)) }
        assertNull(HelpTopic.of("gone"))
    }
}
