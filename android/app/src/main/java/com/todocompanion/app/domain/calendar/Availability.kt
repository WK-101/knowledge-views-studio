package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * R55/R56 — "When am I free?" Computes your open time across a day / week / month at a glance, so you never
 * have to hand-check the calendar before saying yes to a meeting. Purely on-device: it reads your local
 * events (expanded through recurrence), subtracts them (plus an optional buffer) from your availability
 * window on each available weekday, and reports the free slots, per-day and total free time, the single
 * longest open block, and — R56 — the exact busy blocks (for a visual day timeline), the best openings
 * across the whole range (date + time + duration, sorted), and duration-aware "can I fit N minutes?" math.
 */
object Availability {

    /** Your availability rules. The daily window is [startHour, endHour); [days] are 1=Mon..7=Sun. */
    data class Config(
        val days: Set<Int>,
        val startHour: Int,
        val endHour: Int,
        val minSlotMin: Int,
        val bufferMin: Int,
    )

    /** One day's outcome. [available] is false for a weekday you don't accept commitments on. */
    data class DayFree(
        val date: LocalDate,
        val available: Boolean,
        val slots: List<CalendarEngine.Slot>,       // free openings within the window (>= minSlot)
        val busy: List<CalendarEngine.Slot>,         // busy event blocks clipped/merged to the window (R56)
        val reserved: List<CalendarEngine.Slot>,     // protected self-reserved blocks (R57)
        val freeMin: Int,
        val busyMin: Int,
        val windowMin: Int,
        val windowStartMillis: Long,                 // window bounds for proportional timeline drawing (R56)
        val windowEndMillis: Long,
    ) {
        val blockCount: Int get() = slots.size
        val longestSlot: CalendarEngine.Slot? get() = slots.maxByOrNull { it.minutes }
    }

    /** A protected opening: on weekdays [days] (1=Mon..7=Sun), minutes-of-day [startMin, endMin). */
    data class Protected(val days: Set<Int>, val startMin: Int, val endMin: Int)

    fun parseProtected(csv: String): List<Protected> =
        csv.split(";").mapNotNull { part ->
            val f = part.split("|"); if (f.size != 3) return@mapNotNull null
            val days = f[0].split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet()
            val s = f[1].trim().toIntOrNull(); val e = f[2].trim().toIntOrNull()
            if (days.isEmpty() || s == null || e == null || e <= s) null else Protected(days, s, e)
        }

    fun encodeProtected(list: List<Protected>): String =
        list.joinToString(";") { "${it.days.sorted().joinToString(",")}|${it.startMin}|${it.endMin}" }

    /** One opening located in the range — used for the "best openings" list (date + slot). */
    data class Opening(val date: LocalDate, val slot: CalendarEngine.Slot) {
        val minutes: Long get() = slot.minutes
    }

