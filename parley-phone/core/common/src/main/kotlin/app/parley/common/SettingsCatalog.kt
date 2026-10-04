package app.parley.common

/** Top-level groups of Settings, in the order they're listed. Their names are the app's string resources. */
enum class SettingsCategory { APPEARANCE, LAYOUT, CALLS, KEYPAD, CALL_TIME, BLOCKING, CONTACTS, HISTORY, MESSAGING, PRIVACY, BACKUP, NOTIFICATIONS, ABOUT }

/**
 * Screens outside the category pages that hold settings or tools. Settings search opens them for their entries, so
 * every setting is searchable wherever it lives.
 */
enum class SettingPlace {
    TOOLS, BLOCKING, DELETED_CALLS, SIMS, CONTACT_PAGE, SIMPLE_MODE, CALL_TIME, BACKUP, SYNC, TEMPORARY, HELPERS, DRIVE_PROFILE, PHONE_MENUS, SHARED_LABELS,

    /** Settings › Calls' own pages: Answering, During calls, and SIMs & carrier (Calls itself keeps a short list). */
    CALLS_ANSWERING, CALLS_DURING, CALLS_SIMS,

    /**
     * Settings › Reminders: every kind of reminder Parley sends, each with its switch and time (missed calls, To call
     * and follow-ups, keep in touch, birthdays and dates, backups, temporary contacts).
     */
    REMINDERS,

    /** Settings › Privacy › App lock › Unlock with: the Parley PIN and the duress PIN (I21). */
    APP_LOCK,
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
     * A way to a page or a list (Reminders, To call), found by search like a setting but holding no value of its own.
     * The settings budget doesn't count it.
     */
    val link: Boolean = false,
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
    private val K = SettingsCategory.KEYPAD
    private val T = SettingsCategory.CALL_TIME
    private val B = SettingsCategory.BLOCKING
    private val P = SettingsCategory.CONTACTS
    private val H = SettingsCategory.HISTORY
    private val M = SettingsCategory.MESSAGING
    private val S = SettingsCategory.PRIVACY
    private val U = SettingsCategory.BACKUP
    private val N = SettingsCategory.NOTIFICATIONS
    private val O = SettingsCategory.ABOUT

    /** A way to a page or list on [place] ([SettingEntry.link]): searchable, not a setting. */
    private fun link(place: SettingPlace, key: String, category: SettingsCategory) = SettingEntry(key, category, place, link = true)

