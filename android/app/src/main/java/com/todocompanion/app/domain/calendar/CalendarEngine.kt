package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.EventEntity
import com.todocompanion.app.domain.recurrence.Recurrence
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * R38 — the calendar brain. Expands recurring EVENTS into concrete occurrences over a window (EXDATE
 * skips + per-instance overrides honoured), and computes the on-device analytics a dedicated calendar
 * needs: free-slot / gap finding, conflict detection, and a real-load heat-map. Pure functions, offline.
 */
object CalendarEngine {

    data class Occurrence(
        val event: EventEntity,
        val startMillis: Long,
        val endMillis: Long,
        val isOverride: Boolean = false,
    ) {
        fun durationMin(): Long = ((endMillis - startMillis) / 60000L).coerceAtLeast(0)
    }

    private fun epochDay(millis: Long, zone: ZoneId) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    /** Every occurrence of every (visible) event that intersects [windowStart, windowEnd]. */
    fun expand(events: List<EventEntity>, windowStart: Long, windowEnd: Long, zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> {
        val out = ArrayList<Occurrence>()
        val overrides = events.filter { it.recurrenceParentId != null }
        val overrideKey = overrides.associateBy { it.recurrenceParentId!! + "@" + it.recurrenceDate }
        // Standalone override events show at their own moved time.
        overrides.forEach { ov -> if (ov.endMillis >= windowStart && ov.startMillis <= windowEnd) out += Occurrence(ov, ov.startMillis, ov.endMillis, isOverride = true) }

        events.filter { it.recurrenceParentId == null }.forEach { ev ->
            val dur = (ev.endMillis - ev.startMillis).coerceAtLeast(0)
            if (ev.rrule.isBlank()) {
                if (ev.endMillis >= windowStart && ev.startMillis <= windowEnd) out += Occurrence(ev, ev.startMillis, ev.endMillis)
                return@forEach
            }
            val r = Recurrence.parse(ev.rrule)
            val exDays = ev.exDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
            var cur = ev.startMillis
            var emitted = 0
            var guard = 0
            while (guard++ < 2000 && cur <= windowEnd) {
                val day = epochDay(cur, zone)
                val untilDay = r?.untilEpochDay
                if (untilDay != null && day > untilDay) break
                if (r?.count != null && emitted >= r.count) break
                val overrideHere = overrideKey[ev.id + "@" + day]
                if (day !in exDays && overrideHere == null) {
                    if (cur + dur >= windowStart) out += Occurrence(ev, cur, cur + dur)
                }
                emitted++
                val nxt = Recurrence.next(ev.rrule, cur, zone)
                if (nxt <= cur) break
                cur = nxt
            }
        }
        return out.sortedBy { it.startMillis }
    }

    /** Occurrences intersecting the local day [day] (epoch-day). */
    fun onDay(events: List<EventEntity>, day: Long, zone: ZoneId = ZoneId.systemDefault()): List<Occurrence> {
        val dayStart = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = LocalDate.ofEpochDay(day).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return expand(events, dayStart, dayEnd, zone).filter { it.endMillis >= dayStart && it.startMillis <= dayEnd }
    }

    // ── Gap / open-time finder ─────────────────────────────────────────────────────────────────────
    data class Slot(val startMillis: Long, val endMillis: Long) { val minutes get() = ((endMillis - startMillis) / 60000L) }

    /** Free slots on [day] within working hours [workStartHour, workEndHour), at least [minMinutes] long,
     *  around the given busy intervals (events + task blocks the caller passes in). */
    fun freeSlots(busy: List<Pair<Long, Long>>, day: Long, workStartHour: Int, workEndHour: Int, minMinutes: Int, zone: ZoneId = ZoneId.systemDefault()): List<Slot> {
        val date = LocalDate.ofEpochDay(day)
        val windowStart = date.atTime(workStartHour.coerceIn(0, 23), 0).atZone(zone).toInstant().toEpochMilli()
        val windowEnd = date.atTime((workEndHour).coerceIn(1, 24).coerceAtMost(23), 0).atZone(zone).toInstant().toEpochMilli()
            .let { if (workEndHour >= 24) date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else it }
        val merged = mergeIntervals(busy.filter { it.second > windowStart && it.first < windowEnd })
        val out = ArrayList<Slot>()
        var cursor = windowStart
        for ((s, e) in merged) {
            if (s - cursor >= minMinutes * 60000L) out += Slot(cursor, s)
            cursor = maxOf(cursor, e)
        }
        if (windowEnd - cursor >= minMinutes * 60000L) out += Slot(cursor, windowEnd)
        return out
    }

    private fun mergeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
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

    // ── Conflict detection ─────────────────────────────────────────────────────────────────────────
    /** Pairs of occurrences that overlap in time (both busy). */
    fun conflicts(occurrences: List<Occurrence>): List<Pair<Occurrence, Occurrence>> {
        val busy = occurrences.filter { it.event.busy && !it.event.allDay }.sortedBy { it.startMillis }
        val out = ArrayList<Pair<Occurrence, Occurrence>>()
        for (i in busy.indices) for (j in i + 1 until busy.size) {
            if (busy[j].startMillis >= busy[i].endMillis) break
            if (busy[j].startMillis < busy[i].endMillis && busy[i].startMillis < busy[j].endMillis) out += busy[i] to busy[j]
        }
        return out
    }

    /** Does a proposed [start,end] clash with any busy occurrence? */
    fun clashes(occurrences: List<Occurrence>, start: Long, end: Long): List<Occurrence> =
        occurrences.filter { it.event.busy && !it.event.allDay && start < it.endMillis && it.startMillis < end }

    // ── Real-load heat-map ─────────────────────────────────────────────────────────────────────────
    /** Busy minutes per local day over [days] ending today — for the month heat-map. Extra busy
     *  intervals (e.g. tracked time, task blocks) can be folded in by the caller via [extra]. */
    fun busyMinutesByDay(events: List<EventEntity>, startDay: Long, days: Int, zone: ZoneId = ZoneId.systemDefault(), extra: List<Pair<Long, Long>> = emptyList()): Map<Long, Int> {
        val windowStart = LocalDate.ofEpochDay(startDay).atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = LocalDate.ofEpochDay(startDay + days).atStartOfDay(zone).toInstant().toEpochMilli()
        val occ = expand(events, windowStart, windowEnd, zone).filter { it.event.busy && !it.event.allDay }
        val out = HashMap<Long, Int>()
        fun add(s: Long, e: Long) {
            var cur = s
            while (cur < e) {
                val d = epochDay(cur, zone)
                val dayEnd = LocalDate.ofEpochDay(d).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val chunkEnd = minOf(e, dayEnd)
                out[d] = (out[d] ?: 0) + ((chunkEnd - cur) / 60000L).toInt().coerceAtLeast(0)
                cur = chunkEnd
            }
        }
        occ.forEach { add(it.startMillis, it.endMillis) }
        extra.forEach { add(it.first, it.second) }
        return out
    }
}
