package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.domain.AppSettings
import java.time.LocalDate
import java.time.ZoneId

/**
 * Habits that consume time. A time-consuming habit — a 30-min run, 20 minutes of reading, even the
 * ~15 minutes it takes to walk 1,000 steps — is real time the day no longer has. Yet the free-time,
 * "I have time", Plan-my-day and workload surfaces were task-only, so they overstated availability.
 * HabitTime is the single source of truth for a habit's per-day time cost and how it reserves time.
 *
 * Two classes of habit time:
 *  · DEDICATED — a contiguous block that wants a slot (run, meditation). When it carries a time-of-day
 *    (its cueTime, else its first reminder) it reserves that exact interval and can optionally draw as a
 *    calendar block. Without a time it falls back to the ambient reserve (can't place a specific slot).
 *  · AMBIENT   — upkeep spread across the day (steps, water). Never a rigid block; instead it lowers the
 *    day's usable minutes as a soft "habits & upkeep" reserve.
 *
 * The cost is DERIVED from the app's own data wherever possible (unit == "min" → the target; a linked
 * time-tracking activity → its learned median; minutesPerUnit × target), or set MANUALLY as "≈ N min".
 * An unknown cost contributes nothing — we never invent load. The reserve is pending-aware: once today's
 * check-in meets the goal, its minutes are released back to the day, so capacity self-heals through the day.
 *
 * All per-habit config rides settings-JSON (schema-frozen safe), keyed by habit id, mirroring
 * [AppSettings.timeActivityParents]. Purely on-device.
 */
object HabitTime {

    enum class TimeClass { OFF, AMBIENT, DEDICATED }
    enum class CostMode { DERIVED, MANUAL }

    /** Per-habit planning config. [timeClass] null = auto-classify. [manualMin] applies only in MANUAL mode. */
    data class Cfg(
        val timeClass: TimeClass? = null,
        val costMode: CostMode = CostMode.DERIVED,
        val manualMin: Int = 0,
        val showAsBlock: Boolean = false,
    )

    private val MIN_UNITS = setOf("min", "mins", "minute", "minutes")

