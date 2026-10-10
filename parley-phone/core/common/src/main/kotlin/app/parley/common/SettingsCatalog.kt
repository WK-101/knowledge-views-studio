package app.parley.common

/**
 * Top-level groups of Settings, in the order they're listed. Their names are the app's string resources. Settings holds
 * preferences only: tools are launched from Tools, and a tool's search entry here is a link ([SettingEntry.link]).
 * Keypad and Messaging were categories of their own until they went into Calls (and Tools): their links open Calls'
 * pages. Blocking & spam has no page: its row opens the Blocking & screening screen, where all its settings are.
 */
enum class SettingsCategory { APPEARANCE, LAYOUT, CALLS, BLOCKING, CONTACTS, HISTORY, PRIVACY, BACKUP, NOTIFICATIONS, ABOUT }

/**
 * Screens outside the category pages that hold settings or tools. Settings search opens them for their entries, so
 * every setting is searchable wherever it lives.
 */
enum class SettingPlace {
    TOOLS, BLOCKING, SIMS, CONTACT_PAGE, SIMPLE_MODE, CALL_TIME, BACKUP, SYNC, TEMPORARY, HELPERS, DRIVE_PROFILE, PHONE_MENUS, SHARED_LABELS,

    /**
     * Settings › Calls' own pages: Answering, During calls, Keypad & dialling, SIMs & carrier and Situations (Calls
     * itself keeps a short list).
     */
    CALLS_ANSWERING, CALLS_DURING, CALLS_KEYPAD, CALLS_SIMS, CALLS_SITUATIONS,

    /**
     * Settings › Reminders: every kind of reminder Parley sends, each with its switch and time (missed calls, To call
     * and follow-ups, keep in touch, birthdays and dates, backups, temporary contacts).
     */
    REMINDERS,

    /** Settings › Privacy › App lock › Unlock with: the Parley PIN and the duress PIN. */
    APP_LOCK,

    /** Tools › Messaged numbers: when the record of numbers you opened chats with forgets them, beside the list. */
    MESSAGED,
}

/**
 * One searchable setting, identified by its stable [key]. [SettingsCatalog] holds where it lives; its words are the
 * app's string resources (one copy, what the screens show), filled in with [withTexts] for search. A screen may replace
 * the summary with a live value such as "Last backup 2 h ago".
 */
data class SettingEntry(
    val key: String,
    val category: SettingsCategory,
    /** Where the setting lives when it isn't on its category's page (a screen of its own); null: the page. */
    val place: SettingPlace? = null,
    /**
     * A way to a page, a list or a tool (Reminders, To call, Find & merge duplicates…), found by search like a setting
     * but holding no value of its own. The settings budget doesn't count it.
     */
    val link: Boolean = false,
    /**
     * Rarely changed: folded under its page's "Advanced" group, which opens by itself when search points here
     * ([SettingsCatalog.ADVANCED]).
     */
    val advanced: Boolean = false,
    val title: String = "",
    val summary: String = "",
    val keywords: List<String> = emptyList(),
    /** The category's name, which search also matches. */
    val categoryTitle: String = "",
) {
    /** This entry with the words search matches. */
    fun withTexts(title: String, summary: String, keywords: List<String>, categoryTitle: String): SettingEntry = copy(
        title = title,
        summary = summary,
        keywords = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        categoryTitle = categoryTitle,
    )
}

/**
 * Every setting Parley has, one place. The category screens list them from here and Settings search searches this
 * list (with the app's words), so a setting can't be on a screen and missing from search (or the other way round).
 */
object SettingsCatalog {
    private fun e(key: String, category: SettingsCategory) = SettingEntry(key, category)

    /** A setting on a screen of its own ([place]), searchable like the others. */
    private fun at(place: SettingPlace, key: String, category: SettingsCategory) = SettingEntry(key, category, place)

    private val A = SettingsCategory.APPEARANCE
    private val L = SettingsCategory.LAYOUT
    private val C = SettingsCategory.CALLS
    private val B = SettingsCategory.BLOCKING
    private val P = SettingsCategory.CONTACTS
    private val H = SettingsCategory.HISTORY
    private val S = SettingsCategory.PRIVACY
    private val U = SettingsCategory.BACKUP
    private val N = SettingsCategory.NOTIFICATIONS
    private val O = SettingsCategory.ABOUT

