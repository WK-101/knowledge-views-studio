package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.CountdownEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

/**
 * R46 — the "beyond countdowns" features that a single-purpose reminder app can't assemble, kept entirely
 * offline, permission-free and inside the lossless backup (moments live as JSON on the countdown row).
 *
 *  • Moments + keep-in-touch cadence (relationship-upkeep loop / cadence guardian)
 *  • KnowThem — a bundled, no-LLM question deck attached to a person
 *  • Almanac — a bundled today-in-history feed + gentle reflective facts, no server
 *  • Hijri — alternate-calendar recurrence via the platform's Umm-al-Qura calendar (API 26+), no tables
 */

/** One logged moment (or answered question) against an occasion. `d` = epoch-day, `n` = the note. */
@Serializable
data class Moment(val d: Long, val n: String)

object Moments {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(c: CountdownEntity): List<Moment> {
        val raw = c.momentsJson
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Moment>>(raw) }.getOrDefault(emptyList())
            .sortedByDescending { it.d }
    }

    fun encode(list: List<Moment>): String = runCatching { json.encodeToString(list) }.getOrDefault("")

    /** Add a moment (today by default) and return the updated JSON to persist. */
    fun add(c: CountdownEntity, note: String, day: LocalDate = LocalDate.now()): String {
        val list = parse(c) + Moment(day.toEpochDay(), note.trim())
        return encode(list.sortedByDescending { it.d })
    }

    fun remove(c: CountdownEntity, moment: Moment): String =
        encode(parse(c).filterNot { it.d == moment.d && it.n == moment.n })

    fun lastMomentDay(c: CountdownEntity): LocalDate? =
        parse(c).maxByOrNull { it.d }?.let { LocalDate.ofEpochDay(it.d) }

    // #27 gift ledger — gifts are moments prefixed with a marker, so they ride the same JSON store.
    const val GIFT_PREFIX = "🎁 "
    fun addGift(c: CountdownEntity, gift: String, day: LocalDate = LocalDate.now()): String =
        add(c, GIFT_PREFIX + gift.trim(), day)
    fun gifts(c: CountdownEntity): List<Pair<LocalDate, String>> =
        parse(c).filter { it.n.startsWith(GIFT_PREFIX) }
            .map { LocalDate.ofEpochDay(it.d) to it.n.removePrefix(GIFT_PREFIX) }
    fun lastGift(c: CountdownEntity): Pair<LocalDate, String>? = gifts(c).maxByOrNull { it.first.toEpochDay() }

    /** Days since you last logged a moment with this person (null if none yet). */
    fun daysSinceLast(c: CountdownEntity, today: LocalDate = LocalDate.now()): Long? =
        lastMomentDay(c)?.let { ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) }

    /** True when a keep-in-touch cadence is set and it has lapsed (or you've never logged a moment). */
    fun cadenceOverdue(c: CountdownEntity, today: LocalDate = LocalDate.now()): Boolean {
        if (c.keepInTouchDays <= 0) return false
        val since = daysSinceLast(c, today) ?: return true
        return since >= c.keepInTouchDays
    }

    /** A short human status for the keep-in-touch cadence, or null when no cadence is set. */
    fun cadenceLine(c: CountdownEntity, today: LocalDate = LocalDate.now()): String? {
        if (c.keepInTouchDays <= 0) return null
        val since = daysSinceLast(c, today)
        val who = c.personName.ifBlank { c.title }
        return when {
            since == null -> "No moments yet — reach out to $who"
            since == 0L -> "Connected today ✓"
            cadenceOverdue(c, today) -> "It's been $since days — time to reach out to $who"
            else -> "$since days since you connected · every ${c.keepInTouchDays}"
        }
    }
}

/** #3 — alternate-calendar recurrence via the platform Umm-al-Qura Hijri calendar (correct, offline). */
object HijriRecur {
    fun isHijri(c: CountdownEntity) = c.recurCalendar.equals("hijri", ignoreCase = true)

    /** The origin date rendered as "12 Ramadan 1400" style, or null if unavailable. */
    fun originLabel(origin: LocalDate): String? = runCatching {
        val h = HijrahDate.from(origin)
        val month = h.get(ChronoField.MONTH_OF_YEAR)
        val day = h.get(ChronoField.DAY_OF_MONTH)
        val year = h.get(ChronoField.YEAR_OF_ERA)
        "${day} ${HIJRI_MONTHS[(month - 1).coerceIn(0, 11)]} $year AH"
    }.getOrNull()

    /** Next Gregorian date on which this occasion's Hijri month+day recurs, at or after [today]. */
    fun nextOccurrence(origin: LocalDate, today: LocalDate = LocalDate.now()): LocalDate? = runCatching {
        val h = HijrahDate.from(origin)
        val month = h.get(ChronoField.MONTH_OF_YEAR)
        val day = h.get(ChronoField.DAY_OF_MONTH)
        val todayH = HijrahDate.from(today)
        var hy = todayH.get(ChronoField.YEAR_OF_ERA)
        // Try this Hijri year and the next couple (Hijri months are ~11 days earlier each Gregorian year).
        for (add in 0..2) {
            val g = gregorianForHijri(hy + add, month, day)
            if (g != null && !g.isBefore(today)) return@runCatching g
        }
        gregorianForHijri(hy + 3, month, day)
    }.getOrNull()

