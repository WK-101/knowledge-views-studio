package app.parley.common.calls

/** P9: the number the keypad's Call button dials. */
object DialTarget {
    /**
     * The typed number exactly as typed (`*`, `#`, `+`, pauses and waits included): the top search result is only
     * used when the input is a name search (letters from a hardware keyboard). Null when nothing is typed.
     */
    fun pick(typed: String, topMatch: String?): String? {
        val n = typed.trim()
        if (n.isEmpty()) return null
        return if (n.any { it.isLetter() }) topMatch else n
    }
}

/** P9: `*#*#1234#*#*` codes are sent to the app that owns them, not dialled. */
object DialCodes {
    private val secret = Regex("^\\*#\\*#([0-9]+)#\\*#\\*$")

    fun secretCode(number: String): String? = secret.find(number.trim())?.groupValues?.get(1)
}
