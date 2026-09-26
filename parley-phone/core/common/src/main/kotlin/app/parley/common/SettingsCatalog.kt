package app.parley.common

/**
 * Top-level groups of Settings, in the order they're listed. [title] and [summary] are the English reference
 * texts: the app shows its localised string resources (keyed by the enum name) and keeps these for search, so
 * English words still find a setting in every language.
 */
enum class SettingsCategory(val title: String, val summary: String) {
    APPEARANCE("Appearance", "Theme, colours, navigation bar, names"),
    CALLS("Calls", "Answering, SIMs, ringtones, carrier settings"),
    KEYPAD("Keypad", "Tones, letters, speed dial, USSD"),
    CALL_TIME("Call time", "Talk-time reminders, limits, plan minutes"),
    BLOCKING("Blocking & spam", "Rules, spam lists, off hours, tests"),
    CONTACTS("Contacts", "Accounts, labels, temporary contacts, import & export"),
    HISTORY("Recents & history", "Call archive, retention, insights"),
    MESSAGING("Messaging", "Quick replies, your details, messaged numbers"),
    PRIVACY("Privacy & security", "App lock, private contacts, permissions"),
    BACKUP("Backup & sync", "Encrypted backups, sync, undo"),
    NOTIFICATIONS("Notifications & device", "Full-screen calls, battery, system settings"),
    ABOUT("About", "Version, licence, diagnostics"),
}

/**
 * One searchable setting, identified by its stable [key]. In [SettingsCatalog], [title], [summary] and [keywords]
 * are the English reference texts: the app maps [key] to localised string resources for what the screens show
 * (a screen may replace the summary with a live value such as "Last backup 2 h ago") and builds localised
 * entries for search with [localized]. [categoryTitles] are the category names search matches.
 */
data class SettingEntry(
    val key: String,
    val title: String,
    val summary: String,
    val category: SettingsCategory,
    val keywords: List<String> = emptyList(),
    val categoryTitles: List<String> = listOf(category.title),
) {
    /**
     * This entry with localised texts, for search. The English title, keywords and category name stay
     * searchable as keywords, so "dark mode" still finds the theme when the app runs in another language.
     */
    fun localized(title: String, summary: String, keywords: List<String>, categoryTitle: String): SettingEntry = copy(
        title = title,
        summary = summary,
        keywords = (keywords + listOfNotNull(this.title.takeIf { it != title }) + this.keywords)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        categoryTitles = (listOf(categoryTitle) + categoryTitles).distinct(),
    )
}

/**
 * Every setting Parley has, one place. The category screens take their titles from here and Settings search
 * searches this list, so a setting can't be on a screen and missing from search (or the other way round).
 */
object SettingsCatalog {
    private fun e(key: String, title: String, summary: String, category: SettingsCategory, vararg keywords: String) =
        SettingEntry(key, title, summary, category, keywords.toList())

    private val A = SettingsCategory.APPEARANCE
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

