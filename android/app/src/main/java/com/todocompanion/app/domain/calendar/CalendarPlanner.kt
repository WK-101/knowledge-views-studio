package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R41 — the planner brain. Everything a paid scheduling app's "AI" does, done on-device with plain,
 * inspectable rules: a greedy auto-scheduler, a live day-budget, self-calibrating estimates read from
 * your own tracked time, an inferred chronotype, a habit-window risk radar with self-healing block
 * placement, and a longitudinal weekly time-audit. Pure functions — no network, no model, no
 * permission. All of it is only possible because events + tasks + habits + tracked time share one
 * local store; a cloud calendar never holds those four together.
 */
object CalendarPlanner {
    private fun defaultZone() = ZoneId.systemDefault()

    /** Working window [start,end) in epoch-millis for a local day. workEnd==24 means end of day. */
    fun window(day: Long, workStart: Int, workEnd: Int, zone: ZoneId = defaultZone()): Pair<Long, Long> {
        val date = LocalDate.ofEpochDay(day)
        val s = date.atTime(workStart.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        val e = if (workEnd >= 24) date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else date.atTime(workEnd.coerceIn(1, 23), 0).atZone(zone).toInstant().toEpochMilli()
        return s to e
    }

    private fun mergeMs(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.first }
        val out = ArrayList<Pair<Long, Long>>()
        var (cs, ce) = sorted.first()
        for (i in 1 until sorted.size) {
            val (s, e) = sorted[i]
            if (s <= ce) ce = maxOf(ce, e) else { out += cs to ce; cs = s; ce = e }
        }
        out += cs to ce
        return out
    }

    // ── A day as a time budget (moat) ────────────────────────────────────────────────────────────────
    data class Budget(val availableMin: Int, val bookedMin: Int) {
        val remainingMin get() = availableMin - bookedMin
        val overcommitted get() = bookedMin > availableMin
        val fractionUsed get() = if (availableMin <= 0) 1f else (bookedMin.toFloat() / availableMin).coerceIn(0f, 1f)
    }

    /** available = working-window minutes; booked = busy occurrences clipped into the window, merged. */
    fun dayBudget(occurrences: List<CalendarEngine.Occurrence>, day: Long, workStart: Int, workEnd: Int, zone: ZoneId = defaultZone()): Budget {
        val (ws, we) = window(day, workStart, workEnd, zone)
        val avail = ((we - ws) / 60000L).toInt().coerceAtLeast(0)
        val busy = occurrences.filter { it.event.busy && !it.event.allDay }
            .map { maxOf(it.startMillis, ws) to minOf(it.endMillis, we) }
            .filter { it.second > it.first }
        val booked = mergeMs(busy).sumOf { ((it.second - it.first) / 60000L).toInt() }
        return Budget(avail, booked)
    }

    // ── Deterministic auto-scheduler (greedy) ────────────────────────────────────────────────────────
    data class Placement(val task: TaskEntity, val startMillis: Long, val endMillis: Long, val part: Int, val parts: Int) {
        fun durationMin(): Int = (((endMillis - startMillis) / 60000L)).toInt().coerceAtLeast(0)
    }

    /**
     * Rank flexible tasks by deadline, then priority (importance+urgency); place each — chunked to
     * [chunkMin] — into the earliest free slots on [day] within working hours, flowing around existing
     * busy occurrences and blocks already placed this pass. Long tasks split into parts; a task that
     * doesn't fit is simply left unplaced (never silently dropped from its estimate).
     */
    fun autoSchedule(
        tasks: List<TaskEntity>, occurrences: List<CalendarEngine.Occurrence>, day: Long,
        workStart: Int, workEnd: Int, chunkMin: Int = 90, minSlotMin: Int = 15,
        fromMillis: Long? = null, zone: ZoneId = defaultZone(),
    ): List<Placement> {
        val (ws0, we) = window(day, workStart, workEnd, zone)
        val ws = maxOf(ws0, fromMillis ?: ws0)
        if (we - ws < minSlotMin * 60000L) return emptyList()
        val busy = ArrayList(occurrences.filter { it.event.busy && !it.event.allDay }
            .map { maxOf(it.startMillis, ws) to minOf(it.endMillis, we) }.filter { it.second > it.first })
        val ranked = tasks
            .filter { !it.completed && !it.trashed && !it.abandoned && (it.estimateMin ?: 0) >= minSlotMin }
            .sortedWith(compareBy({ it.dueDate ?: Long.MAX_VALUE }, { -(it.importance + it.urgency) }))
        val out = ArrayList<Placement>()
        for (t in ranked) {
            var remaining = t.estimateMin ?: continue
            val parts = Math.ceil(remaining.toDouble() / chunkMin).toInt().coerceAtLeast(1)
            var partIdx = 0
            var guard = 0
            while (remaining >= minSlotMin && guard++ < 50) {
                val want = minOf(remaining, chunkMin)
                val slot = firstFreeSlot(busy, ws, we, want) ?: break
                val end = slot + want * 60000L
                out += Placement(t, slot, end, ++partIdx, parts)
                busy.add(slot to end); busy.sortBy { it.first }
                remaining -= want
            }
        }
        return out.sortedBy { it.startMillis }
    }

