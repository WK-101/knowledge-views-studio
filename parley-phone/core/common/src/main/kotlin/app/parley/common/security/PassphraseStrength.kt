package app.parley.common.security

import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/**
 * A small, offline passphrase-strength estimate in the spirit of zxcvbn (no dictionary download): the passphrase is
 * split into the cheapest pieces an attacker would try first (well-known passwords, words and names, anything that
 * reads like a word, keyboard runs, sequences, repeated characters and repeated halves, years, dates, the usual digit
 * and symbol tail) and whatever is left is counted as brute force. The score is from the estimated
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
        val text = passphrase.take(MAX_ANALYSED)
        val lower = text.lowercase(Locale.ROOT)
        val plain = unleet(lower)
        val n = text.length
        // The cheapest way to cover the passphrase with pieces (not the first way found): cost[j] is the best guess
        // count (log10, plus a little per piece) for the first j characters ([Path]).
        val path = Path(n)
        for (i in 0 until n) {
            if (path.cost[i] != Double.MAX_VALUE) for (m in matchesAt(text, lower, plain, i)) path.offer(i, m)
        }
        // An attacker also has to guess how the pieces combine; characters past the analysed part count as brute force.
        val log = path.logs[n] + log10(factorial(path.tokens[n]).toDouble()) + (passphrase.length - n) * log10(BRUTE_CHARSET)
        val score = scoreOf(log)
        return Estimate(score, log, hintOf(passphrase.length, score, worstHint(path.via, n)))
    }

    /** The cheapest cover of the first j characters found so far, for every j. */
    private class Path(n: Int) {
        val cost = DoubleArray(n + 1) { Double.MAX_VALUE }.also { it[0] = 0.0 }
        val logs = DoubleArray(n + 1)
        val tokens = IntArray(n + 1)
        val via = arrayOfNulls<Match>(n + 1)

        fun offer(i: Int, m: Match) {
            val j = i + m.length
            // A run of brute-forced characters is one piece; every recognised pattern is its own.
            val newPiece = !(m.brute && via[i]?.brute == true)
            val c = cost[i] + m.log10 + if (newPiece) PIECE_COST else 0.0
            if (c < cost[j]) {
                cost[j] = c
                logs[j] = logs[i] + m.log10
                tokens[j] = tokens[i] + if (newPiece) 1 else 0
                via[j] = m
            }
        }
    }

    /** The recognised piece that covers the most of the passphrase: what the hint talks about. */
    private fun worstHint(via: Array<Match?>, n: Int): Hint {
        var worst = Hint.NONE
        var worstLength = 0
        var j = n
        while (j > 0) {
            val m = via[j] ?: break
            if (m.hint != Hint.NONE && m.length >= worstLength) {
                worst = m.hint
                worstLength = m.length
            }
            j -= m.length
        }
        return worst
    }

    private fun scoreOf(log: Double): Int = when {
        log < 3 -> 0
        log < 6 -> 1
        log < 8 -> 2
        log < 10 -> 3
        else -> 4
    }

    private fun hintOf(length: Int, score: Int, worst: Hint): Hint = when {
        length < MIN_LENGTH -> Hint.TOO_SHORT
        worst != Hint.NONE && score < 4 -> worst
        score < MIN_BACKUP_SCORE -> Hint.ADD_WORDS
        else -> Hint.NONE
    }

    private class Match(val length: Int, val log10: Double, val hint: Hint, val brute: Boolean = false)

    /**
     * Every piece that could start at [i]. [lower] is the passphrase in lowercase, [plain] the same with l33t undone
     * (for words only).
     */
    private fun matchesAt(original: String, lower: String, plain: String, i: Int): List<Match> {
        val out = ArrayList<Match>()
        wordMatches(original, lower, plain, i, out)
        // The same piece again right after itself ("summer2024summer2024"): nearly free once the first is guessed.
        for (len in MIN_REPEAT..minOf(i, lower.length - i)) {
            if (lower.regionMatches(i - len, lower, i, len)) out += Match(len, log10(len.toDouble()) + REPEAT_COST, Hint.REPEAT)
        }
        repeatLength(lower, i).takeIf { it >= 3 }?.let { out += Match(it, log10(charsetOf(lower[i]) * it.toDouble()), Hint.REPEAT) }
        sequenceLength(lower, i).takeIf { it >= 3 }?.let { out += Match(it, log10(26.0 * it), Hint.SEQUENCE) }
        keyboardLength(lower, i).takeIf { it >= 3 }?.let { out += Match(it, log10(40.0 * it), Hint.KEYBOARD) }
        dateLength(lower, i).takeIf { it >= 4 }?.let { out += Match(it, if (it == 4) log10(120.0) else log10(365.0 * 120), Hint.DATE) }
        // The usual tail: a digit or two and a symbol at the very end ("1!", "7", "!!").
        tailLength(lower, i)?.let { out += Match(it, TAIL_BASE + TAIL_PER_CHAR * it, Hint.NONE) }
        // Otherwise one character by brute force.
        out += Match(1, log10(charsetOf(original[i]).toDouble()), Hint.NONE, brute = true)
        return out
    }

    /**
     * Words starting at [i]: well-known passwords, words, names and places, and any other run of letters that reads
     * like a word or a name (attackers' dictionaries are far bigger than the list, so it costs about a dictionary
     * look-up, not a random string of that length). A little extra for capitals and l33t.
     */
    private fun wordMatches(original: String, lower: String, plain: String, i: Int, out: MutableList<Match>) {
        for ((w, rank) in COMMON_RANKED) {
            if (w.length >= 3 && plain.startsWith(w, i)) {
                out += Match(w.length, log10(rank.toDouble() + 1) + variations(original, lower, plain, i, w.length), Hint.COMMON)
            }
        }
        var j = i
        while (j < plain.length && j - i < MAX_WORD && plain[j] in 'a'..'z') {
            j++
            val len = j - i
            if (len < MIN_WORD || !wordLike(plain, i, j)) continue
            val brute = (i until j).sumOf { log10(charsetOf(original[it]).toDouble()) }
            out += Match(len, minOf(brute, WORD_BASE + WORD_PER_CHAR * len) + variations(original, lower, plain, i, len), Hint.COMMON)
        }
    }

    /** Extra guesses for how a word is written: a capital first letter or all capitals (×2), mixed case, l33t. */
    private fun variations(original: String, lower: String, plain: String, i: Int, len: Int): Double {
        val seg = original.substring(i, i + len)
        val lowSeg = lower.substring(i, i + len)
        val caps = when {
            seg == lowSeg -> 0.0
            seg == seg.uppercase(Locale.ROOT) || seg.substring(1) == lowSeg.substring(1) -> CAPS_SIMPLE
            else -> CAPS_MIXED
        }
        return caps + if (lowSeg != plain.substring(i, i + len)) LEET_COST else 0.0
    }

    /** Letters that could be a word or name: some vowels, no long consonant clusters, no long runs of one letter. */
    private fun wordLike(s: String, from: Int, to: Int): Boolean {
        var vowels = 0
        var cluster = 0
        for (k in from until to) {
            val c = s[k]
            if (c in VOWELS) {
                vowels++
                cluster = 0
            } else if (++cluster > MAX_CLUSTER) {
                return false
            }
        }
        return vowels * 5 >= (to - from)
    }

    /** From [i] to the end: 1–2 digits and at most one symbol, or 1–2 symbols. */
    private fun tailLength(s: String, i: Int): Int? {
        val rest = s.substring(i)
        return rest.length.takeIf { rest.isNotEmpty() && TAIL.matches(rest) }
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

    private const val MAX_ANALYSED = 128
    private const val BRUTE_CHARSET = 26.0
    private const val PIECE_COST = 0.3
    private const val MIN_WORD = 4
    private const val MAX_WORD = 20
    private const val WORD_BASE = 2.5
    private const val WORD_PER_CHAR = 0.3
    private const val MAX_CLUSTER = 4
    private const val MIN_REPEAT = 3
    private const val REPEAT_COST = 0.3
    private const val CAPS_SIMPLE = 0.3
    private const val CAPS_MIXED = 1.0
    private const val LEET_COST = 0.5
    private const val TAIL_BASE = 0.5
    private const val TAIL_PER_CHAR = 0.6
    private const val VOWELS = "aeiouy"
    private val TAIL = Regex("[0-9]{1,2}[^a-z0-9]?|[^a-z0-9]{1,2}")

    private val LEET = mapOf('4' to 'a', '@' to 'a', '3' to 'e', '1' to 'i', '!' to 'i', '0' to 'o', '5' to 's', '$' to 's', '7' to 't', '+' to 't')

    private val KEYBOARD_ROWS = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm", "qwertzuiop", "yxcvbnm", "azertyuiop", "qsdfghjklm", "wxcvbn")

    /**
     * The passwords, words, first names and places people pick most, best first (from public breach statistics);
     * rank × a little is the guess count. Short and offline on purpose: it catches the common choices, and any other
     * word-like run of letters is costed as a dictionary word ([wordLike]), never as random characters.
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
        jessica daniel thomas robert william david richard joseph james john mary patricia linda barbara elizabeth susan
        sarah karen nancy lisa betty margaret sandra emily donna michelle carol amanda melissa deborah stephanie rebecca
        laura sharon cynthia kathleen amy angela anna brenda pamela nicole emma samantha katherine christine rachel
        catherine heather maria diana julia natalie sophia olivia isabella mia charlotte amelia harper evelyn abigail
        ella avery scarlett grace chloe victoria riley aria lily aubrey zoey hannah nora layla zoe lucy alex andrew
        joshua christopher matthew anthony mark steven paul kevin brian george edward ronald timothy jason jeffrey ryan
        jacob gary nicholas eric jonathan stephen larry justin scott brandon benjamin samuel gregory frank alexander
        patrick raymond jack dennis jerry tyler aaron jose adam henry nathan douglas zachary peter kyle walter ethan
        jeremy harold keith christian roger noah gerald carl terry sean austin arthur lawrence jesse dylan bryan joe
        jordan billy bruce albert willie gabriel logan alan juan wayne roy ralph randy eugene vincent russell elijah
        louis bobby philip johnny oliver harry charles lucas mason liam muhammad ahmed ali fatima aisha omar hassan
        priya rahul amit pooja raj anjali sanjay neha carlos luis miguel pedro jorge ana sofia lucia pablo diego juan
        pierre marie jean sophie hans anna klaus stefan mohammed yusuf ayesha zainab
        liverpool chelsea arsenal manchester united barcelona juventus madrid milan bayern celtic rangers tottenham
        everton newcastle leeds lakers yankees cowboys patriots steelers packers eagles giants dodgers warriors bulls
        london paris berlin rome tokyo sydney toronto chicago boston dallas houston miami seattle denver phoenix vegas
        newyork california texas florida hawaii dublin glasgow edinburgh birmingham bristol cardiff belfast mumbai delhi
        karachi lahore dubai cairo istanbul moscow lisbon amsterdam vienna zurich munich hamburg barcelona valencia
        africa europe asia australia ireland scotland wales italy china japan russia turkey egypt nigeria kenya
        butterfly dolphin tiger lion eagle falcon phoenix wolf bear panther dragonfly unicorn rainbow sunflower rose
        daisy lily jasmine cherry apple peach mango strawberry blueberry coffee pepper ginger cinnamon vanilla
        chocolate candy sugar muffin cupcake pumpkin kitten puppy doggie kitty bunny teddy buddy rocky lucky max
        diamond crystal emerald ruby sapphire pearl gold silver platinum star moon sun sky ocean river mountain forest
        thunder lightning storm snow fire water earth wind shadow ghost angel devil demon heaven hell magic wizard
        warrior knight king queen prince princess castle pirate captain soldier ninja samurai hunter sniper rocket
        football soccer basketball baseball tennis golf hockey cricket rugby boxing racing chess music guitar piano
        dance movie cinema matrix batman spiderman superman ironman marvel pokemon mario zelda minecraft fortnite
        jesus christ god lord faith hope peace freedom liberty justice victory glory trinity blessed amen
        welcome hello goodbye thanks please sorry forever always never maybe nothing something everything
        beautiful pretty handsome awesome amazing perfect super cool crazy happy funny lovely sweetheart darling
        mylove iloveu loveyou babygirl babyboy princesa bonjour hola ciao danke
    """.trimIndent().split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()

    private val COMMON_RANKED: List<Pair<String, Int>> = COMMON.mapIndexed { i, w -> unleet(w) to (i + 1) * 10 }
        .distinctBy { it.first }.sortedByDescending { it.first.length }
}