    /** A way to a page or list on [place] ([SettingEntry.link]): searchable, not a setting. */
    private fun link(place: SettingPlace, key: String, category: SettingsCategory) = SettingEntry(key, category, place, link = true)

    /**
     * A tool, launched from Tools rather than from a Settings page: search still finds it under its category's name
     * and opens the tool itself.
     */
    private fun tool(key: String, category: SettingsCategory) = link(SettingPlace.TOOLS, key, category)

    /** Settings › Reminders. */
    private val REM = SettingPlace.REMINDERS

    /**
     * Settings folded under "Advanced" on their page: rarely changed once set, or only for particular phones and needs.
     * Each page keeps its everyday rows open; the reasons per page are in docs/SETTINGS.md ("Basic and Advanced").
     * Kept apart from the list below so a page's split can change without moving its entries.
     */
    val ADVANCED: Set<String> = setOf(
        // Appearance
        "amoled", "density", "avatar_style", "second_line", "prefer_nickname",
        // Layout & gestures
        "calls_layout", "favorites_in_contacts", "swipe_actions",
        // Calls › Answering, During calls and Keypad & dialling
        "call_background", "auto_answer", "caller_vibration", "answer_rtt",
        "call_haptics", "power_button_ends_call", "memory_prompt", "pre_call_peek", "call_time",
        "keypad_letters", "speed_dial", "ussd", "phone_menus",
        // Contacts
        "mirror_relations", "contact_page", "import_sim", "export_account",
        // Recents & history
        "kept_forever", "import_calls", "people_card",
        // Privacy & security
        "secure_screen", "private_history", "who_can_see", "private_directory", "app_permissions", "delete_all_data",
        // Backup & sync
        "sync", "open_export",
    )

