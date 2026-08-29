package com.todocompanion.app.domain.done

import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.data.entity.TimeEntryEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Done Record (R27). A completed item isn't a dead archive line — it's evidence of something you did.
 * This is the ONE query surface that cross-references the four things the app already stores privately:
 * completed tasks (with [TaskEntity.completedAt]), habit check-ins that met their goal, finished focus
 * sessions, and completed goals/projects — into a single reverse-chronological record. It's pure and
 * derived: no new table, no new permission, nothing leaves the device. The feed, the brag document, the
 * trophy case, on-this-day and lifetime totals all read from here.
 */
enum class DoneKind { TASK, GOAL, PROJECT, HABIT, FOCUS }

/** One thing you finished, normalised across sources so the feed can render them uniformly. */
data class Accomplishment(
    val kind: DoneKind,
    val refId: String,               // task id · habit id · time-entry id
    val title: String,
    val emoji: String? = null,
    val colorArgb: Long? = null,
    val whenMillis: Long,
    val epochDay: Long,
    val minuteOfDay: Int? = null,
    val durationMin: Int = 0,
    val listId: String? = null,
    val isWin: Boolean = false,
    val outcome: String? = null,
    val praise: String? = null,
    val learned: String? = null,
    val mood: Int? = null,
) {
    val isTaskLike: Boolean get() = kind == DoneKind.TASK || kind == DoneKind.GOAL || kind == DoneKind.PROJECT
}

/** Lifetime + personal-best aggregates over the whole record. */
data class DoneStats(
    val totalTasks: Int = 0,
    val totalWins: Int = 0,
    val focusedMinutes: Int = 0,
    val habitCheckins: Int = 0,
    val goalsAchieved: Int = 0,
    val activeDays: Int = 0,
    val bestDayCount: Int = 0,
    val bestDayEpoch: Long? = null,
    val longestStreakDays: Int = 0,
    val currentStreakDays: Int = 0,
)

object DoneRecord {

