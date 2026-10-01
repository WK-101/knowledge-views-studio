package app.parley.common

/**
 * Top-level groups of Settings, in the order they're listed. [title] and [summary] are the English reference
 * texts: the app shows its localised string resources (keyed by the enum name) and keeps these for search, so
 * English words still find a setting in every language.
 */
enum class SettingsCategory(val title: String, val summary: String) {
    APPEARANCE("Appearance", "Theme, colours, language, lists, names"),
    LAYOUT("Layout & gestures", "Navigation bar, combined tabs, taps, swipes, simple mode"),
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
 * Screens outside the category pages that hold settings or tools. Settings search opens them for their entries, so
 * every setting is searchable wherever it lives.
 */
enum class SettingPlace { TOOLS, BLOCKING, DELETED_CALLS, SIMS, CONTACT_PAGE, SIMPLE_MODE, CALL_TIME, BACKUP, SYNC, TEMPORARY, HELPERS }

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
    /** Where the setting lives when it isn't on its category's page (a screen of its own); null: the page. */
    val place: SettingPlace? = null,
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

    /** A setting on a screen of its own ([place]), searchable like the others. */
    private fun at(place: SettingPlace, key: String, title: String, summary: String, category: SettingsCategory, vararg keywords: String) =
        SettingEntry(key, title, summary, category, keywords.toList(), place = place)

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

