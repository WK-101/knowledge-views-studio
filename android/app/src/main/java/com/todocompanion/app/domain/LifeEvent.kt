package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.CountdownEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * R43 — the maths and semantics for "Occasions" (life events), the layer that lifts a plain countdown
 * into birthdays / anniversaries / memorials with age, next-occurrence and zodiac. Pure functions, no
 * Android, no I/O — trivially testable and identical to the model the decompiled Birday app uses, with
 * the leap-day and off-by-one age rules made explicit.
 */
object LifeEvent {

    enum class EventType(
        val label: String,
        val emoji: String,
        /** Recurs every year by default (the toggle can still override). */
        val yearlyByDefault: Boolean,
        /** Age / years-since is meaningful (birthday → age; anniversary → Nth year). */
        val countsAge: Boolean,
        /** Celebratory tone (confetti-ish accent). Memorials are deliberately quiet. */
        val celebratory: Boolean,
        /** The word used before the age number, e.g. "turns 30" vs "30 years". */
        val ageVerb: String,
    ) {
        COUNTDOWN("Countdown", "⏳", false, false, true, ""),
        BIRTHDAY("Birthday", "🎂", true, true, true, "turns"),
        ANNIVERSARY("Anniversary", "💍", true, true, true, ""),
        MEMORIAL("Remembrance", "🕯️", true, true, false, ""),
        NAME_DAY("Name day", "😇", true, false, true, ""),
        HOLIDAY("Holiday", "🎉", true, false, true, ""),
        OTHER("Other", "⭐", true, false, true, "");

        companion object {
            fun from(name: String?): EventType = entries.firstOrNull { it.name == name } ?: COUNTDOWN
        }
    }

    fun type(c: CountdownEntity): EventType = EventType.from(c.eventType)

