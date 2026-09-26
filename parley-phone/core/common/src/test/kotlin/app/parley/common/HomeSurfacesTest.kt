package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSurfacesTest {
    private val four = listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD)
    private val combined = SurfaceLayout(calls = CallsLayout.COMBINED)
    private val favSection = SurfaceLayout(favorites = FavoritesPlacement.SECTION)

    // ------------------------------------------------------------ storage and migration

    @Test fun default_is_separate_and_round_trips() {
        val d = SurfaceLayout()
        assertEquals(CallsLayout.SEPARATE, d.calls)
        assertEquals(FavoritesPlacement.OFF, d.favorites)
        assertEquals(RecentTap.OPEN_DETAILS, d.recentTap)
        assertFalse(d.merged)
        val all = SurfaceLayout(CallsLayout.COMBINED, true, FavoritesPlacement.STRIP, true, true, true, RecentTap.CALL)
        assertEquals(all, SurfaceLayout.decode(all.encode()))
        assertEquals(d, SurfaceLayout.decode(d.encode()))
        assertTrue(all.encode().startsWith("v=${SurfaceLayout.SCHEMA};"))
    }

    @Test fun decode_is_forgiving_and_never_merges_by_accident() {
        assertEquals(SurfaceLayout(), SurfaceLayout.decode(null))
        assertEquals(SurfaceLayout(), SurfaceLayout.decode(""))
        assertEquals(SurfaceLayout(), SurfaceLayout.decode("garbage"))
        // Unknown values (a newer version) fall back to separate; unknown keys are ignored.
        val t = SurfaceLayout.decode("v=9;calls=FLOATING;fav=SECTION;future=1;tap=CALL")
        assertEquals(CallsLayout.SEPARATE, t.calls)
        assertEquals(FavoritesPlacement.SECTION, t.favorites)
        assertEquals(RecentTap.CALL, t.recentTap)
    }

    @Test fun migration_pins_existing_users_to_their_layout() {
        // An update: other settings exist, no layout stored yet → Separate / Off written explicitly.
        val written = SurfaceLayout.migrate(null, existingUser = true)
        assertEquals(SurfaceLayout.EXISTING_USER.encode(), written)
        assertEquals(CallsLayout.SEPARATE, SurfaceLayout.decode(written).calls)
        assertEquals(FavoritesPlacement.OFF, SurfaceLayout.decode(written).favorites)
        // A fresh install gets the fresh-install default, which is also separate (documented choice).
        assertEquals(SurfaceLayout.FRESH_INSTALL.encode(), SurfaceLayout.migrate(null, existingUser = false))
        assertFalse(SurfaceLayout.FRESH_INSTALL.merged)
        // Already on this schema: nothing to write, the user's choice stays.
        assertNull(SurfaceLayout.migrate(combined.encode(), existingUser = true))
        // A value from a newer version is left alone.
        assertNull(SurfaceLayout.migrate("v=99;calls=COMBINED", existingUser = true))
        // No version at all (hand-edited or corrupt): pinned again.
        assertEquals(SurfaceLayout.EXISTING_USER.encode(), SurfaceLayout.migrate("calls=COMBINED", existingUser = true))
        assertEquals(0, SurfaceLayout.schemaOf(null))
        assertEquals(SurfaceLayout.SCHEMA, SurfaceLayout.schemaOf(combined.encode()))
    }

    @Test fun separated_is_one_tap_back() {
        val merged = SurfaceLayout(CallsLayout.COMBINED, false, FavoritesPlacement.STRIP, recentTap = RecentTap.CALL)
        assertTrue(merged.merged)
        val back = merged.separated()
        assertFalse(back.merged)
        assertEquals(RecentTap.CALL, back.recentTap)
    }

    // ------------------------------------------------------------ layout resolution

    @Test fun separate_layout_is_exactly_the_nav_tabs() {
        val tabs = NavTabs().move(StartTab.KEYPAD, 0).setVisible(StartTab.FAVORITES, false)
        val l = HomeLayout(tabs, SurfaceLayout())
        assertEquals(tabs.visible, l.visible)
        assertEquals(tabs.barTabs(StartTab.RECENTS), l.barTabs(StartTab.RECENTS))
        assertTrue(l.absorbed.isEmpty())
        StartTab.entries.forEach { assertEquals(it, l.hostOf(it)) }
        assertFalse(l.keypadDocked)
        assertFalse(l.favoritesInContacts)
        assertEquals(StartTab.FAVORITES, l.circleHost)
    }

    @Test fun combined_calls_hide_the_keypad_tab_without_touching_nav_tabs() {
        val tabs = NavTabs()
        val l = HomeLayout(tabs, combined)
        assertTrue(l.keypadDocked)
        assertEquals(listOf(StartTab.FAVORITES, StartTab.RECENTS, StartTab.CONTACTS), l.visible)
        assertEquals(StartTab.RECENTS, l.hostOf(StartTab.KEYPAD))
        assertTrue(l.opensDockedKeypad(StartTab.KEYPAD))
        assertFalse(l.opensDockedKeypad(StartTab.RECENTS))
        // The saved bar is untouched: switching back restores it exactly.
        assertEquals(tabs.encode(), l.tabs.encode())
        assertEquals(four, HomeLayout(tabs, combined.separated()).visible)
    }

    @Test fun keeping_the_keypad_tab_is_the_users_choice() {
        val l = HomeLayout(NavTabs(), combined.copy(keepKeypadTab = true))
        assertTrue(l.keypadDocked)
        assertEquals(four, l.visible)
        // A dial request then opens the real Keypad tab.
        assertEquals(StartTab.KEYPAD, l.hostOf(StartTab.KEYPAD))
        assertFalse(l.opensDockedKeypad(StartTab.KEYPAD))
    }

    @Test fun a_host_hidden_by_the_user_never_absorbs() {
        // Recents hidden in the bar: the keypad can't dock there, so the Keypad tab stays.
        val noRecents = NavTabs().setVisible(StartTab.RECENTS, false)
        val l = HomeLayout(noRecents, combined)
        assertFalse(l.keypadDocked)
        assertTrue(l.isVisible(StartTab.KEYPAD))
        assertEquals(StartTab.KEYPAD, l.hostOf(StartTab.KEYPAD))
        // Contacts hidden: favourites stay in their tab.
        val noContacts = NavTabs().setVisible(StartTab.CONTACTS, false)
        val f = HomeLayout(noContacts, favSection)
        assertFalse(f.favoritesInContacts)
        assertTrue(f.isVisible(StartTab.FAVORITES))
    }

    @Test fun favourites_in_contacts_hide_the_tab_and_move_the_circle() {
        val l = HomeLayout(NavTabs(), favSection)
        assertTrue(l.favoritesInContacts)
        assertEquals(listOf(StartTab.RECENTS, StartTab.CONTACTS, StartTab.KEYPAD), l.visible)
        assertEquals(StartTab.CONTACTS, l.hostOf(StartTab.FAVORITES))
        // R1: the Circle section follows the favourites into Contacts.
        assertEquals(StartTab.CONTACTS, l.circleHost)
        // Keeping the Favorites tab keeps the Circle there, as before.
        val kept = HomeLayout(NavTabs(), favSection.copy(keepFavoritesTab = true))
        assertEquals(four, kept.visible)
        assertEquals(StartTab.FAVORITES, kept.circleHost)
        // A shown Circle tab has no section anywhere.
        assertNull(HomeLayout(NavTabs().setVisible(StartTab.CIRCLE, true), favSection).circleHost)
    }

    @Test fun start_tab_falls_back_to_the_host() {
        val both = SurfaceLayout(calls = CallsLayout.COMBINED, favorites = FavoritesPlacement.STRIP)
        val l = HomeLayout(NavTabs(), both)
        assertEquals(StartTab.RECENTS, l.startTab(StartTab.KEYPAD))
        assertEquals(StartTab.CONTACTS, l.startTab(StartTab.FAVORITES))
        assertEquals(StartTab.RECENTS, l.startTab(StartTab.RECENTS))
        // A start tab hidden by the user still falls back to the first tab in the bar.
        assertEquals(StartTab.RECENTS, l.startTab(StartTab.CIRCLE))
        // The request keeps the Keypad, so the docked keypad opens unfolded.
        assertEquals(StartTab.KEYPAD, l.startRequest(StartTab.KEYPAD))
        assertEquals(StartTab.RECENTS, l.startRequest(StartTab.CIRCLE))
    }

    @Test fun one_visible_tab_hides_the_bar() {
        // Only Recents and Keypad shown, then combined: one tab left, so no bar.
        val tabs = NavTabs().setVisible(StartTab.FAVORITES, false).setVisible(StartTab.CONTACTS, false)
        val l = HomeLayout(tabs, combined)
        assertEquals(listOf(StartTab.RECENTS), l.visible)
        assertFalse(l.showBar(StartTab.RECENTS))
        // A link to a hidden tab brings the bar back while it's open.
        assertTrue(l.showBar(StartTab.CIRCLE))
        assertTrue(HomeLayout(tabs, SurfaceLayout()).showBar(StartTab.RECENTS))
    }

    @Test fun visible_is_never_empty() {
        StartTab.entries.forEach { only ->
            var tabs = NavTabs()
            StartTab.entries.filter { it != only }.forEach { tabs = tabs.setVisible(it, false) }
            tabs = tabs.setVisible(only, true)
            listOf(SurfaceLayout(), combined, favSection, SurfaceLayout(calls = CallsLayout.COMBINED, favorites = FavoritesPlacement.SECTION)).forEach { s ->
                val l = HomeLayout(tabs, s)
                assertTrue("$only $s", l.visible.isNotEmpty())
                assertTrue(l.isVisible(l.startTab(StartTab.RECENTS)))
            }
        }
    }

    @Test fun layout_settings_are_searchable() {
        fun keys(q: String) = SettingsSearch.search(q).map { it.key }
        listOf("calls_layout", "favorites_in_contacts", "recent_tap").forEach { SettingsCatalog[it] }
        assertTrue("calls_layout" in keys("combine"))
        assertTrue("calls_layout" in keys("keypad"))
        assertTrue("favorites_in_contacts" in keys("favourites"))
        assertTrue("favorites_in_contacts" in keys("favorites"))
        assertTrue("calls_layout" in keys("merge tabs"))
        assertTrue("favorites_in_contacts" in keys("merge tabs"))
        assertEquals("recent_tap", keys("tap recents").first())
    }
}