    val entries: List<SettingEntry> = listOf(
        // Appearance
        e("theme", A),
        e("amoled", A),
        e("dynamic_color", A),
        e("density", A),
        e("nav_tabs", L),
        e("start_tab", L),
        // Optional combined surfaces, and what a tap on a call does (in every layout).
        e("calls_layout", L),
        e("favorites_in_contacts", L),
        // "Sort by" and "Show names as" are apart, as in Android's Contacts; both keep the words of the one setting they were.
        e("sort_names", A),
        e("name_order", A),
        e("second_line", A),
        e("prefer_nickname", A),
        e("swipe_actions", L),
        e("avatar_style", A),
        e("simple_mode", L),
        // Calls
        e("default_dialer", C),
        // When Android refuses the role request without asking.
        e("default_dialer_help", C),
        at(SettingPlace.CALLS_ANSWERING, "answer_gesture", C),
        at(SettingPlace.CALLS_ANSWERING, "call_background", C),
        at(SettingPlace.CALLS_ANSWERING, "caller_photo", C),
        // Off by default: the name the mobile network shows for callers, kept after the call (Recents, the number's
        // page, under a saved name when it differs). The owner asked for it as a setting of its own.
        at(SettingPlace.CALLS_ANSWERING, "network_names", C),
        // Accessibility: RTT, where the carrier supports it. Its group also links Android's TTY and RTT settings, which
        // this entry's words find.
        at(SettingPlace.CALLS_ANSWERING, "answer_rtt", C),
        e("confirm_call", C),
        // Remember what matters.
        // "Notes on the lock screen" went into Privacy › Caller on the lock screen ("Name and notes"): one rule.
        at(SettingPlace.CALLS_DURING, "memory_prompt", C),
        at(SettingPlace.CALLS_DURING, "pre_call_peek", C),
        // One choice: Off, or on every change, with or without the buzz when they answer.
        at(SettingPlace.CALLS_DURING, "call_haptics", C),
        // "Ringing": the ring style (Normal · Increasing · Vibrate first, then ring; Normal by default) and "Flip to silence"
        // (off by default; turning the phone face down while it rings silences it, never declines), one setting. The key
        // stays for old links and search.
        at(SettingPlace.CALLS_ANSWERING, "flip_to_silence", C),
        at(SettingPlace.CALLS_ANSWERING, "unknown_ringtone", C),
        e("pocket_guard", C),
        at(REM, "missed_realert", C),
        // Auto-answer: off by default; only known callers, never during another call, always with a countdown and Cancel.
        at(SettingPlace.CALLS_ANSWERING, "auto_answer", C),
        // Haptic caller ID: set on a contact's or a label's page.
        at(SettingPlace.CALLS_ANSWERING, "caller_vibration", C),
        // Calls › Situations: the Situations themselves, then screens of their own (rescue call, helpers, the car).
        // Bring in my helper: a screen of its own, linked from Calls › Situations (and simple mode's setup).
        at(SettingPlace.HELPERS, "call_helpers", C),
        // Situations ("Driving", "Meeting", "Night", "Travelling" and those made): one tap sets several behaviours and
        // turning it off puts them back. It took the drive profile's entry: the car is set from Situations (Driving),
        // and search finds it by its old words.
        at(SettingPlace.CALLS_SITUATIONS, "situations", C),
        // Phone menus: a screen of its own, linked from Calls › Keypad & dialling.
        at(SettingPlace.PHONE_MENUS, "phone_menus", C),
        at(SettingPlace.PHONE_MENUS, "menu_memory", C),
        // On SIMs & plan minutes; only ever acts while a SIM is abroad.
        at(SettingPlace.SIMS, "assisted_dialling", C),
        at(SettingPlace.SIMS, "local_sim_hint", C),
        at(SettingPlace.CALLS_DURING, "proximity_sensor", C),
        // Off by default; only ever replaces the earpiece, never for emergency calls.
        at(SettingPlace.CALLS_DURING, "speaker_default", C),
        at(SettingPlace.CALLS_DURING, "power_button_ends_call", C),
        e("voicemail", C),
        at(SettingPlace.CALLS_SIMS, "sims", C),
        at(SettingPlace.CALLS_SIMS, "sim_accounts", C),
        at(SettingPlace.CALLS_SIMS, "carrier_settings", C),
        // Calls › Keypad & dialling (the Keypad category was too small for a root row of its own).
        at(SettingPlace.CALLS_KEYPAD, "keypad_tones", C),
        at(SettingPlace.CALLS_KEYPAD, "keypad_vibration", C),
        at(SettingPlace.CALLS_KEYPAD, "keypad_letters", C),
        at(SettingPlace.CALLS_KEYPAD, "speed_dial", C),
        at(SettingPlace.CALLS_KEYPAD, "ussd", C),
        // Talk-time reminders and limits: a screen of its own, on Calls › During calls (the Call time category went).
        at(SettingPlace.CALLS_DURING, "call_time", C),
        at(SettingPlace.SIMS, "plan_minutes", C),
        // Blocking & spam: the root row opens the Blocking & screening screen, where every one of these is.
        at(SettingPlace.BLOCKING, "blocking", B),
        at(SettingPlace.BLOCKING, "repeat_callers", B),
        // Sales lines: one choice (Off · Tag quietly · Tag and silence).
        at(SettingPlace.BLOCKING, "learn_from_calls", B),
        at(SettingPlace.BLOCKING, "expecting_call", B),
        at(SettingPlace.BLOCKING, "expected_hints", B),
        at(SettingPlace.BLOCKING, "spam_lists", B),
        at(SettingPlace.BLOCKING, "templates", B),
        tool("dry_run", B),
        at(SettingPlace.BLOCKING, "transfer", B),
        // Contacts
        // Settings › Contacts, where people look for how their contact list looks (it used to be under Layout & gestures).
        e("row_actions", P),
        e("default_account", P),
        tool("labels", P),
        e("mirror_relations", P),
        tool("temporary_contacts", P),
        // My card, where people look for it (it was under Messaging).
        e("my_details", P),
        // On the Temporary contacts screen, beside the contacts it's about.
        at(SettingPlace.TEMPORARY, "temp_ask_first", P),
        tool("duplicates", P),
        tool("health", P),
        e("contact_page", P),
        e("import_file", P),
        tool("bulk_add", P),
        // Scan QR (search finds it; it opens the scan screen).
        tool("scan_qr", P),
        // Finding your way (on Tools; search opens the page itself).
        tool("coming_from", P),
        // The one hub (it was "What Parley can do" and, separately, Tools): the key stays for old links.
        tool("what_parley_can_do", O),
        e("import_sim", P),
        e("export_vcf", P),
        e("export_csv", P),
        e("export_account", P),
        tool("birthdays", P),
        // Reminders of every kind live on one page (Settings › Reminders), linked from Contacts, Recents and Backup.
        at(REM, "birthday_reminders", P),
        at(REM, "reminder_time", P),
        at(REM, "nudges", P),
        // The Circle.
        at(REM, "date_lead", P),
        at(REM, "circle_delivery", P),
        at(REM, "circle_weekly_cap", P),
        e("log_prompts", P),
        // Recents & history
        e("archive", H),
        // Deleted calls come back from History & undo › Calls.
        tool("history_details", H),
        e("kept_forever", H),
        e("retention", H),
        // One "Recents view" row, the dialog Recents ⋮ opens: layout, style and what a tap does stay three values.
        e("recents_layout", H),
        e("recents_style", H),
        e("recent_tap", H),
        e("recents_remember_filter", H),
        e("clear_history", H),
        tool("insights", H),
        // The People card: one choice (Off · On · On, with who reaches out first), also on the card's ⋮.
        e("people_card", H),
        e("import_calls", H),
        // Quick replies are used when declining, so they are on Calls › Answering (Messaging went).
        at(SettingPlace.CALLS_ANSWERING, "quick_replies", C),
        tool("messaged_numbers", H),
        at(SettingPlace.MESSAGED, "messaged_expiry", H),
        // Privacy & security
        e("app_lock", S),
        e("lock_after", S),
        // How Parley unlocks; the PINs live on a screen of their own (the Privacy page keeps to its rows).
        e("app_lock_method", S),
        at(SettingPlace.APP_LOCK, "parley_pin", S),
        at(SettingPlace.APP_LOCK, "duress_pin", S),
        at(SettingPlace.APP_LOCK, "duress_lock_vault", S),
        e("secure_screen", S),
        e("lock_screen_caller", S),
        e("family_safe_word", S),
        e("hide_vault", S),
        e("private_history", S),
        e("privacy_dashboard", S),
        e("who_can_see", S),
        e("private_directory", S),
        e("app_permissions", S),
        e("delete_all_data", S),
        // Backup & sync
        e("backup", U),
        at(REM, "backup_reminder", U),
        e("sync", U),
        e("journal", U),
        tool("time_machine", U),
        e("open_export", U),
        // Notifications & device
        // The one page for every reminder; its rows are searchable by their own words too.
        link(REM, "reminders", N),
        link(REM, "to_call", H),
        e("notification_settings", N),
        e("full_screen", N),
        e("battery", N),
        e("xiaomi", N),
        // About
        e("version", O),
        e("diagnostics", O),
        e("crash_reports", O),
        // About › Help & tips: every one-time tip shows again.
        e("reset_tips", O),
        // Settings on screens of their own (search opens the screen).
        at(SettingPlace.BLOCKING, "blk_hidden_numbers", B),
        at(SettingPlace.BLOCKING, "blk_non_contacts", B),
        at(SettingPlace.BLOCKING, "blk_off_hours", B),
        at(SettingPlace.BLOCKING, "blk_more_checks", B),
        at(SettingPlace.BLOCKING, "blk_sounds", B),
        at(SettingPlace.BLOCKING, "blk_emergency", B),
        at(SettingPlace.BLOCKING, "blk_notifications", B),
        at(SettingPlace.BLOCKING, "blk_system_list", B),
        at(SettingPlace.SIMPLE_MODE, "simple_keypad", L),
        at(SettingPlace.SIMPLE_MODE, "simple_confirm_decline", L),
        at(SettingPlace.SIMPLE_MODE, "simple_speak", L),
        at(SettingPlace.SIMPLE_MODE, "simple_helpers", L),
        at(SettingPlace.SIMPLE_MODE, "simple_share", L),
        at(SettingPlace.CALL_TIME, "ct_reminders", C),
        at(SettingPlace.CALL_TIME, "ct_limits", C),
        at(SettingPlace.CALL_TIME, "ct_supervised", C),
        at(SettingPlace.BACKUP, "backup_automatic", U),
        at(SettingPlace.BACKUP, "backup_keep", U),
        at(SettingPlace.BACKUP, "backup_restore", U),
        at(SettingPlace.BACKUP, "backup_move_phone", U),
        at(SettingPlace.SYNC, "sync_auto", U),
        at(SettingPlace.SHARED_LABELS, "shared_labels", U),
        at(SettingPlace.SHARED_LABELS, "shared_labels_join", U),
        at(SettingPlace.CONTACT_PAGE, "section_chips", P),
        at(SettingPlace.SIMS, "sim_billing", C),
    ).map { if (it.key in ADVANCED) it.copy(advanced = true) else it }

