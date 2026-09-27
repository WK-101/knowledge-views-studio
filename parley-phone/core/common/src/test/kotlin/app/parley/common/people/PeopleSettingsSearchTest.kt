package app.parley.common.people

import app.parley.common.SettingsCatalog
import app.parley.common.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleSettingsSearchTest {
    @Test fun new_settings_are_searchable() {
        listOf("swipe_actions", "avatar_style", "private_directory", "crash_reports", "my_details").forEach { SettingsCatalog[it] }
        assertEquals("swipe_actions", SettingsSearch.search("swipe").first().key)
        assertEquals("crash_reports", SettingsSearch.search("crash").first().key)
        assertTrue(SettingsSearch.search("android auto").any { it.key == "private_directory" })
        assertTrue(SettingsSearch.search("my card").any { it.key == "my_details" })
    }
}