    fun originDate(c: CountdownEntity): LocalDate =
        Instant.ofEpochMilli(c.targetMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * The next occurrence on/after [today]. For a yearly event we take the origin's month/day and roll
     * it to this year (or next year if this year's date has already passed). An event whose date is
     * *today* counts as today's occurrence — it is not pushed a year out (matches Birday, drives the
     * "today"/confetti path). Feb-29 origins fall back to Feb-28 in common years.
     */
    fun nextOccurrence(c: CountdownEntity, today: LocalDate = LocalDate.now()): LocalDate {
        val origin = originDate(c)
        if (!c.yearly) return origin
        val month = origin.monthValue
        val day = origin.dayOfMonth
        fun dateInYear(year: Int): LocalDate {
            // Feb-29 in a non-leap year → Feb-28 (a defined, stable observance day).
            val maxDom = LocalDate.of(year, month, 1).lengthOfMonth()
            return LocalDate.of(year, month, minOf(day, maxDom))
        }
        val thisYear = dateInYear(today.year)
        return if (thisYear.isBefore(today)) dateInYear(today.year + 1) else thisYear
    }

    /** Days from today to the next occurrence. Negative only for a non-yearly date already in the past. */
    fun daysUntil(c: CountdownEntity, today: LocalDate = LocalDate.now()): Long =
        ChronoUnit.DAYS.between(today, nextOccurrence(c, today))

    /** The current completed age / years-since, or null when the year is unknown or it doesn't count. */
    fun currentAge(c: CountdownEntity, today: LocalDate = LocalDate.now()): Int? {
        if (!type(c).countsAge || !c.yearKnown) return null
        val origin = originDate(c)
        val years = ChronoUnit.YEARS.between(origin, today).toInt()
        return years.coerceAtLeast(0)
    }

    /** The age / Nth year reached AT the next occurrence (what they "turn"). Null when not counted. */
    fun ageAtNext(c: CountdownEntity, today: LocalDate = LocalDate.now()): Int? {
        if (!type(c).countsAge || !c.yearKnown) return null
        val origin = originDate(c)
        val next = nextOccurrence(c, today)
        return (next.year - origin.year).coerceAtLeast(0)
    }

    /** A culturally-loaded milestone for the *upcoming* occurrence, or null. Birthdays: round decades +
     *  {1,18,21}; anniversaries: {5,10,25 silver,40 ruby,50 golden,60 diamond}. */
    fun milestone(c: CountdownEntity, today: LocalDate = LocalDate.now()): String? {
        val n = ageAtNext(c, today) ?: return null
        return when (type(c)) {
            EventType.BIRTHDAY -> when (n) {
                1 -> "1st!"; 18 -> "18 🔑"; 21 -> "21 🔑"; 30, 40, 50, 60, 70, 80, 90, 100, 110 -> "$n 🎉"
                else -> null
            }
            EventType.ANNIVERSARY -> when (n) {
                1 -> "1 year"; 5 -> "5 years"; 10 -> "10th"; 25 -> "Silver 🥈"; 40 -> "Ruby ❤️"
                50 -> "Golden 🥇"; 60 -> "Diamond 💎"; 70 -> "Platinum ✨"; else -> null
            }
            else -> null
        }
    }

    /** Western zodiac sign (sun sign) with a glyph. Only meaningful for birthdays. */
    fun zodiac(c: CountdownEntity): String? {
        if (type(c) != EventType.BIRTHDAY) return null
        val d = originDate(c)
        val m = d.monthValue; val day = d.dayOfMonth
        return when {
            (m == 3 && day >= 21) || (m == 4 && day <= 19) -> "♈ Aries"
            (m == 4 && day >= 20) || (m == 5 && day <= 20) -> "♉ Taurus"
            (m == 5 && day >= 21) || (m == 6 && day <= 20) -> "♊ Gemini"
            (m == 6 && day >= 21) || (m == 7 && day <= 22) -> "♋ Cancer"
            (m == 7 && day >= 23) || (m == 8 && day <= 22) -> "♌ Leo"
            (m == 8 && day >= 23) || (m == 9 && day <= 22) -> "♍ Virgo"
            (m == 9 && day >= 23) || (m == 10 && day <= 22) -> "♎ Libra"
            (m == 10 && day >= 23) || (m == 11 && day <= 21) -> "♏ Scorpio"
            (m == 11 && day >= 22) || (m == 12 && day <= 21) -> "♐ Sagittarius"
            (m == 12 && day >= 22) || (m == 1 && day <= 19) -> "♑ Capricorn"
            (m == 1 && day >= 20) || (m == 2 && day <= 18) -> "♒ Aquarius"
            else -> "♓ Pisces"
        }
    }

    /** "today" / "tomorrow" / "in 5 days" / "3 days ago". */
    fun daysLabel(days: Long): String = when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days > 0 -> "in $days days"
        else -> "${-days} days ago"
    }

    /** The compact chip for the card's age/years, e.g. "turns 30", "25 years", or null. */
    fun ageChip(c: CountdownEntity, today: LocalDate = LocalDate.now()): String? {
        val t = type(c)
        val n = ageAtNext(c, today) ?: return null
        return when (t) {
            EventType.BIRTHDAY -> "turns $n"
            EventType.ANNIVERSARY, EventType.MEMORIAL -> "$n years"
            else -> null
        }
    }

    /** "You've known / been together N years" style long-form line for the detail sheet. */
    fun relationLine(c: CountdownEntity, today: LocalDate = LocalDate.now()): String? {
        val age = currentAge(c, today) ?: return null
        return when (type(c)) {
            EventType.BIRTHDAY -> "Born ${originDate(c).year} · $age years old"
            EventType.ANNIVERSARY -> "$age years together"
            EventType.MEMORIAL -> "$age years of remembrance"
            else -> null
        }
    }

    /** Sort key: yearly events by their days-until; one-off future dates by days-until; past one-offs last. */
    fun sortKey(c: CountdownEntity, today: LocalDate = LocalDate.now()): Long {
        val days = daysUntil(c, today)
        return if (days < 0) Long.MAX_VALUE / 2 + (-days) else days
    }

    enum class Bucket(val label: String) { TODAY("Today"), WEEK("This week"), MONTH("This month"), LATER("Later"), PAST("Past") }