    private val byKey = entries.associateBy { it.key }

    operator fun get(key: String): SettingEntry = byKey[key] ?: error("Unknown setting $key")

    fun inCategory(category: SettingsCategory): List<SettingEntry> = entries.filter { it.category == category }

    /** Whether [key] is folded under its page's "Advanced" group (false for keys that aren't settings). */
    fun isAdvanced(key: String?): Boolean = key != null && byKey[key]?.advanced == true

    /** The settings themselves: every entry but the links to pages and lists. The outer cap counts these rows. */
    val settings: List<SettingEntry> get() = entries.filterNot { it.link }

    /**
     * Rows that store no choice of their own: a screen of its own, a one-off action, a page of Android's, a line of
     * information or a list of data. They are settings rows (search finds them on their page), but the preferences
     * budget doesn't count them: what weighs on people is the number of choices, and a way somewhere isn't one.
     */
    val NOT_STORED: Set<String> = setOf(
        // Screens of their own
        "blocking", "spam_lists", "templates", "transfer", "situations", "call_helpers", "phone_menus", "call_time", "sims",
        "speed_dial", "simple_mode", "contact_page", "my_details", "app_lock_method", "family_safe_word", "privacy_dashboard",
        "who_can_see", "private_directory", "backup", "sync", "journal", "shared_labels", "voicemail",
        // One-off actions
        "import_file", "import_sim", "export_vcf", "export_csv", "export_account", "import_calls", "clear_history", "open_export",
        "reset_tips", "delete_all_data", "backup_restore", "backup_move_phone", "shared_labels_join", "diagnostics", "default_dialer",
        // Android's own pages and information
        "default_dialer_help", "sim_accounts", "carrier_settings", "app_permissions", "notification_settings", "battery", "xiaomi",
        "full_screen", "version", "power_button_ends_call",
        // Lists of data
        "kept_forever",
    )

