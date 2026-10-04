package app.parley.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.parley.common.SettingsCatalog
import app.parley.common.SettingsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings search with the words the pages show (the app's string resources, the one copy), so search can never
 * match a title nobody sees. Old names and synonyms live in each setting's keywords.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsWordsTest {
    private val catalog by lazy { SettingsText.localizedCatalog(ApplicationProvider.getApplicationContext<Context>()) }

    private fun keys(q: String) = SettingsSearch.search(q, catalog).map { it.key }

    @Test fun every_setting_has_words_and_titles_are_distinct_per_category() {
        assertEquals(SettingsCatalog.entries.size, catalog.size)
        catalog.forEach { assertTrue(it.key, it.title.isNotBlank() && it.summary.isNotBlank() && it.categoryTitle.isNotBlank()) }
        assertEquals(catalog.size, catalog.map { it.category to it.title }.toSet().size)
    }

    @Test fun settings_on_their_own_screens_are_searchable() {
        assertEquals("blk_hidden_numbers", keys("withheld").first())
        assertTrue("blk_off_hours" in keys("bedtime"))
        assertTrue("ct_supervised" in keys("parental"))
        assertTrue("backup_keep" in keys("rotation"))
        assertTrue("history_details" in keys("recently deleted"))
        assertTrue("journal" in keys("recently deleted"))
    }

    @Test fun calls_settings_are_found_by_their_old_words() {
        assertEquals("answer_gesture", keys("answer incoming calls").first())
        assertTrue("power_button_ends_call" in keys("power button"))
        assertEquals("speaker_default", keys("speakerphone").first())
        assertEquals("flip_to_silence", keys("face down").first())
        assertTrue("sim_accounts" in keys("wifi calling"))
        assertTrue("proximity_sensor" in keys("proximity"))
        assertTrue("pocket_guard" in keys("pocket dial"))
        assertTrue("missed_realert" in keys("missed call reminder"))
        assertTrue("voicemail" in keys("visual voicemail"))
        assertTrue("auto_answer" in keys("auto answer"))
        assertTrue("auto_answer" in keys("bluetooth"))
        assertTrue("caller_vibration" in keys("vibration pattern"))
        assertTrue("recents_remember_filter" in keys("unknown callers"))
    }

    @Test fun sort_order_and_name_order_are_two_settings() {
        val old = keys("sort and show names by")
        assertTrue(old.toString(), "sort_names" in old && "name_order" in old)
        assertEquals("name_order", keys("show names as").first())
        assertEquals("sort_names", keys("sort by").first())
        assertTrue("name_order" in keys("last name first"))
    }

    @Test fun links_and_reminders_are_found() {
        assertTrue("reminders" in keys("reminders"))
        assertTrue("to_call" in keys("call back later"))
        mapOf(
            "missed_realert" to "re-alert", "birthday_reminders" to "birthday reminders", "reminder_time" to "reminder time",
            "nudges" to "keep in touch", "circle_delivery" to "digest", "circle_weekly_cap" to "per week", "date_lead" to "days before",
            "backup_reminder" to "remind me to back up", "temp_ask_first" to "ask before deleting", "to_call" to "remind me",
            "memory_prompt" to "follow up", "reminders" to "reminders",
        ).forEach { (key, words) -> assertTrue("$words finds $key", key in keys(words)) }
        // Call time's "Reminders & limits" ranks with it: both titles start with the word.
        assertTrue("reminders" in keys("reminders").take(2))
    }

    @Test fun contact_list_buttons_are_found() {
        listOf("call button", "hide buttons", "clean list", "message button", "Call & message buttons").forEach { q ->
            assertTrue(q, "row_actions" in keys(q))
        }
    }

    @Test fun tools_is_one_hub_found_by_both_names() {
        assertEquals("Tools", catalog.first { it.key == "what_parley_can_do" }.title)
        assertTrue("what_parley_can_do" in keys("tools"))
        assertTrue("what_parley_can_do" in keys("what parley can do"))
        assertTrue("what_parley_can_do" in keys("who's in"))
    }

    @Test fun title_matches_rank_first() {
        assertEquals("theme", keys("theme").first())
        assertEquals("speed_dial", keys("speed").first())
        assertEquals("app_lock", keys("app lock").first())
        assertEquals("swipe_actions", keys("swipe").first())
        assertEquals("crash_reports", keys("crash").first())
        assertEquals("recent_tap", keys("tap recents").first())
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
        assertTrue("private_directory" in keys("android auto"))
        assertTrue("my_details" in keys("my card"))
        assertTrue("scan_qr" in keys("qr"))
        assertTrue("calls_layout" in keys("combine"))
        assertTrue("calls_layout" in keys("keypad"))
        assertTrue("favorites_in_contacts" in keys("favourites"))
        assertTrue("favorites_in_contacts" in keys("favorites"))
        assertTrue("calls_layout" in keys("merge tabs"))
        assertTrue("favorites_in_contacts" in keys("merge tabs"))
    }

    @Test fun every_word_must_match_and_case_and_accents_are_ignored() {
        val v = keys("VIBRATION")
        assertTrue("keypad_vibration" in v && "call_haptics" in v)
        assertEquals(listOf("keypad_vibration"), keys("keypad vibration").take(1))
        assertTrue(keys("keypad zzzz").isEmpty())
        assertTrue("theme" in keys("thème"))
    }

    @Test fun category_name_finds_its_settings() {
        val r = keys("privacy")
        assertTrue("privacy_dashboard" in r)
        assertTrue("secure_screen" in r)
    }
}
