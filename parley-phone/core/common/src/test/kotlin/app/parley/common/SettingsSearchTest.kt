package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private fun keys(q: String) = SettingsSearch.search(q).map { it.key }

    @Test fun catalog_keys_are_unique_and_every_category_has_settings() {
        val all = SettingsCatalog.entries
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals(all.size, all.map { it.category to it.title }.toSet().size)
        SettingsCategory.entries.forEach { assertTrue(it.name, SettingsCatalog.inCategory(it).isNotEmpty()) }
        all.forEach { assertTrue(it.key, it.title.isNotBlank() && it.summary.isNotBlank()) }
    }

    @Test fun settings_on_their_own_screens_are_searchable() {
        assertEquals("blk_hidden_numbers", keys("withheld").first())
        assertTrue("blk_off_hours" in keys("bedtime"))
        assertTrue("ct_supervised" in keys("parental"))
        assertTrue("backup_keep" in keys("rotation"))
        assertTrue("history_details" in keys("recently deleted"))
        assertTrue("journal" in keys("recently deleted"))
        assertEquals(SettingPlace.BLOCKING, SettingsCatalog["blk_off_hours"].place)
        assertEquals(SettingPlace.TOOLS, SettingsCatalog["scan_qr"].place)
        assertEquals(null, SettingsCatalog["theme"].place)
    }

    @Test fun layout_and_gestures_is_split_from_appearance() {
        listOf("nav_tabs", "start_tab", "calls_layout", "favorites_in_contacts", "recent_tap", "swipe_actions", "simple_mode")
            .forEach { assertEquals(it, SettingsCategory.LAYOUT, SettingsCatalog[it].category) }
        listOf("theme", "amoled", "density", "avatar_style", "sort_names").forEach { assertEquals(it, SettingsCategory.APPEARANCE, SettingsCatalog[it].category) }
        // No page is overloaded any more (Calls holds 23 since its Accessibility group; more rows belong on a screen of their own).
        SettingsCategory.entries.forEach { c -> assertTrue(c.name, SettingsCatalog.inCategory(c).count { it.place == null } <= 23) }
    }

    @Test fun every_reminder_is_on_reminders_and_found_by_its_old_words() {
        val onReminders = listOf(
            "missed_realert", "birthday_reminders", "reminder_time", "date_lead", "nudges", "circle_delivery", "circle_weekly_cap",
            "backup_reminder", "reminders", "to_call",
        )
        onReminders.forEach { assertEquals(it, SettingPlace.REMINDERS, SettingsCatalog[it].place) }
        // Their category stays, so search still says "Contacts", "Calls" or "Backup & sync" above them.
        assertEquals(SettingsCategory.CALLS, SettingsCatalog["missed_realert"].category)
        assertEquals(SettingsCategory.CONTACTS, SettingsCatalog["birthday_reminders"].category)
        assertEquals(SettingsCategory.BACKUP, SettingsCatalog["backup_reminder"].category)
        mapOf(
            "missed_realert" to "re-alert", "birthday_reminders" to "birthday reminders", "reminder_time" to "reminder time",
            "nudges" to "keep in touch", "circle_delivery" to "digest", "circle_weekly_cap" to "per week", "date_lead" to "days before",
            "backup_reminder" to "remind me to back up", "temp_ask_first" to "ask before deleting", "to_call" to "remind me",
            "memory_prompt" to "follow up", "reminders" to "reminders",
        ).forEach { (key, words) -> assertTrue("$words finds $key", key in keys(words)) }
        // Call time's "Reminders & limits" ranks with it: both titles start with the word.
        assertTrue("reminders" in keys("reminders").take(2))
    }

    @Test fun tools_is_one_hub_found_by_both_names() {
        assertEquals(SettingPlace.TOOLS, SettingsCatalog["what_parley_can_do"].place)
        assertEquals("Tools", SettingsCatalog["what_parley_can_do"].title)
        assertTrue("what_parley_can_do" in keys("tools"))
        assertTrue("what_parley_can_do" in keys("what parley can do"))
    }

    @Test fun contact_list_buttons_live_in_contacts_and_are_found() {
        assertEquals(SettingsCategory.CONTACTS, SettingsCatalog["row_actions"].category)
        assertEquals(null, SettingsCatalog["row_actions"].place)
        listOf("call button", "hide buttons", "clean list", "message button", "Call & message buttons").forEach { q ->
            assertTrue(q, "row_actions" in keys(q))
        }
    }

    @Test fun empty_query_finds_nothing() {
        assertTrue(keys("").isEmpty())
        assertTrue(keys("   ").isEmpty())
    }

    @Test fun title_matches_rank_first() {
        assertEquals("theme", keys("theme").first())
        assertEquals("speed_dial", keys("speed").first())
        assertEquals("app_lock", keys("app lock").first())
    }

    @Test fun keywords_and_synonyms() {
        assertTrue("theme" in keys("dark mode"))
        assertTrue("dynamic_color" in keys("color"))
        assertTrue("nav_tabs" in keys("tabs"))
        assertTrue("nav_tabs" in keys("bottom bar"))
        assertTrue("keypad_letters" in keys("cyrillic"))
        assertTrue("blocking" in keys("spam"))
        assertTrue("temporary_contacts" in keys("expire"))
        assertTrue("carrier_settings" in keys("voicemail"))
    }

    @Test fun every_word_must_match_and_case_and_accents_are_ignored() {
        val v = keys("VIBRATION")
        assertTrue("keypad_vibration" in v && "call_haptics" in v)
        assertEquals(listOf("keypad_vibration"), keys("keypad vibration").take(1))
        assertTrue(keys("keypad zzzz").isEmpty())
        assertTrue("theme" in keys("thème"))
    }

    /** The app replaces titles with string resources; search then matches both languages. */
    private val german = SettingsCatalog.entries.map { e ->
        when (e.key) {
            "theme" -> e.localized("Design", "System, hell oder dunkel", listOf("Dunkelmodus", "Nachtmodus"), "Darstellung")
            "app_lock" -> e.localized("App-Sperre", "Fingerabdruck oder Displaysperre", listOf("Sperre", "Biometrie"), "Datenschutz & Sicherheit")
            else -> e
        }
    }

    private fun germanKeys(q: String) = SettingsSearch.search(q, german).map { it.key }

    @Test fun localized_titles_and_keywords_are_found() {
        assertEquals("theme", germanKeys("design").first())
        assertTrue("theme" in germanKeys("dunkelmodus"))
        assertEquals("app_lock", germanKeys("app-sperre").first())
        assertTrue("app_lock" in germanKeys("datenschutz"))
    }

    @Test fun english_words_still_find_localized_entries() {
        assertTrue("theme" in germanKeys("theme"))
        assertTrue("theme" in germanKeys("dark mode"))
        assertTrue("app_lock" in germanKeys("fingerprint"))
        assertTrue("app_lock" in germanKeys("privacy"))
        val theme = german.first { it.key == "theme" }
        assertEquals("Design", theme.title)
        assertTrue("Theme" in theme.keywords)
        assertEquals(listOf("Darstellung", "Appearance"), theme.categoryTitles)
    }

    @Test fun category_name_finds_its_settings() {
        val r = keys("privacy")
        assertTrue("privacy_dashboard" in r)
        assertTrue("secure_screen" in r)
    }
}
