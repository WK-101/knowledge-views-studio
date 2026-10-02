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

    /** The new "Scan QR code" icon in the Contacts header. */
    const val CONTACTS_SCAN_QR = "contacts_scan_qr"

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

    /** I2: the "Looks like a sales line (your calls)" tag on the call screen, the first time it shows. */
    const val REPUTATION_TAG = "reputation_tag"

    /** Number memory: the first remembered line about a number that isn't a contact (keypad, number history). */
    const val NUMBER_MEMORY = "number_memory"

    // P18: one line on what a concept means, where it first appears (the names are fixed in docs/GLOSSARY.md).

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
 * The "What's new" card. It shows once per app version after an update, as a card the user can dismiss, and
 * never after a fresh install (there is nothing "new" yet). It never changes tabs or layout by itself.
 */
object WhatsNew {
    enum class Decision { SHOW, MARK_SEEN, NOTHING }

    /**
     * [seenVersion] is the last version whose card was seen or skipped (0 = never recorded), [currentVersion] this
     * build's version code; [freshInstall] when this version is the first one installed on the device.
     */
    fun decide(seenVersion: Int, currentVersion: Int, freshInstall: Boolean): Decision = when {
        seenVersion >= currentVersion -> Decision.NOTHING
        freshInstall -> Decision.MARK_SEEN
        else -> Decision.SHOW
    }
}
