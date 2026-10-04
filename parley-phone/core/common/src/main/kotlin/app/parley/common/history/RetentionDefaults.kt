package app.parley.common.history

/**
 * How long call history is kept when nobody chose. A new install keeps five years in Parley's own archive, so it stops
 * growing forever; someone who already used Parley keeps what they had (everything, unless they chose a limit),
 * because a new default must never delete calls they expected to keep.
 *
 * The phone's own call log is a different matter: Android may have restored it from another phone, or it outlived a
 * reinstall, so "a new install" says nothing about whose calls it holds. Parley trims it only once the user chose a
 * limit themselves; a default never touches it.
 */
object RetentionDefaults {
    /** Five years, in days. */
    const val NEW_INSTALL_DAYS = 1825

    /** The choices offered in Settings, in days (0 = forever). The new-install default is among them. */
    val CHOICES: List<Int> = listOf(0, 30, 90, 180, 365, 1095, NEW_INSTALL_DAYS)

    /** The retention in force: [stored] when one was chosen or pinned, else by whether this is an existing user. */
    fun resolve(stored: Int?, existingUser: Boolean): Int = stored ?: if (existingUser) 0 else NEW_INSTALL_DAYS

    /**
     * Whether the retention was the user's own choice, and so also trims the phone's call log. [chosen] is the stored
     * mark, written whenever someone picks a retention. Without it, a stored value comes from a version that had no
     * mark: there the setting only ever held a choice (its default was "forever" and wasn't a limit), except five
     * years, which was never offered then and so can only be a new-install default pinned by an early build.
     */
    fun chosen(stored: Int?, chosen: Boolean?): Boolean = chosen ?: (stored != null && stored > 0 && stored != NEW_INSTALL_DAYS)

    /** Days after which the phone's call log is trimmed (0 = never): only a limit the user chose. */
    fun systemLogDays(days: Int, chosen: Boolean): Int = if (chosen) days else 0

    /**
     * The settings restored from a backup, with the retention that backup meant. A backup made before the five-year
     * default has no retention key when nobody chose one, which meant "keep forever": it must not inherit this phone's
     * new-install default.
     */
    fun restored(map: Map<String, String>, retentionKey: String): Map<String, String> =
        if (retentionKey in map) map else map + (retentionKey to "i:0")
}
