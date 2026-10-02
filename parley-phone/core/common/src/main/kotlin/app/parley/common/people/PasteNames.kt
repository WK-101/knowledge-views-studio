package app.parley.common.people

import app.parley.common.people.PasteParser.Name
import java.util.Locale

/**
 * Whether a line reads as a person's name, and its parts: prefixes ("Dr.", "Prof.", "Frau"), suffixes ("Jr.", "PhD"),
 * particles that belong to the family name ("van", "de", "bin"), "DOE, Jane", and names written in capitals
 * ("JANE DOE" becomes "Jane Doe"). Scripts without capitals (Arabic, Hebrew, CJK…) are accepted as they are.
 */
internal object PasteNames {
    private const val MAX_LENGTH = 60
    private const val MAX_WORDS = 4

    private val PREFIXES = setOf(
        "dr", "prof", "professor", "mr", "mrs", "ms", "mx", "miss", "sir", "dame", "herr", "frau", "mme", "mlle", "madame", "monsieur", "sr",
        "sra", "srta", "don", "dona", "dott", "dottssa", "dottoressa", "ing", "mag", "rev", "hon", "capt", "dipl", "dipl-ing", "eng", "engr",
        "lord", "lady", "dra", "drs", "ir", "mevr", "dhr",
    )
    private val SUFFIXES = setOf(
        "jr", "sr", "ii", "iii", "iv", "phd", "md", "mba", "esq", "cpa", "cfa", "dds", "rn", "msc", "bsc", "ma", "ba", "meng", "beng", "llm",
        "jd", "frcs", "obe", "mbe", "cbe", "pe", "pmp", "mph", "dphil", "facs", "cissp", "llb", "ca", "acca",
    )
    private val PARTICLES = setOf(
        "van", "von", "de", "da", "di", "del", "della", "der", "den", "la", "le", "du", "dos", "das", "ten", "ter", "zu", "bin", "binti", "ibn",
        "bint", "al", "el", "y", "e", "st", "vande", "vanden", "van der", "بن", "بنت", "ابن", "آل",
    )

    /** First halves of Arabic compound names ("Abdul Rahman", "عبد الله"): kept with the word after them. */
    private val JOINED = setOf("abd", "abdul", "abdel", "abu", "عبد", "أبو", "ابو")

    /** Words that make a line something other than a name ("Sales Team", "Customer Service"). */
    private val NOT_NAME = setOf(
        "the", "and", "of", "for", "our", "your", "with", "team", "from", "at", "to", "by", "in", "on", "contact", "regards", "thanks", "hello",
        "dear", "welcome", "call", "email", "phone", "mobile", "office", "sales", "support", "info", "customer", "service", "services",
        "department", "help", "desk", "reception", "open", "closed", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
        "sunday", "mon", "tue", "wed", "thu", "fri", "sat", "sun", "hours", "map", "maps", "directions", "website", "web", "follow", "us",
        "me", "my", "is", "are", "am", "this", "that", "please", "visit", "street", "road", "avenue", "suite", "floor", "building", "box",
        "floor", "new", "free", "best", "kind", "sent", "via", "re", "fwd", "fw", "subject", "date", "attachment", "hall", "booth", "stand",
        "badge", "visitor", "attendee", "delegate", "exhibitor", "staff", "press", "vip", "guest", "conference", "summit", "expo", "event",
    )