    fun parseDays(csv: String): Set<Int> =
        csv.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) }

    /**
     * R59 — scheduled tasks as busy time. A timed, still-open task occupies [due, due + duration], where
     * duration falls back estimate → 30 min. Midnight-dated (all-day) tasks have no fixed slot and are
     * skipped. This is what makes "When am I free?" reflect your task workload, not only calendar events.
     */
    fun taskBusyIntervals(tasks: List<TaskEntity>, zone: ZoneId = ZoneId.systemDefault(), excludeIds: Set<String> = emptySet()): List<Pair<Long, Long>> =
        tasks.asSequence()
            .filter { !it.completed && !it.trashed && !it.abandoned && !it.isAllDay && it.id !in excludeIds }
            .mapNotNull { t ->
                val due = t.dueDate ?: return@mapNotNull null
                val dt = java.time.Instant.ofEpochMilli(due).atZone(zone)
                if (dt.hour == 0 && dt.minute == 0) return@mapNotNull null   // midnight = all-day sentinel: no fixed slot
                val dur = (t.durationMin ?: t.estimateMin ?: 30).coerceAtLeast(15)
                due to (due + dur.toLong() * 60_000L)
            }.toList()

    fun forDays(
        events: List<EventEntity>,
        days: List<LocalDate>,
        cfg: Config,
        zone: ZoneId = ZoneId.systemDefault(),
        protected: List<Protected> = emptyList(),
        // R59 — additional busy intervals (scheduled tasks, time blocks, …) subtracted alongside events.
        extraBusy: List<Pair<Long, Long>> = emptyList(),
    ): List<DayFree> {
        val busyEvents = events.filter { it.busy }
        val windowMin = ((cfg.endHour - cfg.startHour).coerceAtLeast(0)) * 60
        val bufferMs = cfg.bufferMin.toLong() * 60_000L
        return days.map { d ->
            val dow = d.dayOfWeek.value
            val dayStart = d.atStartOfDay(zone).toInstant().toEpochMilli()
            val winStart = dayStart + cfg.startHour.toLong() * 3_600_000L
            val winEnd = dayStart + cfg.endHour.toLong() * 3_600_000L
            if (dow !in cfg.days || windowMin <= 0) {
                return@map DayFree(d, false, emptyList(), emptyList(), emptyList(), 0, 0, windowMin, winStart, winEnd)
            }
            val eventBusy = CalendarEngine.onDay(busyEvents, d.toEpochDay(), zone)
                .map { (it.startMillis - bufferMs) to (it.endMillis + bufferMs) }
            // Scheduled tasks (and any other extra commitments) overlapping this day's window.
            val taskBusy = extraBusy.filter { it.second > winStart && it.first < winEnd }
            val commitBusy = eventBusy + taskBusy
            // Protected self-reserved blocks that fall on this weekday, as raw intervals.
            val reservedRaw = protected.filter { dow in it.days }
                .map { (dayStart + it.startMin.toLong() * 60_000L) to (dayStart + it.endMin.toLong() * 60_000L) }
            // Free = window minus events, tasks AND protected blocks (protected time is never offered).
            val slots = CalendarEngine.freeSlots(commitBusy + reservedRaw, d.toEpochDay(), cfg.startHour, cfg.endHour, cfg.minSlotMin, zone)
            val busyBlocks = mergeToWindow(commitBusy, winStart, winEnd)
            val reservedBlocks = mergeToWindow(reservedRaw, winStart, winEnd)
            val freeMin = slots.sumOf { it.minutes.toInt() }
            val busyMin = busyBlocks.sumOf { it.minutes.toInt() }
            DayFree(d, true, slots, busyBlocks, reservedBlocks, freeMin, busyMin.coerceAtMost(windowMin), windowMin, winStart, winEnd)
        }
    }

    /** Clip busy intervals to [winStart,winEnd], drop empties, sort, and merge overlaps into blocks. */
    private fun mergeToWindow(intervals: List<Pair<Long, Long>>, winStart: Long, winEnd: Long): List<CalendarEngine.Slot> {
        val clipped = intervals
            .map { maxOf(it.first, winStart) to minOf(it.second, winEnd) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        if (clipped.isEmpty()) return emptyList()
        val out = ArrayList<CalendarEngine.Slot>()
        var s = clipped[0].first; var e = clipped[0].second
        for (i in 1 until clipped.size) {
            val (cs, ce) = clipped[i]
            if (cs <= e) e = maxOf(e, ce) else { out.add(CalendarEngine.Slot(s, e)); s = cs; e = ce }
        }
        out.add(CalendarEngine.Slot(s, e))
        return out
    }

    fun totalFreeMin(days: List<DayFree>): Int = days.sumOf { it.freeMin }
    fun totalBusyMin(days: List<DayFree>): Int = days.sumOf { it.busyMin }
    fun totalWindowMin(days: List<DayFree>): Int = days.filter { it.available }.sumOf { it.windowMin }
    fun availableDayCount(days: List<DayFree>): Int = days.count { it.available }
    fun totalBlockCount(days: List<DayFree>): Int = days.sumOf { it.blockCount }

    fun longest(days: List<DayFree>): Pair<LocalDate, CalendarEngine.Slot>? =
        days.flatMap { df -> df.slots.map { df.date to it } }.maxByOrNull { it.second.minutes }

    /** Every opening in the range, longest first — the block-centric view of "where exactly am I free?". */
    fun openings(days: List<DayFree>, minDurationMin: Int = 0): List<Opening> =
        days.flatMap { df -> df.slots.filter { it.minutes >= minDurationMin }.map { Opening(df.date, it) } }
            .sortedByDescending { it.minutes }

    /** How many gaps of at least [durationMin] exist across the range — the duration-aware "can I fit it?" */
    fun openingsOfAtLeast(days: List<DayFree>, durationMin: Int): Int =
        days.sumOf { df -> df.slots.count { it.minutes >= durationMin } }

    /** The single earliest opening that fits [durationMin] — powers "fit this here" / next free block. */
    fun firstOpeningOfAtLeast(days: List<DayFree>, durationMin: Int): Opening? =
        days.asSequence()
            .flatMap { df -> df.slots.asSequence().filter { it.minutes >= durationMin }.map { Opening(df.date, it) } }
            .minByOrNull { it.slot.startMillis }

    /** Mean length of the free blocks in the range (0 if none) — an "are my gaps usably long?" signal. */
    fun avgBlockMin(days: List<DayFree>): Int {
        val all = days.flatMap { it.slots }
        return if (all.isEmpty()) 0 else (all.sumOf { it.minutes.toInt() } / all.size)
    }

    fun fmtMinutes(min: Int): String {
        val h = min / 60; val m = min % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