    val entries: List<SettingEntry> = listOf(
        // Appearance
        e("theme", "Theme", "System, light or dark", A, "dark mode", "night mode", "light mode", "appearance"),
        e("amoled", "Pure black dark theme", "Saves power on OLED screens", A, "amoled", "oled", "black", "battery", "dark"),
        e("dynamic_color", "Wallpaper colours", "Material You dynamic colour", A, "color", "material you", "dynamic", "palette", "accent"),
        e("language", "Language", "The language Parley uses", A, "language", "locale", "translation", "app language", "english", "rtl"),
        e("density", "List density", "Comfortable or compact rows", A, "compact", "spacing", "row height", "size"),
        e("nav_tabs", "Navigation bar", "Show, hide and reorder Favourites, Recents, Contacts and Keypad", L,
            "tabs", "circle", "bottom bar", "bottom navigation", "navigation rail", "reorder", "hide tab", "customise", "customize", "menu"),
        e("start_tab", "Open on", "The tab Parley opens on", L, "start tab", "default tab", "home screen", "launch", "first screen"),
        // Optional combined surfaces, and what a tap on a call does (in every layout).
        e("calls_layout", "Calls layout", "Keypad and Recents as separate tabs, or one screen with the keypad docked at the bottom", L,
            "combine", "combined", "merge", "merge tabs", "fewer tabs", "keypad", "dialpad", "dialer", "recents", "one screen", "docked", "unified", "classic", "layout"),
        e("favorites_in_contacts", "Favourites in Contacts", "Off, a section at the top of Contacts, or a strip of avatars", L,
            "favorites", "favourites", "starred", "combine", "merge", "merge tabs", "fewer tabs", "strip", "carousel", "section", "frequent", "layout"),
        e("recent_tap", "Tapping a call in Recents", "Open its details, or call back straight away", L,
            "tap", "call back", "details", "accidental", "row", "recents", "tap recents to call", "one tap"),
        e("sort_names", "Sort and show names by", "First name or last name", A, "order", "alphabetical", "surname", "family name", "given name"),
        e("second_line", "Second line under names", "Company, nickname, account or number", A, "subtitle", "company", "account", "details"),
        e("prefer_nickname", "Prefer nicknames", "Show “Bob” instead of “Robert Jones” in lists", A, "nickname", "short name"),
        e("swipe_actions", "Swipe actions", "Off by default. Swipe a contact or a call right to call, left to message", L,
            "swipe", "gesture", "slide", "left", "right", "quick actions"),
        e("avatar_style", "Avatars", "Colourful or grey letters; names that start with an emoji show it", A, "avatar", "monogram", "emoji", "picture", "letters", "grey", "gray"),
        e("reset_tips", "Reset tips", "Show the one-time tips again (keypad, Recents, search)", A, "tips", "hints", "coach marks", "help", "tutorial", "onboarding"),
        e("simple_mode", "Simple mode", "Big photo buttons for up to 9 people, a larger keypad and a question before declining. Set it up for someone else", L,
            "elderly", "senior", "assisted", "easy", "large", "big buttons", "grandparent", "accessibility", "launcher", "text to speech", "speak name"),

        // Calls
        e("default_dialer", "Default phone app", "Needed to show calls, manage blocking and the call log", C, "default dialer", "role", "phone app"),
        // When Android refuses the role request without asking.
        e("default_dialer_help", "Can't make Parley the default phone app?", "A step-by-step guide for your Android version, with App info", C,
            "default dialer", "role", "restricted settings", "sideload", "app info", "not asked"),
        e("answer_gesture", "Answer incoming calls by", "Swipe or tap", C, "slide", "swipe", "tap", "pocket", "answer"),
        e("call_background", "Call screen background", "The caller's colour, or plain", C,
            "tint", "colour", "color", "plain", "background", "call screen", "incoming screen", "wallpaper"),
        e("caller_photo", "Show contact photo on the call screen", "The photo and call-screen picture; each contact can override it", C,
            "photo", "picture", "avatar", "image", "caller", "call screen", "incoming screen", "hide photo"),
        e("confirm_call", "Confirm before calling", "Avoids accidental calls from lists and search", C, "accidental", "ask before", "pocket dial"),
        // Remember what matters.
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
        // Auto-answer: off by default; only known callers, never during another call, always with a countdown and Cancel.
        e("auto_answer", "Answer automatically", "Off. With a headset or Bluetooth, in simple mode, or for people you choose, after a few seconds", C,
            "auto answer", "auto-answer", "answer automatically", "headset", "bluetooth", "car", "hands-free", "handsfree", "earbuds",
            "simple mode", "chosen", "countdown", "seconds"),
        // Haptic caller ID: set on a contact's or a label's page.
        e("caller_vibration", "Vibration for callers", "Give a person or a label a rhythm of their own, so you can tell who's calling without looking", C,
            "vibration pattern", "custom vibration", "haptic", "haptic caller id", "vibrate", "heartbeat", "morse", "pocket", "deaf", "silent"),
        // Bring in my helper: a screen of its own, linked from the Calls page (and simple mode's setup).
        at(SettingPlace.HELPERS, "call_helpers", "Helpers", "Up to 3 people you trust, added to a call with one tap", C,
            "helper", "family", "trusted", "add call", "conference", "merge", "scam"),
        e("proximity_sensor", "Turn the screen off at your ear", "Uses the proximity sensor during earpiece calls", C,
            "proximity", "sensor", "screen off", "black screen", "pocket", "broken sensor"),
        e("power_button_ends_call", "Power button ends call", "Android's accessibility setting", C,
            "power", "hang up", "end call", "accessibility", "button"),
        e("voicemail", "Voicemail", "Your voicemail inbox and the carrier's voicemail settings", C,
            "visual voicemail", "vvm", "inbox", "mailbox", "voice mail", "messages"),
        e("sims", "SIMs & plan minutes", "Plan minutes and settings for each SIM", C, "dual sim", "sim card", "esim", "plan"),
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
        at(SettingPlace.SIMS, "plan_minutes", "Plan minutes per SIM", "Billing increments and an 80 % warning (in SIMs & plan minutes)", C,
            "billing", "minutes", "plan", "bundle", "tariff"),

        // Blocking & spam
        e("blocking", "Blocking & screening", "Allow and block rules, off hours and extra checks", B,
            "block", "blocked numbers", "spam", "reject", "silence", "screening", "robocall", "off hours", "do not disturb"),
        e("repeat_callers", "Let repeat callers through", "An unknown number blocked earlier rings if it calls again within 3 minutes", B, "urgent", "twice", "emergency"),
        e("learn_from_calls", "Learn from your calls",
            "Tag numbers that look like sales lines, from how your own calls with them went. Stays on this phone.", B,
            "reputation", "sales", "telemarketing", "spam", "tag", "learn", "range"),
        e("silence_sales_lines", "Silence numbers that look like sales lines (your calls)", "Contacts, numbers you allow and repeat callers still ring", B,
            "reputation", "sales", "telemarketing", "silence", "mute", "spam"),
        e("expecting_call", "Expecting a call", "Let unknown callers ring for a while", B, "snooze", "delivery", "courier", "unknown"),
        e("expected_hints", "Expecting a call from your notes", "Notes, To call items and delivery QR codes can let unknown callers ring for a while", B,
            "expecting", "delivery", "courier", "parcel", "note", "promise", "to call", "callback"),
        e("spam_lists", "Spam lists", "Offline lists you add yourself, nothing is sent anywhere", B, "lists", "parleylist", "ftc", "arcep", "database"),
        e("templates", "Rule templates", "Ready-made rules for your country", B, "regulator", "presets", "toll free", "premium"),
        e("dry_run", "Test a call", "See what your rules would do, and replay last week", B, "simulate", "dry run", "test", "why"),
        e("transfer", "Import & share rules", "From Call Blocker, YACB, NoPhoneSpam or CSV", B, "import", "export", "share", "csv"),

        // Contacts
        // Settings › Contacts, where people look for how their contact list looks (it used to be under Layout & gestures).
        e("row_actions", "Call and message buttons in the list", "On each contact. Turn off for a clean list; tapping a contact still opens it.", P,
            "quick actions", "buttons", "call button", "message button", "sms", "row", "clean list", "hide buttons", "contact list",
            "Call & message buttons on contacts"),
        e("default_account", "Save new contacts to", "The account new contacts go to", P, "account", "google", "phone", "default account"),
        e("labels", "Labels", "Rename, merge, label ringtones", P, "groups", "tags", "categories"),
        e("mirror_relations", "Add relations to both contacts", "\"Mother: Ana\" here adds \"Child\" on Ana's contact", P,
            "relation", "relationship", "two-way", "both ways", "reciprocal", "family", "mirror", "spouse", "parent", "child"),
        e("temporary_contacts", "Temporary contacts", "Contacts that delete themselves after a while", P, "temp", "expire", "expiry", "self-destruct", "delete automatically"),
        // On the Temporary contacts screen, beside the contacts it's about.
        at(
            SettingPlace.TEMPORARY, "temp_ask_first", "Ask before deleting temporary contacts",
            "When their time is up, one notification asks: delete, keep 7 more days or keep", P,
            "temporary", "temp", "expire", "expiry", "confirm", "ask", "delete automatically", "keep",
        ),
        e("duplicates", "Find & merge duplicates", "Contacts saved twice", P, "merge", "duplicate", "dedupe", "join"),
        e("health", "Contact health check", "Numbers without country code, empty and stale contacts", P, "tidy", "clean up", "fix", "cleanup"),
        e("contact_page", "Contact page sections", "Order, fold or hide the sections of a contact's page", P,
            "sections", "order", "reorder", "fold", "collapse", "expand", "hide", "timeline", "layout", "jump"),
        e("import_file", "Import from .vcf or .csv file", "Any CSV (Google, Outlook, a spreadsheet): choose what each column holds. With a report.", P,
            "vcard", "vcf", "csv", "import", "google", "outlook", "excel", "spreadsheet", "columns", "mapping"),
        e("bulk_add", "Add several numbers", "Paste a list of numbers and save them at once, to a label, privately or for a few days", P,
            "bulk", "many", "paste", "list", "batch", "import numbers", "leads"),
        // Scan QR (search finds it; it opens the scan screen).
        at(SettingPlace.TOOLS, "scan_qr", "Scan QR code", "Read a contact, number, chat link, Wi-Fi or web address from a photo, without camera access", P,
            "qr", "qr code", "scan", "scanner", "barcode", "vcard", "business card", "wifi", "whatsapp", "signal", "telegram", "camera"),
        e("import_sim", "Import from SIM card", "Copy the SIM's phonebook into your contacts", P, "sim", "phonebook", "copy"),
        e("export_vcf", "Export all to .vcf file", "Plain-text backup you control", P, "vcard", "export", "backup"),
        e("export_csv", "Export all to .csv file", "For spreadsheets", P, "spreadsheet", "excel", "export"),
        e("export_account", "Export one account to .vcf", "Contacts from one account only", P, "export", "account"),
        e("birthdays", "Birthdays & dates", "Upcoming birthdays and anniversaries", P, "anniversary", "events", "dates"),
        e("birthday_reminders", "Birthday reminders", "A notification on the day", P, "notification", "remind", "birthday"),
        e("reminder_time", "Reminder time", "When birthday reminders arrive", P, "hour", "time", "birthday"),
        e("nudges", "Keep-in-touch nudges", "For contacts where you set a reminder", P, "reach out", "remind", "call back", "keep in touch"),
        // The Circle.
        e("date_lead", "Remind me before dates", "On the day, or also 1, 3 or 7 days before", P, "birthday", "anniversary", "lead time", "days before", "early", "advance"),
        e("circle_delivery", "How keep-in-touch reminders arrive", "A weekly digest on Sunday, or one at a time as they come due", P,
            "digest", "weekly", "sunday", "circle", "remind", "keep in touch", "nudge", "notification"),
        e("circle_weekly_cap", "At most per week", "Keep-in-touch reminders a week, when they come as due", P, "limit", "cap", "how many", "circle", "nudge"),
        e("log_prompts", "Log messages you start", "After Parley opens a chat or video call with someone in your circle", P,
            "log", "interaction", "whatsapp", "signal", "telegram", "sms", "video", "circle", "ask", "snackbar"),

        // Recents & history
        e("archive", "Keep full call history", "Parley keeps its own encrypted copy, because Android may drop old calls", H, "archive", "call log", "history", "forever"),
        at(SettingPlace.DELETED_CALLS, "history_details", "Deleted calls", "Restore calls deleted in the last 30 days, in History & undo", H,
            "undo", "restore", "recently deleted", "trash", "bin"),
        e("kept_forever", "Numbers kept forever", "Calls with these numbers stay, whatever the retention", H, "keep forever", "archive", "retention", "pin"),
        e("csv_bom", "Excel-friendly CSV", "Adds a byte-order mark so accents show correctly in Excel", H, "excel", "csv", "accents", "byte order mark", "export"),
        e("retention", "Keep call history", "Delete calls from the system call log after a while", H, "retention", "delete old calls", "auto delete", "call log"),
        e("sim_labels", "Show SIM in call history", "Only when two SIMs are active", H, "dual sim", "sim label"),
        e("recents_layout", "Call list layout", "Grouped, every call on its own row, or grouped by day", H,
            "chronological", "grouped", "by day", "ungroup", "list", "call log", "layout"),
        e("recents_remember_filter", "Remember the Recents filter", "Recents opens on the filter you used last, such as Unknown or Contacts", H,
            "filter", "chips", "unknown callers", "unknown numbers", "contacts only", "remember", "last filter"),
        e("recents_style", "Recents style", "Rich: shapes, tints and a Call back button for missed calls. Simple: plain icons", H,
            "rich", "simple", "colours", "colors", "icons", "missed", "call back", "style", "legend", "colour blind"),
        e("clear_history", "Clear call history", "All calls, calls from unknown numbers or missed calls, with an export first", H,
            "delete", "clear", "wipe", "erase", "unknown numbers", "call log"),
        e("insights", "Call insights", "Talk time, top people, calls you didn't return", H, "statistics", "stats", "charts", "talk time"),
        // The People card.
        e("people_card", "People card in Call insights", "Reach in your circle, open loops and your year, in Call insights", H,
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
        e("family_safe_word", "Family safe word", "A private question for callers who say they're family, set on a label's page", S,
            "safe word", "scam", "grandparent", "impostor", "voice clone", "family", "question"),
        e("hide_vault", "Hide private contacts", "Discreet mode: private contacts and their calls disappear from lists and search", S, "vault", "discreet", "private", "hidden"),
        e("private_history", "Private call history", "Calls with private contacts are moved out of the system call log", S, "vault", "private calls", "call log"),
        e("privacy_dashboard", "Privacy dashboard", "What Parley can access and why", S, "permissions", "data", "internet", "tracking"),
        e("who_can_see", "Who can see your contacts", "Which apps can read your contacts", S, "apps", "access", "contact scopes", "grapheneos"),
        e("private_names", "Let apps show private names", "Approved apps can look up one private name at a time", S, "caller id", "lookup", "private names"),
        e("private_directory", "Private names in other phone apps", "Off by default. A phone app you approve (for example Google Phone, also in the car) can show who is calling", S,
            "directory", "car", "work profile", "android auto", "caller id", "dialer", "private names"),
        e("app_permissions", "App permissions (system)", "Android's settings for Parley", S, "permissions", "system", "app info"),
        e("delete_all_data", "Delete all Parley data", "Everything Parley keeps on this phone, after an optional backup", S,
            "erase", "wipe", "reset", "clear data", "forget", "start over", "remove everything"),

        // Backup & sync
        e("backup", "Backup & restore", "Encrypted backups to a folder you choose", U, "restore", "export", "encrypted", "new phone", "move", "transfer"),
        e("backup_reminder", "Remind me to back up", "A quiet reminder when there's been no backup for a while", U, "reminder", "overdue", "backup", "notification", "nag"),
        e("sync", "Sync between your phones", "Through a Syncthing / Nextcloud folder, no server", U, "syncthing", "nextcloud", "folder", "second phone"),
        e("journal", "History & undo", "Deleted contacts and calls, changes and daily snapshots: undo for 30 days", U,
            "undo", "trash", "restore", "deleted", "bin", "recently deleted", "journal"),
        e("time_machine", "Daily snapshots (time machine)", "Daily snapshots for 6 months: see and undo changes", U, "snapshots", "history", "versions", "restore"),
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

        // Settings on screens of their own (search opens the screen).
        at(SettingPlace.BLOCKING, "blk_hidden_numbers", "Silence or block hidden numbers", "Private, unknown and withheld numbers", B,
            "hidden", "private number", "withheld", "no caller id", "anonymous", "unknown"),
        at(SettingPlace.BLOCKING, "blk_non_contacts", "Only people I know ring", "Numbers that aren't in your contacts are silenced or blocked", B,
            "unknown", "strangers", "contacts only", "whitelist", "allow list"),
        at(SettingPlace.BLOCKING, "blk_off_hours", "Off hours", "Quiet times when only the people you choose ring", B,
            "schedule", "night", "sleep", "work hours", "weekend", "quiet", "bedtime"),
        at(SettingPlace.BLOCKING, "blk_more_checks", "More checks", "Neighbour spoofing, failed caller verification, numbers that can't exist", B,
            "spoofing", "stir shaken", "verification", "invalid", "neighbour", "neighbor"),
        at(SettingPlace.BLOCKING, "blk_sounds", "Sounds for screened calls", "Favourites ring loud; ringtones for repeat callers and likely spam", B,
            "ringtone", "loud", "favourites", "favorites", "repeat", "spam sound"),
        at(SettingPlace.BLOCKING, "blk_emergency", "Emergency numbers", "Emergency numbers always ring through, with extra numbers you add", B,
            "112", "911", "999", "emergency", "always ring"),
        at(SettingPlace.BLOCKING, "blk_notifications", "Blocked call notifications", "A notice when a call is blocked, reported or likely spam", B,
            "notification", "alert", "blocked call"),
        at(SettingPlace.BLOCKING, "blk_system_list", "Blocked numbers (system list)", "Android's own list, shared with other phone apps", B,
            "system", "blocked numbers", "shared", "android"),
        at(SettingPlace.SIMPLE_MODE, "simple_keypad", "Simple mode: keypad button", "A large keypad button on the simple home screen", L, "keypad", "big", "dial"),
        at(SettingPlace.SIMPLE_MODE, "simple_confirm_decline", "Simple mode: ask before declining", "A question before a call is declined", L, "decline", "accidental", "reject"),
        at(SettingPlace.SIMPLE_MODE, "simple_speak", "Simple mode: say who is calling", "Reads the caller's name aloud", L, "text to speech", "speak", "announce", "caller name"),
        at(SettingPlace.SIMPLE_MODE, "simple_helpers", "Simple mode: helpers", "A big Add my helper button during calls", L, "helper", "family", "add call"),
        at(SettingPlace.SIMPLE_MODE, "simple_share", "Simple mode: set up another phone", "Share the setup as an encrypted file or QR code", L, "share", "qr", "family", "another phone"),
        at(SettingPlace.CALL_TIME, "ct_reminders", "Talk-time reminders", "A beep or a vibration every few minutes during a call", T, "beep", "vibrate", "reminder", "minutes"),
        at(SettingPlace.CALL_TIME, "ct_limits", "Call time limits", "A warning, or the call ends, after a set time, for a contact or a label", T, "limit", "maximum", "end call"),
        at(SettingPlace.CALL_TIME, "ct_supervised", "Supervised mode", "Limits that can't be changed without unlocking", T, "parental", "child", "lock", "supervised"),
        at(SettingPlace.BACKUP, "backup_automatic", "Automatic backups", "Back up on a schedule to your folder", U, "schedule", "daily", "weekly", "auto"),
        at(SettingPlace.BACKUP, "backup_keep", "Backups to keep", "How many backups stay in the folder", U, "rotation", "keep", "old backups"),
        at(SettingPlace.BACKUP, "backup_restore", "Restore a backup", "From a backup file, with an undo afterwards", U, "restore", "import backup"),
        at(SettingPlace.BACKUP, "backup_move_phone", "Move to a new phone", "Everything to your new phone, step by step", U, "new phone", "transfer", "migrate"),
        at(SettingPlace.SYNC, "sync_auto", "Sync automatically", "Keep two phones in step through a shared folder", U, "auto sync", "syncthing", "nextcloud"),
        at(SettingPlace.CONTACT_PAGE, "section_chips", "Jump to a section", "Chips on long contact pages that jump to a section", P, "chips", "jump", "sections"),
        at(SettingPlace.SIMS, "sim_billing", "Billing increments per SIM", "Per-second or per-minute billing, what counts, and the 80 % warning", C,
            "billing", "per minute", "per second", "rounding", "plan", "tariff"),
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