    private fun firstFreeSlot(busy: List<Pair<Long, Long>>, ws: Long, we: Long, wantMin: Int): Long? {
        val need = wantMin * 60000L
        var cursor = ws
        for ((s, e) in mergeMs(busy)) {
            if (s - cursor >= need) return cursor
            cursor = maxOf(cursor, e)
        }
        return if (we - cursor >= need) cursor else null
    }

    // ── Self-calibrating estimate + planned-vs-actual (moat) ─────────────────────────────────────────
    /** Actual tracked minutes per task, from finished time entries. */
    fun actualsByTask(entries: List<TimeEntryEntity>): Map<String, Int> =
        entries.filter { it.taskId != null && it.endMillis != null }
            .groupBy { it.taskId!! }
            .mapValues { (_, es) -> es.sumOf { (((it.endMillis!! - it.startMillis) / 60000L)).toInt().coerceAtLeast(0) } }

    data class EstimateBias(val samples: Int, val medianRatio: Double) {
        /** >1 means you routinely under-estimate; the human line. */
        fun sentence(): String {
            val pct = (medianRatio * 100).toInt()
            return when {
                medianRatio >= 1.15 -> "Your tasks run about $pct% of their estimate — you tend to under-estimate."
                medianRatio <= 0.85 -> "Your tasks finish in about $pct% of their estimate — you leave yourself slack."
                else -> "Your estimates are close — tasks run about $pct% of what you plan."
            }
        }
        fun suggestFor(estimateMin: Int): Int = (estimateMin * medianRatio).toInt().coerceAtLeast(5)
    }

    /** Median actual/planned ratio across tasks that had both an estimate and tracked time. */
    fun estimateBias(tasks: List<TaskEntity>, entries: List<TimeEntryEntity>): EstimateBias? {
        val actuals = actualsByTask(entries)
        val ratios = tasks.mapNotNull { t ->
            val est = t.estimateMin ?: return@mapNotNull null
            val act = actuals[t.id] ?: return@mapNotNull null
            if (est <= 0 || act <= 0) null else act.toDouble() / est
        }.sorted()
        if (ratios.size < 3) return null
        return EstimateBias(ratios.size, ratios[ratios.size / 2])
    }

    // ── Inferred chronotype (moat) ───────────────────────────────────────────────────────────────────
    data class Chronotype(val peakHours: List<Int>, val byHour: IntArray, val samples: Int) {
        fun label(): String {
            val p = peakHours.minOrNull() ?: return "not enough history yet"
            return when {
                p < 9 -> "an early-morning peak"
                p < 12 -> "a late-morning peak"
                p < 15 -> "an early-afternoon peak"
                p < 18 -> "a late-afternoon peak"
                else -> "an evening peak"
            }
        }
    }

    /**
     * Histogram of when focus work actually happens — task completions and tracked intervals weighted by
     * length — mapped to local hour. Peaks are the hours at ≥75% of the busiest. No survey, no wearable.
     */
    fun inferChronotype(tasks: List<TaskEntity>, entries: List<TimeEntryEntity>, zone: ZoneId = defaultZone()): Chronotype? {
        val hist = IntArray(24)
        var n = 0
        tasks.filter { it.completed && it.completedAt != null }.forEach {
            val h = Instant.ofEpochMilli(it.completedAt!!).atZone(zone).hour; hist[h]++; n++
        }
        entries.filter { it.endMillis != null }.forEach {
            val h = Instant.ofEpochMilli(it.startMillis).atZone(zone).hour
            val w = ((((it.endMillis!! - it.startMillis) / 60000L)).toInt() / 30).coerceIn(1, 6)
            hist[h] += w; n += w
        }
        if (n < 8) return null
        val max = hist.maxOrNull() ?: 0
        if (max <= 0) return null
        val peaks = (0..23).filter { hist[it] >= max * 0.75 && hist[it] > 0 }
        return Chronotype(peaks, hist, n)
    }

    // ── Habit-window radar + self-healing placement (moat) ───────────────────────────────────────────
    data class HabitRisk(val habit: HabitEntity, val day: Long, val windowStartMin: Int)

