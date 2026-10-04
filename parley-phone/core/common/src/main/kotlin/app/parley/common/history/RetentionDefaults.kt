package app.parley.common.history

/**
 * How long call history is kept when nobody chose. A new install keeps five years, so the archive and the call log
 * stop growing forever; someone who already used Parley keeps what they had (everything, unless they chose a limit),
 * because a new default must never delete calls they expected to keep.
 */
object RetentionDefaults {
    /** Five years, in days. */
    const val NEW_INSTALL_DAYS = 1825

    /** The choices offered in Settings, in days (0 = forever). The new-install default is among them. */
    val CHOICES: List<Int> = listOf(0, 30, 90, 180, 365, 1095, NEW_INSTALL_DAYS)

    /** The retention in force: [stored] when one was chosen or pinned, else by whether this is an existing user. */
    fun resolve(stored: Int?, existingUser: Boolean): Int = stored ?: if (existingUser) 0 else NEW_INSTALL_DAYS
}
