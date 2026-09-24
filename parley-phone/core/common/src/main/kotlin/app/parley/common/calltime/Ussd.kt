package app.parley.common.calltime

/**
 * Recognises USSD codes (balance checks like `*100#`, `#123*1#`) typed on the keypad (A13).
 *
 * Supplementary-service codes (call forwarding `**21*…#`, waiting `*43#`, caller ID `*31#`, barring, PIN
 * changes, `*#06#`) are *not* USSD: the phone handles them itself when they are dialled like a call, so they
 * keep going through Telecom. Hidden `*#*#…#*#*` codes are handled elsewhere.
 */
object Ussd {
    private val shape = Regex("^[*#][0-9*#]*#$")
    private val mmi = Regex("^(\\*#|\\*\\*|##|\\*|#)(\\d{2,3})(\\*[0-9*+#]*)?#$")

    /** GSM 22.030 supplementary service codes. */
    private val supplementary = setOf(
        "21", "67", "61", "62", "002", "004", // call forwarding
        "30", "31", "76", "77", "300", // CLIP, CLIR, COLP, COLR, CNAP
        "43", // call waiting
        "33", "331", "332", "35", "351", "330", "333", "353", // call barring
        "03", "04", "042", "05", "052", // passwords, PIN and PUK changes
        "06", // IMEI
    )

    fun isUssd(input: String): Boolean {
        val s = input.trim()
        if (s.length < 3 || !shape.matches(s)) return false
        if (s.startsWith("*#*#")) return false
        val m = mmi.matchEntire(s)
        if (m != null && m.groupValues[2] in supplementary) return false
        // Needs at least one digit: "*#" or "##" alone mean nothing.
        return s.any { it.isDigit() }
    }

    /** Carriers pad replies with blank lines and trailing spaces. */
    fun tidy(reply: CharSequence?): String = reply?.toString().orEmpty().lines().joinToString("\n") { it.trimEnd() }.trim()

    /** Keeps the newest [max] replies. */
    fun append(history: List<UssdEntry>, entry: UssdEntry, max: Int = 50): List<UssdEntry> = (listOf(entry) + history).take(max)
}
