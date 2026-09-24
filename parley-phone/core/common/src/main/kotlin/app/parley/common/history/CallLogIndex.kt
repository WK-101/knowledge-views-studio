package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.PhoneNumbers
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil

/** A contact as the index needs it: identity, display name and every number. */
data class IndexContact(val id: Long, val lookupKey: String, val name: String, val numbers: List<String>)

/** A call with its keys resolved. */
data class IndexedCall(
    val call: CallEntry,
    /** [NumberKeys.of] of the call's number. */
    val numberKey: String,
    /** [Person.key] of whoever the call was with. */
    val personKey: String,
) {
    val date: Long get() = call.date
    val type: CallType get() = call.type
    val durationSec: Long get() = call.durationSec
}

/**
 * Whoever a call was with: a contact (all of their numbers) or a single number that isn't in contacts.
 * [name] is the contact name, else the name the call log cached; it is **null** when nothing is known and is
 * never a placeholder such as "Unknown", so a later successful lookup always wins.
 */
data class Person(
    val key: String,
    val contactId: Long?,
    val lookupKey: String?,
    val name: String?,
    /** Numbers seen in calls (and, for contacts, every number of the contact) as [NumberKeys]. */
    val numberKeys: List<String>,
    /** A dialable number for this person as it appears in the log. */
    val number: String,
    /** True/false when the contacts lookup worked; null when it failed (unknown, not "not a contact"). */
    val isContact: Boolean?,
)

/** Half-open time range `[from, until)` in epoch millis. */
data class Period(val from: Long, val until: Long) {
    operator fun contains(t: Long): Boolean = t >= from && t < until

    companion object {
        val ALL = Period(Long.MIN_VALUE, Long.MAX_VALUE)
        fun lastDays(days: Int, now: Long): Period = Period(now - days * CallLogIndex.DAY, Long.MAX_VALUE)
        fun day(date: LocalDate, zone: ZoneId): Period =
            Period(date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        fun between(from: LocalDate, untilExclusive: LocalDate, zone: ZoneId): Period =
            Period(from.atStartOfDay(zone).toInstant().toEpochMilli(), untilExclusive.atStartOfDay(zone).toInstant().toEpochMilli())
    }
}

/** Counts per call type and talk time, kept separately for incoming and outgoing calls. */
data class CallTotals(
    val incoming: Int = 0,
    val outgoing: Int = 0,
    val missed: Int = 0,
    val rejected: Int = 0,
    val blocked: Int = 0,
    val voicemail: Int = 0,
    val other: Int = 0,
    /** Incoming calls that connected (talk time > 0). */
    val answeredIn: Int = 0,
    /** Outgoing calls that connected (talk time > 0). */
    val answeredOut: Int = 0,
    val talkInSec: Long = 0,
    val talkOutSec: Long = 0,
) {
    val total: Int get() = incoming + outgoing + missed + rejected + blocked + voicemail + other
    val talkSec: Long get() = talkInSec + talkOutSec
    val isEmpty: Boolean get() = total == 0

    operator fun plus(e: CallEntry): CallTotals {
        val d = e.durationSec.coerceAtLeast(0)
        return when (e.type) {
            CallType.INCOMING -> copy(incoming = incoming + 1, answeredIn = answeredIn + if (d > 0) 1 else 0, talkInSec = talkInSec + d)
            // Answered on another device: it reached you, but the talk time isn't on this phone.
            CallType.ANSWERED_EXTERNALLY -> copy(incoming = incoming + 1)
            CallType.OUTGOING -> copy(outgoing = outgoing + 1, answeredOut = answeredOut + if (d > 0) 1 else 0, talkOutSec = talkOutSec + d)
            CallType.MISSED -> copy(missed = missed + 1)
            CallType.REJECTED -> copy(rejected = rejected + 1)
            CallType.BLOCKED -> copy(blocked = blocked + 1)
            CallType.VOICEMAIL -> copy(voicemail = voicemail + 1)
            CallType.UNKNOWN -> copy(other = other + 1)
        }
    }

    companion object {
        fun of(calls: Iterable<CallEntry>): CallTotals = calls.fold(CallTotals()) { t, e -> t + e }
    }
}

data class PersonTotals(val person: Person, val totals: CallTotals, val lastCall: Long)

/** Talk time for one week starting on [weekStart]. */
data class WeekBucket(val weekStart: LocalDate, val talkInSec: Long, val talkOutSec: Long, val calls: Int) {
    val talkSec: Long get() = talkInSec + talkOutSec
}

/** Calls per weekday × hour. Rows are Monday..Sunday (index 0..6), columns hours 0..23. */
class Heatmap internal constructor(private val cells: Array<IntArray>) {
    operator fun get(day: DayOfWeek, hour: Int): Int = cells[day.value - 1][hour]
    fun row(day: DayOfWeek): List<Int> = cells[day.value - 1].toList()
    val max: Int get() = cells.maxOf { r -> r.max() }
    val total: Int get() = cells.sumOf { it.sum() }

