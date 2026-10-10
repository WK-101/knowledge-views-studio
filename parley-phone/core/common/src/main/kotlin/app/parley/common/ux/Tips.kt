package app.parley.common.ux

/**
 * One-time coach marks. Each mark has a stable id; once dismissed it never shows again until the user picks
 * "Reset tips". Later features add their own ids here (for example the Circle suggestions or "Log this?").
 */
object Tips {
    const val KEYPAD_SPEED_DIAL = "keypad_speed_dial"
    const val RECENTS_SWIPE = "recents_swipe"
    const val HEADER_SEARCH = "header_search"

    /** "Leave" on the simple home needs a press and hold. */
    const val SIMPLE_LEAVE = "simple_leave"

    /**
     * Contacts' add button and its menu (New contact, Scan QR code, Add several numbers). Its own id, not the old "Scan
     * QR" tip's: whoever dismissed that one still learns the button is a menu now.
     */
    const val CONTACTS_ADD_MENU = "contacts_add_menu"

    /** The keypad docked in Recents folds away with a swipe down or a scroll, and comes back with its button. */
    const val DOCKED_KEYPAD = "docked_keypad"

    /** The layout options, offered once in the "What's new" card. */
    const val LAYOUT_OFFER = "layout_offer"

    /** Long-press an app's Message / Voice / Video button in "Reach via apps" to make it the usual way. */
    const val REACH_USUAL = "reach_usual"

    /** The "To call" strip at the top of Recents, the first time it shows. */
    const val TO_CALL = "to_call"

    /** Press and hold Speaker on the call screen for the list of audio outputs. */
    const val CALL_AUDIO_ROUTES = "call_audio_routes"

    /** The sync watchdog, explained once in the Contact health check. */
    const val SYNC_WATCHDOG = "sync_watchdog"

    /** The "Looks like a sales line (your calls)" tag on the call screen, the first time it shows. */
    const val REPUTATION_TAG = "reputation_tag"

    /** Number memory: the first remembered line about a number that isn't a contact (keypad, number history). */
    const val NUMBER_MEMORY = "number_memory"

    /** The in-call keypad's "Last time: 2 › 1 › 4" row, the first time it shows. */
    const val MENU_MEMORY = "menu_memory"

    /** Press and hold the keypad's Call pill (or a contact's Call) for "Call with a reason…". */
    const val CALL_REASON = "call_reason"

    // One line on what a concept means, where it first appears (the names are fixed in docs/GLOSSARY.md).

    /** A private contact's page: hidden from other apps, kept encrypted in Parley. */
    const val CONCEPT_PRIVATE = "concept_private"

    /** A temporary contact's page, or the Temporary contacts screen: it deletes itself. */
    const val CONCEPT_TEMPORARY = "concept_temporary"

    /** The Circle tab, once it has people: keep-in-touch, not a group and not Favourites. */
    const val CONCEPT_CIRCLE = "concept_circle"

    /** The Labels screen: your own groups. */
    const val CONCEPT_LABELS = "concept_labels"

    /** Favourites with people in it: starred contacts, which other apps see too. */
    const val CONCEPT_FAVOURITES = "concept_favourites"

    /** History & undo: the one place to get something back. */
    const val CONCEPT_HISTORY_UNDO = "concept_history_undo"

    /** "Paste details" at the top of a new contact, the first time it shows. */
    const val PASTE_DETAILS = "paste_details"

    /** My card: shared cards are signed, so contacts with Parley get your updates; "Shared with" lists who has it. */
    const val SIGNED_CARD = "signed_card"

    /** "Drive profile on" on the call screen, the first time a marked car is connected during a call. */
    const val DRIVE_PROFILE = "drive_profile"

    /** Sonic caller ID: "Make a ringtone for …" on a contact's or a label's page, the first time it shows. */
    const val CALLER_TUNE = "caller_tune"

