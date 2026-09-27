package app.parley.common.security

import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * A small, offline passphrase-strength estimate in the spirit of zxcvbn (no dictionary download): the passphrase is
 * split into the cheapest pieces an attacker would try first (well-known passwords and words, keyboard runs,
 * sequences, repeats, years, dates) and whatever is left is counted as brute force. The score is from the estimated
 * number of guesses: 0 (guessed at once) to 4 (out of reach offline, even against a stolen backup).
 */
object PassphraseStrength {
    /** What weakens a passphrase most, for a hint under the field. */
    enum class Hint { NONE, TOO_SHORT, COMMON, KEYBOARD, SEQUENCE, REPEAT, DATE, ADD_WORDS }

    data class Estimate(val score: Int, val guessesLog10: Double, val hint: Hint)

    /** New backups need at least this score (and [MIN_LENGTH]). */
    const val MIN_BACKUP_SCORE = 3
    const val MIN_LENGTH = 10

    /** Whether [passphrase] is strong enough for a new backup key. */
    fun acceptableForBackup(passphrase: String): Boolean =
        passphrase.length >= MIN_LENGTH && estimate(passphrase).score >= MIN_BACKUP_SCORE

    fun estimate(passphrase: String): Estimate {
        if (passphrase.isEmpty()) return Estimate(0, 0.0, Hint.TOO_SHORT)
        val lower = passphrase.lowercase(Locale.ROOT)
        val plain = unleet(lower)
        var log = 0.0
        var worst = Hint.NONE
        var worstShare = 0.0
        var tokens = 0
        var brute = false
        var i = 0
        while (i < plain.length) {
            val m = bestMatch(passphrase, lower, plain, i)
            log += m.log10
            // A run of brute-forced characters is one piece; every recognised pattern is its own.
            if (m.hint != Hint.NONE || !brute) tokens++
            brute = m.hint == Hint.NONE
            // The piece that covers the most of the passphrase for its cost is what the hint talks about.
            if (m.hint != Hint.NONE) {
                val share = m.length.toDouble() / plain.length
                if (share > worstShare) { worstShare = share; worst = m.hint }
            }
            i += m.length
        }
        // An attacker also has to guess how the pieces combine.
        log += log10(factorial(tokens).toDouble())
        val score = when {
            log < 3 -> 0
            log < 6 -> 1
            log < 8 -> 2
            log < 10 -> 3
            else -> 4
        }
        val hint = when {
            passphrase.length < MIN_LENGTH -> Hint.TOO_SHORT
            worst != Hint.NONE && score < 4 -> worst
            score < MIN_BACKUP_SCORE -> Hint.ADD_WORDS
            else -> Hint.NONE
        }
        return Estimate(score, log, hint)
    }

    private class Match(val length: Int, val log10: Double, val hint: Hint)

    /** [s] is the lowercase passphrase, [plain] the same with l33t undone (for the word list only). */
    private fun bestMatch(original: String, s: String, plain: String, i: Int): Match {
        val candidates = ArrayList<Match>()
        // Well-known passwords and words (longest first), with a little extra for capitals and l33t.
        for ((w, rank) in COMMON_RANKED) {
            if (w.length >= 3 && plain.startsWith(w, i)) {
                val variations = if (original.substring(i, i + w.length) != COMMON_BY_PLAIN[w]) 1.0 else 0.0
                candidates += Match(w.length, log10(rank.toDouble() + 1) + variations, Hint.COMMON)
            }
        }
        repeatLength(s, i).takeIf { it >= 3 }?.let { candidates += Match(it, log10(charsetOf(s[i]) * it.toDouble()), Hint.REPEAT) }
        sequenceLength(s, i).takeIf { it >= 3 }?.let { candidates += Match(it, log10(26.0 * it), Hint.SEQUENCE) }
        keyboardLength(s, i).takeIf { it >= 3 }?.let { candidates += Match(it, log10(40.0 * it), Hint.KEYBOARD) }
        dateLength(s, i).takeIf { it >= 4 }?.let { candidates += Match(it, if (it == 4) log10(120.0) else log10(365.0 * 120), Hint.DATE) }
        // Otherwise one character by brute force.
        candidates += Match(1, log10(charsetOf(original[i]).toDouble()), Hint.NONE)
        // Prefer the match that explains the most characters most cheaply (cost per character).
        return candidates.minBy { it.log10 / it.length - it.length * 1e-6 }
    }

