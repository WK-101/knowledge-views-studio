package app.parley.common

/** Top-level groups of Settings, in the order they're listed. */
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
 * One searchable setting. [title] and [summary] are what the settings screens show (a screen may replace the
 * summary with a live value such as "Last backup 2 h ago"); [keywords] are extra words people search with.
 */
data class SettingEntry(
    val key: String,
    val title: String,
    val summary: String,
    val category: SettingsCategory,
    val keywords: List<String> = emptyList(),
)

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
        e("density", "List density", "Comfortable or compact rows", A, "compact", "spacing", "row height", "size"),
        e("nav_tabs", "Navigation bar", "Show, hide and reorder Favorites, Recents, Contacts and Keypad", A,
            "tabs", "bottom bar", "bottom navigation", "navigation rail", "reorder", "hide tab", "customise", "customize", "menu"),
        e("start_tab", "Open on", "The tab Parley opens on", A, "start tab", "default tab", "home screen", "launch", "first screen"),
        e("row_actions", "Call & message buttons on contacts", "Tapping a contact still opens it", A, "quick actions", "buttons", "sms", "row"),
        e("sort_names", "Sort and show names by", "First name or last name", A, "order", "alphabetical", "surname", "family name", "given name"),
        e("second_line", "Second line under names", "Company, nickname, account or number", A, "subtitle", "company", "account", "details"),
        e("prefer_nickname", "Prefer nicknames", "Show “Bob” instead of “Robert Jones” in lists", A, "nickname", "short name"),
        e("swipe_actions", "Swipe actions", "Off by default. Swipe a contact or a call right to call, left to message", A,
            "swipe", "gesture", "slide", "left", "right", "quick actions"),
        e("avatar_style", "Avatars", "Colourful or grey letters; names that start with an emoji show it", A, "avatar", "monogram", "emoji", "picture", "letters", "grey", "gray"),

        // Calls
        e("default_dialer", "Default phone app", "Needed to show calls, manage blocking and the call log", C, "default dialer", "role", "phone app"),
        e("answer_gesture", "Answer incoming calls by", "Swipe or tap", C, "slide", "swipe", "tap", "pocket", "answer"),
        e("confirm_call", "Confirm before calling", "Avoids accidental calls from lists and search", C, "accidental", "ask before", "pocket dial"),
        e("call_haptics", "Vibrate on call events", "When a call connects, ends, is swapped or merged", C, "vibration", "haptic", "buzz"),
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

        // Recents & history
        e("archive", "Keep full call history", "Parley keeps its own encrypted copy, because Android may drop old calls", H, "archive", "call log", "history", "forever"),
        e("history_details", "Kept calls & recently deleted", "Numbers kept forever, 30-day undo for deleted calls, export", H, "undo", "restore", "keep forever", "export"),
        e("retention", "Keep call history", "Delete calls from the system call log after a while", H, "retention", "delete old calls", "auto delete", "call log"),
        e("sim_labels", "Show SIM in call history", "Only when two SIMs are active", H, "dual sim", "sim label"),
        e("insights", "Call insights", "Talk time, top people, calls you didn't return", H, "statistics", "stats", "charts", "talk time"),
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
        e("sync", "Sync between your phones", "Through a Syncthing / Nextcloud folder, no server", U, "syncthing", "nextcloud", "folder", "second phone"),
        e("journal", "Recently deleted & changed", "Undo for 30 days", U, "undo", "trash", "restore", "deleted", "bin"),
        e("time_machine", "What changed (time machine)", "Daily snapshots for 6 months: see and undo changes", U, "snapshots", "history", "versions", "restore"),

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
            TextSearch.normalize(e.category.title).split(' ').any { it.startsWith(word) } -> 20
            word.length >= 3 && TextSearch.normalize(e.summary).split(' ', ',', '.', '(', ')').any { it.startsWith(word) } -> 10
            else -> 0
        }
    }
}
