package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.TimeActivityEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The statistics engine behind the Time tab's Statistics screen — the rich, ranged breakdowns that
 * take the tracker from "a list of intervals" to a real time analytics view (à la Simple Time Tracker):
 * a distribution over any Day/Week/Month/Year, and a per-activity drill-down with averages, session
 * lengths, a time-of-day histogram and a period-over-period comparison. Pure over the store; offline.
 */
object TimeStats {

    enum class Range(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year") }

    data class Slice(val activityId: String, val name: String, val emoji: String?, val colorArgb: Long?, val minutes: Int)
    data class Overview(val label: String, val totalMin: Int, val activeDays: Int, val slices: List<Slice>)
    data class Detail(
        val totalMin: Int, val prevTotalMin: Int, val sessions: Int, val activeDays: Int,
        val avgActiveDayMin: Int, val longestMin: Int, val shortestMin: Int, val typicalMin: Int,
        val byHour: List<Int>, val bestDayEpoch: Long?, val bestDayMin: Int,
    )

    /** Inclusive [start, end] local-date window for a range anchored on [anchor]. */
    fun window(range: Range, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (range) {
        Range.DAY -> anchor to anchor
        Range.WEEK -> anchor.minusDays(6) to anchor
        Range.MONTH -> anchor.withDayOfMonth(1) to anchor.withDayOfMonth(anchor.lengthOfMonth())
        Range.YEAR -> anchor.withDayOfYear(1) to anchor.withDayOfYear(anchor.lengthOfYear())
    }

    /** Move the anchor by one range unit (for prev/next paging). */
    fun shift(range: Range, anchor: LocalDate, dir: Long): LocalDate = when (range) {
        Range.DAY -> anchor.plusDays(dir)
        Range.WEEK -> anchor.plusWeeks(dir)
        Range.MONTH -> anchor.plusMonths(dir)
        Range.YEAR -> anchor.plusYears(dir)
    }

    fun label(range: Range, anchor: LocalDate, zone: ZoneId): String {
        val today = LocalDate.now(zone)
        val (s, e) = window(range, anchor)
        return when (range) {
            Range.DAY -> when (anchor) {
                today -> "Today"; today.minusDays(1) -> "Yesterday"
                else -> anchor.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
            }
            Range.WEEK -> "${s.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))} – ${e.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
            Range.MONTH -> anchor.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy"))
            Range.YEAR -> anchor.year.toString()
        }
    }

    private fun millis(s: LocalDate, e: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = s.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    fun overview(entries: List<TimeEntryEntity>, activities: List<TimeActivityEntity>, range: Range, anchor: LocalDate, zone: ZoneId, now: Long): Overview {
        val (s, e) = window(range, anchor)
        val (winStart, winEnd) = millis(s, e, zone)
        val byId = activities.associateBy { it.id }
        val slices = TimeTracking.totalsByActivity(entries, winStart, winEnd, now)
            .map { t -> val a = byId[t.activityId]; Slice(t.activityId, a?.name ?: "—", a?.emoji, a?.colorArgb, t.minutes) }
            .filter { it.minutes > 0 }
            .sortedByDescending { it.minutes }
        val total = slices.sumOf { it.minutes }
        val activeDays = (s.toEpochDay()..e.toEpochDay()).count { d ->
            val (ds, de) = millis(LocalDate.ofEpochDay(d), LocalDate.ofEpochDay(d), zone)
            TimeTracking.totalMinutes(entries, ds, de, now) > 0
        }
        return Overview(label(range, anchor, zone), total, activeDays, slices)
    }

    fun detail(entries: List<TimeEntryEntity>, activityId: String, range: Range, anchor: LocalDate, zone: ZoneId, now: Long): Detail {
        val (s, e) = window(range, anchor)
        val (winStart, winEnd) = millis(s, e, zone)
        val mine = entries.filter { it.activityId == activityId }

        val sessionMins = mine.mapNotNull { en ->
            val m = TimeTracking.minutesInWindow(en.startMillis, en.endMillis, winStart, winEnd, now)
            if (m > 0) m else null
        }
        val total = sessionMins.sum()
        val sessions = sessionMins.size
        val longest = sessionMins.maxOrNull() ?: 0
        val shortest = sessionMins.minOrNull() ?: 0
        val typical = sessionMins.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

        val perDay = (s.toEpochDay()..e.toEpochDay()).associateWith { d ->
            val (ds, de) = millis(LocalDate.ofEpochDay(d), LocalDate.ofEpochDay(d), zone)
            mine.sumOf { TimeTracking.minutesInWindow(it.startMillis, it.endMillis, ds, de, now) }
        }.filter { it.value > 0 }
        val activeDays = perDay.size
        val avgActive = if (activeDays > 0) total / activeDays else 0
        val best = perDay.maxByOrNull { it.value }

        val byHour = IntArray(24)
        mine.forEach { en -> addHours(byHour, en, winStart, winEnd, now, zone) }

        val prevAnchor = shift(range, anchor, -1)
        val (ps, pe) = window(range, prevAnchor)
        val (pws, pwe) = millis(ps, pe, zone)
        val prevTotal = mine.sumOf { TimeTracking.minutesInWindow(it.startMillis, it.endMillis, pws, pwe, now) }

        return Detail(total, prevTotal, sessions, activeDays, avgActive, longest, shortest, typical, byHour.toList(), best?.key, best?.value ?: 0)
    }

    // ── Trends — the longer-arc patterns (weekday rhythm, day-by-day trajectory, peak hours) ────────
    data class Trends(
        val byWeekdayMin: List<Int>,     // Mon..Sun total minutes across the window (index 0 = Monday)
        val byWeekdayDays: List<Int>,    // how many of each weekday fell in the window (for averaging)
        val dailyTotals: List<Pair<Long, Int>>,  // (epochDay, minutes) for every day in the window, chronological
        val peakByHour: List<Int>,       // 24 hour-of-day buckets, aggregated over the whole window
        val totalMin: Int, val prevTotalMin: Int, val windowDays: Int, val activeDays: Int,
    )

    fun trends(entries: List<TimeEntryEntity>, range: Range, anchor: LocalDate, zone: ZoneId, now: Long): Trends {
        val (s, e) = window(range, anchor)
        val (winStart, winEnd) = millis(s, e, zone)
        val dailyTotals = (s.toEpochDay()..e.toEpochDay()).map { d ->
            val (ds, de) = millis(LocalDate.ofEpochDay(d), LocalDate.ofEpochDay(d), zone)
            d to TimeTracking.totalMinutes(entries, ds, de, now)
        }
        val wdMin = IntArray(7); val wdDays = IntArray(7)
        dailyTotals.forEach { (d, m) ->
            val wd = LocalDate.ofEpochDay(d).dayOfWeek.value - 1   // Monday = 0
            wdMin[wd] += m; wdDays[wd] += 1
        }
        val byHour = IntArray(24)
        entries.forEach { en -> addHours(byHour, en, winStart, winEnd, now, zone) }
        val total = dailyTotals.sumOf { it.second }
        val activeDays = dailyTotals.count { it.second > 0 }
        val prevAnchor = shift(range, anchor, -1)
        val (ps, pe) = window(range, prevAnchor)
        val (pws, pwe) = millis(ps, pe, zone)
        val prevTotal = TimeTracking.totalMinutes(entries, pws, pwe, now)
        return Trends(wdMin.toList(), wdDays.toList(), dailyTotals, byHour.toList(), total, prevTotal, dailyTotals.size, activeDays)
    }

    // ── Correlations — "on days you track A, you also track B" (the cross-activity co-occurrence) ────
    data class CoOccur(
        val aId: String, val aName: String, val aEmoji: String?,
        val bId: String, val bName: String, val bEmoji: String?,
        val aDays: Int, val together: Int, val pct: Int,
    )

    /**
     * The strongest co-occurrence per activity over the last [lookbackDays]: of the days you tracked A at
     * all, what share also had B. This is the tracker's answer to the habits screen's correlations — the
     * "these two travel together" insight a unified store makes possible. Only reasonably-supported pairs
     * are returned (A tracked ≥ 3 days, ≥ 2 shared days, ≥ 40% co-occurrence), strongest first.
     */
    fun correlations(entries: List<TimeEntryEntity>, activities: List<TimeActivityEntity>, zone: ZoneId, now: Long, lookbackDays: Int = 60, maxOut: Int = 5): List<CoOccur> {
        val today = LocalDate.now(zone)
        val start = today.minusDays((lookbackDays - 1).toLong())
        val byId = activities.associateBy { it.id }
        val daysActive = HashMap<String, MutableSet<Long>>()
        for (d in start.toEpochDay()..today.toEpochDay()) {
            val (ds, de) = millis(LocalDate.ofEpochDay(d), LocalDate.ofEpochDay(d), zone)
            entries.forEach { en ->
                if (TimeTracking.minutesInWindow(en.startMillis, en.endMillis, ds, de, now) > 0)
                    daysActive.getOrPut(en.activityId) { HashSet() }.add(d)
            }
        }
        val ids = daysActive.keys.toList()
        val out = ArrayList<CoOccur>()
        for (a in ids) {
            val aSet = daysActive[a] ?: continue
            if (aSet.size < 3) continue
            var bestB: String? = null; var bestTogether = 0
            for (b in ids) {
                if (b == a) continue
                val together = (daysActive[b] ?: continue).count { it in aSet }
                if (together > bestTogether) { bestTogether = together; bestB = b }
            }
            val bId = bestB ?: continue
            if (bestTogether < 2) continue
            val pct = bestTogether * 100 / aSet.size
            if (pct < 40) continue
            val aA = byId[a]; val bA = byId[bId]
            out.add(CoOccur(a, aA?.name ?: "—", aA?.emoji, bId, bA?.name ?: "—", bA?.emoji, aSet.size, bestTogether, pct))
        }
        return out.sortedByDescending { it.pct * it.aDays }.take(maxOut)
    }

    /** Distribute one interval's minutes into the hour-of-day buckets it spans (clamped to the window). */
    private fun addHours(byHour: IntArray, en: TimeEntryEntity, winStart: Long, winEnd: Long, now: Long, zone: ZoneId) {
        val start = maxOf(en.startMillis, winStart)
        val end = minOf(en.endMillis ?: now, winEnd)
        if (end <= start) return
        var t = start
        var guard = 0
        while (t < end && guard < 100_000) {
            guard++
            val z = Instant.ofEpochMilli(t).atZone(zone)
            val hour = z.hour
            val hourEnd = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
            val seg = minOf(end, hourEnd) - t
            byHour[hour] += (seg / 60_000L).toInt()
            t = hourEnd
        }
    }
}