    /** [text] as a name, or null. [loose]: the text said it is a name ("Name: …"), so only the form is checked. */
    @Suppress("CyclomaticComplexMethod")
    fun parse(text: String, loose: Boolean = false): Name? {
        var t = text.trim().trim(',', ';', ':', '|', '-').trim()
        if (t.isEmpty() || t.length > MAX_LENGTH) return null
        if (t.any { it.isDigit() || it in "@/:&+#()[]{}<>=_*\"!?" }) return null
        var suffix = ""
        var familyFirst: Pair<String, String>? = null
        val commas = t.split(',').map { it.trim() }
        when {
            commas.size == 2 -> {
                val (left, right) = commas
                val rightWords = right.split(' ').filter { it.isNotEmpty() }
                when {
                    rightWords.isNotEmpty() && rightWords.all { norm(it) in SUFFIXES } -> {
                        suffix = right
                        t = left
                    }
                    // "DOE, Jane": family name in capitals first, as on badges and lists.
                    (loose || isCaps(left)) && !left.contains(' ') && rightWords.size in 1..2 -> familyFirst = left to right
                    else -> return null
                }
            }
            commas.size > 2 -> return null
        }
        if (familyFirst != null) {
            val (family, given) = familyFirst
            if (!token(family) || !given.split(' ').all(::token) || PasteWords.isCountry(given)) return null
            return Name(given = cased(given), family = cased(family))
        }
        val words = t.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val prefix = ArrayList<String>()
        while (words.size > 1 && norm(words[0]) in PREFIXES) prefix += words.removeAt(0)
        val suffixes = ArrayList<String>()
        while (words.size > 1 && norm(words.last()) in SUFFIXES && words.last() != words.last().lowercase(Locale.ROOT)) {
            suffixes.add(0, words.removeAt(words.lastIndex))
        }
        if (suffixes.isNotEmpty()) suffix = (suffixes + listOfNotNull(suffix.ifEmpty { null })).joinToString(" ")
        if (words.isEmpty() || !words.all(::token)) return null
        val folded = words.map { PasteWords.fold(it).trim('.') }
        if (folded.any { it in NOT_NAME && it !in PARTICLES }) return null
        if (words.count { PasteWords.fold(it) !in PARTICLES } > MAX_WORDS) return null
        if (PasteWords.isCountry(t)) return null
        if (!loose && words.size == 1 && words[0].length < 2) return null
        // A lowercase word that isn't a particle ("Jane doe") is a sentence, not a name; caseless scripts have none.
        if (words.drop(1).any { w -> w.first().isLowerCase() && PasteWords.fold(w) !in PARTICLES }) return null
        return split(words.map(::cased), prefix.joinToString(" "), suffix)
    }

    private fun split(words: List<String>, prefix: String, suffix: String): Name {
        // "Abdul Rahman Khan": the joined pair is one given name.
        val w = ArrayList<String>()
        for (x in words) {
            val prev = w.lastOrNull()
            if (prev != null && PasteWords.fold(prev) in JOINED && w.size == 1) w[w.lastIndex] = "$prev $x" else w += x
        }
        if (w.size == 1) return Name(prefix = prefix, given = w[0], suffix = suffix)
        val particle = (1 until w.size - 1).firstOrNull { PasteWords.fold(w[it]) in PARTICLES }
        val familyStart = particle ?: (w.size - 1)
        return Name(
            prefix = prefix,
            given = w[0],
            middle = w.subList(1, familyStart).joinToString(" "),
            family = w.subList(familyStart, w.size).joinToString(" "),
            suffix = suffix,
        )
    }

    private fun norm(w: String) = PasteWords.fold(w).replace(".", "").replace(",", "")

    /** A name word: letters (with - ' . inside), starting with a capital, a particle, or in a script without case. */
    private fun token(w: String): Boolean {
        val core = w.trim('.', ',')
        if (core.isEmpty() || !core.any { it.isLetter() }) return false
        if (!core.all { it.isLetter() || it in "-'’." || it.isMark() }) return false
        // A dot only after an initial ("J.", "J.R.").
        if (core.contains('.') && core.count { it.isLetter() } > 2) return false
        val first = core.first { it.isLetter() }
        return first.isUpperCase() || !first.isLowerCase() || PasteWords.fold(core) in PARTICLES
    }

    private fun Char.isMark(): Boolean =
        Character.getType(this).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    private fun isCaps(w: String): Boolean = w.any { it.isLetter() } && w.filter { it.isLetter() }.all { it.isUpperCase() }

    /** "JANE" → "Jane", "O'NEILL" → "O'Neill", "ANNE-MARIE" → "Anne-Marie"; mixed case stays as written. */
    fun cased(s: String): String = s.split(' ').joinToString(" ") { w ->
        if (w.length < 2 || !isCaps(w) || w.count { it.isLetter() } <= 1) {
            w
        } else {
            val sb = StringBuilder()
            var start = true
            for (c in w) {
                sb.append(if (start) c.uppercaseChar() else c.lowercaseChar())
                start = c in "-'’ "
            }
            sb.toString()
        }
    }

    /** "jane.doe@…", "jane_doe@…" → Jane Doe; null for role mailboxes ("info@") and anything else. */
    fun fromEmail(email: String): Name? {
        val local = email.substringBefore('@').lowercase(Locale.ROOT)
        val parts = local.split('.', '_')
        if (parts.size != 2 || parts.any { it.length < 2 || !it.all { c -> c in 'a'..'z' } }) return null
        if (parts.any { it in PasteWords.ROLE_MAILBOXES }) return null
        return Name(given = parts[0].replaceFirstChar { it.titlecase(Locale.ROOT) }, family = parts[1].replaceFirstChar { it.titlecase(Locale.ROOT) })
    }
}
