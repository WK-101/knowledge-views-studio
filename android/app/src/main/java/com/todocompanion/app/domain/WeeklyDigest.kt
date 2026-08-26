package com.todocompanion.app.domain

import com.todocompanion.app.data.entity.FocusSessionEntity
import com.todocompanion.app.data.entity.HabitCheckinEntity
import com.todocompanion.app.data.entity.HabitEntity
import com.todocompanion.app.data.entity.TaskEntity
import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.ZoneId

/**
 * R2 — the weekly "state of you" digest. Reads across BOTH halves of the store (habits, tasks, focus)
 * and narrates the last 7 days versus the 7 before it, entirely on-device. One honest snapshot the
 * Momentum screen and the weekly notification both render from.
 *
 * Every delta here is an exact count over two real windows — never an estimate — so the arrows can be
 * trusted. The single subjective element is the closing takeaway line, which is derived, not invented.
 */
object WeeklyDigest {

    data class Metric(val label: String, val value: String, val delta: Int, val deltaUnit: String = "")
    data class Digest(
        val momentum: Int,
        val metrics: List<Metric>,
        val bestHabit: String?,       // strongest active build habit this week
        val slippingHabit: String?,   // active build habit with the lowest strength (room to grow)
        val headline: String,         // one-line "state of you"
        val takeaway: String,         // one concrete nudge
        val hasData: Boolean,
    )

    fun compute(
        habits: List<HabitEntity>,
        checkins: List<HabitCheckinEntity>,
        tasks: List<TaskEntity>,
        focus: List<FocusSessionEntity>,
        momentum: Int,
        today: Long,
        zone: ZoneId,
        timeThisWeek: Int = 0,   // T6: tracked minutes this week / last week (0,0 ⇒ no Time row)
        timeLastWeek: Int = 0,
    ): Digest {
        val thisWeek = ((today - 6)..today).toSet()
        val lastWeek = ((today - 13)..(today - 7)).toSet()
        val active = habits.filter { !it.archived && !it.paused && it.habitType != "break" }

        // Check-ins (goal-meeting "done" days) this week vs last.
        fun checkinsIn(days: Set<Long>) = checkins.count { c ->
            c.epochDay in days && c.status == "done" &&
                habits.firstOrNull { it.id == c.habitId }?.let { HabitStats.meetsGoal(it, c.count) } == true
        }
        val ciNow = checkinsIn(thisWeek); val ciPrev = checkinsIn(lastWeek)

        // Tasks completed, by completion timestamp.
        fun tasksIn(days: Set<Long>) = tasks.count { t ->
            t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in days } == true
        }
        val tNow = tasksIn(thisWeek); val tPrev = tasksIn(lastWeek)

        // Focus minutes.
        fun focusIn(days: Set<Long>) = focus.filter { it.epochDay in days }.sumOf { it.minutes }
        val fNow = focusIn(thisWeek); val fPrev = focusIn(lastWeek)

        // Strength ranking of active build habits (as of today).
        val strengths = active.map { h ->
            val hc = checkins.filter { it.habitId == h.id }
            val done = hc.filter { it.status == "done" && HabitStats.meetsGoal(h, it.count) }.map { it.epochDay }.toSet()
            val skip = hc.filter { it.status == "skip" }.map { it.epochDay }.toSet()
            val rel = hc.filter { HabitStats.isRelapse(h, it.count) }.map { it.epochDay }.toSet()
            h to HabitStats.strength(h, done, skip, rel, today)
        }
        val best = strengths.maxByOrNull { it.second }
        val slipping = strengths.filter { it.second < 60 }.minByOrNull { it.second }

        val hasData = active.isNotEmpty() || tasks.any { it.completedAt != null } || focus.isNotEmpty()

        val metrics = buildList {
            add(Metric("Check-ins", ciNow.toString(), ciNow - ciPrev))
            add(Metric("Tasks done", tNow.toString(), tNow - tPrev))
            add(Metric("Focus", "${fNow}m", fNow - fPrev, "m"))
            // T6: fold tracked time in when there's any (Time module on with data).
            if (timeThisWeek > 0 || timeLastWeek > 0)
                add(Metric("Time", "${timeThisWeek}m", timeThisWeek - timeLastWeek, "m"))
        }

        val headline = when {
            !hasData -> "Your week is a blank page — start one habit or finish one task and this fills in."
            momentum >= 75 -> "A strong week — consistency is carrying across habits, tasks and focus."
            momentum >= 45 -> "A steady week — you're a few nudges from a great one."
            else -> "A rebuilding week — small wins now move this the fastest."
        }

        // Choose the most useful single nudge from what actually changed.
        val takeaway = when {
            !hasData -> "Tap Capture and add the first thing on your mind."
            slipping != null -> "“${slipping.first.name}” is at ${slipping.second}% — one check-in tomorrow is the cheapest point you'll gain."
            ciNow < ciPrev -> "Check-ins dipped ${ciPrev - ciNow} vs last week — protect the one habit you care about most."
            tNow > tPrev && fNow >= fPrev -> "More done and more focus than last week — bank it with a short weekly review."
            best != null -> "“${best.first.name}” leads at ${best.second}% — anchor a newer habit right after it."
            else -> "Keep logging — the cross-habit links sharpen with another week of data."
        }

        return Digest(
            momentum = momentum,
            metrics = metrics,
            bestHabit = best?.let { "${it.first.name} · ${it.second}%" },
            slippingHabit = slipping?.let { "${it.first.name} · ${it.second}%" },
            headline = headline,
            takeaway = takeaway,
            hasData = hasData,
        )
    }
}