    /** The busiest (day, hour), or null when empty. */
    fun peak(): Pair<DayOfWeek, Int>? {
        if (total == 0) return null
        var best = DayOfWeek.MONDAY to 0
        for (d in DayOfWeek.entries) for (h in 0..23) if (this[d, h] > this[best.first, best.second]) best = d to h
        return best
    }
}

/** A missed or rejected call you haven't answered since: no later outgoing call and no later answered call. */
data class UnreturnedCall(val person: Person, val last: IndexedCall, val count: Int)

/** "You usually talk every [usualGapDays] days". */
data class Rhythm(val usualGapDays: Int, val samples: Int, val daysSinceLast: Int, val suggestedReminderDays: Int)

/** When calls usually get answered; the app maps each value to a localised phrase ("usually answers after 6 pm"). */
enum class AnswerWindow {
    MORNING,
    AFTERNOON,
    EVENING,
    ;

    companion object {
        fun of(hour: Int): AnswerWindow = when (hour) {
            in 6..11 -> MORNING
            in 12..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

enum class TrendDirection { UP, DOWN, STEADY }

/** Calls in the last 90 days against the 90 days before. */
data class Trend(val recent: Int, val previous: Int, val direction: TrendDirection)

data class PersonInsights(
    val person: Person,
    val totals: CallTotals,
    /** Every number of the person as E.164 where possible (fallback keys otherwise). */
    val numbers: List<String>,
    val lastCall: IndexedCall?,
    val firstCall: IndexedCall?,
    val averagePerMonth: Double,
    val heatmap: Heatmap,
    val trend: Trend,
    /** When your outgoing calls get answered noticeably more often; null without a clear pattern. */
    val answerWindow: AnswerWindow?,
    val rhythm: Rhythm?,
)

/**
 * One shared, immutable index over the whole call history (system call log plus Parley's archive).
 * Build it off the main thread with [build]; every query is a pure function of the index and its inputs,
 * so it is safe to share between screens, workers and features (insights, plan meter, quotas, reputation,
 * dry runs).
 *
 * - Calls are keyed by E.164 ([NumberKeys]), with national numbers read in [countryIso], so the trunk
 *   prefix never splits one person in two.
 * - Calls are grouped by [Person]: all numbers of a contact belong to one person.
 * - Times are bucketed in [zone].
 */
class CallLogIndex private constructor(
    /** Newest first. */
    val calls: List<IndexedCall>,
    val people: Map<String, Person>,
    val countryIso: String?,
    val zone: ZoneId,
    /** False when the contacts lookup failed: names may be missing and [Person.isContact] is null. */
    val contactsKnown: Boolean,
    private val byPerson: Map<String, List<IndexedCall>>,
    private val contactByNumber: Map<String, String>,
    private val contactByMatch: Map<String, String>,
) {
    val isEmpty: Boolean get() = calls.isEmpty()

    fun person(key: String): Person? = people[key]

    /** Person key for any number (a contact's other number maps to the same person). */
    fun personKeyFor(number: String?): String {
        val key = NumberKeys.of(number, countryIso)
        if (key == NumberKeys.HIDDEN) return NumberKeys.HIDDEN
        return contactByNumber[key] ?: contactByMatch[PhoneNumbers.matchKey(number)]?.takeIf { it != AMBIGUOUS } ?: numberPersonKey(key)
    }

    /** Calls in [period], optionally for one person and/or one SIM, newest first. */
    fun calls(period: Period = Period.ALL, personKey: String? = null, simId: String? = null): List<IndexedCall> {
        val source = if (personKey != null) byPerson[personKey].orEmpty() else calls
        return source.filter { it.date in period && (simId == null || it.call.accountId == simId) }
    }

    fun totals(period: Period = Period.ALL, personKey: String? = null, simId: String? = null): CallTotals =
        CallTotals.of(calls(period, personKey, simId).map { it.call })

    /** Totals for one calendar day in [zone] (Recents day summary). */
    fun day(date: LocalDate): CallTotals = totals(Period.day(date, zone))

    /** Everyone you talked with in [period] (hidden numbers excluded), most talk time first. */
    fun perPerson(period: Period = Period.ALL): List<PersonTotals> =
        calls(period).filter { it.personKey != NumberKeys.HIDDEN }
            .groupBy { it.personKey }
            .mapNotNull { (k, list) -> people[k]?.let { PersonTotals(it, CallTotals.of(list.map { c -> c.call }), list.first().date) } }
            .sortedWith(compareByDescending<PersonTotals> { it.totals.talkSec }.thenByDescending { it.totals.total })

    fun topByTalkTime(period: Period, n: Int = 5): List<PersonTotals> = perPerson(period).filter { it.totals.talkSec > 0 }.take(n)

    fun topByCount(period: Period, n: Int = 5): List<PersonTotals> =
        perPerson(period).filter { it.totals.total - it.totals.blocked > 0 }
            .sortedWith(compareByDescending<PersonTotals> { it.totals.total - it.totals.blocked }.thenByDescending { it.totals.talkSec }).take(n)

    /** Totals per SIM (phone account id; null for calls without one). */
    fun perSim(period: Period = Period.ALL): Map<String?, CallTotals> =
        calls(period).groupBy { it.call.accountId }.mapValues { (_, l) -> CallTotals.of(l.map { it.call }) }

    /** Weekday × hour counts of the calls matching [filter]. Blocked calls are left out by default. */
    fun heatmap(period: Period = Period.ALL, personKey: String? = null, filter: (IndexedCall) -> Boolean = { it.type != CallType.BLOCKED }): Heatmap {
        val cells = Array(7) { IntArray(24) }
        for (c in calls(period, personKey)) {
            if (!filter(c)) continue
            val t = Instant.ofEpochMilli(c.date).atZone(zone)
            cells[t.dayOfWeek.value - 1][t.hour]++
        }
        return Heatmap(cells)
    }

    /**
     * Talk time per week in [period] (which must be bounded), including weeks without calls,
     * oldest first. Weeks start on [firstDay].
     */
    fun weeklyTalk(period: Period, firstDay: DayOfWeek = DayOfWeek.MONDAY, now: Long = System.currentTimeMillis()): List<WeekBucket> {
        val list = calls(period)
        val fromMillis = if (period.from == Long.MIN_VALUE) list.lastOrNull()?.date ?: now else period.from
        val untilMillis = if (period.until == Long.MAX_VALUE) now else period.until - 1
        if (untilMillis < fromMillis) return emptyList()
        fun weekOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(firstDay))
        val first = weekOf(fromMillis)
        val last = weekOf(untilMillis)
        val weeks = ChronoUnit.WEEKS.between(first, last).toInt() + 1
        if (weeks > MAX_WEEKS) return emptyList()
        val inSec = LongArray(weeks)
        val outSec = LongArray(weeks)
        val count = IntArray(weeks)
        for (c in list) {
            val i = ChronoUnit.WEEKS.between(first, weekOf(c.date)).toInt()
            if (i !in 0 until weeks) continue
            count[i]++
            val d = c.durationSec.coerceAtLeast(0)
            when (c.type) {
                CallType.INCOMING -> inSec[i] += d
                CallType.OUTGOING -> outSec[i] += d
                else -> Unit
            }
        }
        return (0 until weeks).map { WeekBucket(first.plusWeeks(it.toLong()), inSec[it], outSec[it], count[it]) }
    }

    /** People whose last missed/rejected call in [period] you haven't returned, most recent first. */
    fun unreturned(period: Period = Period.ALL): List<UnreturnedCall> {
        val out = ArrayList<UnreturnedCall>()
        for ((key, list) in byPerson) {
            if (key == NumberKeys.HIDDEN) continue
            var missedCount = 0
            var lastMissed: IndexedCall? = null
            // Newest first: count missed calls until the first contact that "returns" them.
            for (c in list) {
                if (c.type == CallType.OUTGOING || (c.type == CallType.INCOMING && c.durationSec > 0) || c.type == CallType.ANSWERED_EXTERNALLY) break
                if (c.type == CallType.MISSED || c.type == CallType.REJECTED) {
                    missedCount++
                    if (lastMissed == null) lastMissed = c
                }
            }
            val lm = lastMissed ?: continue
            if (lm.date !in period) continue
            val p = people[key] ?: continue
            out += UnreturnedCall(p, lm, missedCount)
        }
        return out.sortedByDescending { it.last.date }
    }

    /**
     * How often you usually talk with [personKey]: the median gap between days with an answered call in the
     * last year. Needs at least four such days; null otherwise.
     */
    fun rhythm(personKey: String, now: Long = System.currentTimeMillis()): Rhythm? {
        val since = now - 365 * DAY
        val days = byPerson[personKey].orEmpty()
            .filter { it.date in since..now && it.durationSec > 0 && (it.type == CallType.INCOMING || it.type == CallType.OUTGOING) }
            .map { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
            .distinct().sorted()
        if (days.size < 4) return null
        val gaps = days.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.sorted()
        val median = if (gaps.size % 2 == 1) gaps[gaps.size / 2].toDouble() else (gaps[gaps.size / 2 - 1] + gaps[gaps.size / 2]) / 2.0
        val usual = ceil(median).toInt().coerceAtLeast(1)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return Rhythm(
            usualGapDays = usual,
            samples = gaps.size,
            daysSinceLast = ChronoUnit.DAYS.between(days.last(), today).toInt(),
            // Nudge once the usual gap is clearly exceeded, not every other time.
            suggestedReminderDays = ceil(median * 1.5).toInt().coerceIn(3, 365),
        )
    }

    /** Everything the per-contact "Calls" section shows. */
    fun insights(personKey: String, now: Long = System.currentTimeMillis()): PersonInsights? {
        val person = people[personKey] ?: return null
        val list = byPerson[personKey].orEmpty()
        val real = list.filter { it.type != CallType.BLOCKED }
        val first = real.lastOrNull()
        val months = if (first == null) 1.0 else ((now - first.date).toDouble() / (30.44 * DAY)).coerceAtLeast(1.0)
        val recent = real.count { it.date in (now - 90 * DAY)..now }
        val previous = real.count { it.date in (now - 180 * DAY) until (now - 90 * DAY) }
        val direction = when {
            recent + previous < 3 -> TrendDirection.STEADY
            recent >= previous * 1.25 + 1 -> TrendDirection.UP
            recent <= previous * 0.75 - 1 -> TrendDirection.DOWN
            else -> TrendDirection.STEADY
        }
        return PersonInsights(
            person = person,
            totals = CallTotals.of(list.map { it.call }),
            numbers = person.numberKeys,
            lastCall = real.firstOrNull(),
            firstCall = first,
            averagePerMonth = real.size / months,
            heatmap = heatmap(personKey = personKey),
            trend = Trend(recent, previous, direction),
            answerWindow = answerWindow(list),
            rhythm = rhythm(personKey, now),
        )
    }

    /**
     * From your outgoing calls: a time window in which they are answered clearly more often than otherwise
     * (at least 60% answered, 25 points above the rest, 3+ attempts on both sides).
     */
    internal fun answerWindow(list: List<IndexedCall>): AnswerWindow? {
        val outgoing = list.filter { it.type == CallType.OUTGOING }
        if (outgoing.size < 6) return null
        return AnswerWindow.entries.map { w ->
            val (inside, outside) = outgoing.partition { AnswerWindow.of(Instant.ofEpochMilli(it.date).atZone(zone).hour) == w }
            Triple(w, inside, outside)
        }.filter { (_, inside, outside) -> inside.size >= 3 && outside.size >= 3 }
            .map { (w, inside, outside) -> Triple(w, inside.count { it.durationSec > 0 }.toDouble() / inside.size, outside.count { it.durationSec > 0 }.toDouble() / outside.size) }
            .filter { (_, rin, rout) -> rin >= 0.6 && rin - rout >= 0.25 }
            .maxByOrNull { it.second }?.first
    }

    companion object {
        const val DAY = 86_400_000L
        private const val MAX_WEEKS = 600
        private const val AMBIGUOUS = "\u0000"

        fun numberPersonKey(numberKey: String) = if (numberKey == NumberKeys.HIDDEN) NumberKeys.HIDDEN else "n:$numberKey"
        fun contactPersonKey(c: IndexContact) = "c:" + c.lookupKey.ifEmpty { c.id.toString() }

        /**
         * Builds the index. [calls] may come in any order and may contain the same call twice (system log and
         * archive); duplicates are dropped by [NumberKeys.dedupe], keeping the first occurrence.
         * [contacts] is null when the contacts lookup failed or isn't available: calls are then keyed by number,
         * names come only from the call log, and nothing is marked "not a contact".
         */
        fun build(calls: List<CallEntry>, contacts: List<IndexContact>?, countryIso: String?, zone: ZoneId = ZoneId.systemDefault()): CallLogIndex {
            val contactByNumber = HashMap<String, String>()
            val contactByMatch = HashMap<String, String>()
            val contactInfo = HashMap<String, IndexContact>()
            for (c in contacts.orEmpty().sortedBy { it.id }) {
                val pk = contactPersonKey(c)
                contactInfo.putIfAbsent(pk, c)
                for (n in c.numbers) {
                    val nk = NumberKeys.of(n, countryIso)
                    if (nk == NumberKeys.HIDDEN) continue
                    contactByNumber.putIfAbsent(nk, pk)
                    val mk = PhoneNumbers.matchKey(n)
                    if (mk.length >= 7) {
                        val prev = contactByMatch[mk]
                        contactByMatch[mk] = if (prev == null || prev == pk) pk else AMBIGUOUS
                    }
                }
            }
            val seen = HashSet<String>()
            val indexed = ArrayList<IndexedCall>(calls.size)
            for (e in calls.sortedByDescending { it.date }) {
                val hidden = e.presentationHidden || e.number.isBlank()
                if (!hidden && !seen.add(NumberKeys.dedupe(e.number, e.date) + "|" + e.type)) continue
                val nk = if (hidden) NumberKeys.HIDDEN else NumberKeys.of(e.number, countryIso)
                val pk = when {
                    nk == NumberKeys.HIDDEN -> NumberKeys.HIDDEN
                    else -> contactByNumber[nk] ?: contactByMatch[PhoneNumbers.matchKey(e.number)]?.takeIf { it != AMBIGUOUS } ?: numberPersonKey(nk)
                }
                indexed += IndexedCall(e, nk, pk)
            }
            val byPerson = indexed.groupBy { it.personKey }
            val people = HashMap<String, Person>()
            for ((pk, list) in byPerson) {
                val contact = contactInfo[pk]
                val seenKeys = list.map { it.numberKey }.distinct()
                people[pk] = Person(
                    key = pk,
                    contactId = contact?.id,
                    lookupKey = contact?.lookupKey,
                    // Contact name, else the newest name the call log cached. Never a placeholder.
                    name = contact?.name?.takeIf { it.isNotBlank() } ?: list.firstNotNullOfOrNull { it.call.cachedName?.takeIf { n -> n.isNotBlank() } },
                    numberKeys = (seenKeys + contact?.numbers.orEmpty().map { NumberKeys.of(it, countryIso) }).filter { it != NumberKeys.HIDDEN }.distinct(),
                    number = list.first().call.number,
                    isContact = when {
                        pk == NumberKeys.HIDDEN -> false
                        contact != null -> true
                        contacts == null -> null
                        else -> false
                    },
                )
            }
            return CallLogIndex(indexed, people, countryIso, zone, contacts != null, byPerson, contactByNumber, contactByMatch)
        }
    }
}
