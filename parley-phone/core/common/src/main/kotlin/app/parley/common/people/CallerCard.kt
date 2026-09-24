package app.parley.common.people

/**
 * I6: the extra lines on a caller card: "Engineer · Acme" and a "who is this" context line ("Plumber, fixed the
 * boiler in May"), for private contacts too. What the in-call screen may show and what a notification may say are
 * different: notifications can reach the lock screen and notification listeners.
 */
object CallerCard {
    /** "Engineer · Acme", or whichever of the two is set, or null. */
    fun subtitle(title: String?, company: String?): String? =
        listOfNotNull(title?.trim()?.ifEmpty { null }, company?.trim()?.ifEmpty { null })
            .distinctBy { it.lowercase() }.joinToString(" · ").ifEmpty { null }

    /** The context line, one line, at most [max] characters. */
    fun context(text: String?, max: Int = 80): String? {
        val t = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }

    /**
     * The extra line of a missed-call notification (only the private version: the public, lock-screen version never
     * has it). A private contact's details never show in discreet mode, or when the notification doesn't show its
     * name either. Regular contacts: the context line, else the job line.
     */
    fun missedCallLine(isPrivate: Boolean, hideVault: Boolean, subtitle: String?, context: String?): String? {
        if (isPrivate && hideVault) return null
        return context?.let { context(it, 60) } ?: subtitle
    }
}