    /** A shared label's part of its label page: everyone sees the same contacts, each change says who made it. */
    const val SHARED_LABEL = "shared_label"

    /**
     * The Situation tile, offered once (Android's own "Add tile?" question, no permission) the first time a Situation
     * is turned on from its row. Android 13 and later only; earlier versions have no way to ask, and never will.
     */
    const val SITUATION_TILE = "situation_tile"

    /** Android 13, the first that lets an app ask to add its Quick Settings tile. */
    private const val TILE_REQUEST_SDK = 33

    /** Whether turning a Situation on ([turningOn]) asks to add its tile now: once, and only where Android can ask. */
    fun offersSituationTile(turningOn: Boolean, seen: Set<String>, sdk: Int): Boolean =
        turningOn && sdk >= TILE_REQUEST_SDK && SITUATION_TILE !in seen

    /** Ids are stored comma-separated; anything that isn't a plain id is dropped. */
    private val ID = Regex("[a-z0-9_]{1,40}")

    fun decode(stored: String?): Set<String> =
        stored.orEmpty().split(',').map { it.trim() }.filter { ID.matches(it) }.toSet()

    fun encode(seen: Set<String>): String = seen.filter { ID.matches(it) }.sorted().joinToString(",")

    /**
     * Which of the marks asking to show right now actually shows: one at a time, so marks never pile up. The one
     * already showing keeps its place; otherwise the first unseen one in [requested] order.
     */
    fun visible(requested: List<String>, seen: Set<String>, showing: String?): String? =
        showing?.takeIf { it in requested && it !in seen } ?: requested.firstOrNull { it !in seen }
}

/**
 * The "What's new" card. It shows once per app version after an update, as a card the user can dismiss. A fresh
 * install has nothing "new" yet: it gets a short "What Parley can do" introduction instead, once. It never changes
 * tabs or layout by itself.
 */
object WhatsNew {
    enum class Decision {
        /** What's new in this version (an update). */
        SHOW,

        /** The first run's short introduction (a fresh install). */
        INTRO,
        NOTHING,
    }

    /**
     * [seenVersion] is the last version whose card was seen or skipped (0 = never recorded), [currentVersion] this
     * build's version code; [freshInstall] when this version is the first one installed on the device.
     */
    fun decide(seenVersion: Int, currentVersion: Int, freshInstall: Boolean): Decision = when {
        seenVersion >= currentVersion -> Decision.NOTHING
        freshInstall -> Decision.INTRO
        else -> Decision.SHOW
    }

    /**
     * The release ("6.2") the user last saw a card for. Builds before 6.4 stored only the version code, so those
     * codes are looked up here; null when nothing usable was stored (the card then names this release's rows).
     */
    fun lastSeenRelease(seenName: String?, seenVersion: Int): String? =
        seenName?.takeIf { it.isNotBlank() }?.let(CapabilityCatalog::majorMinor) ?: RELEASE_OF_CODE[seenVersion]

    /** Version codes of the releases that stored no name (from the build file's history). */
    private val RELEASE_OF_CODE: Map<Int, String> = mapOf(
        16 to "4.5", 17 to "4.6", 18 to "4.7", 19 to "5.0", 20 to "5.0", 21 to "5.1", 22 to "5.2", 23 to "5.3",
        24 to "5.3", 25 to "5.4", 26 to "5.5", 27 to "5.6", 28 to "5.7", 29 to "6.0", 30 to "6.1", 31 to "6.2",
        32 to "6.2", 33 to "6.2", 34 to "6.2", 35 to "6.3",
    )

    /**
     * The rows an update card names: what Tools started showing since the release seen last ([CapabilityCatalog.visibleSince]).
     * A fresh install never gets here (it has the introduction instead).
     */
    fun named(seenName: String?, seenVersion: Int, currentName: String, max: Int = 5): List<Capability> =
        CapabilityCatalog.visibleSince(lastSeenRelease(seenName, seenVersion), currentName, max)
}