    private fun gregorianForHijri(hy: Int, hm: Int, hd: Int): LocalDate? = runCatching {
        // Some Hijri years have a 29-day final months; clamp the day into range for that month/year.
        val firstOfMonth = HijrahDate.of(hy, hm, 1)
        val len = firstOfMonth.lengthOfMonth()
        val d = hd.coerceIn(1, len)
        LocalDate.from(HijrahDate.of(hy, hm, d))
    }.getOrNull()

    val HIJRI_MONTHS = listOf(
        "Muharram", "Safar", "Rabiʿ I", "Rabiʿ II", "Jumada I", "Jumada II",
        "Rajab", "Shaʿban", "Ramadan", "Shawwal", "Dhuʾl-Qaʿda", "Dhuʾl-Hijja"
    )
}

/** #21 — a pure-offline date-intelligence lab: moon phase, planetary ages, weekday, "date + N days",
 *  the next solstice/equinox. No location, no network — just arithmetic. */
object DateLab {
    private const val SYNODIC = 29.530588853
    private const val REF_NEW_MOON_JD = 2451550.1   // 2000-01-06 18:14 UTC, a known new moon

    private fun toJulian(date: LocalDate): Double {
        var y = date.year; var m = date.monthValue
        if (m <= 2) { y -= 1; m += 12 }
        val a = Math.floor(y / 100.0)
        val b = 2 - a + Math.floor(a / 4.0)
        return Math.floor(365.25 * (y + 4716)) + Math.floor(30.6001 * (m + 1)) + date.dayOfMonth + b - 1524.5
    }

    private val PHASES = listOf(
        "🌑 New moon", "🌒 Waxing crescent", "🌓 First quarter", "🌔 Waxing gibbous",
        "🌕 Full moon", "🌖 Waning gibbous", "🌗 Last quarter", "🌘 Waning crescent",
    )

    fun moonPhase(date: LocalDate = LocalDate.now()): String {
        val age = ((toJulian(date) - REF_NEW_MOON_JD) % SYNODIC + SYNODIC) % SYNODIC
        val idx = (Math.round(age / SYNODIC * 8.0) % 8).toInt()
        return PHASES[idx]
    }

    fun planetAge(origin: LocalDate, today: LocalDate, orbitDays: Double): Double =
        ChronoUnit.DAYS.between(origin, today).coerceAtLeast(0) / orbitDays

    fun marsAge(origin: LocalDate, today: LocalDate = LocalDate.now()) = planetAge(origin, today, 686.98)
    fun jupiterAge(origin: LocalDate, today: LocalDate = LocalDate.now()) = planetAge(origin, today, 4332.59)

    fun datePlus(date: LocalDate, days: Long): LocalDate = date.plusDays(days)

    /** Next solstice/equinox on/after [from], using standard nominal dates — offline, no ephemeris. */
    fun nextSeasonMarker(from: LocalDate = LocalDate.now()): Pair<String, LocalDate> {
        val markers = listOf(
            "Spring equinox" to (3 to 20), "Summer solstice" to (6 to 21),
            "Autumn equinox" to (9 to 22), "Winter solstice" to (12 to 21),
        )
        for (yr in listOf(from.year, from.year + 1)) {
            for ((label, md) in markers) {
                val d = LocalDate.of(yr, md.first, md.second)
                if (!d.isBefore(from)) return label to d
            }
        }
        return markers.first().let { it.first to LocalDate.of(from.year + 1, it.second.first, it.second.second) }
    }
}

/** #20 — a bundled, no-LLM "how well do you know them" deck. Answers save as moments (private notes). */
object KnowThem {
    val QUESTIONS = listOf(
        "What are they most looking forward to right now?",
        "What's a small thing that always makes them laugh?",
        "Who is their oldest friend, and how did they meet?",
        "What did they want to be when they were a child?",
        "What's a meal that means home to them?",
        "What's a worry they carry that they rarely mention?",
        "What song can they not help singing along to?",
        "What's the kindest thing anyone has done for them?",
        "Where would they go if they had a free week and any budget?",
        "What are they secretly proud of?",
        "What's their idea of a perfect ordinary day?",
        "What's a book, film or show that changed how they think?",
        "What did their childhood bedroom look like?",
        "What's a skill they wish they had?",
        "Who do they turn to when things go wrong?",
        "What's a tradition they'd never want to lose?",
        "What's something they've changed their mind about?",
        "What does a good week look like for them lately?",
        "What's a compliment they'd love to hear but never ask for?",
        "What's the story behind their name?",
        "What are they reading, watching or listening to this month?",
        "What's a place from their past they'd love to revisit?",
        "What's a fear they've overcome?",
        "What would their younger self be amazed they'd done?",
    )

