package com.todocompanion.app.domain.done

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * R32 — the "Living Record" read-side: turns the finished-work feed into a database you look back on and
 * mine. Everything here is a PURE function over the already-built [Accomplishment] feed (plus tags for the
 * skills roll-up). No new table, no network, no LLM — just on-device heuristics with min-support guards so
 * an insight only shows once it's actually meaningful.
 *
 *  1. Done heatmap   — a GitHub-style day grid of how much you finished.
 *  2. Milestone ledger — earned totals/streak/focus badges, each with the date it was reached.
 *  4. Pattern insights — best weekday, peak hour, standout list, focus↔finish lift.
 *  8. Skills ledger  — finished work rolled up by tag (falling back to list) into evidence-backed areas.
 */
object LivingRecord {

    // ── 1 · Done heatmap ─────────────────────────────────────────────────────────────────────────
    data class HeatCell(val count: Int, val minutes: Int)

    /** Per-day intensity: how many things finished, and how many focus minutes, that day. */
    fun heatmap(items: List<Accomplishment>): Map<Long, HeatCell> =
        items.groupBy { it.epochDay }.mapValues { (_, day) ->
            HeatCell(day.size, day.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin })
        }

    // ── 2 · Milestone ledger ─────────────────────────────────────────────────────────────────────
    data class Milestone(
        val key: String, val emoji: String, val label: String, val detail: String,
        val reachedEpochDay: Long?, val reached: Boolean, val progress: Float, // 0..1 toward next
    )

    private val COUNT_TIERS = listOf(1, 10, 25, 50, 100, 250, 500, 1000, 2500)
    private val FOCUS_HOUR_TIERS = listOf(10, 25, 50, 100, 250, 500)
    private val WIN_TIERS = listOf(1, 10, 25, 50, 100)

    /** The full ledger: reached badges (with the day each was earned) plus the single next target per track. */
    fun milestones(items: List<Accomplishment>, today: LocalDate = LocalDate.now()): List<Milestone> {
        if (items.isEmpty()) return emptyList()
        val asc = items.sortedBy { it.whenMillis }
        val out = ArrayList<Milestone>()

        // Total things finished — the day the Nth was reached is asc[N-1].
        tierMilestones(asc.size, COUNT_TIERS, asc.map { it.epochDay }, "done", "🏁") { n -> "$n finished" }.let(out::addAll)
        // Focus hours — walk the cumulative minutes to find when each hour-tier was crossed.
        run {
            val focus = asc.filter { it.kind == DoneKind.FOCUS && it.durationMin > 0 }
            var cum = 0
            val crossDay = HashMap<Int, Long>()
            focus.forEach { f -> val before = cum / 60; cum += f.durationMin; val after = cum / 60
                FOCUS_HOUR_TIERS.forEach { t -> if (before < t && after >= t && t !in crossDay) crossDay[t] = f.epochDay } }
            out += trackMilestones(cum / 60, FOCUS_HOUR_TIERS, crossDay, "focus", "🎯") { n -> "${n}h focused" }
        }
        // Wins flagged.
        run {
            val wins = asc.filter { it.isWin }
            out += tierMilestones(wins.size, WIN_TIERS, wins.map { it.epochDay }, "wins", "⭐") { n -> "$n wins" }
        }
        // Streaks (the running/longest active-day chains).
        val stats = DoneRecord.stats(items)
        if (stats.longestStreakDays >= 3)
            out += Milestone("streak-long", "🔥", "${stats.longestStreakDays}-day streak", "Longest run of active days", null, true, 0f)
        if (stats.currentStreakDays >= 2)
            out += Milestone("streak-now", "⚡", "${stats.currentStreakDays}-day streak, live", "Keep it going today", null, true, 0f)
        if (stats.bestDayCount >= 3)
            out += Milestone("best-day", "🚀", "Biggest day: ${stats.bestDayCount}", "Most ever finished in one day", stats.bestDayEpoch, true, 0f)
        return out
    }

    private inline fun tierMilestones(total: Int, tiers: List<Int>, ascDays: List<Long>, key: String, emoji: String, label: (Int) -> String): List<Milestone> {
        val crossDay = HashMap<Int, Long>()
        tiers.forEach { t -> if (total >= t && t <= ascDays.size) crossDay[t] = ascDays[t - 1] }
        return trackMilestones(total, tiers, crossDay, key, emoji, label)
    }

    private inline fun trackMilestones(total: Int, tiers: List<Int>, crossDay: Map<Int, Long>, key: String, emoji: String, label: (Int) -> String): List<Milestone> {
        val out = ArrayList<Milestone>()
        tiers.filter { total >= it }.forEach { t -> out += Milestone("$key-$t", emoji, label(t), "Reached", crossDay[t], true, 1f) }
        val next = tiers.firstOrNull { total < it }
        if (next != null) {
            val prev = tiers.lastOrNull { it <= total } ?: 0
            val prog = ((total - prev).toFloat() / (next - prev)).coerceIn(0f, 1f)
            out += Milestone("$key-next", emoji, label(next), "$total / $next", null, false, prog)
        }
        return out
    }

    // ── 4 · Pattern insights (heuristic, guarded) ────────────────────────────────────────────────
    data class Insight(val emoji: String, val text: String)

    fun insights(items: List<Accomplishment>, today: LocalDate = LocalDate.now()): List<Insight> {
        val out = ArrayList<Insight>()
        val taskish = items.filter { it.isTaskLike }
        val activeDays = items.map { it.epochDay }.toSet().size
        if (activeDays < 10 || items.size < 20) return out   // not enough signal yet

        // Best weekday by finishes.
        val byDow = items.groupBy { LocalDate.ofEpochDay(it.epochDay).dayOfWeek }
        byDow.maxByOrNull { it.value.size }?.let { (dow, list) ->
            if (list.size >= 5) out += Insight("📅", "You finish most on ${dow.getDisplayName(TextStyle.FULL, Locale.getDefault())}s.")
        }

        // Peak hour, from anything with a clock time.
        val timed = items.mapNotNull { it.minuteOfDay }.map { it / 60 }
        if (timed.size >= 20) {
            val h = timed.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            if (h != null) out += Insight("⏰", "Your most productive hour is around ${hourLabel(h)}.")
        }

        // Standout list.
        if (taskish.size >= 15) {
            val byList = taskish.groupingBy { it.listId }.eachCount().filterKeys { it != null }
            byList.maxByOrNull { it.value }?.let { (id, n) ->
                if (n >= taskish.size / 3 && n >= 5) out += Insight("📂", "A third of your finishes cluster in one list.")
            }
        }

        // Focus → finish lift: do you finish more on days you also logged focus?
        val focusDays = items.filter { it.kind == DoneKind.FOCUS }.map { it.epochDay }.toSet()
        if (focusDays.size >= 5) {
            val finishesByDay = taskish.groupBy { it.epochDay }.mapValues { it.value.size }
            val onFocus = finishesByDay.filterKeys { it in focusDays }.values
            val offFocus = finishesByDay.filterKeys { it !in focusDays }.values
            if (onFocus.isNotEmpty() && offFocus.isNotEmpty()) {
                val a = onFocus.average(); val b = offFocus.average()
                if (b > 0 && a >= b * 1.25) {
                    val pct = (((a - b) / b) * 100).toInt().coerceIn(1, 400)
                    out += Insight("🔗", "On days you log focus, you finish about $pct% more.")
                }
            }
        }

        // Weekend vs weekday rhythm.
        val weekend = items.count { val d = LocalDate.ofEpochDay(it.epochDay).dayOfWeek; d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY }
        if (items.size >= 30) {
            val weekendShare = weekend.toDouble() / items.size
            if (weekendShare < 0.12) out += Insight("💼", "You're a weekday finisher — weekends are for rest.")
            else if (weekendShare > 0.45) out += Insight("🌤️", "Weekends are when a lot of your work actually lands.")
        }
        return out
    }

    private fun hourLabel(h: Int): String = when {
        h == 0 -> "midnight"; h < 12 -> "$h AM"; h == 12 -> "noon"; else -> "${h - 12} PM"
    }

    // ── 8 · Skills ledger ────────────────────────────────────────────────────────────────────────
    data class Skill(val name: String, val fromTag: Boolean, val count: Int, val minutes: Int, val lastEpochDay: Long, val sampleIds: List<String>)

    /** Roll finished, task-like work into "skill" areas: grouped by tag when a task is tagged, otherwise by
     *  its list. Only areas with real evidence (>= [minCount] finishes) surface, so it reads as achievement,
     *  not noise. [tagsByTask] maps a task id → its tag NAMES; [listNameById] maps a list id → its name. */
    fun skills(items: List<Accomplishment>, tagsByTask: Map<String, List<String>>, listNameById: Map<String, String>, minCount: Int = 5): List<Skill> {
        data class Bucket(val fromTag: Boolean, val list: MutableList<Accomplishment> = ArrayList())
        val buckets = LinkedHashMap<String, Bucket>()
        items.asSequence().filter { it.isTaskLike }.forEach { a ->
            val tags = tagsByTask[a.refId].orEmpty().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) tags.forEach { t -> buckets.getOrPut("#$t") { Bucket(true) }.list.add(a) }
            else { val ln = a.listId?.let { listNameById[it] } ?: return@forEach; buckets.getOrPut(ln) { Bucket(false) }.list.add(a) }
        }
        return buckets.filterValues { it.list.size >= minCount }.map { (name, b) ->
            Skill(name, b.fromTag, b.list.size, b.list.sumOf { it.durationMin },
                b.list.maxOf { it.epochDay }, b.list.sortedByDescending { it.whenMillis }.take(3).map { it.refId })
        }.sortedByDescending { it.count }
    }
}
