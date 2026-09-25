package app.parley.common.ux

/**
 * U2: one-time coach marks. Each mark has a stable id; once dismissed it never shows again until the user picks
 * "Reset tips". Later features add their own ids here (for example the Circle suggestions or "Log this?").
 */
object Tips {
    const val KEYPAD_SPEED_DIAL = "keypad_speed_dial"
    const val RECENTS_SWIPE = "recents_swipe"
    const val HEADER_SEARCH = "header_search"

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
 * U6: the "What's new" card. It shows once per app version after an update, as a card the user can dismiss, and
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
