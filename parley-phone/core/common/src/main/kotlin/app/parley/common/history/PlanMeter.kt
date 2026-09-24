package app.parley.common.history

import app.parley.common.CallEntry
import app.parley.common.CallType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How the carrier rounds each call. Only used to *calculate* usage; stored durations are never changed. */
enum class BillingIncrement(val seconds: Int, val label: String) {
    PER_SECOND(1, "Per second"),
    HALF_MINUTE(30, "Per 30 seconds"),
    PER_MINUTE(60, "Per minute"),
}

/** What kind of number a call went to (from libphonenumber's getNumberType plus the SIM country). */
enum class NumberCategory(val label: String) {
    MOBILE("Mobile numbers"),
    LANDLINE("Landlines"),
    /** Numbering plans that don't tell mobile from fixed (e.g. North America). Counted if either is. */
    MOBILE_OR_LANDLINE("Mobile or landline"),
    INTERNATIONAL("International"),
    /** Free for the caller: never counted. */
    TOLL_FREE("Toll-free"),
    OTHER("Other (premium, shared cost, VoIP, short codes)"),
}

/** A SIM's plan. [simId] is the phone account id the call log uses. */
@Serializable
data class PlanConfig(
    val simId: String,
    val enabled: Boolean = true,
    val allowanceMinutes: Int = 300,
    /** Day of month the allowance renews (1–31; clamped to the month's last day). */
    val cycleStartDay: Int = 1,
    val increment: BillingIncrement = BillingIncrement.PER_MINUTE,
    val countMobile: Boolean = true,
    val countLandline: Boolean = true,
    val countInternational: Boolean = false,
    val countOther: Boolean = false,
    /** Some plans (e.g. in the US) also count incoming minutes. */
    val countIncoming: Boolean = false,
    val warnAtPercent: Int = 80,
) {
    fun counts(c: NumberCategory): Boolean = when (c) {
        NumberCategory.MOBILE -> countMobile
        NumberCategory.LANDLINE -> countLandline
        NumberCategory.MOBILE_OR_LANDLINE -> countMobile || countLandline
        NumberCategory.INTERNATIONAL -> countInternational
        NumberCategory.TOLL_FREE -> false
        NumberCategory.OTHER -> countOther
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val list = ListSerializer(serializer())
        fun encodeList(l: List<PlanConfig>): String = json.encodeToString(list, l)
        fun decodeList(s: String?): List<PlanConfig> = if (s.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(list, s) }.getOrDefault(emptyList())
    }
}

data class PlanUsage(
    val config: PlanConfig,
    /** Billed seconds after rounding each counted call up to the increment. */
    val billedSec: Long,
    /** Real talk time of the same calls. */
    val talkSec: Long,
    val callsCounted: Int,
    val cycleStart: LocalDate,
    /** First day of the next cycle. */
    val cycleEnd: LocalDate,
    val daysLeft: Int,
) {
    /** Minutes as the carrier counts them (rounded up). */
    val usedMinutes: Long get() = (billedSec + 59) / 60
    val fraction: Float get() = if (config.allowanceMinutes <= 0) 0f else usedMinutes.toFloat() / config.allowanceMinutes
    val isNear: Boolean get() = config.allowanceMinutes > 0 && usedMinutes * 100 >= config.allowanceMinutes.toLong() * config.warnAtPercent
    val isOver: Boolean get() = config.allowanceMinutes > 0 && usedMinutes > config.allowanceMinutes

    /** "212 of 300 min used · 9 days left". */
    fun summary(): String = "$usedMinutes of ${config.allowanceMinutes} min used · " + if (daysLeft == 1) "last day" else "$daysLeft days left"
}

object PlanMeter {
    /** A call's duration rounded **up** to the billing increment. Returns a new value; never mutates anything. */
    fun billedSeconds(durationSec: Long, increment: BillingIncrement): Long {
        if (durationSec <= 0) return 0
        val inc = increment.seconds.toLong()
        return (durationSec + inc - 1) / inc * inc
    }

    /** The billing cycle containing [today]: `[start, nextStart)`. */
    fun cycle(today: LocalDate, startDay: Int): Pair<LocalDate, LocalDate> {
        fun startIn(ym: YearMonth): LocalDate = ym.atDay(startDay.coerceIn(1, ym.lengthOfMonth()))
        val thisMonth = startIn(YearMonth.from(today))
        val start = if (!today.isBefore(thisMonth)) thisMonth else startIn(YearMonth.from(today).minusMonths(1))
        val next = startIn(YearMonth.from(start).plusMonths(1))
        return start to next
    }

    /**
     * Usage of [config]'s SIM in the current cycle: connected outgoing calls (and incoming ones if the plan
     * counts them) to counted number categories, each rounded up to the increment.
     * [categorize] classifies a number (libphonenumber in the app). [calls] are read, never modified.
     */
    fun usage(config: PlanConfig, calls: Iterable<CallEntry>, categorize: (String) -> NumberCategory, now: Long, zone: ZoneId): PlanUsage {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val (start, end) = cycle(today, config.cycleStartDay)
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = end.atStartOfDay(zone).toInstant().toEpochMilli()
        var billed = 0L
        var talk = 0L
        var n = 0
        val cache = HashMap<String, NumberCategory>()
        for (e in calls) {
            if (e.accountId != config.simId || e.date < from || e.date >= until || e.durationSec <= 0) continue
            val counted = e.type == CallType.OUTGOING || (config.countIncoming && e.type == CallType.INCOMING)
            if (!counted) continue
            if (!config.counts(cache.getOrPut(e.number) { categorize(e.number) })) continue
            billed += billedSeconds(e.durationSec, config.increment)
            talk += e.durationSec
            n++
        }
        // Days left including today.
        val daysLeft = ChronoUnit.DAYS.between(today, end).toInt().coerceAtLeast(1)
        return PlanUsage(config, billed, talk, n, start, end, daysLeft)
    }
}
