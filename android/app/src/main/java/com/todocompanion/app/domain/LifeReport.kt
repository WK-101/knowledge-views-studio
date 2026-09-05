package com.todocompanion.app.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ω4 — the annual life report: a self-contained HTML "year in review" rendered entirely on-device,
 * yours to keep or share. Spotify-Wrapped energy without a byte leaving the phone — impossible for any
 * app whose data lives on someone else's server.
 *
 * Track 1 (Unify) — this is now pure HTML templating: every NUMBER it shows is read from a
 * [YearReviewed.Recap] the caller computed with the one year spine over the canonical
 * [YearReviewed.calendarYearWindow]. It no longer folds raw entities itself, so the HTML report, the
 * "Year, reviewed" screen and The Record's Wrapped all agree exactly — and, because the spine carries
 * the felt lane too, the report can now show how the year *felt* (avg rating/mood, the feeling named
 * most) alongside tasks/habits/time.
 */
object LifeReport {

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun hrs(min: Int): String = when {
        min <= 0 -> "0"
        min < 60 -> "${min}m"
        else -> "%.1f".format(min / 60.0).removeSuffix(".0") + "h"
    }

    private fun oneDp(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun buildHtml(year: Int, recap: YearReviewed.Recap): String {
        val monthLabels = listOf("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
        // The monthly rhythm is spine-fed (accomplishments per calendar month across the window). Labels come
        // from the window's first month so a partial current year (Jan..today) still lines up.
        val firstMonthIdx = if (recap.monthlyDone.isNotEmpty()) LocalDate.ofEpochDay(recap.startDay).monthValue - 1 else 0
        val monthly = recap.monthlyDone
        val monthMax = (monthly.maxOrNull() ?: 1).coerceAtLeast(1)

        val actMax = (recap.topActivities.maxOfOrNull { it.minutes } ?: 1).coerceAtLeast(1)

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
          .felt{background:var(--card);border:1px solid var(--hair);border-radius:16px;padding:18px;margin-bottom:16px;display:flex;gap:20px;flex-wrap:wrap}
          .felt .fitem .fn{font-size:24px;font-weight:800}
          .felt .fitem .fk{color:var(--muted);font-size:13px}
          .quote{font-size:18px;line-height:1.4;margin:0}
          .qmeta{color:var(--muted);font-size:12px;margin-top:8px}
          .foot{color:var(--muted);font-size:12px;text-align:center;margin-top:26px}
        """.trimIndent())
        sb.append("</style></head><body><div class=\"wrap\">")
        sb.append("<div class=\"eyebrow\">Your year in review</div>")
        sb.append("<h1>").append(year).append("</h1>")
        sb.append("<p class=\"sub\">A private, on-device recap across how your year went — and how it felt.</p>")

        // Headline stats — all read straight off the spine [YearReviewed.Recap].
        sb.append("<div class=\"grid\">")
        fun stat(n: String, k: String) { sb.append("<div class=\"stat\"><div class=\"n\">").append(n).append("</div><div class=\"k\">").append(k).append("</div></div>") }
        stat(recap.tasksFinished.toString(), "tasks completed")
        stat(recap.habitDaysKept.toString(), "habit check-ins kept")
        stat(hrs(recap.trackedMinutes), "hours tracked")
        stat(hrs(recap.focusMinutesDone), "hours focused")
        sb.append("</div>")

        // How the year felt — the spine's felt lane (avg rating/mood, the feeling named most).
        val feltItems = buildList {
            if (recap.ratedDays > 0) add(oneDp(recap.avgRating) + "★" to "average day (${recap.ratedDays} rated)")
            if (recap.moodDays > 0) add(oneDp(recap.avgMood) + "★" to "evening mood (${recap.moodDays} days)")
            if (recap.topEmotionWord.isNotBlank() && recap.topEmotionCount >= 3)
                add(esc(recap.topEmotionWord) to "felt most · ${recap.topEmotionCount} days")
        }
        if (feltItems.isNotEmpty()) {
            sb.append("<div class=\"felt\">")
            feltItems.forEach { (n, k) ->
                sb.append("<div class=\"fitem\"><div class=\"fn\">").append(n).append("</div><div class=\"fk\">").append(k).append("</div></div>")
            }
            sb.append("</div>")
        }

        // Top activities.
        if (recap.topActivities.isNotEmpty()) {
            sb.append("<div class=\"card\"><h2>Where your time went</h2>")
            recap.topActivities.forEach { a ->
                val name = ((a.emoji?.plus(" ")) ?: "") + a.name
                val pct = (a.minutes * 100 / actMax).coerceIn(2, 100)
                sb.append("<div class=\"bar\"><div class=\"lab\">").append(esc(name)).append("</div>")
                    .append("<div class=\"track\"><div class=\"fill\" style=\"width:").append(pct).append("%\"></div></div>")
                    .append("<div class=\"val\">").append(hrs(a.minutes)).append("</div></div>")
            }
            sb.append("</div>")
        }

        // Monthly rhythm — accomplishments per month, straight from the spine.
        if (monthly.any { it > 0 }) {
            sb.append("<div class=\"card\"><h2>Your rhythm by month</h2><div class=\"months\">")
            monthly.forEachIndexed { i, v ->
                val h = (v * 90 / monthMax).coerceIn(2, 90)
                val label = monthLabels[(firstMonthIdx + i) % 12]
                sb.append("<div class=\"col\"><div class=\"mb\" style=\"height:").append(h).append("px\"></div><div class=\"ml\">").append(label).append("</div></div>")
            }
            sb.append("</div></div>")
        }

        // Habits held — consistency (kept / expected), matching the "Year, reviewed" screen exactly.
        if (recap.habitConsistency.isNotEmpty()) {
            sb.append("<div class=\"card\"><h2>Habits you held</h2>")
            recap.habitConsistency.forEach { h ->
                val name = ((h.emoji?.plus(" ")) ?: "") + h.name
                sb.append("<div class=\"bar\"><div class=\"lab\">").append(esc(name)).append("</div>")
                    .append("<div class=\"track\"><div class=\"fill\" style=\"width:").append(h.pct.coerceIn(2, 100)).append("%\"></div></div>")
                    .append("<div class=\"val\">").append(h.pct).append("%</div></div>")
            }
            sb.append("</div>")
        }

        // A standout highlight, if the year has one.
        if (recap.highlightText.isNotBlank()) {
            sb.append("<div class=\"card\"><h2>A highlight</h2>")
            sb.append("<p class=\"quote\">“").append(esc(recap.highlightText)).append("”</p>")
            if (recap.highlightEpochDay > 0) {
                val d = LocalDate.ofEpochDay(recap.highlightEpochDay)
                val stars = if (recap.highlightRating in 1..5) "  ·  " + "★".repeat(recap.highlightRating) else ""
                sb.append("<div class=\"qmeta\">").append(d.dayOfMonth).append(" ")
                    .append(d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())).append(" ").append(d.year)
                    .append(stars).append("</div>")
            }
            sb.append("</div>")
        }

        sb.append("<div class=\"foot\">Generated on your device — nothing left the phone.</div>")
        sb.append("</div></body></html>")
        return sb.toString()
    }
}
