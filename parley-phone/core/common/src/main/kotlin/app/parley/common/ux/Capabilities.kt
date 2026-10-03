package app.parley.common.ux

import app.parley.common.SettingsCatalog
import app.parley.common.TextSearch

/**
 * Tools (P8, once "What Parley can do"): the one hub, grouped by what people want done rather than by where a feature
 * lives. Each row is one line and opens the feature; each job shows its [Capability.featured] rows and folds the rest
 * under "More". The rows come from [CapabilityCatalog] only, so the page, its search and the What's new card can't
 * disagree, and a test checks that every row still leads somewhere.
 */
enum class Job(val title: String) {
    STOP_SPAM("Stop spam"),
    NEVER_LOSE("Never lose a contact"),
    STAY_IN_TOUCH("Stay in touch"),
    KNOW_WHO("Know who's calling"),
    KEEP_PRIVATE("Keep it private"),
    MESSAGE("Message without saving"),
    BETTER_CALLS("Calls that work better"),
}

/** Screens a row can open directly. The app maps each one to its route with an exhaustive `when`. */
enum class AppScreen {
    BLOCKING, SPAM_LISTS, TEST_A_CALL, RULE_TEMPLATES, BLOCK_LIST_IMPORT,
    HISTORY_UNDO, SNAPSHOTS, BACKUP, SYNC, HEALTH_CHECK, DUPLICATES, COMING_FROM,
    CIRCLE, BIRTHDAYS, TO_CALL, CALL_INSIGHTS, TRIP,
    LABELS, SCAN_QR, MY_CARD, HELPERS,
    PRIVACY_DASHBOARD, WHO_CAN_SEE, TEMPORARY,
    KEYPAD, MESSAGED_NUMBERS, BULK_ADD, NEW_CONTACT,
    CALL_TIME, SIMS, SIMPLE_MODE,
}

/**
 * A row the hub runs in place instead of opening something: Lock now (only while the app lock is on) and the
 * Expecting a call switch. The row's [Capability.target] is still where it leads elsewhere (search, tests).
 */
enum class CapabilityAction { LOCK_NOW, EXPECTING_CALL }

/** Where a row leads: a screen, or a setting (opened exactly where Settings search would open it). */
sealed interface CapabilityTarget {
    data class Screen(val screen: AppScreen) : CapabilityTarget

    /** A key of [SettingsCatalog]. */
    data class Setting(val key: String) : CapabilityTarget
}

/**
 * One row. [title] and [summary] are the English reference texts (the app shows string resources keyed by [key],
 * search keeps matching these as well); [since] is the release that brought it ("4.6"), for the "New" mark.
 * [featured] rows show without opening "More"; [action] makes the row a control rather than a link.
 */
data class Capability(
    val key: String,
    val job: Job,
    val title: String,
    val summary: String,
    val target: CapabilityTarget,
    val keywords: List<String> = emptyList(),
    val since: String? = null,
    val featured: Boolean = false,
    val action: CapabilityAction? = null,
)

object CapabilityCatalog {
    private fun screen(key: String, job: Job, title: String, summary: String, s: AppScreen, vararg kw: String, since: String? = null) =
        Capability(key, job, title, summary, CapabilityTarget.Screen(s), kw.toList(), since)

    private fun setting(key: String, job: Job, title: String, summary: String, setting: String, vararg kw: String, since: String? = null) =
        Capability(key, job, title, summary, CapabilityTarget.Setting(setting), kw.toList(), since)

    /** This row shown before "More", and run in place when [action] is set. */
    private fun Capability.top(action: CapabilityAction? = null) = copy(featured = true, action = action)

    private val SPAM = Job.STOP_SPAM
    private val LOSE = Job.NEVER_LOSE
    private val TOUCH = Job.STAY_IN_TOUCH
    private val WHO = Job.KNOW_WHO
    private val PRIVATE = Job.KEEP_PRIVATE
    private val MSG = Job.MESSAGE
    private val CALLS = Job.BETTER_CALLS