    /** Settings › Reminders. */
    private val REM = SettingPlace.REMINDERS

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
        e("recent_tap", L),
        // "Sort by" and "Show names as" are apart, as in Android's Contacts; both keep the words of the one setting they were.
        e("sort_names", A),
        e("name_order", A),
        e("second_line", A),
        e("prefer_nickname", A),
        e("swipe_actions", L),
        e("avatar_style", A),
        e("reset_tips", A),
        e("simple_mode", L),
        // Calls
        e("default_dialer", C),
        // When Android refuses the role request without asking.
        e("default_dialer_help", C),
        at(SettingPlace.CALLS_ANSWERING, "answer_gesture", C),
        at(SettingPlace.CALLS_ANSWERING, "call_background", C),
        at(SettingPlace.CALLS_ANSWERING, "caller_photo", C),
        // Accessibility: RTT, where the carrier supports it. Its group also links Android's TTY and RTT settings, which
        // this entry's words find.
        at(SettingPlace.CALLS_ANSWERING, "answer_rtt", C),
        e("confirm_call", C),
        // Remember what matters.
        at(SettingPlace.CALLS_DURING, "memory_prompt", C),
        at(SettingPlace.CALLS_DURING, "memory_lock_screen", C),
        at(SettingPlace.CALLS_DURING, "pre_call_peek", C),
        at(SettingPlace.CALLS_DURING, "call_haptics", C),
        at(SettingPlace.CALLS_DURING, "connect_haptic", C),
        // Off by default: turning the phone face down while it rings silences it (never declines).
        at(SettingPlace.CALLS_ANSWERING, "flip_to_silence", C),
        at(SettingPlace.CALLS_ANSWERING, "unknown_ringtone", C),
        e("pocket_guard", C),
        at(REM, "missed_realert", C),
        // Auto-answer: off by default; only known callers, never during another call, always with a countdown and Cancel.
        at(SettingPlace.CALLS_ANSWERING, "auto_answer", C),
        // Haptic caller ID: set on a contact's or a label's page.
        at(SettingPlace.CALLS_ANSWERING, "caller_vibration", C),
        // Bring in my helper: a screen of its own, linked from Calls › Situations (and simple mode's setup).
        at(SettingPlace.HELPERS, "call_helpers", C),
        // The drive profile: a screen of its own, linked from Calls › Situations; off until a car is marked.
        at(SettingPlace.DRIVE_PROFILE, "drive_profile", C),
        // Phone menus: a screen of its own, linked from Calls › Situations.
        at(SettingPlace.PHONE_MENUS, "phone_menus", C),
        at(SettingPlace.PHONE_MENUS, "menu_memory", C),
        // L6: on SIMs & plan minutes; only ever acts while a SIM is abroad.
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
        // Keypad
        e("keypad_tones", K),
        e("keypad_vibration", K),
        e("keypad_letters", K),
        e("speed_dial", K),
        e("ussd", K),
        // Call time
        e("call_time", T),
        at(SettingPlace.SIMS, "plan_minutes", C),
        // Blocking & spam
        e("blocking", B),
        e("repeat_callers", B),
        e("learn_from_calls", B),
        e("silence_sales_lines", B),
        e("expecting_call", B),
        e("expected_hints", B),
        e("spam_lists", B),
        e("templates", B),
        e("dry_run", B),
        e("transfer", B),
        // Contacts
        // Settings › Contacts, where people look for how their contact list looks (it used to be under Layout & gestures).
        e("row_actions", P),
        e("default_account", P),
        e("labels", P),
        e("mirror_relations", P),
        e("temporary_contacts", P),
        // On the Temporary contacts screen, beside the contacts it's about.
        at(SettingPlace.TEMPORARY, "temp_ask_first", P),
        e("duplicates", P),
        e("health", P),
        e("contact_page", P),
        e("import_file", P),
        e("bulk_add", P),
        // Scan QR (search finds it; it opens the scan screen).
        at(SettingPlace.TOOLS, "scan_qr", P),
        // Finding your way (on Tools; search opens the page itself).
        at(SettingPlace.TOOLS, "coming_from", P),
        // The one hub (it was "What Parley can do" and, separately, Tools): the key stays for old links.
        at(SettingPlace.TOOLS, "what_parley_can_do", O),
        e("import_sim", P),
        e("export_vcf", P),
        e("export_csv", P),
        e("export_account", P),
        e("birthdays", P),
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
        at(SettingPlace.DELETED_CALLS, "history_details", H),
        e("kept_forever", H),
        e("csv_bom", H),
        e("retention", H),
        e("sim_labels", H),
        e("recents_layout", H),
        e("recents_remember_filter", H),
        e("recents_style", H),
        e("clear_history", H),
        e("insights", H),
        // The People card.
        e("people_card", H),
        e("first_mover", H),
        e("import_calls", H),
        // Messaging
        e("quick_replies", M),
        e("my_details", M),
        e("messaged_numbers", M),
        e("messaged_expiry", M),
        // Privacy & security
        e("app_lock", S),
        e("lock_after", S),
        // I21: how Parley unlocks; the PINs live on a screen of their own (the Privacy page keeps to its rows).
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
        e("private_names", S),
        e("private_directory", S),
        e("app_permissions", S),
        e("delete_all_data", S),
        // Backup & sync
        e("backup", U),
        at(REM, "backup_reminder", U),
        e("sync", U),
        e("journal", U),
        e("time_machine", U),
        e("markdown_export", U),
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
        at(SettingPlace.CALL_TIME, "ct_reminders", T),
        at(SettingPlace.CALL_TIME, "ct_limits", T),
        at(SettingPlace.CALL_TIME, "ct_supervised", T),
        at(SettingPlace.BACKUP, "backup_automatic", U),
        at(SettingPlace.BACKUP, "backup_keep", U),
        at(SettingPlace.BACKUP, "backup_restore", U),
        at(SettingPlace.BACKUP, "backup_move_phone", U),
        at(SettingPlace.SYNC, "sync_auto", U),
        at(SettingPlace.SHARED_LABELS, "shared_labels", U),
        at(SettingPlace.SHARED_LABELS, "shared_labels_join", U),
        at(SettingPlace.CONTACT_PAGE, "section_chips", P),
        at(SettingPlace.SIMS, "sim_billing", C),
    )

    private val byKey = entries.associateBy { it.key }

    operator fun get(key: String): SettingEntry = byKey[key] ?: error("Unknown setting $key")

    fun inCategory(category: SettingsCategory): List<SettingEntry> = entries.filter { it.category == category }

    /** The settings themselves: every entry but the links to pages and lists. What the settings budget counts. */
    val settings: List<SettingEntry> get() = entries.filterNot { it.link }
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