    val entries: List<SettingEntry> = listOf(
        // Appearance
        e("theme", "Theme", "System, light or dark", A, "dark mode", "night mode", "light mode", "appearance"),
        e("amoled", "Pure black dark theme", "Saves power on OLED screens", A, "amoled", "oled", "black", "battery", "dark"),
        e("dynamic_color", "Wallpaper colours", "Material You dynamic colour", A, "color", "material you", "dynamic", "palette", "accent"),
        e("language", "Language", "The language Parley uses", A, "language", "locale", "translation", "app language", "english", "rtl"),
        e("density", "List density", "Comfortable or compact rows", A, "compact", "spacing", "row height", "size"),
        e("nav_tabs", "Navigation bar", "Show, hide and reorder Favorites, Recents, Contacts and Keypad", A,
            "tabs", "circle", "bottom bar", "bottom navigation", "navigation rail", "reorder", "hide tab", "customise", "customize", "menu"),
        e("start_tab", "Open on", "The tab Parley opens on", A, "start tab", "default tab", "home screen", "launch", "first screen"),
        // S1/S2 (v3.3): optional combined surfaces, and what a tap on a call does (in every layout).
        e("calls_layout", "Calls layout", "Keypad and Recents as separate tabs, or one screen with the keypad docked at the bottom", A,
            "combine", "combined", "merge", "merge tabs", "fewer tabs", "keypad", "dialpad", "dialer", "recents", "one screen", "docked", "unified", "classic", "layout"),
        e("favorites_in_contacts", "Favourites in Contacts", "Off, a section at the top of Contacts, or a strip of avatars", A,
            "favorites", "favourites", "starred", "combine", "merge", "merge tabs", "fewer tabs", "strip", "carousel", "section", "frequent", "layout"),
        e("recent_tap", "Tapping a call in Recents", "Open its details, or call back straight away", A,
            "tap", "call back", "details", "accidental", "row", "recents", "tap recents to call", "one tap"),
        e("row_actions", "Call & message buttons on contacts", "Tapping a contact still opens it", A, "quick actions", "buttons", "sms", "row"),
        e("sort_names", "Sort and show names by", "First name or last name", A, "order", "alphabetical", "surname", "family name", "given name"),
        e("second_line", "Second line under names", "Company, nickname, account or number", A, "subtitle", "company", "account", "details"),
        e("prefer_nickname", "Prefer nicknames", "Show “Bob” instead of “Robert Jones” in lists", A, "nickname", "short name"),
        e("swipe_actions", "Swipe actions", "Off by default. Swipe a contact or a call right to call, left to message", A,
            "swipe", "gesture", "slide", "left", "right", "quick actions"),
        e("avatar_style", "Avatars", "Colourful or grey letters; names that start with an emoji show it", A, "avatar", "monogram", "emoji", "picture", "letters", "grey", "gray"),
        // U2
        e("reset_tips", "Reset tips", "Show the one-time tips again (keypad, Recents, search)", A, "tips", "hints", "coach marks", "help", "tutorial", "onboarding"),
        // X4 (v3.2)
        e("simple_mode", "Simple mode", "Big photo buttons for up to 9 people, a larger keypad and a question before declining. Set it up for someone else", A,
            "elderly", "senior", "assisted", "easy", "large", "big buttons", "grandparent", "accessibility", "launcher", "text to speech", "speak name"),

        // Calls
        e("default_dialer", "Default phone app", "Needed to show calls, manage blocking and the call log", C, "default dialer", "role", "phone app"),
        // P4: when Android refuses the role request without asking.
        e("default_dialer_help", "Can't make Parley the default phone app?", "A step-by-step guide for your Android version, with App info", C,
            "default dialer", "role", "restricted settings", "sideload", "app info", "not asked"),
        e("answer_gesture", "Answer incoming calls by", "Swipe or tap", C, "slide", "swipe", "tap", "pocket", "answer"),
        e("confirm_call", "Confirm before calling", "Avoids accidental calls from lists and search", C, "accidental", "ask before", "pocket dial"),
        // R8/R9/X1 (v3.2): remember what matters.
        e("memory_prompt", "Anything to remember? after calls", "A note and a follow-up reminder after calls with your contacts", C,
            "note", "notes", "remember", "promise", "follow up", "after call", "post-call", "memory"),
        e("memory_lock_screen", "Notes on the lock screen", "Show the last note and promises on the incoming-call screen while the phone is locked", C,
            "lock screen", "note", "promise", "incoming", "privacy"),
        e("pre_call_peek", "Peek before calling", "The last note, promises and a good time to call, before you call from a contact's page", C,
            "peek", "before calling", "note", "promise", "good time", "local time", "time zone"),
        e("call_haptics", "Vibrate on call events", "When a call connects, ends, is swapped or merged", C, "vibration", "haptic", "buzz"),
        e("connect_haptic", "Vibrate when a call connects", "A short buzz when the other person answers", C, "vibration", "haptic", "answered", "picked up"),
        e("unknown_ringtone", "Ringtone for unknown callers", "A different ringtone for numbers not in your contacts", C, "sound", "ring", "tone", "unknown numbers"),
        e("pocket_guard", "Ask before pocket calls", "A favourite, the widget or a shortcut asks first while the phone is covered", C,
            "pocket dial", "butt dial", "accidental", "proximity", "widget", "shortcut", "favourite", "favorite"),
        e("missed_realert", "Remind me of missed calls", "Alert again every few minutes until you've seen them", C,
            "re-alert", "repeat", "reminder", "missed call", "nag", "notification"),
        e("proximity_sensor", "Turn the screen off at your ear", "Uses the proximity sensor during earpiece calls", C,
            "proximity", "sensor", "screen off", "black screen", "pocket", "broken sensor"),
        e("power_button_ends_call", "Power button ends call", "Android's accessibility setting", C,
            "power", "hang up", "end call", "accessibility", "button"),
        e("voicemail", "Voicemail", "Your voicemail inbox and the carrier's voicemail settings", C,
            "visual voicemail", "vvm", "inbox", "mailbox", "voice mail", "messages"),
        e("sims", "SIMs", "Plan minutes and settings for each SIM", C, "dual sim", "sim card", "esim", "plan"),
        e("sim_accounts", "SIM & calling accounts", "Default SIM, Wi-Fi calling (system settings)", C, "wifi calling", "wi-fi", "volte", "default sim", "calling account"),
        e("carrier_settings", "Call forwarding, waiting & voicemail", "Carrier settings (system)", C, "forward", "divert", "voicemail", "call waiting", "carrier", "operator"),

        // Keypad
        e("keypad_tones", "Keypad tones", "Play a tone for each key", K, "dtmf", "sound", "beep", "dialpad"),
        e("keypad_vibration", "Keypad vibration", "Vibrate when you press a key", K, "haptic", "vibrate", "dialpad"),
        e("keypad_letters", "Keypad letters", "A second alphabet on the keys, for searching names", K,
            "t9", "alphabet", "language", "cyrillic", "russian", "ukrainian", "greek", "hebrew", "arabic", "chinese", "japanese", "korean"),
        e("speed_dial", "Speed dial", "Long-press 2–9 on the keypad", K, "shortcut", "quick dial", "one touch"),
        e("ussd", "USSD replies", "Balance checks and other codes like *100#, kept on this phone", K, "balance", "codes", "mmi", "carrier"),

        // Call time
        e("call_time", "Reminders & limits", "Talk-time reminders, call-length limits and allowances", T,
            "timer", "beep", "duration", "limit", "allowance", "supervised", "parental", "talk time"),
        e("plan_minutes", "Plan minutes per SIM", "Billing increments and an 80 % warning", T, "billing", "minutes", "plan", "bundle", "tariff"),

        // Blocking & spam
        e("blocking", "Blocking & screening", "Allow and block rules, off hours and extra checks", B,
            "block", "blocked numbers", "spam", "reject", "silence", "screening", "robocall", "off hours", "do not disturb"),
        e("repeat_callers", "Let repeat callers through", "An unknown number blocked earlier rings if it calls again within 3 minutes", B, "urgent", "twice", "emergency"),
        e("expecting_call", "Expecting a call", "Let unknown callers ring for a while", B, "snooze", "delivery", "courier", "unknown"),
        e("spam_lists", "Spam lists", "Offline lists you add yourself, nothing is sent anywhere", B, "lists", "parleylist", "ftc", "arcep", "database"),
        e("templates", "Rule templates", "Ready-made rules for your country", B, "regulator", "presets", "toll free", "premium"),
        e("dry_run", "Test a call", "See what your rules would do, and replay last week", B, "simulate", "dry run", "test", "why"),
        e("transfer", "Import & share rules", "From Call Blocker, YACB, NoPhoneSpam or CSV", B, "import", "export", "share", "csv"),

        // Contacts
        e("default_account", "Save new contacts to", "The account new contacts go to", P, "account", "google", "phone", "default account"),
        e("labels", "Labels", "Rename, merge, label ringtones", P, "groups", "tags", "categories"),
        e("temporary_contacts", "Temporary contacts", "Contacts that delete themselves after a while", P, "temp", "expire", "expiry", "self-destruct", "delete automatically"),
        e("duplicates", "Find & merge duplicates", "Contacts saved twice", P, "merge", "duplicate", "dedupe", "join"),
        e("health", "Contact health check", "Numbers without country code, empty and stale contacts", P, "tidy", "clean up", "fix", "cleanup"),
        e("import_file", "Import from .vcf or .csv file", "Any CSV (Google, Outlook, a spreadsheet): choose what each column holds. With a report.", P,
            "vcard", "vcf", "csv", "import", "google", "outlook", "excel", "spreadsheet", "columns", "mapping"),
        e("bulk_add", "Add several numbers", "Paste a list of numbers and save them at once, to a label, privately or for a few days", P,
            "bulk", "many", "paste", "list", "batch", "import numbers", "leads"),
        e("import_sim", "Import from SIM card", "Copy the SIM's phonebook into your contacts", P, "sim", "phonebook", "copy"),
        e("export_vcf", "Export all to .vcf file", "Plain-text backup you control", P, "vcard", "export", "backup"),
        e("export_csv", "Export all to .csv file", "For spreadsheets", P, "spreadsheet", "excel", "export"),
        e("export_account", "Export one account to .vcf", "Contacts from one account only", P, "export", "account"),
        e("birthdays", "Birthdays & dates", "Upcoming birthdays and anniversaries", P, "anniversary", "events", "dates"),
        e("birthday_reminders", "Birthday reminders", "A notification on the day", P, "notification", "remind", "birthday"),
        e("reminder_time", "Reminder time", "When birthday reminders arrive", P, "hour", "time", "birthday"),
        e("nudges", "Keep-in-touch nudges", "For contacts where you set a reminder", P, "reach out", "remind", "call back", "keep in touch"),
        // R3–R5 (v3.2): the Circle.
        e("date_lead", "Remind me before dates", "On the day, or also 1, 3 or 7 days before", P, "birthday", "anniversary", "lead time", "days before", "early", "advance"),
        e("circle_delivery", "How keep-in-touch reminders arrive", "A weekly digest on Sunday, or one at a time as they come due", P,
            "digest", "weekly", "sunday", "circle", "remind", "keep in touch", "nudge", "notification"),
        e("circle_weekly_cap", "At most per week", "Keep-in-touch reminders a week, when they come as due", P, "limit", "cap", "how many", "circle", "nudge"),
        e("log_prompts", "Log messages you start", "After Parley opens a chat or video call with someone in your circle", P,
            "log", "interaction", "whatsapp", "signal", "telegram", "sms", "video", "circle", "ask", "snackbar"),

        // Recents & history
        e("archive", "Keep full call history", "Parley keeps its own encrypted copy, because Android may drop old calls", H, "archive", "call log", "history", "forever"),
        e("history_details", "Kept calls & recently deleted", "Numbers kept forever, 30-day undo for deleted calls, export", H, "undo", "restore", "keep forever", "export"),
        e("retention", "Keep call history", "Delete calls from the system call log after a while", H, "retention", "delete old calls", "auto delete", "call log"),
        e("sim_labels", "Show SIM in call history", "Only when two SIMs are active", H, "dual sim", "sim label"),
        e("recents_layout", "Call list layout", "Grouped, every call on its own row, or grouped by day", H,
            "chronological", "grouped", "by day", "ungroup", "list", "call log", "layout"),
        // R4 (v3.3)
        e("recents_style", "Recents style", "Rich: shapes, tints and a Call back button for missed calls. Simple: plain icons", H,
            "rich", "simple", "colours", "colors", "icons", "missed", "call back", "style", "legend", "colour blind"),
        e("clear_history", "Clear call history", "All calls, calls from unknown numbers or missed calls, with an export first", H,
            "delete", "clear", "wipe", "erase", "unknown numbers", "call log"),
        e("insights", "Call insights", "Talk time, top people, calls you didn't return", H, "statistics", "stats", "charts", "talk time"),
        // R6 (v3.2): the People card.
        e("people_card", "People card in Insights", "Reach in your circle, open loops and your year, in Call insights", H,
            "people", "reach", "circle", "open loops", "year in review", "insights"),
        e("first_mover", "Who usually reaches out first", "On the People card. Only you see it", H, "first", "reaches out", "initiates", "calls first", "people"),
        e("import_calls", "Import call history from CSV", "From Parley, Logger or a spreadsheet, with a dry run first", H, "csv", "import", "call log"),

        // Messaging
        e("quick_replies", "Quick reply messages", "Sent when you decline a call with a message", M, "sms", "decline", "reply", "text"),
        e("my_details", "My card", "Your own details: share them as a QR code or vCard, and use them for “Send my details”", M,
            "me", "my details", "my number", "my name", "share", "business card", "profile", "vcard", "qr"),
        e("messaged_numbers", "Messaged numbers", "Numbers you opened a chat with from Parley: see, delete or stop keeping them", M,
            "whatsapp", "signal", "telegram", "record", "history", "clear", "privacy", "last messaged"),
        e("messaged_expiry", "Forget messaged numbers after", "Never, 7, 30 or 90 days", M, "expire", "auto delete", "retention", "whatsapp", "record"),

        // Privacy & security
        e("app_lock", "App lock", "Fingerprint, face or screen lock to open Parley. Incoming calls always show.", S,
            "lock", "biometric", "fingerprint", "face", "pin", "password", "security"),
        e("lock_after", "Lock again after", "How long Parley can stay in the background", S, "timeout", "lock", "delay"),
        e("secure_screen", "Hide screen content", "Blocks screenshots and hides Parley in the recent-apps view", S, "screenshot", "recents", "secure", "flag secure"),
        e("hide_vault", "Hide private contacts", "Discreet mode: private contacts and their calls disappear from lists and search", S, "vault", "discreet", "private", "hidden"),
        e("private_history", "Private call history", "Calls with private contacts are moved out of the system call log", S, "vault", "private calls", "call log"),
        e("privacy_dashboard", "Privacy dashboard", "What Parley can access and why", S, "permissions", "data", "internet", "tracking"),
        e("who_can_see", "Who can see your contacts", "Which apps can read your contacts", S, "apps", "access", "contact scopes", "grapheneos"),
        e("private_names", "Let apps show private names", "Approved apps can look up one private name at a time", S, "caller id", "lookup", "private names"),
        e("private_directory", "Private names in other phone apps", "Off by default. A phone app you approve (for example Google Phone, also in the car) can show who is calling", S,
            "directory", "car", "work profile", "android auto", "caller id", "dialer", "private names"),
        e("app_permissions", "App permissions (system)", "Android's settings for Parley", S, "permissions", "system", "app info"),

        // Backup & sync
        e("backup", "Backup & restore", "Encrypted backups to a folder you choose", U, "restore", "export", "encrypted", "new phone", "move", "transfer"),
        // C3
        e("backup_reminder", "Remind me to back up", "A quiet reminder when there's been no backup for a while", U, "reminder", "overdue", "backup", "notification", "nag"),
        e("sync", "Sync between your phones", "Through a Syncthing / Nextcloud folder, no server", U, "syncthing", "nextcloud", "folder", "second phone"),
        e("journal", "Recently deleted & changed", "Undo for 30 days", U, "undo", "trash", "restore", "deleted", "bin"),
        e("time_machine", "What changed (time machine)", "Daily snapshots for 6 months: see and undo changes", U, "snapshots", "history", "versions", "restore"),
        // C5 (v3.2)
        e("markdown_export", "Export notes as Markdown", "One .md file per person with notes and timeline, to a folder you choose", U,
            "markdown", "md", "obsidian", "notes", "logseq", "export", "folder", "timeline"),

        // Notifications & device
        e("notification_settings", "Notification settings", "Sounds and importance of Parley's notifications (system)", N, "sound", "missed call", "notification", "alerts"),
        e("full_screen", "Allow full-screen incoming calls", "Show incoming calls over the lock screen", N, "lock screen", "full screen", "incoming", "heads up"),
        e("battery", "Battery optimisation", "Some phones delay calls for optimised apps", N, "battery", "optimization", "doze", "unrestricted", "background"),
        e("xiaomi", "Xiaomi: lock screen & pop-up permissions", "Allow “Show on lock screen” and pop-up windows", N, "miui", "redmi", "poco", "hyperos"),

        // About
        e("version", "Parley version", "Free and open source (GPL-3.0). No internet access, no ads, no trackers, no accounts.", O,
            "version", "about", "licence", "license", "gpl", "open source", "build"),
        e("diagnostics", "Export diagnostics", "App version, device and settings, with numbers masked", O, "debug", "logs", "bug report", "support", "raw", "dump"),
        e("crash_reports", "Keep crash reports", "Off by default. After a crash, Parley offers the report on the next start; nothing is sent", O,
            "crash", "bug", "error", "report", "stack trace", "debug"),
    )

    private val byKey = entries.associateBy { it.key }

    operator fun get(key: String): SettingEntry = byKey[key] ?: error("Unknown setting $key")

    fun inCategory(category: SettingsCategory): List<SettingEntry> = entries.filter { it.category == category }
}

/** Search over [SettingsCatalog]: accent- and case-insensitive, every word must match, best matches first. */
object SettingsSearch {
    fun search(query: String, entries: List<SettingEntry> = SettingsCatalog.entries): List<SettingEntry> {
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
            e.categoryTitles.any { c -> TextSearch.normalize(c).split(' ').any { it.startsWith(word) } } -> 20
            word.length >= 3 && TextSearch.normalize(e.summary).split(' ', ',', '.', '(', ')').any { it.startsWith(word) } -> 10
            else -> 0
        }
    }
}