    val rows: List<Capability> = listOf(
        // Stop spam
        screen("who_rings", SPAM, "Choose who can ring", "Only people you know, quiet nights or everyone, in one tap", AppScreen.BLOCKING,
            "block", "silence", "screening", "unknown callers", "strangers", "preset").top(),
        screen("spam_lists", SPAM, "Offline spam lists", "Lists you add yourself warn or silence known spam numbers", AppScreen.SPAM_LISTS,
            "spam", "list", "robocall", "ftc", "arcep").top(),
        setting("sales_lines", SPAM, "Spot sales lines from your own calls", "A quiet tag, with an optional rule to silence them", "learn_from_calls",
            "telemarketing", "sales", "reputation", since = "4.5"),
        screen("test_call", SPAM, "Test a call", "See what your rules would do, and replay last week", AppScreen.TEST_A_CALL,
            "dry run", "simulate", "why"),
        screen("rule_templates", SPAM, "Rules for your country", "Ready-made ranges from regulators, installed as a group", AppScreen.RULE_TEMPLATES,
            "templates", "regulator", "premium", "toll free"),
        screen(
            "block_import", SPAM, "Bring your block list", "From Call Blocker, Yet Another Call Blocker, NoPhoneSpam or a spreadsheet",
            AppScreen.BLOCK_LIST_IMPORT,
            "import", "yacb", "call blocker", "nophonespam", "csv",
        ),
        setting("expecting", SPAM, "Expecting a call", "Let unknown callers ring for a while, such as a delivery", "expecting_call",
            "delivery", "courier", "snooze").top(CapabilityAction.EXPECTING_CALL),

        // Never lose a contact
        screen("history_undo", LOSE, "Undo a delete, edit or merge", "History & undo keeps 30 days of changes", AppScreen.HISTORY_UNDO,
            "undo", "restore", "deleted", "trash", "recently deleted").top(),
        screen("snapshots", LOSE, "Daily snapshots", "See what changed in your contacts, and put any version back", AppScreen.SNAPSHOTS,
            "time machine", "versions", "history"),
        screen("backup", LOSE, "Encrypted backups", "To a folder you choose, on a schedule, checked after writing", AppScreen.BACKUP,
            "backup", "restore", "new phone", "export").top(),
        screen("sync", LOSE, "Sync between your phones", "Through a Syncthing or Nextcloud folder, no server", AppScreen.SYNC,
            "syncthing", "nextcloud", "second phone"),
        screen("coming_from", LOSE, "Coming from another phone?", "Bring contacts, call history and block lists from your old phone", AppScreen.COMING_FROM,
            "import", "switch", "iphone", "icloud", "samsung", "google", "vcf", "move", since = "4.6").top(),
        setting("import_export", LOSE, "Import & export contacts", "vCard and CSV files, the SIM card, one account", "import_file",
            "import", "export", "vcf", "vcard", "csv", "sim", "spreadsheet").top(),
        screen("health", LOSE, "Contact health check", "Numbers without a country code, empty and stale contacts", AppScreen.HEALTH_CHECK,
            "tidy", "clean up", "fix").top(),
        screen("duplicates", LOSE, "Find & merge duplicates", "Contacts saved twice, merged with one undo", AppScreen.DUPLICATES,
            "merge", "duplicate", "dedupe"),

        // Stay in touch
        setting("reminders", TOUCH, "All your reminders", "Missed calls, To call, keep in touch, birthdays and backups", "reminders",
            "remind", "reminder", "notification", "nudge", "digest", "follow up", "re-alert").top(),
        screen("circle", TOUCH, "Keep in touch with your Circle", "Gentle reminders for the people you want to stay close to", AppScreen.CIRCLE,
            "circle", "remind", "keep in touch", "friends", "family").top(),
        screen("birthdays", TOUCH, "Birthdays & dates", "Upcoming birthdays with a reminder on the day or before", AppScreen.BIRTHDAYS,
            "birthday", "anniversary", "dates").top(),
        screen("to_call", TOUCH, "To call", "Calls you said you'd make, and missed calls you haven't returned", AppScreen.TO_CALL,
            "remind me", "call back", "missed").top(),
        setting("remember", TOUCH, "Anything to remember?", "A note and a follow-up after calls with your contacts", "memory_prompt",
            "note", "promise", "follow up"),
        // Also a chip on Contacts; this row keeps it reachable when the Contacts tab is hidden.
        screen("whos_in", TOUCH, "Who's in…", "People linked to a city you're visiting, by address, notes or number", AppScreen.TRIP,
            "who's in", "whos in", "trip", "travel", "city", "visiting", "abroad"),
        screen("insights", TOUCH, "Call insights", "Talk time, top people and calls you didn't return", AppScreen.CALL_INSIGHTS,
            "statistics", "stats", "talk time"),

        // Know who's calling
        screen("labels", WHO, "Labels with their own ringtone", "Family, Work or any label, with a ringtone and a vibration", AppScreen.LABELS,
            "groups", "ringtone", "label").top(),
        setting("caller_vibration", WHO, "A vibration of their own", "Tell who's calling without looking", "caller_vibration",
            "haptic", "vibrate", "pattern"),
        setting("unknown_ringtone", WHO, "A different ringtone for unknown callers", "Hear at once that it isn't one of your contacts", "unknown_ringtone",
            "ringtone", "sound", "unknown"),
        screen("scan_qr", WHO, "Scan a contact's QR code", "From a photo, without camera access", AppScreen.SCAN_QR,
            "qr", "scan", "business card").top(),
        setting("safe_word", WHO, "Family safe word", "A question only your family can answer, for calls that say they're family", "family_safe_word",
            "scam", "impostor", "voice clone", since = "4.5"),

        // Keep it private
        setting("lock_now", PRIVATE, "Lock Parley now", "Without waiting for the app lock's timeout", "app_lock",
            "lock", "app lock", "hide", "close").top(CapabilityAction.LOCK_NOW),
        screen("privacy_dashboard", PRIVATE, "Privacy dashboard", "What Parley can see, and why", AppScreen.PRIVACY_DASHBOARD,
            "permissions", "data", "internet").top(),
        setting("private_contacts", PRIVATE, "Private contacts", "Kept only in Parley, encrypted, hidden from other apps", "hide_vault",
            "vault", "hidden", "discreet", "encrypted").top(),
        screen("temporary", PRIVATE, "Temporary contacts", "Contacts that delete themselves after a time you choose", AppScreen.TEMPORARY,
            "temporary", "expire", "self-destruct").top(),
        screen("who_can_see", PRIVATE, "Who can see your contacts", "Which apps can read your address book", AppScreen.WHO_CAN_SEE,
            "apps", "access", "contact scopes"),
        setting("app_lock", PRIVATE, "App lock", "Your fingerprint, face or screen lock to open Parley", "app_lock",
            "lock", "fingerprint", "biometric", "pin"),

        // Message without saving
        screen("message_number", MSG, "Message a number without saving it", "Type it on the keypad, then Message or call on…", AppScreen.KEYPAD,
            "whatsapp", "signal", "telegram", "chat", "unsaved").top(),
        screen("messaged", MSG, "Messaged numbers", "The chats you opened from Parley; delete them or stop keeping them", AppScreen.MESSAGED_NUMBERS,
            "record", "history", "whatsapp").top(),
        screen("bulk_add", MSG, "Add several numbers", "Paste a list and save it at once, privately or for a few days", AppScreen.BULK_ADD,
            "bulk", "paste", "list"),
        screen("my_card", MSG, "Send my details", "Your card as a QR code or vCard", AppScreen.MY_CARD,
            "my card", "share", "vcard", "qr"),
        screen("paste_details", MSG, "Make a contact from pasted text", "Paste a signature or profile, tick what to keep", AppScreen.NEW_CONTACT,
            "paste", "signature", "business card", "copy", "clipboard", since = "4.6"),
        screen("card_updates", MSG, "A card that stays current", "Contacts see your new number when you change it", AppScreen.MY_CARD,
            "signed", "update", "new number", "changed my number", "shared with", since = "4.6"),
        setting("quick_replies", MSG, "Reply when you can't answer", "A short message when you decline a call", "quick_replies",
            "sms", "decline", "busy"),

        // Calls that work better
        setting("auto_answer", CALLS, "Answer automatically", "With a headset, in the car or for people you choose", "auto_answer",
            "headset", "bluetooth", "car", "hands-free").top(),
        screen("helpers", CALLS, "Add a helper to a call", "Someone you trust, joined in with one tap", AppScreen.HELPERS,
            "helper", "family", "conference", since = "4.5"),
        screen("call_time", CALLS, "Talk-time reminders and limits", "A quiet beep, or a call that ends on time", AppScreen.CALL_TIME,
            "timer", "limit", "beep"),
        screen("sims", CALLS, "Plan minutes per SIM", "Billing increments and a warning at 80 %", AppScreen.SIMS,
            "dual sim", "minutes", "plan"),
        setting("pocket_guard", CALLS, "No more pocket calls", "Favourites and widgets ask first while the phone is covered", "pocket_guard",
            "pocket", "accidental", "butt dial"),
        setting("missed_realert", CALLS, "Remind me of missed calls", "Alert again every few minutes until you've seen them", "missed_realert",
            "re-alert", "missed call"),
        screen("simple_mode", CALLS, "Simple mode", "Big photo buttons and a larger keypad, set up for someone else", AppScreen.SIMPLE_MODE,
            "elderly", "senior", "easy", "large").top(),
    )