    /** The real preferences: settings that store a choice. What the preferences budget counts. */
    val preferences: List<SettingEntry> get() = settings.filterNot { it.key in NOT_STORED }

    /** Recents & history's one "Recents view" row shows these three values (search finds each by its own words). */
    val RECENTS_VIEW: Set<String> = setOf("recents_layout", "recents_style", "recent_tap")

    /**
     * Whether [category] has a page of its own. Blocking & spam doesn't: its root row opens the Blocking & screening
     * screen, where every one of its settings lives.
     */
    fun hasPage(category: SettingsCategory): Boolean = inCategory(category).any { it.place == null }
}

/** Search over [SettingsCatalog] with its words: accent- and case-insensitive, every word must match, best matches first. */
object SettingsSearch {
    fun search(query: String, entries: List<SettingEntry>): List<SettingEntry> {
        val words = TextSearch.normalize(query).split(' ', ',', '-', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.mapIndexedNotNull { i, e ->
            var total = 0
            for (w in words) {
                val s = score(w, e)
                if (s == 0) return@mapIndexedNotNull null
                total += s
            }
            Triple(e, total, i)
        }.sortedWith(compareByDescending<Triple<SettingEntry, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }
    }

    /** Best score of one query word against an entry; 0 = no match. */
    internal fun score(word: String, e: SettingEntry): Int {
        val title = TextSearch.normalize(e.title)
        val titleWords = title.split(' ', '&', '(', ')', '/', '-', '.', ',').filter { it.isNotEmpty() }
        return when {
            title.startsWith(word) -> 100
            titleWords.any { it.startsWith(word) } -> 80
            e.keywords.any { k -> TextSearch.normalize(k).split(' ').any { it.startsWith(word) } } -> 60
            title.contains(word) -> 50
            e.keywords.any { TextSearch.normalize(it).contains(word) } -> 40
            TextSearch.normalize(e.categoryTitle).split(' ').any { it.startsWith(word) } -> 20
            word.length >= 3 && TextSearch.normalize(e.summary).split(' ', ',', '.', '(', ')').any { it.startsWith(word) } -> 10
            else -> 0
        }
    }
}
