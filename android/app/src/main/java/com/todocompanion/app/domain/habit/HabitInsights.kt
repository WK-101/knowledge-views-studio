package com.todocompanion.app.domain.habit

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A one-tap action the coach can offer alongside a finding (L1: insights that act, not just observe). */
sealed interface InsightAction {
    /** Open this habit's detail. */
    data class Open(val habitId: String) : InsightAction
    /** Stack [childId] after [anchorId] (set the anchor). */
    data class Stack(val childId: String, val anchorId: String, val childName: String, val anchorName: String) : InsightAction
}

/** One plain-language finding surfaced to the user. [priority] ranks how interesting it is (higher first). */
data class Insight(val emoji: String, val text: String, val priority: Int, val action: InsightAction? = null)

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
                out += Insight("🔥", "You're $gap ${plural(gap, "day", "days")} from your best ‘${h.name}’ streak ($best). Protect it today.", 100 - gap, InsightAction.Open(h.id))
            } else if (best >= 7 && cur == best && cur > 0) {
                out += Insight("🏆", "‘${h.name}’ is at your best streak ever — $best ${plural(best, "day", "days")}. Keep it alive.", 96, InsightAction.Open(h.id))
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
                    out += Insight("📉", "‘${h.name}’ slips on ${weekdayName(worst)}s — that's your weak day. Plan for it.", 70, InsightAction.Open(h.id))
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
            if (p >= 0.75f && both >= 6 && a.anchorHabitId != b.id) {
                out += Insight("🔗", "You do ‘${a.name}’ on ${(p * 100).toInt()}% of days you do ‘${b.name}’. Stack them?", 60 + (p * 20).toInt(),
                    InsightAction.Stack(a.id, b.id, a.name, b.name))
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
                        out += Insight("⚡", "You finish $pct% more tasks on days you do ‘${h.name}’.", 88, InsightAction.Open(h.id))
                    }
                }
            }
        }

        // 5. L2 — keystone habit: the one whose done-days best predict a "good day" (more tasks done
        //    and more OTHER habits done). Charles Duhigg's idea, computed on data only we hold.
        if (active.size >= 2 && tasksByDay.isNotEmpty()) {
            val allDone = active.associateWith { doneByHabit.getValue(it) }
            var bestHabit: HabitEntity? = null; var bestLift = 0.0
            active.forEach { h ->
                if (h.habitType == "break") return@forEach
                val done = allDone.getValue(h)
                val window = (0 until 60).map { today - it }
                val onD = window.filter { it in done }
                val offD = window.filter { it !in done }
                if (onD.size < 8 || offD.size < 8) return@forEach
                fun goodness(day: Long): Double {
                    val t = (tasksByDay[day] ?: 0).toDouble()
                    val others = active.count { it.id != h.id && day in allDone.getValue(it) }.toDouble()
                    return t + others
                }
                val onAvg = onD.map { goodness(it) }.average()
                val offAvg = offD.map { goodness(it) }.average()
                val lift = onAvg - offAvg
                if (lift > bestLift) { bestLift = lift; bestHabit = h }
            }
            bestHabit?.let { k -> if (bestLift >= 0.8) out += Insight("🗝️", "‘${k.name}’ looks like your keystone — your best days tend to follow it. Guard this one.", 92, InsightAction.Open(k.id)) }
        }

        // 6. A steady encouragement: the strongest current habit.
        val strongest = active.maxByOrNull { h ->
            HabitStats.strength(h, doneByHabit.getValue(h), skipByHabit.getValue(h), relapseByHabit.getValue(h), today)
        }
        if (strongest != null) {
            val s = HabitStats.strength(strongest, doneByHabit.getValue(strongest), skipByHabit.getValue(strongest), relapseByHabit.getValue(strongest), today)
            if (s >= 55) out += Insight("💪", "‘${strongest.name}’ is your strongest habit right now — ${s}/100. Lean on it.", 40, InsightAction.Open(strongest.id))
        }

        // 7. N3 — reminder tuning: a scheduled habit frequently missed at its set reminder time.
        active.forEach { h ->
            if (h.habitType == "break" || h.reminderTimes.isBlank()) return@forEach
            val expected = (0 until 30).map { today - it }.count { HabitStats.isExpectedDay(h, it) }
            if (expected >= 8) {
                val rate = HabitStats.rate(h, doneByHabit.getValue(h), skipByHabit.getValue(h), today, 30)
                if (rate in 0.05f..0.5f) {
                    val t = h.reminderTimes.split(",").mapNotNull { it.trim().toIntOrNull() }.minOrNull()
                    val tl = t?.let { " (${"%02d:%02d".format(it / 60, it % 60)})" } ?: ""
                    out += Insight("⏰", "‘${h.name}’ gets missed a lot at its reminder$tl — try a time that better fits your day.", 50, InsightAction.Open(h.id))
                }
            }
        }

        return out.sortedByDescending { it.priority }.distinctBy { it.text }.take(max)
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many
    private fun weekdayName(idx0: Int): String =
        java.time.DayOfWeek.of(idx0 + 1).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())

    // ---- M3: the daily coach brief ----
    /** One prioritised move in the morning brief. */
    data class BriefMove(val emoji: String, val text: String, val action: InsightAction? = null)
    /** A proactive morning card: where today stands + the highest-leverage moves, aimed at the start of the day. */
    data class DailyBrief(val headline: String, val sub: String, val moves: List<BriefMove>)

    /**
     * The flagship of the coach: one card for the start of the day. It reads today's due habits from the
     * shared store, then aims the insight engine at this morning — the keystone first, the streak most at
     * risk right now, a stacking cue, then whatever else is most worth knowing. Pure and offline.
     */
    fun dailyBrief(
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        tasks: List<TaskEntity>,
        today: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        maxMoves: Int = 4,
    ): DailyBrief? {
        val active = habits.filter { !it.archived && !it.paused }
        if (active.isEmpty()) return null
        val doneByHabit = active.associateWith { doneDays(it, checkins) }
        fun skips(h: HabitEntity) = checkins.filter { it.habitId == h.id && it.status == "skip" }.map { it.epochDay }.toSet()
        fun relapses(h: HabitEntity) = checkins.filter { it.habitId == h.id && HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
        fun todayCount(h: HabitEntity) = checkins.firstOrNull { it.habitId == h.id && it.epochDay == today }?.count ?: 0

        val due = active.filter { HabitStats.dueToday(it, today, doneByHabit.getValue(it), todayCount(it)) }
        val expected = active.filter { it.habitType != "break" && HabitStats.isExpectedDay(it, today) }
        val doneToday = expected.count { HabitStats.meetsGoal(it, todayCount(it)) }

        val moves = ArrayList<BriefMove>()
        val insights = compute(habits, checkins, tasks, today, zone, max = 8)
        // Keystone leads — start the day with the habit that best predicts a good one.
        insights.firstOrNull { it.emoji == "🗝️" }?.let { moves += BriefMove(it.emoji, it.text, it.action) }
        // The streak most at risk right now: due today, still unchecked, and worth protecting.
        due.map { h -> h to HabitStats.currentStreak(h, doneByHabit.getValue(h), skips(h), relapses(h), today) }
            .filter { it.second >= 3 }.maxByOrNull { it.second }
            ?.let { (h, s) -> if (moves.none { m -> (m.action as? InsightAction.Open)?.habitId == h.id })
                moves += BriefMove("🔥", "‘${h.name}’ is on a $s-day streak and still due today — lock it in first.", InsightAction.Open(h.id)) }
        // A stacking cue if one is strong.
        insights.firstOrNull { it.action is InsightAction.Stack }?.let { if (moves.none { m -> m.text == it.text }) moves += BriefMove(it.emoji, it.text, it.action) }
        // Fill the rest from the ranked insights.
        for (i in insights) { if (moves.size >= maxMoves) break; if (moves.none { it.text == i.text }) moves += BriefMove(i.emoji, i.text, i.action) }

        val headline = when {
            expected.isEmpty() && due.isEmpty() -> "Nothing scheduled today"
            due.isEmpty() -> "All caught up — every habit done 🎉"
            else -> "$doneToday of ${expected.size} habits done"
        }
        val sub = if (due.isNotEmpty())
            "Still to go: " + due.take(3).joinToString(", ") { it.name } + (if (due.size > 3) " +${due.size - 3} more" else "")
        else "You're clear for the day."
        return DailyBrief(headline, sub, moves.take(maxMoves))
    }
}
