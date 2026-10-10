package app.parley.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test fun catalog_keys_are_unique_and_every_category_has_settings() {
        val all = SettingsCatalog.entries
        assertEquals(all.size, all.map { it.key }.toSet().size)
        SettingsCategory.entries.forEach { assertTrue(it.name, SettingsCatalog.inCategory(it).isNotEmpty()) }
    }

    @Test fun settings_on_their_own_screens_have_a_place() {
        assertEquals(SettingPlace.BLOCKING, SettingsCatalog["blk_off_hours"].place)
        assertEquals(SettingPlace.TOOLS, SettingsCatalog["scan_qr"].place)
        assertEquals(null, SettingsCatalog["theme"].place)
    }

    @Test fun layout_and_gestures_is_split_from_appearance() {
        listOf("nav_tabs", "start_tab", "calls_layout", "favorites_in_contacts", "swipe_actions", "simple_mode")
            .forEach { assertEquals(it, SettingsCategory.LAYOUT, SettingsCatalog[it].category) }
        listOf(
            "theme", "amoled", "density", "avatar_style", "sort_names",
        ).forEach { assertEquals(it, SettingsCategory.APPEARANCE, SettingsCatalog[it].category) }
        // No page is overloaded: more rows belong on a screen of their own (a SettingPlace).
        SettingsCategory.entries.forEach { c -> assertTrue(c.name, SettingsCatalog.inCategory(c).count { it.place == null } <= PAGE_LIMIT) }
    }

    @Test fun calls_is_a_short_list_with_pages_of_its_own() {
        val onCalls = SettingsCatalog.inCategory(SettingsCategory.CALLS).filter { it.place == null }.map { it.key }
        assertTrue(onCalls.toString(), onCalls.size <= 8)
        assertTrue("default_dialer" in onCalls)
        mapOf(
            "answer_gesture" to SettingPlace.CALLS_ANSWERING, "auto_answer" to SettingPlace.CALLS_ANSWERING, "answer_rtt" to SettingPlace.CALLS_ANSWERING,
            "unknown_ringtone" to SettingPlace.CALLS_ANSWERING, "proximity_sensor" to SettingPlace.CALLS_DURING,
            "flip_to_silence" to SettingPlace.CALLS_ANSWERING, "speaker_default" to SettingPlace.CALLS_DURING,
            "power_button_ends_call" to SettingPlace.CALLS_DURING, "memory_prompt" to SettingPlace.CALLS_DURING,
            "sims" to SettingPlace.CALLS_SIMS, "carrier_settings" to SettingPlace.CALLS_SIMS,
            "call_helpers" to SettingPlace.HELPERS, "phone_menus" to SettingPlace.PHONE_MENUS,
            "call_time" to SettingPlace.CALLS_DURING, "situations" to SettingPlace.CALLS_SITUATIONS,
            "network_names" to SettingPlace.CALLS_ANSWERING, "quick_replies" to SettingPlace.CALLS_ANSWERING,
            "keypad_tones" to SettingPlace.CALLS_KEYPAD, "speed_dial" to SettingPlace.CALLS_KEYPAD, "ussd" to SettingPlace.CALLS_KEYPAD,
        ).forEach { (key, place) -> assertEquals(key, place, SettingsCatalog[key].place) }
        // The Call time category dissolved into Calls › Situations; its settings are Calls' now.
        listOf("call_time", "ct_reminders", "ct_limits", "ct_supervised").forEach { assertEquals(it, SettingsCategory.CALLS, SettingsCatalog[it].category) }
    }

    /**
     * Settings holds preferences: a tool is launched from Tools, and its Settings entry is only a way there (a link),
     * so search finds it without a launcher row on a Settings page.
     */
    @Test fun tools_are_links_to_tools_not_settings() {
        val tools = listOf(
            "dry_run", "labels", "temporary_contacts", "duplicates", "health", "bulk_add", "birthdays", "insights", "messaged_numbers",
            "history_details", "time_machine", "scan_qr", "coming_from", "what_parley_can_do",
        )
        tools.forEach { k ->
            assertEquals(k, SettingPlace.TOOLS, SettingsCatalog[k].place)
            assertTrue(k, SettingsCatalog[k].link)
        }
        assertTrue(SettingsCatalog.entries.filter { it.place == SettingPlace.TOOLS }.all { it.link })
        // Folded into one choice each, or gone with the private-name lookup provider.
        // The drive profile's entry became Situations (the car is set from there).
        listOf("silence_sales_lines", "connect_haptic", "private_names", "drive_profile").forEach { k ->
            assertTrue(k, SettingsCatalog.entries.none { it.key == k })
        }
    }

    /**
     * The settings tree: Keypad and Messaging dissolved into Calls (and Tools), Blocking & spam opens its screen, one
     * row for how Recents looks, and the rows that held one rarely changed value each went (folded into another choice,
     * a tick box of the export sheet, or always on when it matters).
     */
    @Test fun the_settings_tree_keeps_its_keys_in_their_new_places() {
        listOf("keypad_tones", "keypad_vibration", "keypad_letters", "speed_dial", "ussd", "quick_replies", "phone_menus", "call_time")
            .forEach { assertEquals(it, SettingsCategory.CALLS, SettingsCatalog[it].category) }
        assertTrue(SettingsCatalog.isAdvanced("phone_menus") && SettingsCatalog.isAdvanced("call_time"))
        assertTrue(!SettingsCatalog.isAdvanced("keypad_tones") && !SettingsCatalog.isAdvanced("quick_replies"))
        assertEquals(SettingPlace.MESSAGED, SettingsCatalog["messaged_expiry"].place)
        // Blocking & spam has no page: all its settings are on the screen its row opens.
        assertTrue(!SettingsCatalog.hasPage(SettingsCategory.BLOCKING))
        assertTrue(SettingsCatalog.inCategory(SettingsCategory.BLOCKING).filterNot { it.link }.all { it.place == SettingPlace.BLOCKING })
        SettingsCategory.entries.filter { it != SettingsCategory.BLOCKING }.forEach { assertTrue(it.name, SettingsCatalog.hasPage(it)) }
        // One "Recents view" row for three values.
        SettingsCatalog.RECENTS_VIEW.forEach { assertEquals(it, SettingsCategory.HISTORY, SettingsCatalog[it].category) }
        // Help & tips is under About.
        assertEquals(SettingsCategory.ABOUT, SettingsCatalog["reset_tips"].category)
        listOf("memory_lock_screen", "first_mover", "csv_bom", "sim_labels").forEach { k -> assertTrue(k, SettingsCatalog.entries.none { it.key == k }) }
        // Circle settings land on a group that is open: its own row isn't folded under Advanced.
        assertTrue(!SettingsCatalog.isAdvanced("log_prompts"))
    }

    @Test fun sort_order_and_name_order_are_two_settings() {
        assertEquals(SettingsCategory.APPEARANCE, SettingsCatalog["name_order"].category)
        assertEquals(SettingsCategory.APPEARANCE, SettingsCatalog["sort_names"].category)
    }

    /**
     * Settings may not grow without anyone noticing. Two budgets: the real preferences (rows that store a choice) stay at
     * or below [PREFERENCES_CEILING], and every settings row, ways to screens and one-off actions included, stays at or
     * below [SETTINGS_CEILING] so links don't sprawl either. Links to pages and lists (Reminders, To call) are
     * searchable but aren't rows of Settings, so neither counts them.
     */
    @Test fun settings_do_not_grow_silently() {
        val p = SettingsCatalog.preferences.size
        assertTrue(
            "Settings has $p preferences, more than its ceiling of $PREFERENCES_CEILING. Replace an existing setting or fold the new " +
                "one into it rather than adding to the list; a setting moved onto a screen of its own (SettingPlace) still counts. " +
                "Raise PREFERENCES_CEILING only when the owner agrees.",
            p <= PREFERENCES_CEILING,
        )
        val n = SettingsCatalog.settings.size
        assertTrue("Settings has $n rows, more than the outer cap of $SETTINGS_CEILING.", n <= SETTINGS_CEILING)
    }

    @Test fun rows_that_store_nothing_are_named_and_are_settings_rows() {
        SettingsCatalog.NOT_STORED.forEach { k ->
            val e = SettingsCatalog.entries.firstOrNull { it.key == k }
            assertTrue("$k is in NOT_STORED but not a settings row", e != null && !e.link)
        }
        // A switch or a choice is always a preference.
        listOf("theme", "app_lock", "lock_screen_caller", "people_card", "repeat_callers", "keypad_tones").forEach { k ->
            assertTrue(k, SettingsCatalog.preferences.any { it.key == k })
        }
    }

    @Test fun links_are_ways_to_pages_and_tools_not_settings() {
        val links = SettingsCatalog.entries.filter { it.link }
        assertEquals(listOf("reminders", "to_call"), links.filter { it.place == SettingPlace.REMINDERS }.map { it.key })
        assertTrue(links.all { it.place == SettingPlace.REMINDERS || it.place == SettingPlace.TOOLS })
    }

    @Test fun every_reminder_is_on_reminders() {
        val onReminders = listOf(
            "missed_realert", "birthday_reminders", "reminder_time", "date_lead", "nudges", "circle_delivery", "circle_weekly_cap",
            "backup_reminder", "reminders", "to_call",
        )
        onReminders.forEach { assertEquals(it, SettingPlace.REMINDERS, SettingsCatalog[it].place) }
        // Their category stays, so search still says "Contacts", "Calls" or "Backup & sync" above them.
        assertEquals(SettingsCategory.CALLS, SettingsCatalog["missed_realert"].category)
        assertEquals(SettingsCategory.CONTACTS, SettingsCatalog["birthday_reminders"].category)
        assertEquals(SettingsCategory.BACKUP, SettingsCatalog["backup_reminder"].category)
    }

    @Test fun contact_list_buttons_live_in_contacts() {
        assertEquals(SettingsCategory.CONTACTS, SettingsCatalog["row_actions"].category)
        assertEquals(null, SettingsCatalog["row_actions"].place)
    }

    @Test fun advanced_settings_are_real_settings_and_each_page_keeps_its_basics_open() {
        SettingsCatalog.ADVANCED.forEach { k ->
            val e = SettingsCatalog.entries.firstOrNull { it.key == k }
            assertTrue("$k is in ADVANCED but not a setting", e != null && !e.link && e.advanced)
        }
        assertTrue(SettingsCatalog.isAdvanced("amoled"))
        assertTrue(!SettingsCatalog.isAdvanced("theme") && !SettingsCatalog.isAdvanced(null) && !SettingsCatalog.isAdvanced("no_such_key"))
        // Every page still opens on something to set, and a basic user sees a short page.
        SettingsCategory.entries.filter { SettingsCatalog.hasPage(it) }.forEach { c ->
            val basics = SettingsCatalog.inCategory(c).count { it.place == null && !it.advanced }
            assertTrue("${c.name} shows $basics basic rows", basics in 1..BASIC_LIMIT)
        }
    }

    /** The scorer itself, on made-up words (the real ones are the app's, tested there). */
    private val sample = listOf(
        SettingEntry("theme", SettingsCategory.APPEARANCE).withTexts("Theme", "System, light or dark", listOf("dark mode"), "Appearance"),
        SettingEntry("keypad_vibration", SettingsCategory.CALLS).withTexts("Keypad vibration", "Buzz on each key", listOf("haptic"), "Keypad"),
        SettingEntry("call_haptics", SettingsCategory.CALLS).withTexts("Call vibration", "When a call connects", listOf("vibration"), "Calls"),
    )

    private fun sampleKeys(q: String) = SettingsSearch.search(q, sample).map { it.key }

    @Test fun empty_query_finds_nothing() {
        assertTrue(sampleKeys("").isEmpty())
        assertTrue(sampleKeys("   ").isEmpty())
    }

    @Test fun every_word_must_match_and_case_and_accents_are_ignored() {
        val v = sampleKeys("VIBRATION")
        assertTrue("keypad_vibration" in v && "call_haptics" in v)
        assertEquals(listOf("keypad_vibration"), sampleKeys("keypad vibration").take(1))
        assertTrue(sampleKeys("keypad zzzz").isEmpty())
        assertTrue("theme" in sampleKeys("thème"))
    }

    @Test fun title_beats_keyword_beats_category() {
        assertEquals("theme", sampleKeys("theme").first())
        assertEquals(listOf("theme"), sampleKeys("dark mode"))
        assertEquals(listOf("theme"), sampleKeys("appearance"))
    }

    private companion object {
        /** Searchable rows a category page may hold itself. */
        const val PAGE_LIMIT = 22

        /** Rows a category page shows before its "Advanced" group. */
        const val BASIC_LIMIT = 12

        /**
         * Every setting, wherever it lives (links not counted). 161 before 5.1; 5.1 added "Show names as" (name_order),
         * which the plan asked for by splitting "Sort and show names by" in two, as Android's Contacts does. 5.3 added
         * "Start calls on speaker" (speaker_default) and "Flip to silence" (flip_to_silence), which the owner approved
         * with the plan; "proximity only after answering" folded into the proximity setting instead. 5.6 lowered it to 147:
         * Settings holds preferences only, so 11 tool launchers became links to Tools (with Scan QR, Coming from another
         * phone? and Tools itself), Sales lines and Vibrate during calls each became one choice, and "Let apps show
         * private names" went with the lookup provider. 6.0's Situations took the drive profile's entry, so the count held.
         * 6.2.2 raised it to 148 for "Remember names from the network" (network_names), off by default: the owner asked
         * for it as its own switch, and no caller-ID setting holds it honestly (the contact photo, the lock-screen rule
         * and the ringtone for unknown callers are each about something else). Since 6.4 it is the outer cap on every
         * row, and the real preferences have a budget of their own ([PREFERENCES_CEILING]).
         */
        const val SETTINGS_CEILING = 148

        /**
         * Settings that store a choice (the owner's decision in 6.4: count choices, not ways to screens). 6.4's settings
         * tree took four away (notes on the lock screen into "Caller on the lock screen", "who reaches out first" into
         * the People card's choice, the CSV byte-order mark into the export sheet, SIM labels always on with two SIMs).
         */
        const val PREFERENCES_CEILING = 100
    }
}