    fun bucket(c: CountdownEntity, today: LocalDate = LocalDate.now()): Bucket {
        val days = daysUntil(c, today)
        return when {
            days == 0L -> Bucket.TODAY
            days < 0 -> Bucket.PAST
            days <= 7 -> Bucket.WEEK
            days <= 31 -> Bucket.MONTH
            else -> Bucket.LATER
        }
    }

    // ── R45 · count-up, units, date-facts, milestone radar ───────────────────────────────────────

    /** Whole days elapsed since the origin date (for count-up "time since"); ≥0 for past origins. */
    fun daysSince(c: CountdownEntity, today: LocalDate = LocalDate.now()): Long =
        ChronoUnit.DAYS.between(originDate(c), today).coerceAtLeast(0)

    /** The signed day span the card shows: count-up → days since origin; else → days until next. */
    fun primaryDays(c: CountdownEntity, today: LocalDate = LocalDate.now()): Long =
        if (c.countUp) daysSince(c, today) else daysUntil(c, today)

    /** Convert a day-count to the chosen unit's value. */
    fun inUnit(days: Long, unit: String, from: LocalDate, to: LocalDate): Long = when (unit) {
        "weeks" -> days / 7
        "hours" -> days * 24
        "sleeps" -> days
        "workdays" -> workdaysBetween(if (days >= 0) from else to, if (days >= 0) to else from)
        else -> days
    }

    fun unitLabel(unit: String, n: Long): String = when (unit) {
        "weeks" -> if (n == 1L) "week" else "weeks"
        "hours" -> if (n == 1L) "hour" else "hours"
        "sleeps" -> if (n == 1L) "sleep" else "sleeps"
        "workdays" -> "work days"
        else -> if (n == 1L) "day" else "days"
    }

    private fun workdaysBetween(a: LocalDate, b: LocalDate): Long {
        if (!b.isAfter(a)) return 0
        var d = a; var n = 0L
        while (d.isBefore(b)) { if (d.dayOfWeek.value <= 5) n++; d = d.plusDays(1) }
        return n
    }

    /** The big number + unit label the card shows, honouring count-up + unit. */
    fun displayCount(c: CountdownEntity, today: LocalDate = LocalDate.now()): Pair<Long, String> {
        val days = primaryDays(c, today)
        val from = if (c.countUp) originDate(c) else today
        val to = if (c.countUp) today else nextOccurrence(c, today)
        val n = inUnit(kotlin.math.abs(days), c.unit, from, to)
        return n to unitLabel(c.unit, n)
    }

    // ---- Date-fact pack -------------------------------------------------------------------------
    private val CHINESE = listOf("🐀 Rat", "🐂 Ox", "🐅 Tiger", "🐇 Rabbit", "🐉 Dragon", "🐍 Snake",
        "🐎 Horse", "🐐 Goat", "🐒 Monkey", "🐓 Rooster", "🐕 Dog", "🐖 Pig")

    fun chineseZodiac(c: CountdownEntity): String? {
        if (type(c) != EventType.BIRTHDAY || !c.yearKnown) return null
        val y = originDate(c).year
        return CHINESE[(((y - 1900) % 12) + 12) % 12]
    }

    /** Numerology Life-Path number (single digit, or master 11/22/33). Birthdays only. */
    fun lifePath(c: CountdownEntity): Int? {
        if (type(c) != EventType.BIRTHDAY || !c.yearKnown) return null
        fun reduce(n: Int): Int { var x = n; while (x > 9 && x != 11 && x != 22 && x != 33) x = x.toString().sumOf { it - '0' }; return x }
        val d = originDate(c)
        return reduce(reduce(d.year) + reduce(d.monthValue) + reduce(d.dayOfMonth))
    }

    /** The "golden birthday" — the age when age == day-of-month. */
    fun goldenBirthday(c: CountdownEntity): Pair<Int, LocalDate>? {
        if (type(c) != EventType.BIRTHDAY || !c.yearKnown) return null
        val d = originDate(c); val age = d.dayOfMonth
        return age to d.plusYears(age.toLong())
    }

