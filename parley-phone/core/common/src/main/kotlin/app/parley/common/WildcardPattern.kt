package app.parley.common

/**
 * A wildcard number pattern: '*' = any run of digits (none included), '?' = exactly one digit, '+' only as the first
 * character, the separators " -()." ignored, digits literal. Anything else makes the pattern invalid.
 *
 * Matching is linear in practice and O(n·m) at worst (two pointers with one backtrack point), whatever the
 * pattern: patterns arrive from shared templates, QR codes and imported lists, and a regex with many `[0-9]*` runs
 * backtracks exponentially on a number that doesn't match.
 */
class WildcardPattern private constructor(private val plus: Boolean, private val tokens: CharArray) {

    /** True when all of [s] matches (like `Regex.matches`). */
    fun matches(s: String): Boolean {
        var start = 0
        if (plus) {
            if (s.isEmpty() || s[0] != '+') return false
            start = 1
        }
        // Only digits are left in the pattern, so any other character in the number can never match.
        for (i in start until s.length) if (s[i] !in '0'..'9') return false
        var si = start
        var ti = 0
        var starAt = -1 // token index just after the last '*' seen
        var starMatch = 0 // where that star's run of digits ends in s
        while (si < s.length) {
            if (ti < tokens.size && (tokens[ti] == '?' || tokens[ti] == s[si])) {
                si++
                ti++
            } else if (ti < tokens.size && tokens[ti] == '*') {
                ti++
                starAt = ti
                starMatch = si
            } else if (starAt >= 0) {
                // Let the last star take one more digit and retry from there.
                starMatch++
                si = starMatch
                ti = starAt
            } else {
                return false
            }
        }
        while (ti < tokens.size && tokens[ti] == '*') ti++
        return ti == tokens.size
    }

    companion object {
        /** Longer patterns are refused (the rule editor, templates and imports cap them at this too). */
        const val MAX_LENGTH = 200

        fun compile(pattern: String): WildcardPattern? {
            val p = pattern.trim()
            if (p.isEmpty() || p.length > MAX_LENGTH) return null
            val out = StringBuilder(p.length)
            var plus = false
            for ((i, c) in p.withIndex()) {
                when {
                    // Consecutive stars mean the same as one.
                    c == '*' -> if (out.isEmpty() || out.last() != '*') out.append('*')
                    c == '?' || c in '0'..'9' -> out.append(c)
                    c == '+' && i == 0 -> plus = true
                    c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                    else -> return null
                }
            }
            return WildcardPattern(plus, out.toString().toCharArray())
        }
    }
}
