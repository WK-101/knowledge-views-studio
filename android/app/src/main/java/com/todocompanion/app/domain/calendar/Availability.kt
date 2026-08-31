package com.todocompanion.app.domain.calendar

import com.todocompanion.app.data.entity.EventEntity
import java.time.LocalDate
import java.time.ZoneId

/**
 * R55 — "When am I free?" Computes your open time across a day / week / month at a glance, so you never
 * have to hand-check the calendar before saying yes to a meeting. Purely on-device: it reads your local
 * events (expanded through recurrence), subtracts them (plus an optional buffer) from your availability
 * window on each available weekday, and reports the free slots, per-day and total free time, and the
 * single longest open block — duration-aware, so "do I have a free 90 minutes this week?" is one glance.
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
        val slots: List<CalendarEngine.Slot>,
        val freeMin: Int,
        val busyMin: Int,
        val windowMin: Int,
    )

    fun parseDays(csv: String): Set<Int> =
        csv.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.toSet().ifEmpty { setOf(1, 2, 3, 4, 5) }

    fun forDays(events: List<EventEntity>, days: List<LocalDate>, cfg: Config, zone: ZoneId = ZoneId.systemDefault()): List<DayFree> {
        val busyEvents = events.filter { it.busy }
        val windowMin = ((cfg.endHour - cfg.startHour).coerceAtLeast(0)) * 60
        val bufferMs = cfg.bufferMin.toLong() * 60_000L
        return days.map { d ->
            val dow = d.dayOfWeek.value
            if (dow !in cfg.days || windowMin <= 0) return@map DayFree(d, false, emptyList(), 0, 0, windowMin)
            val busy = CalendarEngine.onDay(busyEvents, d.toEpochDay(), zone)
                .map { (it.startMillis - bufferMs) to (it.endMillis + bufferMs) }
            val slots = CalendarEngine.freeSlots(busy, d.toEpochDay(), cfg.startHour, cfg.endHour, cfg.minSlotMin, zone)
            val freeMin = slots.sumOf { it.minutes.toInt() }
            DayFree(d, true, slots, freeMin, (windowMin - freeMin).coerceAtLeast(0), windowMin)
        }
    }

    fun totalFreeMin(days: List<DayFree>): Int = days.sumOf { it.freeMin }
    fun totalWindowMin(days: List<DayFree>): Int = days.filter { it.available }.sumOf { it.windowMin }
    fun availableDayCount(days: List<DayFree>): Int = days.count { it.available }
    fun longest(days: List<DayFree>): Pair<LocalDate, CalendarEngine.Slot>? =
        days.flatMap { df -> df.slots.map { df.date to it } }.maxByOrNull { it.second.minutes }

    /** How many gaps of at least [durationMin] exist across the range — the duration-aware "can I fit it?" */
    fun openingsOfAtLeast(days: List<DayFree>, durationMin: Int): Int =
        days.sumOf { df -> df.slots.count { it.minutes >= durationMin } }

    fun fmtMinutes(min: Int): String {
        val h = min / 60; val m = min % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