    /** Build the whole accomplishment feed, newest first. Skips trashed items and un-timestamped completions. */
    fun build(
        tasks: List<TaskEntity>,
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        timeEntries: List<TimeEntryEntity>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Accomplishment> {
        val out = ArrayList<Accomplishment>(tasks.size)

        // Completed tasks, goals and projects — the completion timestamp is the accomplishment moment.
        tasks.asSequence()
            .filter { it.completed && !it.trashed && !it.abandoned && it.completedAt != null }
            .forEach { t ->
                val at = t.completedAt!!
                val d = Instant.ofEpochMilli(at).atZone(zone)
                val kind = when { t.isGoal -> DoneKind.GOAL; t.isProject -> DoneKind.PROJECT; else -> DoneKind.TASK }
                out += Accomplishment(
                    kind = kind, refId = t.id, title = t.title.ifBlank { "Untitled" },
                    colorArgb = t.colorArgb ?: t.flagColorArgb, whenMillis = at,
                    epochDay = d.toLocalDate().toEpochDay(), minuteOfDay = d.hour * 60 + d.minute,
                    durationMin = t.durationMin ?: 0, listId = t.listId,
                    isWin = t.winFlag, outcome = t.outcomeNote?.takeIf { it.isNotBlank() },
                    praise = t.praiseQuote?.takeIf { it.isNotBlank() }, learned = t.learnedNote?.takeIf { it.isNotBlank() },
                    mood = t.mood,
                )
            }

        // Habit check-ins that actually met the day's goal (a skip or a partial isn't an accomplishment).
        val habitById = habits.associateBy { it.id }
        checkins.asSequence()
            .filter { it.status == "done" }
            .forEach { c ->
                val h = habitById[c.habitId] ?: return@forEach
                if (!HabitStats.meetsGoal(h, c.count)) return@forEach
                val day = LocalDate.ofEpochDay(c.epochDay)
                val whenMs = day.atStartOfDay(zone).toInstant().toEpochMilli() + (c.doneAtMinute ?: 12 * 60) * 60_000L
                out += Accomplishment(
                    kind = DoneKind.HABIT, refId = h.id,
                    title = (h.emoji?.plus(" ") ?: "") + h.name, emoji = h.emoji, colorArgb = h.colorArgb,
                    whenMillis = whenMs, epochDay = c.epochDay, minuteOfDay = c.doneAtMinute,
                )
            }

        // Finished focus sessions off the one timeline (kind == "focus", with an end).
        timeEntries.asSequence()
            .filter { it.kind == "focus" && it.endMillis != null && it.endMillis!! > it.startMillis }
            .forEach { e ->
                val d = Instant.ofEpochMilli(e.startMillis).atZone(zone)
                val mins = ((e.endMillis!! - e.startMillis) / 60_000L).toInt().coerceAtLeast(1)
                val label = "Focused ${mins}m" + (e.note.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                out += Accomplishment(
                    kind = DoneKind.FOCUS, refId = e.id, title = label, emoji = "🎯",
                    whenMillis = e.startMillis, epochDay = d.toLocalDate().toEpochDay(),
                    minuteOfDay = d.hour * 60 + d.minute, durationMin = mins, listId = null,
                )
            }

        return out.sortedByDescending { it.whenMillis }
    }

    /** Lifetime totals + personal bests over an already-built feed. */
    fun stats(items: List<Accomplishment>): DoneStats {
        if (items.isEmpty()) return DoneStats()
        val byDay = items.groupBy { it.epochDay }
        val best = byDay.maxByOrNull { it.value.size }
        val days = byDay.keys.toSortedSet()
        // Longest run of consecutive active days, and the current run ending today (or yesterday).
        var longest = 0; var run = 0; var prev: Long? = null
        for (d in days) {
            run = if (prev != null && d == prev!! + 1) run + 1 else 1
            if (run > longest) longest = run
            prev = d
        }
        val today = LocalDate.now().toEpochDay()
        var current = 0; var cursor = if (days.contains(today)) today else today - 1
        while (days.contains(cursor)) { current++; cursor-- }
        return DoneStats(
            totalTasks = items.count { it.kind == DoneKind.TASK },
            totalWins = items.count { it.isWin },
            focusedMinutes = items.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin },
            habitCheckins = items.count { it.kind == DoneKind.HABIT },
            goalsAchieved = items.count { it.kind == DoneKind.GOAL || it.kind == DoneKind.PROJECT },
            activeDays = byDay.size,
            bestDayCount = best?.value?.size ?: 0,
            bestDayEpoch = best?.key,
            longestStreakDays = longest,
            currentStreakDays = current,
        )
    }

    /** "On this day": items finished on today's calendar date in a previous month or year. */
    fun onThisDay(items: List<Accomplishment>, today: LocalDate = LocalDate.now()): List<Accomplishment> =
        items.filter {
            val d = LocalDate.ofEpochDay(it.epochDay)
            d.isBefore(today) && d.dayOfMonth == today.dayOfMonth &&
                (d.year < today.year || d.monthValue != today.monthValue)
        }.sortedByDescending { it.whenMillis }

    // ---------- Phase 3–4: the receipt primitive, and everything built on it ----------

    /** R28 Phase 3 — one achievement-shaped line for a finished task (the atom of the brag doc / résumé). */
    fun receiptLine(a: Accomplishment, listName: String?): String {
        val b = StringBuilder("- ")
        if (a.isWin) b.append("⭐ ")
        b.append(a.title)
        if (a.durationMin > 0) b.append(" _(${a.durationMin}m)_")
        listName?.takeIf { it != "Inbox" }?.let { b.append(" · $it") }
        a.outcome?.let { b.append(" — $it") }
        a.praise?.let { b.append("  \n  > “$it”") }
        return b.toString()
    }

    /** R28 Phase 3 — résumé/CV bullet points from real finished work: impact-first, quantified where the
     *  data allows. Written from what you DID, not what you typed. */
    fun resumeMarkdown(items: List<Accomplishment>, listNameById: Map<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine("# Résumé lines")
        sb.appendLine()
        sb.appendLine("_Generated from your finished work — edit freely._")
        sb.appendLine()
        val tasks = items.filter { it.isTaskLike }
        // Lead with wins and outcome-carrying items — the strongest evidence.
        val strong = tasks.filter { it.isWin || it.outcome != null }.sortedByDescending { it.durationMin }
        if (strong.isNotEmpty()) {
            sb.appendLine("## Highlights")
            strong.take(20).forEach { a ->
                val name = a.listId?.let { listNameById[it] }
                val impact = a.outcome?.let { " — $it" } ?: ""
                val time = if (a.durationMin >= 60) " (${a.durationMin / 60}h invested)" else ""
                sb.appendLine("- ${a.title}$impact$time" + (name?.takeIf { it != "Inbox" }?.let { " · $it" } ?: ""))
            }
            sb.appendLine()
        }
        // Then a compact per-project tally so scope is legible.
        val byProject = tasks.groupBy { it.listId?.let { id -> listNameById[id] } ?: "Other" }.toList().sortedByDescending { it.second.size }
        sb.appendLine("## By area")
        byProject.take(12).forEach { (name, group) ->
            val mins = group.sumOf { it.durationMin }
            sb.appendLine("- **$name** — ${group.size} completed" + (if (mins >= 60) ", ${mins / 60}h" else ""))
        }
        return sb.toString()
    }

    /** R28 Phase 4 — the living archive: the whole record as a portable, offline "book of what I've done",
     *  grouped by year then project. */
    fun archiveMarkdown(items: List<Accomplishment>, listNameById: Map<String, String>, today: LocalDate = LocalDate.now()): String {
        val sb = StringBuilder()
        sb.appendLine("# The record — everything done")
        sb.appendLine()
        val s = stats(items)
        sb.appendLine("_${s.totalTasks} tasks · ${s.goalsAchieved} goals · ${s.focusedMinutes / 60}h focused · ${s.habitCheckins} habit days · ${s.totalWins} wins · ${s.activeDays} active days._")
        sb.appendLine()
        items.groupBy { LocalDate.ofEpochDay(it.epochDay).year }.toSortedMap(compareByDescending { it }).forEach { (year, ofYear) ->
            sb.appendLine("## $year")
            ofYear.filter { it.isTaskLike }.groupBy { it.listId?.let { id -> listNameById[id] } ?: "Other" }.toList().sortedBy { it.first }.forEach { (name, group) ->
                sb.appendLine("### $name")
                group.sortedByDescending { it.whenMillis }.forEach { sb.appendLine(receiptLine(it, name)) }
                sb.appendLine()
            }
            val habitDays = ofYear.count { it.kind == DoneKind.HABIT }
            val focus = ofYear.filter { it.kind == DoneKind.FOCUS }.sumOf { it.durationMin }
            if (habitDays > 0 || focus > 0) { sb.appendLine("_Also: $habitDays habit days, ${focus / 60}h focused._"); sb.appendLine() }
        }
        sb.appendLine("_Generated ${today} on-device. This record is yours, permanently and privately._")
        return sb.toString()
    }

    /** R28 Phase 3 — the honesty ledger: how your estimates compare to the time actually invested on the
     *  tasks you finished. [trackedByTask] is minutes tracked per task id. */
    data class LedgerRow(val label: String, val estimateMin: Int, val actualMin: Int) {
        val ratio: Float get() = if (estimateMin > 0) actualMin.toFloat() / estimateMin else 0f
    }
    data class Honesty(val overall: LedgerRow?, val worst: List<LedgerRow>)
    fun honesty(tasks: List<TaskEntity>, trackedByTask: Map<String, Int>, listNameById: Map<String, String>): Honesty {
        val rows = tasks.asSequence()
            .filter { it.completed && !it.trashed && (it.estimateMin ?: 0) > 0 }
            .mapNotNull { t ->
                val actual = trackedByTask[t.id] ?: t.durationMin ?: 0
                if (actual <= 0) null else LedgerRow(t.title.ifBlank { "Untitled" }, t.estimateMin!!, actual)
            }.toList()
        if (rows.isEmpty()) return Honesty(null, emptyList())
        val overall = LedgerRow("All estimated tasks", rows.sumOf { it.estimateMin }, rows.sumOf { it.actualMin })
        val worst = rows.sortedByDescending { kotlin.math.abs(it.ratio - 1f) }.take(4)
        return Honesty(overall, worst)
    }

    /** R28 Phase 4 — kind, evidence-based momentum: recovery and standout effort, not just streaks. */
    fun comeback(items: List<Accomplishment>, today: LocalDate = LocalDate.now()): List<String> {
        if (items.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val thisWeek = items.count { !LocalDate.ofEpochDay(it.epochDay).isBefore(today.minusDays(6)) }
        val lastWeek = items.count {
            val d = LocalDate.ofEpochDay(it.epochDay)
            d.isBefore(today.minusDays(6)) && !d.isBefore(today.minusDays(13))
        }
        if (thisWeek > lastWeek && lastWeek >= 0) out += "More done this week than last — $thisWeek vs $lastWeek."
        if (lastWeek <= 2 && thisWeek >= lastWeek + 3) out += "Bounced back after a quieter week. 💪"
        // The standout finish this month, by effort.
        val monthStart = today.withDayOfMonth(1).toEpochDay()
        val hardest = items.filter { it.isTaskLike && it.epochDay >= monthStart }.maxByOrNull { it.durationMin }
        if (hardest != null && hardest.durationMin >= 30) out += "Biggest finish this month: “${hardest.title}” (${hardest.durationMin}m)."
        return out
    }
}