    /** The date this person/date reaches a round number of days old (10 000, 20 000, 25 000…). */
    fun nextRoundDayMilestone(c: CountdownEntity, today: LocalDate = LocalDate.now()): Pair<Int, LocalDate>? {
        if (!c.yearKnown) return null
        val origin = originDate(c)
        val livedDays = ChronoUnit.DAYS.between(origin, today)
        for (round in listOf(1000, 5000, 10000, 15000, 20000, 25000, 30000, 40000)) {
            if (livedDays < round) return round to origin.plusDays(round.toLong())
        }
        return null
    }

    /** The date this reaches 1 billion seconds (~31.7 years) — a cult milestone. */
    fun billionSeconds(c: CountdownEntity): LocalDate? =
        if (c.yearKnown) originDate(c).plusDays(1_000_000_000L / 86_400L) else null

    fun dayOfWeekBorn(c: CountdownEntity): String? =
        if (c.yearKnown) originDate(c).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) else null

    // ---- Milestone radar: rare upcoming milestones across all occasions --------------------------
    data class RadarHit(val occasionId: String, val label: String, val date: LocalDate, val daysUntil: Long, val emoji: String)

    fun radar(all: List<CountdownEntity>, today: LocalDate = LocalDate.now(), horizonDays: Long = 120): List<RadarHit> {
        val out = ArrayList<RadarHit>()
        for (c in all) {
            if (c.archived) continue
            val who = c.personName.ifBlank { c.title }
            fun add(label: String, date: LocalDate, emoji: String) {
                val d = ChronoUnit.DAYS.between(today, date)
                if (d in 0..horizonDays) out.add(RadarHit(c.id, label, date, d, emoji))
            }
            // Golden birthday
            goldenBirthday(c)?.let { (age, date) -> if (!date.isBefore(today)) add("$who's golden birthday ($age)", date, "🥂") }
            // 10k/… days old
            nextRoundDayMilestone(c)?.let { (round, date) -> add("$who turns ${"%,d".format(round)} days old", date, "✨") }
            // 1 billion seconds
            billionSeconds(c)?.let { date -> add("$who at 1 billion seconds", date, "⏱️") }
            // round-number birthday/anniversary already covered by milestone(); add its next occurrence
            milestone(c, today)?.let { m -> add("$who — $m", nextOccurrence(c, today), type(c).emoji) }
        }
        return out.sortedBy { it.daysUntil }
    }

    // ---- Life in weeks (#18) & life-spent (#13) --------------------------------------------------
    /** Total weeks in a life of [lifeYears]; each square is one week. Tesla/Wait-But-Why "4000 weeks". */
    fun totalLifeWeeks(lifeYears: Int = 80): Int = lifeYears * 52

    /** Whole weeks lived since birth (0 if the year is unknown or the date is in the future). */
    fun weeksLived(c: CountdownEntity, today: LocalDate = LocalDate.now()): Int {
        if (!c.yearKnown) return 0
        val w = ChronoUnit.WEEKS.between(originDate(c), today)
        return w.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Percentage of an [lifeYears]-year life already lived, 0..100 (null if the year is unknown). */
    fun lifeSpentPct(c: CountdownEntity, lifeYears: Int = 80, today: LocalDate = LocalDate.now()): Int? {
        if (!c.yearKnown) return null
        val lived = weeksLived(c, today).toDouble()
        val total = totalLifeWeeks(lifeYears).toDouble()
        return ((lived / total) * 100.0).toInt().coerceIn(0, 100)
    }

    /** The best occasion to visualise as a "life in weeks": a favourite birthday with a known year,
     *  else any birthday with a known year, else null. */
    fun lifeInWeeksSubject(all: List<CountdownEntity>): CountdownEntity? {
        val births = all.filter { !it.archived && it.yearKnown && type(it) == EventType.BIRTHDAY }
        return births.firstOrNull { it.favorite } ?: births.minByOrNull { originDate(it) }
    }
}
