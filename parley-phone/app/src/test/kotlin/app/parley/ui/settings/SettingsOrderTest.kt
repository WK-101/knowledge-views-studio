package app.parley.ui.settings

import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings opens on what a phone app's settings are changed for most; how it looks comes after Notifications. */
class SettingsOrderTest {
    private val order = categoryGroups.flatten().filterNotNull()

    @Test fun calls_and_blocking_come_first() {
        assertEquals(listOf(SettingsCategory.CALLS, SettingsCategory.BLOCKING), categoryGroups.first())
        assertTrue(order.indexOf(SettingsCategory.APPEARANCE) > order.indexOf(SettingsCategory.NOTIFICATIONS))
        assertTrue(order.indexOf(SettingsCategory.LAYOUT) > order.indexOf(SettingsCategory.NOTIFICATIONS))
        assertEquals(SettingsCategory.ABOUT, order.last())
    }

    @Test fun every_category_is_listed_once_and_search_still_finds_every_setting() {
        assertEquals(SettingsCategory.entries.toSet(), order.toSet())
        assertEquals(order.size, order.toSet().size)
        // The order moved rows on the home page only: every setting is still in a category search covers.
        assertEquals(SettingsCatalog.entries.size, SettingsCategory.entries.sumOf { SettingsCatalog.inCategory(it).size })
    }
}
