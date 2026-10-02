package app.parley.common.people

import java.util.Locale

/**
 * A birthday written out in a pasted text ("12 March 1990", "March 12, 1990", "1990-03-12", "12.03.1990", "12/03",
 * "12. März"), as Android stores it: `yyyy-MM-dd`, or `--MM-dd` without a year. Only used where the text says it is a
 * birthday, so a date elsewhere (a meeting, an office's opening) is never taken for one.
 */
internal object PasteDates {
    private val MONTHS: Map<String, Int> = buildMap {
        val names = listOf(
            listOf("january", "jan", "januar", "janvier", "janv", "enero", "ene", "gennaio", "gen", "januari", "janeiro", "jänner", "janner"),
            listOf("february", "feb", "februar", "fevrier", "fevr", "fev", "febrero", "febbraio", "februari", "fevereiro"),
            listOf("march", "mar", "marz", "maerz", "mars", "marzo", "maart", "marco", "mrt"),
            listOf("april", "apr", "avril", "avr", "abril", "abr", "aprile"),
            listOf("may", "mai", "mayo", "maggio", "mei", "maio", "mag"),
            listOf("june", "jun", "juni", "juin", "junio", "giugno", "junho", "giu"),
            listOf("july", "jul", "juli", "juillet", "juil", "julio", "luglio", "julho", "lug"),
            listOf("august", "aug", "aout", "agosto", "ago", "augustus"),
            listOf("september", "sep", "sept", "septembre", "septiembre", "settembre", "set", "setembro"),
            listOf("october", "oct", "oktober", "okt", "octobre", "octubre", "ottobre", "ott", "outubro", "out"),
            listOf("november", "nov", "novembre", "noviembre", "novembro"),
            listOf("december", "dec", "dezember", "dez", "decembre", "diciembre", "dic", "dicembre", "dezembro"),
        )
        names.forEachIndexed { i, l -> l.forEach { put(it, i + 1) } }
    }

    private val ISO = Regex("""^(\d{4})[-./](\d{1,2})[-./](\d{1,2})$""")
    private val NUMERIC = Regex("""^(\d{1,2})[-./](\d{1,2})(?:[-./](\d{2}|\d{4}))?\.?$""")
    private val DAY_MONTH = Regex("""^(\d{1,2})(?:st|nd|rd|th|er|º|°)?\.?\s*(?:de\s+)?(\p{L}+)\.?(?:\s*(?:de\s+)?,?\s*(\d{4}))?$""")
    private val MONTH_DAY = Regex("""^(\p{L}+)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?(?:\s+(\d{4}))?$""")

    /** Countries that write the month first ("03/12/1990" is March 12). */
    private val MONTH_FIRST = setOf("US", "PH", "FM", "MH", "PW", "GU", "AS", "MP", "PR", "VI", "UM", "BZ")

    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100
    private const val CENTURY_CUT = 30
    private val DAYS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun parse(text: String, region: String?): String? {
        val t = PasteWords.fold(text.trim().trimEnd('.', ',', ';')).replace(Regex("\\s+"), " ")
        ISO.matchEntire(t)?.let { m -> return make(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        NUMERIC.matchEntire(t)?.let { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val year = year(m.groupValues[3])
            val monthFirst = region?.uppercase(Locale.ROOT) in MONTH_FIRST
            // A part above 12 can only be the day, whichever way the country writes it.
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                monthFirst -> b to a
                else -> a to b
            }
            return make(year, month, day)
        }
        DAY_MONTH.matchEntire(t)?.let { m ->
            val month = MONTHS[m.groupValues[2]] ?: return null
            return make(year(m.groupValues[3]), month, m.groupValues[1].toInt())
        }
        MONTH_DAY.matchEntire(t)?.let { m ->
            val month = MONTHS[m.groupValues[1]] ?: return null
            return make(year(m.groupValues[3]), month, m.groupValues[2].toInt())
        }
        return null
    }

    private fun year(s: String): Int? {
        if (s.isEmpty()) return null
        val y = s.toInt()
        return if (s.length == 2) (if (y > CENTURY_CUT) 1900 + y else 2000 + y) else y
    }

    private fun make(year: Int?, month: Int, day: Int): String? {
        if (month !in 1..12 || day < 1 || day > DAYS[month - 1]) return null
        if (year != null) {
            if (year !in MIN_YEAR..MAX_YEAR) return null
            val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
            if (month == 2 && day == 29 && !leap) return null
            return String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
        }
        return String.format(Locale.ROOT, "--%02d-%02d", month, day)
    }
}