    private fun charsetOf(c: Char): Int = when {
        c.isDigit() -> 10
        c in 'a'..'z' || c in 'A'..'Z' -> 26
        c.isLetter() -> 40
        else -> 33
    }

    private fun repeatLength(s: String, i: Int): Int {
        var n = 1
        while (i + n < s.length && s[i + n] == s[i]) n++
        return n
    }

    private fun sequenceLength(s: String, i: Int): Int {
        if (i + 1 >= s.length) return 1
        val step = s[i + 1] - s[i]
        if (step != 1 && step != -1) return 1
        var n = 2
        while (i + n < s.length && s[i + n] - s[i + n - 1] == step) n++
        return n
    }

    private fun keyboardLength(s: String, i: Int): Int {
        var best = 1
        for (row in KEYBOARD_ROWS) {
            for (dir in listOf(row, row.reversed())) {
                var n = 0
                var p = dir.indexOf(s[i])
                if (p < 0) continue
                while (i + n < s.length && p < dir.length && dir[p] == s[i + n]) { n++; p++ }
                best = max(best, n)
            }
        }
        return best
    }

    /** A year (1900–2099) or a date of 6–8 digits such as 311299 or 19991231. */
    private fun dateLength(s: String, i: Int): Int {
        var n = 0
        while (i + n < s.length && s[i + n].isDigit() && n < 8) n++
        if (n >= 6) return n
        if (n >= 4) {
            val y = s.substring(i, i + 4).toInt()
            if (y in 1900..2099) return 4
        }
        return 0
    }

    private fun unleet(s: String): String = buildString(s.length) {
        for (c in s) append(LEET[c] ?: c)
    }

    private fun factorial(n: Int): Long = (1..minOf(n, 20)).fold(1L) { a, b -> a * b }

    private val LEET = mapOf('4' to 'a', '@' to 'a', '3' to 'e', '1' to 'i', '!' to 'i', '0' to 'o', '5' to 's', '$' to 's', '7' to 't', '+' to 't')

    private val KEYBOARD_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwertzuiop", "yxcvbnm", "azertyuiop", "qsdfghjklm", "wxcvbn")

    /**
     * The passwords and words people pick most, best first (from public breach statistics); rank × a little is the
     * guess count. Short and offline on purpose: it catches the common choices, the rest is counted as brute force.
     */
    private val COMMON = """
        password 123456 123456789 qwerty 12345678 111111 1234567890 1234567 password1 12345 iloveyou 000000 123123 abc123
        qwerty123 1q2w3e4r admin letmein welcome monkey dragon football baseball sunshine princess master shadow login
        passw0rd starwars trustno1 superman batman michael jennifer hunter ashley charlie mustang access secret freedom
        whatever qazwsx ninja azerty solo hello hottie flower loveme zaq1zaq1 password123 123qwe killer jordan harley
        ranger buster soccer hockey tigger computer summer internet samsung pokemon cheese chocolate cookie maggie
        banana orange purple yellow silver golden forever angel family friends lovely love baby honey happy sweet
        london paris berlin madrid america england france germany spain india pakistan brazil mexico canada
        parley phone backup contacts android google apple iphone galaxy nokia huawei xiaomi motorola
        january february march april june july august september october november december
        monday tuesday wednesday thursday friday saturday sunday winter spring autumn
        mother father sister brother daughter husband wife mama papa
        test guest user root default changeme temp private secure security
        the and you that was for are with his they this have from one had word but not what all were when your can said
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    private val COMMON_RANKED: List<Pair<String, Int>> = COMMON.mapIndexed { i, w -> unleet(w) to (i + 1) * 10 }
        .distinctBy { it.first }.sortedByDescending { it.first.length }

    /** The word as listed, for telling a plain use from one with capitals or l33t. */
    private val COMMON_BY_PLAIN: Map<String, String> = COMMON.associateBy { unleet(it) }
}
