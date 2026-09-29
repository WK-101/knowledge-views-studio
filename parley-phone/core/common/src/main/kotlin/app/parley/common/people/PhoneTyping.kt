package app.parley.common.people

import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Formats a phone number as it is typed in the contact editor ("07700900123" shows as "07700 900123"), for display
 * only: what is saved is exactly what was typed, so a number from another app or account is never rewritten.
 *
 * Formatting is skipped (the text shows as typed) when it holds anything besides digits and one leading "+" (spaces,
 * dashes, pauses, letters: the person is formatting it themselves) or when libphonenumber can't format it without
 * dropping or reordering a character. So the result always contains the typed characters in order, and [toShown]
 * / [toTyped] map cursor positions between the two.
 */
class PhoneTyping private constructor(
    val typed: String,
    val shown: String,
    /** For each typed position 0..typed.length, its position in [shown]. */
    private val typedToShown: IntArray,
) {
    fun toShown(offset: Int): Int = typedToShown[offset.coerceIn(0, typed.length)]

    fun toTyped(offset: Int): Int {
        val o = offset.coerceIn(0, shown.length)
        // The last typed position at or before this shown position.
        var best = 0
        for (i in typedToShown.indices) if (typedToShown[i] <= o) best = i
        return best
    }

    companion object {
        private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

        /** Only plain digits (optionally after one leading "+") are formatted. */
        fun formattable(text: String): Boolean {
            if (text.length < 2) return false
            val body = if (text.startsWith("+")) text.substring(1) else text
            return body.isNotEmpty() && body.all { it in '0'..'9' }
        }

        fun of(text: String, regionIso: String): PhoneTyping {
            if (!formattable(text)) return identity(text)
            val formatted = runCatching {
                val f = util.getAsYouTypeFormatter(regionIso.uppercase(java.util.Locale.ROOT))
                var out = ""
                for (ch in text) out = f.inputDigit(ch)
                out
            }.getOrNull() ?: return identity(text)
            return mapped(text, formatted) ?: identity(text)
        }

        private fun identity(text: String) = PhoneTyping(text, text, IntArray(text.length + 1) { it })

        /**
         * Maps [typed] into [shown] when [shown] is [typed] with only spaces, dashes, dots, slashes or brackets
         * added; null otherwise (the formatter changed something, so it isn't safe to show).
         */
        internal fun mapped(typed: String, shown: String): PhoneTyping? {
            val map = IntArray(typed.length + 1)
            var s = 0
            for (t in typed.indices) {
                while (s < shown.length && shown[s] != typed[t]) {
                    if (shown[s] !in SEPARATORS) return null
                    s++
                }
                if (s >= shown.length) return null
                map[t] = s
                s++
            }
            // Separators after the last typed character stay after the cursor.
            if (shown.substring(s).any { it !in SEPARATORS }) return null
            map[typed.length] = s
            return PhoneTyping(typed, shown, map)
        }

        private const val SEPARATORS = " -.()/ "
    }
}