    // ── per-habit config persistence (value string: "class|mode|manualMin|block") ────────────────────
    fun parseCfg(v: String?): Cfg {
        if (v.isNullOrBlank()) return Cfg()
        val f = v.split("|")
        val cls = when (f.getOrNull(0)) {
            "off" -> TimeClass.OFF
            "ambient" -> TimeClass.AMBIENT
            "dedicated" -> TimeClass.DEDICATED
            else -> null   // "auto" or unknown
        }
        val mode = if (f.getOrNull(1) == "manual") CostMode.MANUAL else CostMode.DERIVED
        val manual = f.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 1440) ?: 0
        val block = f.getOrNull(3) == "1"
        return Cfg(cls, mode, manual, block)
    }

    fun encodeCfg(c: Cfg): String {
        val cls = when (c.timeClass) {
            TimeClass.OFF -> "off"
            TimeClass.AMBIENT -> "ambient"
            TimeClass.DEDICATED -> "dedicated"
            null -> "auto"
        }
        val mode = if (c.costMode == CostMode.MANUAL) "manual" else "derived"
        return "$cls|$mode|${c.manualMin}|${if (c.showAsBlock) "1" else "0"}"
    }

    fun cfgFor(settings: AppSettings, habitId: String): Cfg = parseCfg(settings.habitTimeCfg[habitId])

    /** True when nothing has been chosen — so a default cfg can be dropped from the map to keep it small. */
    fun isDefault(c: Cfg): Boolean =
        c.timeClass == null && c.costMode == CostMode.DERIVED && c.manualMin == 0 && !c.showAsBlock

    // ── cost derivation ladder ────────────────────────────────────────────────────────────────────────
    private fun isMinUnit(h: HabitEntity): Boolean = h.unit?.trim()?.lowercase() in MIN_UNITS

    /** The time-of-day this habit names, if any: its cueTime, else its first reminder. Null = untimed. */
    fun cueMinute(h: HabitEntity): Int? =
        h.cueTime?.takeIf { it in 0..1439 }
            ?: h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.firstOrNull { it in 0..1439 }

    /** Per-occurrence minutes derived from the app's own data. [learnedMin] = median from a linked activity. */
    fun derivedCostMin(h: HabitEntity, learnedMin: Int? = null): Int {
        val target = h.targetPerDay.coerceAtLeast(1)
        return when {
            isMinUnit(h) -> target                                    // "meditate 20 min" → 20
            learnedMin != null && learnedMin > 0 -> learnedMin        // learned from a linked tracked activity
            h.minutesPerUnit > 0 -> h.minutesPerUnit * target         // per-unit time × target
            else -> 0                                                 // unknown → no invented load
        }.coerceIn(0, 1440)
    }

    /** Effective per-occurrence cost: a set MANUAL value wins, otherwise the derived ladder. */
    fun costMin(h: HabitEntity, cfg: Cfg, learnedMin: Int? = null): Int =
        if (cfg.costMode == CostMode.MANUAL && cfg.manualMin > 0) cfg.manualMin else derivedCostMin(h, learnedMin)

    /** Auto-classification when the user hasn't chosen: only explicit-duration habits default to a block. */
    fun autoClass(h: HabitEntity): TimeClass =
        if (isMinUnit(h)) TimeClass.DEDICATED else TimeClass.AMBIENT

    fun effectiveClass(h: HabitEntity, cfg: Cfg): TimeClass {
        // Rolling-quota habits (N×/week or /month) have no fixed day and can't own a specific block, so
        // they are always ambient (amortized across the period) unless explicitly turned off.
        if (h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH)
            return if (cfg.timeClass == TimeClass.OFF) TimeClass.OFF else TimeClass.AMBIENT
        return cfg.timeClass ?: autoClass(h)
    }

    // ── the per-day model ────────────────────────────────────────────────────────────────────────────
    /** One habit's time reservation on a given day. [cueMin] is non-null only for a placeable dedicated block. */
    data class DayHabit(
        val habitId: String,
        val name: String,
        val emoji: String?,
        val colorArgb: Long?,
        val timeClass: TimeClass,
        val costMin: Int,
        val cueMin: Int?,
        val pending: Boolean,
        val showAsBlock: Boolean,
    )

    /**
     * Every habit that reserves time on [epochDay]. [todayEd] is "today" (rollover-adjusted) so the reserve
     * is pending-aware: on today a goal-met habit releases its minutes; future days are always pending.
     * [learnedByHabit] optionally supplies a learned median (minutes) per habit id.
     */
    fun forDay(
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        settings: AppSettings,
        epochDay: Long,
        todayEd: Long,
        learnedByHabit: Map<String, Int> = emptyMap(),
    ): List<DayHabit> {
        val out = ArrayList<DayHabit>()
        for (h in habits) {
            if (h.archived || h.paused || h.habitType == "break") continue
            if (epochDay < h.startEpochDay()) continue
            val cfg = cfgFor(settings, h.id)
            val cls = effectiveClass(h, cfg)
            if (cls == TimeClass.OFF) continue
            val amortized = h.freqType == HabitStats.FREQ_TIMES_WEEK || h.freqType == HabitStats.FREQ_TIMES_MONTH
            if (!amortized && !HabitStats.isExpectedDay(h, epochDay)) continue
            val perOcc = costMin(h, cfg, learnedByHabit[h.id])
            if (perOcc <= 0) continue
            val cost = if (amortized) {
                val period = if (h.freqType == HabitStats.FREQ_TIMES_WEEK) 7.0 else 30.0
                val times = h.freqParam.coerceAtLeast(1)
                Math.round(perOcc * times / period).toInt().coerceAtLeast(1)
            } else perOcc
            val pending = when {
                epochDay > todayEd -> true
                epochDay < todayEd -> false
                amortized -> true    // an amortized daily reserve stays steady; not tied to one day's check-in
                else -> {
                    val ci = checkins.firstOrNull { it.habitId == h.id && it.epochDay == epochDay }
                    !(ci != null && ci.status == "done" && HabitStats.meetsGoal(h, ci.count))
                }
            }
            val cue = if (cls == TimeClass.DEDICATED && !amortized) cueMinute(h) else null
            out.add(
                DayHabit(
                    habitId = h.id, name = h.name, emoji = h.emoji, colorArgb = h.colorArgb,
                    timeClass = cls, costMin = cost, cueMin = cue, pending = pending,
                    showAsBlock = cfg.showAsBlock && cls == TimeClass.DEDICATED && cue != null,
                )
            )
        }
        return out
    }

    /**
     * Dedicated, timed, still-pending habits as busy intervals on [epochDay] — feed straight into
     * [com.todocompanion.app.domain.calendar.Availability.forDays]'s extraBusy alongside scheduled tasks.
     */
    fun busyIntervals(dayHabits: List<DayHabit>, epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<Long, Long>> {
        val dayStart = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
        return dayHabits
            .filter { it.pending && it.timeClass == TimeClass.DEDICATED && it.cueMin != null }
            .map { h ->
                val s = dayStart + h.cueMin!!.toLong() * 60_000L
                s to (s + h.costMin.toLong() * 60_000L)
            }
    }

    /** Pending minutes NOT placed as a specific interval — ambient upkeep plus dedicated-but-untimed. The band. */
    fun ambientReserveMin(dayHabits: List<DayHabit>): Int =
        dayHabits
            .filter { it.pending && (it.timeClass == TimeClass.AMBIENT || it.cueMin == null) }
            .sumOf { it.costMin }

    /** All pending habit minutes on the day (interval + ambient) — the budget-model reserve (workload/forecast). */
    fun totalReserveMin(dayHabits: List<DayHabit>): Int =
        dayHabits.filter { it.pending }.sumOf { it.costMin }
}
