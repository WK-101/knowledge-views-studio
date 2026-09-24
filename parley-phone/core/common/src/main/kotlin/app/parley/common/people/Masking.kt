package app.parley.common.people

/**
 * Masks personal data in diagnostics text: phone numbers keep only their last two digits, e-mail addresses
 * keep only their domain, and content/lookup URIs lose their ids. Used by default for "Export diagnostics".
 */
object Masking {
    // A run of 5+ digits, possibly with spaces, dashes, dots, brackets and a leading + in between.
    private val number = Regex("\\+?\\(?\\d[\\d\\s().\\-]{3,}\\d")
    private val email = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})")
    private val contentUri = Regex("content://[\\w.\\-]+(/[\\w.%\\-]+)*")

    fun mask(text: String): String {
        var s = email.replace(text) { m -> "•••@" + m.groupValues[1] }
        s = contentUri.replace(s) { m -> "content://" + m.value.removePrefix("content://").substringBefore('/') + "/•••" }
        s = number.replace(s) { m -> maskNumber(m.value) }
        return s
    }

    /** "+44 7700 900123" → "••••••••••23" (digit count kept, so formats stay recognisable). */
    fun maskNumber(n: String): String {
        val digits = n.count { it.isDigit() }
        if (digits < 5) return n
        val tail = n.filter { it.isDigit() }.takeLast(2)
        return "•".repeat(digits - 2) + tail
    }
}