    /** Over [days] from [startDay], flag days where a busy event overlaps a habit's reminder window. */
    fun habitWindowRisks(
        habits: List<HabitEntity>, events: List<EventEntity>, startDay: Long, days: Int,
        durationMin: Int = 30, zone: ZoneId = defaultZone(),
    ): List<HabitRisk> {
        val out = ArrayList<HabitRisk>()
        for (d in startDay until startDay + days) {
            val occ = CalendarEngine.onDay(events, d, zone).filter { it.event.busy && !it.event.allDay }
            if (occ.isEmpty()) continue
            for (h in habits) {
                val mins = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }
                for (m in mins) {
                    val s = LocalDate.ofEpochDay(d).atStartOfDay(zone).plusMinutes(m.toLong()).toInstant().toEpochMilli()
                    val e = s + durationMin * 60000L
                    if (occ.any { it.startMillis < e && s < it.endMillis }) out += HabitRisk(h, d, m)
                }
            }
        }
        return out
    }

    /** Nearest free start on [day] to a preferred minute-of-day, sliding out in 15-min steps. */
    fun slideToFree(
        day: Long, preferredMin: Int, durationMin: Int, events: List<EventEntity>,
        workStart: Int, workEnd: Int, zone: ZoneId = defaultZone(),
    ): Long? {
        val (ws, we) = window(day, workStart, workEnd, zone)
        val busy = CalendarEngine.onDay(events, day, zone).filter { it.event.busy && !it.event.allDay }.map { it.startMillis to it.endMillis }
        val pref = LocalDate.ofEpochDay(day).atStartOfDay(zone).plusMinutes(preferredMin.toLong()).toInstant().toEpochMilli()
        val need = durationMin * 60000L
        fun free(at: Long) = at >= ws && at + need <= we && busy.none { at < it.second && it.first < at + need }
        if (free(pref)) return pref
        val step = 15 * 60000L
        for (k in 1..48) {
            val fwd = pref + k * step; if (fwd + need <= we && free(fwd)) return fwd
            val back = pref - k * step; if (back >= ws && free(back)) return back
        }
        return null
    }

    // ── Weekly time-audit / retrospective engine (moat) ──────────────────────────────────────────────
    data class Audit(
        val weekStartDay: Long,
        val bookedMinByDay: Map<Long, Int>,       // events, per day
        val minutesByCalendar: Map<String, Int>,  // events, per calendar id
        val busiestDay: Long?,
        val totalBookedMin: Int,
        val trackedMin: Int,                       // from time entries
        val estimateBias: EstimateBias?,
        val habitAdherencePct: Int?,               // caller-supplied (needs check-in data)
        val advice: List<String>,
    )

    fun weeklyAudit(
        events: List<EventEntity>, entries: List<TimeEntryEntity>, tasks: List<TaskEntity>,
        weekStartDay: Long, workStart: Int, workEnd: Int,
        habitAdherencePct: Int? = null, zone: ZoneId = defaultZone(),
    ): Audit {
        val winStart = LocalDate.ofEpochDay(weekStartDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val winEnd = LocalDate.ofEpochDay(weekStartDay + 7).atStartOfDay(zone).toInstant().toEpochMilli()
        val occ = CalendarEngine.expand(events, winStart, winEnd, zone).filter { it.event.busy && !it.event.allDay }
        val byDay = HashMap<Long, Int>()
        val byCal = HashMap<String, Int>()
        occ.forEach {
            val d = Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate().toEpochDay()
            val m = it.durationMin().toInt()
            byDay[d] = (byDay[d] ?: 0) + m
            byCal[it.event.calendarId] = (byCal[it.event.calendarId] ?: 0) + m
        }
        val tracked = entries.filter { it.endMillis != null && it.startMillis in winStart until winEnd }
            .sumOf { (((it.endMillis!! - it.startMillis) / 60000L)).toInt().coerceAtLeast(0) }
        val total = byDay.values.sum()
        val busiest = byDay.maxByOrNull { it.value }?.key
        val bias = estimateBias(tasks, entries)
        val dailyCapMin = ((workEnd.coerceIn(1, 24) - workStart.coerceIn(0, 23)) * 60)

        val advice = ArrayList<String>()
        busiest?.let { d ->
            val mins = byDay[d] ?: 0
            if (dailyCapMin > 0 && mins > dailyCapMin) {
                val dow = LocalDate.ofEpochDay(d).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                advice += "$dow runs ${pct(mins, dailyCapMin)}% booked — heaviest day of the week. Move one block off it."
            }
        }
        bias?.let {
            if (it.medianRatio >= 1.2) advice += "You under-estimate by ~${((it.medianRatio - 1) * 100).toInt()}%. Pad tomorrow's blocks to stay honest."
            else if (it.medianRatio <= 0.8) advice += "Tasks finish faster than planned (~${(it.medianRatio * 100).toInt()}% of estimate). You can commit to a little more."
        }
        if (habitAdherencePct != null && habitAdherencePct < 60) advice += "Habit adherence was $habitAdherencePct% this week — the radar can defend those windows next week."
        if (total == 0 && tracked == 0) advice += "A quiet week on the calendar — try blocking a couple of your tasks to give the day some shape."
        if (advice.isEmpty()) advice += "A balanced week — nothing over-booked, and your estimates held up."

        return Audit(weekStartDay, byDay, byCal, busiest, total, tracked, bias, habitAdherencePct, advice)
    }

    private fun pct(a: Int, b: Int): Int = if (b <= 0) 0 else ((a * 100f) / b).toInt()
}
