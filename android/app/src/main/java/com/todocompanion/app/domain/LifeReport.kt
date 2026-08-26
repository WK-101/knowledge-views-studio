package com.todocompanion.app.domain

import com.todocompanion.app.domain.habit.HabitStats
import java.time.Instant
import java.time.LocalDate

/**
 * Ω4 — the annual life report: a self-contained HTML "year in review" rendered entirely on-device
 * from all three modules, yours to keep or share. Spotify-Wrapped energy without a byte leaving the
 * phone — impossible for any app whose data lives on someone else's server.
 */
object LifeReport {

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun hrs(min: Int): String = when {
        min <= 0 -> "0"
        min < 60 -> "${min}m"
        else -> "%.1f".format(min / 60.0).removeSuffix(".0") + "h"
    }

    fun buildHtml(year: Int, ctx: OmegaContext): String {
        val zone = ctx.zone
        val startDay = LocalDate.of(year, 1, 1).toEpochDay()
        val endDay = LocalDate.of(year, 12, 31).toEpochDay()
        val yearDays = (startDay..endDay).toSet()
        fun millisOf(d0: Long, d1: Long): Pair<Long, Long> {
            val s = LocalDate.ofEpochDay(d0).atStartOfDay(zone).toInstant().toEpochMilli()
            val e = LocalDate.ofEpochDay(d1 + 1).atStartOfDay(zone).toInstant().toEpochMilli()
            return s to e
        }
        val (winStart, winEnd) = millisOf(startDay, endDay)

        val tasksDone = ctx.tasks.count { t ->
            t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in yearDays } == true
        }
        val checkins = ctx.checkins.count { c ->
            c.epochDay in yearDays && c.status == "done" &&
                ctx.habits.firstOrNull { it.id == c.habitId }?.let { HabitStats.meetsGoal(it, c.count) } == true
        }
        val focusMin = ctx.focus.filter { it.epochDay in yearDays }.sumOf { it.minutes }
        val trackedMin = TimeTracking.totalMinutes(ctx.timeEntries, winStart, winEnd, ctx.now)

        val byAct = TimeTracking.totalsByActivity(ctx.timeEntries, winStart, winEnd, ctx.now)
            .sortedByDescending { it.minutes }.take(6)
        val actMax = (byAct.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)

        val bestHabits = habitStrengths(ctx.habits, ctx.checkins, minOf(endDay, ctx.today))
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(5)

