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
}
