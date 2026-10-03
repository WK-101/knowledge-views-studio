package app.parley.ui.settings

import app.parley.common.SettingsCatalog
import app.parley.common.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every setting Settings search finds leads somewhere; the menu-memory switch to its own page (H1). */
class SettingRoutesTest {
    @Test fun every_setting_has_a_route() {
        SettingsCategory.entries.flatMap { SettingsCatalog.inCategory(it) }.forEach { settingRoute(it) }
    }

    @Test fun menu_memory_is_on_phone_menus() {
        val calls = SettingsCatalog.inCategory(SettingsCategory.CALLS)
        assertEquals(CallsRoutes.PhoneMenus, settingRoute(calls.first { it.key == "menu_memory" }))
        assertEquals(CallsRoutes.PhoneMenus, settingRoute(calls.first { it.key == "phone_menus" }))
    }

    @Test fun calls_settings_open_their_own_page_on_the_row() {
        assertEquals(CallsRoutes.Page("ANSWERING", "auto_answer"), settingRoute(SettingsCatalog["auto_answer"]))
        assertEquals(CallsRoutes.Page("DURING", "proximity_sensor"), settingRoute(SettingsCatalog["proximity_sensor"]))
        assertEquals(CallsRoutes.Page("SIMS", "carrier_settings"), settingRoute(SettingsCatalog["carrier_settings"]))
        assertEquals(CallsSubPage.ANSWERING, CallsSubPage.of("not a page"))
        // Every page with settings of its own is reachable from search.
        CallsSubPage.entries.mapNotNull { it.place }.forEach { place ->
            assertEquals(place.name, true, SettingsCatalog.entries.any { it.place == place })
        }
    }
}