    /** A stable question for a given person on a given day (rotates daily, deterministic — no RNG state). */
    fun questionFor(c: CountdownEntity, today: LocalDate = LocalDate.now()): String {
        val seed = (c.id.hashCode().toLong() and 0xffff) + today.toEpochDay()
        val idx = ((seed % QUESTIONS.size) + QUESTIONS.size) % QUESTIONS.size
        return QUESTIONS[idx.toInt()]
    }
}

/** #24 / #23 — a bundled offline almanac: this-day-in-history + gentle reflective micro-facts. No network. */
object Almanac {
    /** A notable historical fact for the given day, keyed by MM-DD; null on days with no curated entry. */
    fun onThisDay(date: LocalDate = LocalDate.now()): String? =
        HISTORY["%02d-%02d".format(date.monthValue, date.dayOfMonth)]

    /** A gentle, finite-time reflection — rotates daily so the nudge doesn't repeat. */
    fun reflection(date: LocalDate = LocalDate.now()): String =
        REFLECTIONS[(date.toEpochDay().mod(REFLECTIONS.size.toLong())).toInt()]

    // A curated, brand-neutral set — enough to feel alive across the year without shipping a database.
    private val HISTORY: Map<String, String> = mapOf(
        "01-01" to "1959 — the metric-based International System of Units groundwork year begins; many nations adopt New Year reforms.",
        "01-04" to "1643 — Isaac Newton was born (Old Style); he'd reshape how we describe motion and gravity.",
        "01-27" to "1756 — Wolfgang Amadeus Mozart was born in Salzburg.",
        "02-11" to "1847 — Thomas Edison was born; he'd hold over a thousand patents.",
        "02-12" to "1809 — Charles Darwin and Abraham Lincoln were both born on this day.",
        "02-15" to "1564 — Galileo Galilei was born in Pisa.",
        "02-27" to "1932 — Elizabeth Taylor was born; a defining star of classic cinema.",
        "03-14" to "1879 — Albert Einstein was born; also, decades later, celebrated as Pi Day.",
        "03-17" to "461 — the traditional date of Saint Patrick's death, now a global celebration.",
        "04-12" to "1961 — Yuri Gagarin became the first human in space.",
        "04-15" to "1452 — Leonardo da Vinci was born near Vinci, Italy.",
        "04-22" to "1970 — the first Earth Day was observed.",
        "04-23" to "1564 — William Shakespeare was baptised (his traditional birthday).",
        "05-05" to "1818 — Karl Marx was born in Trier.",
        "05-20" to "1506 — Christopher Columbus died in Valladolid.",
        "05-25" to "1977 — 'Star Wars' first opened in cinemas.",
        "06-05" to "1723 — Adam Smith, author of 'The Wealth of Nations', was baptised.",
        "06-18" to "1815 — the Battle of Waterloo was fought.",
        "06-21" to "the June solstice — the longest or shortest day, depending on your hemisphere.",
        "07-04" to "1776 — the US Declaration of Independence was adopted.",
        "07-16" to "1969 — Apollo 11 launched toward the first Moon landing.",
        "07-20" to "1969 — Apollo 11 landed and humans first walked on the Moon.",
        "08-06" to "1991 — the first public website went live at CERN.",
        "08-15" to "1769 — Napoleon Bonaparte was born in Corsica.",
        "08-28" to "1963 — Martin Luther King Jr. delivered his 'I Have a Dream' speech.",
        "09-02" to "1969 — the first message precursor work on ARPANET began this autumn; the internet's seed.",
        "09-21" to "1937 — 'The Hobbit' by J.R.R. Tolkien was first published.",
        "10-01" to "1908 — the Ford Model T was introduced.",
        "10-14" to "1947 — Chuck Yeager first broke the sound barrier in level flight.",
        "10-31" to "1517 — Martin Luther is said to have posted his Ninety-five Theses.",
        "11-09" to "1989 — the Berlin Wall fell.",
        "11-11" to "1918 — the armistice ending the First World War took effect.",
        "12-10" to "1901 — the first Nobel Prizes were awarded.",
        "12-17" to "1903 — the Wright brothers made the first powered flight.",
        "12-21" to "the December solstice — the turning point of the year's light.",
        "12-25" to "1642 — Isaac Newton was born (Old Style calendar).",
    )

    private val REFLECTIONS: List<String> = listOf(
        "The average life is about 4,000 weeks. This is one of them — spend it on what you'd remember.",
        "You will meet very few people as often as you assume. Reach out to one of them today.",
        "A year has 52 weekends. How many are already promised to something that matters?",
        "The people you love are getting a day older today too. So is the time you have with them.",
        "Most of your ordinary days you won't remember — but a few you'll never forget. Make one.",
        "If you saw your parents twice a year, and they're 65, you may have around 30 visits left.",
        "Time you spend is gone; time you invest compounds. Today can be either.",
        "You've already lived thousands of days. The next one is not guaranteed — but it is yours.",
        "Someone would love to hear from you today and isn't expecting to. That's the whole gift.",
        "A birthday is a lap counter, not a verdict. What lap are you enjoying?",
    )
}
