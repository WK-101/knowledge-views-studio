package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One plain-language finding surfaced to the user. [priority] ranks how interesting it is (higher first). */
data class Insight(val emoji: String, val text: String, val priority: Int)

/**
 * The on-device coach: a pure, offline analysis over the ONE store this app holds — habits, their
 * check-ins, tasks and focus — that a standalone tracker structurally cannot compute. It finds
 * plain-language patterns (near-best streaks, weekday dips, habit↔habit and habit↔task correlations)
 * with no network and no AI service. Deterministic and unit-testable; guards against thin data so it
 * never reports a coincidence as a pattern.
 */
object HabitInsights {

    private fun doneDays(h: HabitEntity, checkins: List<HabitCheckinEntity>): Set<Long> =
        checkins.filter { it.habitId == h.id && it.status == "done" && HabitStats.meetsGoal(h, it.count) }
            .map { it.epochDay }.toSet()

    fun compute(
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        tasks: List<TaskEntity>,
        today: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        max: Int = 5,
    ): List<Insight> {
        val active = habits.filter { !it.archived && !it.paused }
        if (active.isEmpty()) return emptyList()
        val out = ArrayList<Insight>()
        val doneByHabit = active.associateWith { doneDays(it, checkins) }
        val skipByHabit = active.associateWith { h -> checkins.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet() }
        val relapseByHabit = active.associateWith { h -> checkins.filter { it.habitId == h.id && HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet() }

        // 1. Near-best-streak — the sharpest motivator.
        active.forEach { h ->
            val done = doneByHabit.getValue(h); val skip = skipByHabit.getValue(h); val rel = relapseByHabit.getValue(h)
            val cur = HabitStats.currentStreak(h, done, skip, rel, today)
            val best = HabitStats.bestStreak(h, done, skip, rel, today)
            if (best >= 4 && cur in (best - 3) until best) {
                val gap = best - cur
                out += Insight("🔥", "You're $gap ${plural(gap, "day", "days")} from your best ‘${h.name}’ streak ($best). Protect it today.", 100 - gap)
            } else if (best >= 7 && cur == best && cur > 0) {
                out += Insight("🏆", "‘${h.name}’ is at your best streak ever — $best ${plural(best, "day", "days")}. Keep it alive.", 96)
            }
        }

        // 2. Weekday dip — where a habit tends to slip.
        active.forEach { h ->
            val done = doneByHabit.getValue(h); val skip = skipByHabit.getValue(h)
            // Only meaningful for roughly-daily habits with history.
            if (h.habitType == "break") return@forEach
            val rates = HabitStats.weekdayRates(done, skip, today, 120)
            val observed = rates.count { it > 0f }
            if (observed >= 5) {
                val avg = rates.filter { it > 0f }.average().toFloat()
                val worst = rates.indices.minByOrNull { rates[it] } ?: return@forEach
                if (avg > 0.4f && rates[worst] < avg * 0.6f) {
                    out += Insight("📉", "‘${h.name}’ slips on ${weekdayName(worst)}s — that's your weak day. Plan for it.", 70)
                }
            }
        }

        // 3. Habit ↔ habit co-occurrence — stacking candidates.
        for (i in active.indices) for (j in active.indices) {
            if (i == j) continue
            val a = active[i]; val b = active[j]
            if (a.habitType == "break" || b.habitType == "break") continue
            val da = doneByHabit.getValue(a); val db = doneByHabit.getValue(b)
            if (db.size < 8) continue
            val both = da.count { it in db }
            val p = both.toFloat() / db.size
            if (p >= 0.75f && both >= 6) {
                out += Insight("🔗", "You do ‘${a.name}’ on ${(p * 100).toInt()}% of days you do ‘${b.name}’. Stack them?", 60 + (p * 20).toInt())
            }
        }

        // 4. Habit ↔ task productivity — the cross-module edge nobody else has.
        val tasksByDay = HashMap<Long, Int>()
        tasks.forEach { t ->
            val at = t.completedAt ?: return@forEach
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
            if (t.completed && today - day in 0..90) tasksByDay[day] = (tasksByDay[day] ?: 0) + 1
        }
        if (tasksByDay.isNotEmpty()) {
            active.forEach { h ->
                if (h.habitType == "break") return@forEach
                val done = doneByHabit.getValue(h)
                val window = (0 until 90).map { today - it }
                val onDays = window.filter { it in done }
                val offDays = window.filter { it !in done && HabitStats.isExpectedDay(h, it) }
                if (onDays.size >= 8 && offDays.size >= 8) {
                    val onAvg = onDays.map { tasksByDay[it] ?: 0 }.average()
                    val offAvg = offDays.map { tasksByDay[it] ?: 0 }.average()
                    if (offAvg >= 0.3 && onAvg >= offAvg * 1.25) {
                        val pct = (((onAvg - offAvg) / offAvg) * 100).toInt().coerceAtMost(400)
                        out += Insight("⚡", "You finish $pct% more tasks on days you do ‘${h.name}’.", 88)
                    }
                }
            }
        }

        // 5. A steady encouragement: the strongest current habit.
        val strongest = active.maxByOrNull { h ->
            HabitStats.strength(h, doneByHabit.getValue(h), skipByHabit.getValue(h), relapseByHabit.getValue(h), today)
        }
        if (strongest != null) {
            val s = HabitStats.strength(strongest, doneByHabit.getValue(strongest), skipByHabit.getValue(strongest), relapseByHabit.getValue(strongest), today)
            if (s >= 55) out += Insight("💪", "‘${strongest.name}’ is your strongest habit right now — ${s}/100. Lean on it.", 40)
        }

        return out.sortedByDescending { it.priority }.distinctBy { it.text }.take(max)
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
    private fun weekdayName(idx0: Int): String =
        java.time.DayOfWeek.of(idx0 + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
}