        // Month-by-month tracked minutes (falls back to tasks-completed when there's no time data).
        val useTasks = trackedMin == 0 && tasksDone > 0
        val monthly = (1..12).map { m ->
            val md0 = LocalDate.of(year, m, 1)
            val md1 = md0.withDayOfMonth(md0.lengthOfMonth())
            if (useTasks) {
                val d = (md0.toEpochDay()..md1.toEpochDay()).toSet()
                ctx.tasks.count { t -> t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay() in d } == true }
            } else {
                val (s, e) = millisOf(md0.toEpochDay(), md1.toEpochDay())
                TimeTracking.totalMinutes(ctx.timeEntries, s, e, ctx.now)
            }
        }
        val monthMax = (monthly.maxOrNull() ?: 1).coerceAtLeast(1)
        val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")

        val sb = StringBuilder()
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Your ").append(year).append("</title><style>")
        sb.append("""
          :root{--bg:#FBFAF7;--card:#fff;--ink:#211B30;--muted:#6C6482;--accent:#6650A4;--soft:#EBE5F7;--hair:#E7E2EE}
          @media (prefers-color-scheme:dark){:root{--bg:#141019;--card:#1C1726;--ink:#EDE8F5;--muted:#A79EBE;--accent:#B9A6EC;--soft:#2A2340;--hair:#2C2539}}
          *{box-sizing:border-box}
          body{margin:0;background:var(--bg);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;line-height:1.5}
          .wrap{max-width:760px;margin:0 auto;padding:32px 20px 64px}
          .eyebrow{font-size:12px;letter-spacing:.18em;text-transform:uppercase;color:var(--accent);font-weight:700}
          h1{font-size:44px;letter-spacing:-.02em;margin:8px 0 4px}
          .sub{color:var(--muted);margin:0 0 26px}
          .grid{display:grid;grid-template-columns:repeat(2,1fr);gap:12px;margin-bottom:26px}
          .stat{background:var(--card);border:1px solid var(--hair);border-radius:16px;padding:18px}
          .stat .n{font-size:32px;font-weight:800;letter-spacing:-.02em}
          .stat .k{color:var(--muted);font-size:13px;margin-top:4px}
          .card{background:var(--card);border:1px solid var(--hair);border-radius:16px;padding:18px;margin-bottom:16px}
          h2{font-size:16px;margin:0 0 12px}
          .bar{display:flex;align-items:center;gap:10px;margin:7px 0}
          .bar .lab{width:120px;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
          .bar .track{flex:1;height:12px;background:var(--soft);border-radius:6px;overflow:hidden}
          .bar .fill{height:12px;background:var(--accent);border-radius:6px}
          .bar .val{width:52px;text-align:right;font-size:12px;color:var(--muted)}
          .months{display:flex;align-items:flex-end;gap:4px;height:96px;margin-top:6px}
          .months .col{flex:1;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;gap:5px}
          .months .mb{width:100%;background:var(--accent);border-radius:4px 4px 0 0;min-height:2px}
          .months .ml{font-size:10px;color:var(--muted)}
          .foot{color:var(--muted);font-size:12px;text-align:center;margin-top:26px}
        """.trimIndent())
        sb.append("</style></head><body><div class=\"wrap\">")
        sb.append("<div class=\"eyebrow\">Your year in review</div>")
        sb.append("<h1>").append(year).append("</h1>")
        sb.append("<p class=\"sub\">A private, on-device recap across tasks, habits and time.</p>")

        // Headline stats.
        sb.append("<div class=\"grid\">")
        fun stat(n: String, k: String) { sb.append("<div class=\"stat\"><div class=\"n\">").append(n).append("</div><div class=\"k\">").append(k).append("</div></div>") }
        stat(tasksDone.toString(), "tasks completed")
        stat(checkins.toString(), "habit check-ins kept")
        stat(hrs(trackedMin), "hours tracked")
        stat(hrs(focusMin), "hours focused")
        sb.append("</div>")

        // Top activities.
        if (byAct.isNotEmpty()) {
            sb.append("<div class=\"card\"><h2>Where your time went</h2>")
            byAct.forEach { at ->
                val a = ctx.activities.firstOrNull { it.id == at.activityId }
                val name = ((a?.emoji?.plus(" ")) ?: "") + (a?.name ?: "—")
                val pct = (at.minutes * 100 / actMax).coerceIn(2, 100)
                sb.append("<div class=\"bar\"><div class=\"lab\">").append(esc(name)).append("</div>")
                    .append("<div class=\"track\"><div class=\"fill\" style=\"width:").append(pct).append("%\"></div></div>")
                    .append("<div class=\"val\">").append(hrs(at.minutes)).append("</div></div>")
            }
            sb.append("</div>")
        }

        // Monthly rhythm.
        if (monthly.any { it > 0 }) {
            sb.append("<div class=\"card\"><h2>").append(if (useTasks) "Tasks by month" else "Tracked time by month").append("</h2><div class=\"months\">")
            monthly.forEachIndexed { i, v ->
                val h = (v * 90 / monthMax).coerceIn(2, 90)
                sb.append("<div class=\"col\"><div class=\"mb\" style=\"height:").append(h).append("px\"></div><div class=\"ml\">").append(monthLabels[i]).append("</div></div>")
            }
            sb.append("</div></div>")
        }

        // Strongest habits.
        if (bestHabits.isNotEmpty()) {
            sb.append("<div class=\"card\"><h2>Habits you held</h2>")
            bestHabits.forEach { (h, s) ->
                sb.append("<div class=\"bar\"><div class=\"lab\">").append(esc(h.name)).append("</div>")
                    .append("<div class=\"track\"><div class=\"fill\" style=\"width:").append(s.coerceIn(2, 100)).append("%\"></div></div>")
                    .append("<div class=\"val\">").append(s).append("%</div></div>")
            }
            sb.append("</div>")
        }

        sb.append("<div class=\"foot\">Generated on your device — nothing left the phone.</div>")
        sb.append("</div></body></html>")
        return sb.toString()
    }
}