    /** The rows of [job], in catalog order. */
    fun forJob(job: Job): List<Capability> = rows.filter { it.job == job }

    /** The rows of [job] the hub shows before "More". */
    fun featured(job: Job): List<Capability> = forJob(job).filter { it.featured }

    /** The rows of [job] folded under "More". */
    fun more(job: Job): List<Capability> = forJob(job).filterNot { it.featured }

    /** Rows that arrived in release [version] ("4.6", or "4.6.1": only major.minor counts). */
    fun newIn(version: String): List<Capability> {
        val release = majorMinor(version)
        return rows.filter { it.since != null && it.since == release }
    }

    /** "4.6.0-debug" → "4.6". */
    fun majorMinor(version: String): String = version.substringBefore('-').split('.').take(2).joinToString(".")

    /** Setting keys the rows point at that [SettingsCatalog] doesn't know (empty when every row leads somewhere). */
    fun unknownSettings(): List<String> = rows.mapNotNull { (it.target as? CapabilityTarget.Setting)?.key }
        .filter { key -> SettingsCatalog.entries.none { it.key == key } }
}

/**
 * Search over the page: accent- and case-insensitive, every word must start a word of the row's title, summary, job
 * or keywords. [texts] gives each row's shown (localised) title and summary; the English ones keep matching too.
 */
object CapabilitySearch {
    fun search(query: String, rows: List<Capability>, texts: (Capability) -> Pair<String, String> = { it.title to it.summary }): List<Capability> {
        val words = words(query)
        if (words.isEmpty()) return rows
        return rows.filter { c ->
            val (title, summary) = texts(c)
            val haystack = (listOf(title, summary, c.title, c.summary, c.job.title) + c.keywords).flatMap(::words).toSet()
            words.all { w -> haystack.any { it.startsWith(w) } }
        }
    }

    private fun words(s: String): List<String> =
        TextSearch.normalize(s).split(' ', ',', '.', '&', '/', '(', ')', '-', '…', '?', '\'').filter { it.isNotEmpty() }
}
