package com.todocompanion.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.todocompanion.app.domain.DayShareConfig
import com.todocompanion.app.domain.HabitDetail
import com.todocompanion.app.domain.PeriodShareConfig
import com.todocompanion.app.domain.ShareStyle
import com.todocompanion.app.domain.TaskDetail
import com.todocompanion.app.domain.TimeDetail
import kotlin.math.roundToInt

/**
 * R106 — renders a shareable "My day" card to a PNG entirely on-device (android.graphics, no Compose
 * capture, no network) and builds a plain-text equivalent. Privacy by construction: the caller decides
 * what goes in (task titles / reflection can be omitted), and the image is only shared if the user
 * picks a target. Reuses ProgressCard.saveAndShareUri + share for the file/FileProvider/ACTION_SEND path.
 *
 * The modern share system is modular and period-spanning: the day card ([renderShare]) and the week /
 * month / year roll-up card ([renderPeriodShare]) share one small vertical-stack layout engine
 * ([ShareCanvas]) and one set of visual primitives — the app's modern completion mark (a filled accent
 * disc + white check, like DoneTick), boxed tonal metric tiles (like StatTile), labelled lists and
 * paragraphs — and both branch on a [ShareStyle]: PERSONAL (the warm dark card) or PROFESSIONAL (a clean,
 * near-white "proof of work" document). Every card is variable-height, guarded end-to-end, and never
 * blank; an enabled-but-empty section simply draws nothing.
 */
object DayCard {
    data class Data(
        val dateLabel: String,
        val rating: Int,            // 0 unset, 1–5
        val done: Int,
        val habitsKept: Int,
        val habitsExpected: Int,
        val focusMin: Int,
        val trackedMin: Int,
        val wins: List<String>,     // already privacy-filtered by the caller (empty = omit)
        val reflection: String,     // already privacy-filtered (blank = omit)
        val moodEmoji: String,
        val accentArgb: Long?,
    )

    private const val W = 1080
    private const val H = 1350

    private fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    fun render(d: Data): Bitmap {
        val accent = d.accentArgb?.toInt() ?: 0xFF6650A4.toInt()
        val bg = 0xFF16121F.toInt()
        val onBg = 0xFFEDE8F5.toInt()
        val muted = 0xFF9B93AC.toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }

        // Eyebrow + date.
        reg.textSize = 38f; reg.color = accent
        c.drawText("MY DAY", 72f, 130f, reg)
        bold.textSize = 72f; bold.color = onBg
        c.drawText(ellipsize(d.dateLabel, bold, (W - 144).toFloat()), 72f, 210f, bold)

        // Rating stars (or mood) as the emotional headline.
        var y = 320f
        if (d.rating in 1..5) {
            bold.textSize = 80f; bold.color = accent
            c.drawText("★".repeat(d.rating) + "☆".repeat(5 - d.rating), 72f, y, bold)
            y += 60f
        } else if (d.moodEmoji.isNotBlank()) {
            bold.textSize = 84f; c.drawText(d.moodEmoji, 72f, y, bold); y += 60f
        }
        y += 40f

        // Stat tiles: done / habits / focus / tracked.
        val tiles = buildList {
            add("✓" to "${d.done} done")
            if (d.habitsExpected > 0) add("🔁" to "${d.habitsKept}/${d.habitsExpected} habits")
            if (d.focusMin > 0) add("🎯" to "${hm(d.focusMin)} focus")
            if (d.trackedMin > 0) add("⧗" to "${hm(d.trackedMin)} tracked")
        }
        bold.textSize = 52f; bold.color = onBg
        tiles.forEach { (icon, label) ->
            c.drawText("$icon  $label", 72f, y + 44f, bold)
            y += 86f
        }
        y += 24f

        // Wins.
        if (d.wins.isNotEmpty()) {
            reg.textSize = 34f; reg.color = accent
            c.drawText("WINS", 72f, y, reg); y += 56f
            bold.textSize = 44f; bold.color = onBg
            d.wins.take(3).forEach { w ->
                c.drawText(ellipsize("⭐  $w", bold, (W - 144).toFloat()), 72f, y, bold); y += 66f
            }
            y += 20f
        }

        // A line of reflection.
        if (d.reflection.isNotBlank()) {
            reg.textSize = 34f; reg.color = accent
            c.drawText("REFLECTION", 72f, y, reg); y += 56f
            bold.textSize = 42f; bold.color = onBg; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            wrap(d.reflection, bold, (W - 144).toFloat()).take(4).forEach { line ->
                c.drawText(line, 72f, y, bold); y += 58f
            }
            bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // Footer.
        reg.textSize = 32f; reg.color = muted
        c.drawText("Kairo · a day, closed · 100% offline", 72f, (H - 60).toFloat(), reg)
        return bmp
    }

    /** Plain-text equivalent for the "share as text" path. */
    fun text(d: Data): String = buildString {
        append("My day — ${d.dateLabel}\n")
        if (d.rating in 1..5) append("★".repeat(d.rating) + "☆".repeat(5 - d.rating) + "\n")
        append("✓ ${d.done} done")
        if (d.habitsExpected > 0) append(" · 🔁 ${d.habitsKept}/${d.habitsExpected} habits")
        if (d.focusMin > 0) append(" · 🎯 ${hm(d.focusMin)} focus")
        if (d.trackedMin > 0) append(" · ⧗ ${hm(d.trackedMin)} tracked")
        append("\n")
        if (d.wins.isNotEmpty()) { append("\nWins:\n"); d.wins.take(3).forEach { append("⭐ $it\n") } }
        if (d.reflection.isNotBlank()) append("\n“${d.reflection}”\n")
        append("\n— via Kairo")
    }

    // ── Daily-review SHARE redesign · a config-driven, variable-height "My day" card ──────────────────
    //
    // The redesigned day share is modular: [DayShareData] carries every optional block the day can offer,
    // and [DayShareConfig] (domain/) decides which blocks are drawn. [renderShare] lays the enabled blocks
    // out top-to-bottom on a VARIABLE-HEIGHT bitmap via the shared [ShareCanvas] engine, branching on the
    // config's [ShareStyle]. The old fixed-height [render]/[text] paths are untouched. A block never
    // renders when its data is empty, so an enabled-but-empty section can never leave a gap.

    /** One habit line for the DETAILED habits block: its name, whether it was kept, and an optional
     *  numeric detail ("3/5 glasses"). */
    data class HabitLine(val name: String, val kept: Boolean, val detail: String)

    /** One activity line for the DETAILED tracked-time block: its name and minutes tracked. */
    data class ActivityLine(val name: String, val minutes: Int)

    /** One answered daily-question line: the (shortened) question and its 1–5 effort score. */
    data class QuestionLine(val label: String, val score: Int)

    /**
     * Everything the redesigned day card can show — the superset of blocks. The caller fills every field
     * from the day's data; [DayShareConfig] then selects which are actually drawn. Anything blank/empty is
     * simply skipped, so the same data object serves any config.
     */
    data class DayShareData(
        val dateLabel: String,
        val rating: Int,                 // 0 unset, 1–5
        val moodEmoji: String,           // "" = none
        val energy: Int,                 // 0 unset, 1–5
        val emotion: String,             // the named emotion word; "" = none
        val wins: List<String>,
        val highlight: String,
        val gratitude: List<String>,     // three good things (each may carry its inline "…and why")
        val lesson: String,
        val reflection: String,
        val themes: List<String>,        // top recurring theme words
        val taskTitles: List<String>,
        val taskCount: Int,
        val habits: List<HabitLine>,
        val habitsKept: Int,
        val habitsExpected: Int,
        val activities: List<ActivityLine>,
        val trackedMin: Int,
        val questions: List<QuestionLine>,
        val goalsAdvanced: List<String>,
        val valuesHonored: List<String>,
        val tomorrowFocus: String,
        val woopObstacle: String,
        val woopPlan: String,
        val pattern: String,             // a single soft, non-causal observation ("" = none)
        val accentArgb: Long?,
    )

    /** Render the day card honouring [cfg], drawing only the enabled, non-empty blocks on a variable-height
     *  bitmap and branching on the chosen [ShareStyle]. Never throws / never blank — guarded end-to-end. */
    fun renderShare(d: DayShareData, cfg: DayShareConfig): Bitmap {
        val accent = d.accentArgb?.toInt() ?: DEFAULT_SHARE_ACCENT
        val pal = if (cfg.style == ShareStyle.PROFESSIONAL) SharePalette.professional(accent) else SharePalette.personal(accent)
        return try {
            val sc = ShareCanvas(pal)

            // ── Header ──
            if (pal.professional) {
                sc.gap(64f); sc.bigTitle("Day Record", 64f); sc.subtitle(d.dateLabel); sc.accentRule(); sc.gap(18f)
            } else {
                sc.gap(72f); sc.label("MY DAY"); sc.bigTitle(d.dateLabel); sc.gap(24f)
            }

            // ── Felt state ──
            if (cfg.rating && d.rating in 1..5) sc.stars(d.rating)
            if (cfg.moodEnergyEmotion) {
                val moodEmotion = listOf(d.moodEmoji, d.emotion).filter { it.isNotBlank() }.joinToString("   ")
                if (moodEmotion.isNotBlank()) sc.statLine(moodEmotion, size = 56f, rowH = 86f)
                if (d.energy in 1..5) sc.energyDots(d.energy)
            }
            sc.gap(20f)

            // ── Compact counts as boxed tonal tiles (tasks / habits / tracked, count-mode) ──
            val tiles = buildList {
                if (cfg.tasks == TaskDetail.COUNT) add(ShareTile("✓", d.taskCount.toString(), "done"))
                if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add(ShareTile("🔁", "${d.habitsKept}/${d.habitsExpected}", "habits"))
                if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add(ShareTile("⧗", hmLabel(d.trackedMin), "tracked"))
            }
            if (tiles.isNotEmpty()) { sc.tiles(tiles); sc.gap(4f) }

            // ── Highlights ──
            if (cfg.wins && d.wins.isNotEmpty()) { sc.listSection("WINS", d.wins.map { "⭐  $it" }, rowH = 64f, size = 44f); sc.gap(12f) }
            if (cfg.highlight && d.highlight.isNotBlank()) { sc.label("HIGHLIGHT"); sc.paragraph(d.highlight, 42f, italic = false, maxLines = 3); sc.gap(12f) }
            if (cfg.gratitude && d.gratitude.isNotEmpty()) { sc.markListSection("GRATEFUL FOR", d.gratitude.map { true to it }, rowH = 64f, size = 42f); sc.gap(12f) }
            if (cfg.lesson && d.lesson.isNotBlank()) { sc.label("LESSON"); sc.paragraph(d.lesson, 42f, italic = false, maxLines = 3); sc.gap(12f) }

            // ── Reflection ──
            if (cfg.reflection && d.reflection.isNotBlank()) { sc.label("REFLECTION"); sc.paragraph(d.reflection, 42f, italic = true, maxLines = 6); sc.gap(8f) }
            if (cfg.themes && d.themes.isNotEmpty()) { sc.themesLine(d.themes); sc.gap(12f) }

            // ── Tasks (FULL) · Habits (DETAILED) · Tracked time (DETAILED) — modern marks in lists ──
            if (cfg.tasks == TaskDetail.FULL && d.taskTitles.isNotEmpty()) {
                val labelText = if (pal.professional) "COMPLETED WORK" else "COMPLETED · ${d.taskCount}"
                sc.markListSection(labelText, d.taskTitles.map { true to it }, rowH = 56f, size = 40f); sc.gap(12f)
            }
            if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
                val rows = d.habits.map { h -> h.kept to (h.name + (if (h.detail.isNotBlank()) "   ${h.detail}" else "")) }
                sc.markListSection("HABITS · ${d.habitsKept}/${d.habitsExpected}", rows, rowH = 56f, size = 40f); sc.gap(12f)
            }
            if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
                sc.listSection("TIME · ${hmLabel(d.trackedMin)}", d.activities.map { "${it.name}   ${hmLabel(it.minutes)}" }, rowH = 56f, size = 40f); sc.gap(12f)
            }

            // ── Assessments ──
            if (cfg.dailyQuestions && d.questions.isNotEmpty()) {
                val rows = d.questions.map { q -> "★".repeat(q.score.coerceIn(0, 5)) + "  " + q.label }
                sc.listSection("DAILY QUESTIONS", rows, rowH = 58f, size = 38f); sc.gap(12f)
            }
            if (cfg.alignment && (d.goalsAdvanced.isNotEmpty() || d.valuesHonored.isNotEmpty())) {
                sc.label("ALIGNMENT")
                if (d.goalsAdvanced.isNotEmpty()) sc.statLine("🎯  " + d.goalsAdvanced.joinToString(", "), size = 40f, rowH = 60f)
                if (d.valuesHonored.isNotEmpty()) sc.statLine("🧭  " + d.valuesHonored.joinToString(", "), size = 40f, rowH = 60f)
                sc.gap(12f)
            }

            // ── Tomorrow ──
            val showTomorrow = (cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) ||
                (cfg.woop && (d.woopObstacle.isNotBlank() || d.woopPlan.isNotBlank()))
            if (showTomorrow) {
                sc.label("TOMORROW")
                if (cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) sc.paragraph("🎯  ${d.tomorrowFocus}", 42f, italic = false, maxLines = 2)
                if (cfg.woop && d.woopObstacle.isNotBlank()) sc.paragraph("🧱  ${d.woopObstacle}", 38f, italic = false, maxLines = 2)
                if (cfg.woop && d.woopPlan.isNotBlank()) sc.paragraph("🧭  ${d.woopPlan}", 38f, italic = false, maxLines = 2)
                sc.gap(12f)
            }

            // ── Insights ──
            if (cfg.pattern && d.pattern.isNotBlank()) { sc.label("A PATTERN"); sc.paragraph(d.pattern, 38f, italic = true, maxLines = 4); sc.gap(8f) }

            // ── Footer ──
            footer(sc, pal, cfg.footerTagline, "Kairo · a day, closed · 100% offline")
            sc.paint()
        } catch (t: Throwable) {
            fallbackCard(pal, "My day", d.dateLabel)
        }
    }

    /** The plain-text equivalent of [renderShare], honouring the same [cfg]. */
    fun shareText(d: DayShareData, cfg: DayShareConfig): String = buildString {
        append("My day — ${d.dateLabel}\n")
        if (cfg.rating && d.rating in 1..5) append("★".repeat(d.rating) + "☆".repeat(5 - d.rating) + "\n")
        if (cfg.moodEnergyEmotion) {
            val parts = buildList {
                if (d.moodEmoji.isNotBlank()) add(d.moodEmoji)
                if (d.emotion.isNotBlank()) add(d.emotion)
                if (d.energy in 1..5) add("energy " + "◆".repeat(d.energy) + "◇".repeat(5 - d.energy))
            }
            if (parts.isNotEmpty()) append(parts.joinToString("  ") + "\n")
        }
        val counts = buildList {
            if (cfg.tasks == TaskDetail.COUNT) add("• ${d.taskCount} done")
            if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add("🔁 ${d.habitsKept}/${d.habitsExpected} habits")
            if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add("⧗ ${hmLabel(d.trackedMin)} tracked")
        }
        if (counts.isNotEmpty()) append(counts.joinToString(" · ") + "\n")
        if (cfg.wins && d.wins.isNotEmpty()) { append("\nWins:\n"); d.wins.forEach { append("⭐ $it\n") } }
        if (cfg.highlight && d.highlight.isNotBlank()) append("\n✨ ${d.highlight}\n")
        if (cfg.gratitude && d.gratitude.isNotEmpty()) { append("\nGrateful for:\n"); d.gratitude.forEach { append("• $it\n") } }
        if (cfg.lesson && d.lesson.isNotBlank()) append("\n💡 ${d.lesson}\n")
        if (cfg.reflection && d.reflection.isNotBlank()) append("\n“${d.reflection}”\n")
        if (cfg.themes && d.themes.isNotEmpty()) append("\nThemes: ${d.themes.joinToString(", ")}\n")
        if (cfg.tasks == TaskDetail.FULL && d.taskTitles.isNotEmpty()) { append("\nCompleted:\n"); d.taskTitles.forEach { append("• $it\n") } }
        if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
            append("\nHabits:\n"); d.habits.forEach { append((if (it.kept) "•" else "◦") + " " + it.name + (if (it.detail.isNotBlank()) " · ${it.detail}" else "") + "\n") }
        }
        if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
            append("\nTime tracked:\n"); d.activities.forEach { append("• ${it.name} · ${hmLabel(it.minutes)}\n") }
        }
        if (cfg.dailyQuestions && d.questions.isNotEmpty()) {
            append("\nDaily questions:\n"); d.questions.forEach { append("${it.label} — ${it.score}/5\n") }
        }
        if (cfg.alignment && (d.goalsAdvanced.isNotEmpty() || d.valuesHonored.isNotEmpty())) {
            append("\n")
            if (d.goalsAdvanced.isNotEmpty()) append("🎯 Moved: ${d.goalsAdvanced.joinToString(", ")}\n")
            if (d.valuesHonored.isNotEmpty()) append("🧭 Honored: ${d.valuesHonored.joinToString(", ")}\n")
        }
        if ((cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) || (cfg.woop && (d.woopObstacle.isNotBlank() || d.woopPlan.isNotBlank()))) {
            append("\nTomorrow:\n")
            if (cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) append("🎯 ${d.tomorrowFocus}\n")
            if (cfg.woop && d.woopObstacle.isNotBlank()) append("🧱 ${d.woopObstacle}\n")
            if (cfg.woop && d.woopPlan.isNotBlank()) append("🧭 ${d.woopPlan}\n")
        }
        if (cfg.pattern && d.pattern.isNotBlank()) append("\n${d.pattern}\n")
        if (cfg.footerTagline) append("\n— via Kairo")
    }

    // ── Period-spanning SHARE · the WEEK / MONTH / YEAR roll-up card ───────────────────────────────────
    //
    // The period share is the roll-up sibling of the day share: [PeriodShareData] carries every optional
    // block a reviewed period can offer, [PeriodShareConfig] (domain/) decides which are drawn, and
    // [renderPeriodShare] lays them out via the same [ShareCanvas] engine — same modern marks, boxed
    // tiles, lists and PERSONAL/PROFESSIONAL styles as the day card. [PeriodKind] tailors the labels and
    // gates the week-only execution score. Data-adaptive and guarded: empty sections are skipped and a
    // render can never crash or blank.

    /** Which reviewed period a [PeriodShareData] describes — tailors labels and gates the week-only score. */
    enum class PeriodKind { WEEK, MONTH, YEAR }

    /** One habit's kept-vs-expected over a period, for the DETAILED habit-consistency block. */
    data class ConsistencyLine(val name: String, val kept: Int, val expected: Int) {
        val pct: Int get() = if (expected <= 0) 0 else (kept * 100) / expected
    }

    /**
     * Everything the period roll-up card can show — the superset of blocks. The caller computes every
     * field from the period's roll-up ([com.todocompanion.app.domain.ReviewRollup] /
     * [com.todocompanion.app.domain.YearReviewed]); [PeriodShareConfig] then selects which are drawn.
     */
    data class PeriodShareData(
        val periodLabel: String,         // headline / subtitle, e.g. "1–7 Sep", "September 2026", "The last 12 months"
        val reviewedDays: Int,
        val periodDays: Int,
        val avgRating: Double,           // 0 = none rated
        val avgMood: Double,             // 0 = none logged
        val moodFace: String,            // "" = none
        val hasExec: Boolean,            // whether an execution score is available (week)
        val execPlanned: Int,
        val execCompleted: Int,
        val execPct: Int,
        val wins: List<String>,          // top wins (week / month)
        val winsCount: Int,              // total good-things counted (year, when no win texts)
        val highlight: String,           // a standout highlight ("" = none)
        val habits: List<ConsistencyLine>,
        val habitsKept: Int,             // summed kept across habits
        val habitsExpected: Int,         // summed expected across habits
        val activities: List<ActivityLine>,
        val trackedMin: Int,
        val goals: List<String>,         // goals advanced, already formatted
        val themes: List<String>,        // top recurring theme words
        val tasksDone: Int,
        val accentArgb: Long?,
    )

    /** Render the WEEK / MONTH / YEAR roll-up card honouring [cfg] and [kind], branching on the chosen
     *  [ShareStyle]. Data-adaptive and guarded end-to-end — never throws, never blank. */
    fun renderPeriodShare(d: PeriodShareData, cfg: PeriodShareConfig, kind: PeriodKind): Bitmap {
        val accent = d.accentArgb?.toInt() ?: DEFAULT_SHARE_ACCENT
        val pal = if (cfg.style == ShareStyle.PROFESSIONAL) SharePalette.professional(accent) else SharePalette.personal(accent)
        val eyebrow = when (kind) { PeriodKind.WEEK -> "MY WEEK"; PeriodKind.MONTH -> "MY MONTH"; PeriodKind.YEAR -> "YEAR, REVIEWED" }
        val proTitle = when (kind) { PeriodKind.WEEK -> "Week Record"; PeriodKind.MONTH -> "Month Record"; PeriodKind.YEAR -> "Year Record" }
        return try {
            val sc = ShareCanvas(pal)

            // ── Header ──
            if (pal.professional) {
                sc.gap(64f); sc.bigTitle(proTitle, 64f); sc.subtitle(d.periodLabel); sc.accentRule(); sc.gap(12f)
            } else {
                sc.gap(72f); sc.label(eyebrow); sc.bigTitle(d.periodLabel); sc.gap(16f)
            }

            // ── Period label + days reviewed ──
            val reviewedText = if (kind == PeriodKind.YEAR) "${d.reviewedDays} days reviewed"
                else "${d.reviewedDays} of ${d.periodDays} days reviewed"
            sc.statLine(reviewedText, size = 44f, color = pal.muted, rowH = 62f)
            sc.gap(14f)

            // ── Felt trend ──
            if (cfg.feltTrend) {
                var drewFelt = false
                if (d.avgRating > 0) {
                    sc.stars(d.avgRating.roundToInt().coerceIn(1, 5))
                    sc.statLine("avg ${oneDpStr(d.avgRating)}", size = 34f, color = pal.muted, rowH = 46f)
                    drewFelt = true
                }
                if (d.avgMood > 0 && d.moodFace.isNotBlank()) {
                    sc.statLine("${d.moodFace}   mood ${oneDpStr(d.avgMood)} avg", size = 46f, rowH = 66f)
                    drewFelt = true
                }
                if (drewFelt) sc.gap(8f)
            }

            // ── At-a-glance boxed tiles: tasks / habits / tracked / execution ──
            val tiles = buildList {
                if (cfg.tasks && d.tasksDone > 0) add(ShareTile("✓", d.tasksDone.toString(), "done"))
                if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add(ShareTile("🔁", "${d.habitsKept}/${d.habitsExpected}", "habits"))
                if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add(ShareTile("⧗", hmLabel(d.trackedMin), "tracked"))
                if (kind == PeriodKind.WEEK && cfg.executionScore && d.hasExec) add(ShareTile("🎯", "${d.execPct}%", "executed"))
            }
            if (tiles.isNotEmpty()) { sc.tiles(tiles); sc.gap(4f) }

            // ── Execution score detail (week only) ──
            if (kind == PeriodKind.WEEK && cfg.executionScore && d.hasExec) {
                sc.statLine("${d.execPct}% executed · ${d.execCompleted}/${d.execPlanned} planned", size = 40f, rowH = 58f)
                sc.gap(10f)
            }

            // ── Wins / highlights ──
            if (cfg.wins) {
                if (d.wins.isNotEmpty()) { sc.listSection(if (kind == PeriodKind.YEAR) "HIGHLIGHTS" else "WINS", d.wins.map { "⭐  $it" }, rowH = 62f, size = 42f); sc.gap(10f) }
                if (d.highlight.isNotBlank()) { sc.label("A HIGHLIGHT"); sc.paragraph(d.highlight, 42f, italic = true, maxLines = 3); sc.gap(10f) }
                if (d.wins.isEmpty() && d.highlight.isBlank() && d.winsCount > 0) { sc.statLine("⭐  ${d.winsCount} good things noticed", size = 42f, rowH = 60f); sc.gap(10f) }
            }

            // ── Habit consistency (detailed = per-habit kept/expected) ──
            if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
                sc.listSection("HABITS · ${d.habitsKept}/${d.habitsExpected}", d.habits.map { "${it.name}   ${it.kept}/${it.expected} · ${it.pct}%" }, rowH = 56f, size = 38f)
                sc.gap(10f)
            }

            // ── Time by activity (detailed = per-activity) ──
            if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
                sc.listSection("TIME · ${hmLabel(d.trackedMin)}", d.activities.map { "${it.name}   ${hmLabel(it.minutes)}" }, rowH = 56f, size = 38f)
                sc.gap(10f)
            }

            // ── Goals advanced ──
            if (cfg.goals && d.goals.isNotEmpty()) { sc.listSection("GOALS ADVANCED", d.goals.map { "🎯  $it" }, rowH = 58f, size = 40f); sc.gap(10f) }

            // ── Themes ──
            if (cfg.themes && d.themes.isNotEmpty()) { sc.themesLine(d.themes); sc.gap(10f) }

            // ── Footer ──
            val tag = when (kind) {
                PeriodKind.WEEK -> "Kairo · a week, reviewed · 100% offline"
                PeriodKind.MONTH -> "Kairo · a month, reviewed · 100% offline"
                PeriodKind.YEAR -> "Kairo · a year, reviewed · 100% offline"
            }
            footer(sc, pal, cfg.footerTagline, tag)
            sc.paint()
        } catch (t: Throwable) {
            val title = when (kind) { PeriodKind.WEEK -> "My week"; PeriodKind.MONTH -> "My month"; PeriodKind.YEAR -> "My year" }
            fallbackCard(pal, title, d.periodLabel)
        }
    }

    /** The plain-text equivalent of [renderPeriodShare], honouring the same [cfg] and [kind]. */
    fun periodShareText(d: PeriodShareData, cfg: PeriodShareConfig, kind: PeriodKind): String = buildString {
        val title = when (kind) { PeriodKind.WEEK -> "My week"; PeriodKind.MONTH -> "My month"; PeriodKind.YEAR -> "My year" }
        append("$title — ${d.periodLabel}\n")
        append(if (kind == PeriodKind.YEAR) "📆 ${d.reviewedDays} days reviewed\n" else "📆 ${d.reviewedDays}/${d.periodDays} days reviewed\n")
        if (cfg.feltTrend) {
            val parts = buildList {
                if (d.avgRating > 0) add("★ ${oneDpStr(d.avgRating)} avg")
                if (d.avgMood > 0) add("mood ${oneDpStr(d.avgMood)}")
            }
            if (parts.isNotEmpty()) append(parts.joinToString(" · ") + "\n")
        }
        val counts = buildList {
            if (cfg.tasks && d.tasksDone > 0) add("• ${d.tasksDone} done")
            if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add("🔁 ${d.habitsKept}/${d.habitsExpected} habits")
            if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add("⧗ ${hmLabel(d.trackedMin)} tracked")
            if (kind == PeriodKind.WEEK && cfg.executionScore && d.hasExec) add("🎯 ${d.execPct}% executed")
        }
        if (counts.isNotEmpty()) append(counts.joinToString(" · ") + "\n")
        if (cfg.wins) {
            if (d.wins.isNotEmpty()) { append("\n${if (kind == PeriodKind.YEAR) "Highlights" else "Wins"}:\n"); d.wins.forEach { append("⭐ $it\n") } }
            if (d.highlight.isNotBlank()) append("\n“${d.highlight}”\n")
            if (d.wins.isEmpty() && d.highlight.isBlank() && d.winsCount > 0) append("\n⭐ ${d.winsCount} good things noticed\n")
        }
        if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
            append("\nHabits:\n"); d.habits.forEach { append("• ${it.name} · ${it.kept}/${it.expected} (${it.pct}%)\n") }
        }
        if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
            append("\nTime tracked:\n"); d.activities.forEach { append("• ${it.name} · ${hmLabel(it.minutes)}\n") }
        }
        if (cfg.goals && d.goals.isNotEmpty()) { append("\nGoals advanced:\n"); d.goals.forEach { append("🎯 $it\n") } }
        if (cfg.themes && d.themes.isNotEmpty()) append("\nThemes: ${d.themes.joinToString(", ")}\n")
        if (cfg.footerTagline) append("\n— via Kairo")
    }

    // ── Track 1.5 · the any-period recap share card (used by the Omega recap screen) ────────────────────

    /** Everything the recap card needs, already computed by the caller from the period recap. */
    data class RecapData(
        val title: String,           // e.g. "This week" / "Last month"
        val avgRating: Double,       // 0 = none rated
        val lines: List<String>,     // already-formatted stat lines ("✓ Tasks done · 12")
        val narrative: String,       // the one-paragraph story ("" = omit)
        val accentArgb: Long?,
    )

    /**
     * Render the any-period recap to a PNG in the same visual language as the day cards — accent eyebrow,
     * big title, optional rating stars, stat lines, the narrative, footer. Guarded end-to-end so a share
     * can never crash or produce a blank image.
     */
    fun renderRecap(d: RecapData): Bitmap {
        val accent = d.accentArgb?.toInt() ?: 0xFF6650A4.toInt()
        val bg = 0xFF16121F.toInt()
        val onBg = 0xFFEDE8F5.toInt()
        val muted = 0xFF9B93AC.toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        try {
            val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }

            reg.textSize = 38f; reg.color = accent
            c.drawText("RECAP", 72f, 130f, reg)
            bold.textSize = 72f; bold.color = onBg
            c.drawText(ellipsize(d.title, bold, (W - 144).toFloat()), 72f, 210f, bold)

            var y = 320f
            if (d.avgRating > 0) {
                val r = d.avgRating.roundToInt().coerceIn(1, 5)
                bold.textSize = 80f; bold.color = accent
                c.drawText("★".repeat(r) + "☆".repeat(5 - r), 72f, y, bold)
                reg.textSize = 34f; reg.color = muted
                c.drawText("avg ${oneDp(d.avgRating)}", 72f + 470f, y - 18f, reg)
                y += 60f
            }
            y += 40f

            bold.textSize = 50f; bold.color = onBg
            d.lines.take(6).forEach { line ->
                c.drawText(ellipsize(line, bold, (W - 144).toFloat()), 72f, y + 44f, bold)
                y += 82f
            }
            y += 24f

            if (d.narrative.isNotBlank()) {
                reg.textSize = 34f; reg.color = accent
                c.drawText("THE STORY", 72f, y, reg); y += 56f
                bold.textSize = 40f; bold.color = onBg; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                wrap(d.narrative, bold, (W - 144).toFloat()).take(6).forEach { line ->
                    c.drawText(line, 72f, y, bold); y += 54f
                }
                bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            reg.textSize = 32f; reg.color = muted
            c.drawText("Kairo · a period, recapped · 100% offline", 72f, (H - 60).toFloat(), reg)
        } catch (t: Throwable) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 64f }
            c.drawText(d.title.ifBlank { "Recap" }, 72f, 160f, p)
        }
        return bmp
    }

    /** Plain-text equivalent for the recap card's "share as text" path. */
    fun recapText(d: RecapData): String = buildString {
        append("${d.title} — recap\n")
        if (d.avgRating > 0) append("★ ${oneDp(d.avgRating)} avg\n")
        d.lines.take(6).forEach { append("$it\n") }
        if (d.narrative.isNotBlank()) append("\n${d.narrative}\n")
        append("\n— via Kairo")
    }

    private fun oneDp(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val words = s.split(" ")
        val lines = mutableListOf<String>()
        var cur = ""
        for (w in words) {
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(test) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = test
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }
}

// ── Shared share-card rendering framework (day + period) ──────────────────────────────────────────────

private const val SHARE_W = 1080
private const val SHARE_MAX_H = 6000
private val DEFAULT_SHARE_ACCENT = 0xFF6650A4.toInt()

/**
 * The visual palette a share card renders in. PERSONAL is the warm dark "a day, closed" card; PROFESSIONAL
 * is a clean, near-white "proof of work" document with dark ink and a restrained accent.
 */
private class SharePalette(
    val bg: Int, val ink: Int, val muted: Int, val accent: Int, val onAccent: Int,
    val tileFill: Int, val tileStroke: Int, val ring: Int, val professional: Boolean,
) {
    companion object {
        fun personal(accent: Int) = SharePalette(
            bg = 0xFF16121F.toInt(), ink = 0xFFEDE8F5.toInt(), muted = 0xFF9B93AC.toInt(),
            accent = accent, onAccent = 0xFFFFFFFF.toInt(),
            tileFill = 0xFF241F30.toInt(), tileStroke = 0, ring = 0xFF9B93AC.toInt(), professional = false,
        )

        fun professional(accent: Int) = SharePalette(
            bg = 0xFFF7F7F4.toInt(), ink = 0xFF1C1B22.toInt(), muted = 0xFF6C6B76.toInt(),
            accent = accent, onAccent = 0xFFFFFFFF.toInt(),
            tileFill = 0xFFFFFFFF.toInt(), tileStroke = 0xFFE3E2DC.toInt(), ring = 0xFFC7C6C2.toInt(), professional = true,
        )
    }
}

/** One at-a-glance metric tile: an icon/emoji, a big value and a small label. */
private class ShareTile(val icon: String, val value: String, val label: String)

/**
 * A small vertical-stack layout engine for the variable-height share cards. Sections are appended as
 * measured blocks, then painted top-to-bottom onto a bitmap sized to the content and clamped to a sane
 * maximum. Style-aware via [pal]. Never blank: an enabled-but-empty section simply adds nothing, and the
 * caller guards the whole build.
 */
private class ShareCanvas(
    val pal: SharePalette,
    private val left: Float = 72f,
    private val contentW: Float = (SHARE_W - 144).toFloat(),
) {
    private class Block(val h: Float, val draw: (Canvas, Float) -> Unit)

    private val blocks = mutableListOf<Block>()
    var used = 0f
        private set
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }

    private fun push(h: Float, draw: (Canvas, Float) -> Unit) {
        blocks.add(Block(h, draw)); used += h
    }

    fun gap(h: Float) = push(h) { _, _ -> }

    /** An uppercase section label — accent (personal) / muted (professional). */
    fun label(text: String) = push(52f) { c, top ->
        reg.textSize = if (pal.professional) 30f else 34f
        reg.color = if (pal.professional) pal.muted else pal.accent
        reg.typeface = Typeface.DEFAULT
        c.drawText(text, left, top + 38f, reg)
    }

    /** The big card headline (ink, bold). */
    fun bigTitle(text: String, size: Float = 72f) = push(size + 24f) { c, top ->
        bold.textSize = size; bold.color = pal.ink; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(cardEllipsize(text, bold, contentW), left, top + size, bold)
    }

    /** A muted sub-headline under the title (professional date / period subtitle). */
    fun subtitle(text: String, size: Float = 42f) = push(size + 16f) { c, top ->
        reg.textSize = size; reg.color = pal.muted; reg.typeface = Typeface.DEFAULT
        c.drawText(cardEllipsize(text, reg, contentW), left, top + size, reg)
    }

    /** A thin accent rule — a restrained flourish under the professional header. */
    fun accentRule() = push(26f) { c, top ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND }
        c.drawLine(left, top + 12f, left + 96f, top + 12f, p)
    }

    /** Rating stars in the accent colour. */
    fun stars(rating: Int) = push(96f) { c, top ->
        val r = rating.coerceIn(0, 5)
        bold.textSize = 80f; bold.color = pal.accent; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText("★".repeat(r) + "☆".repeat(5 - r), left, top + 74f, bold)
    }

    /** The energy diamonds row. */
    fun energyDots(energy: Int) = push(56f) { c, top ->
        val e = energy.coerceIn(0, 5)
        reg.textSize = 38f; reg.color = pal.muted; reg.typeface = Typeface.DEFAULT
        c.drawText("Energy  " + "◆".repeat(e) + "◇".repeat(5 - e), left, top + 40f, reg)
    }

    /** A short themes line ("Themes · a · b · c"). */
    fun themesLine(words: List<String>) = push(60f) { c, top ->
        reg.textSize = 36f; reg.color = if (pal.professional) pal.muted else pal.accent; reg.typeface = Typeface.DEFAULT
        c.drawText(cardEllipsize("Themes · " + words.joinToString(" · "), reg, contentW), left, top + 40f, reg)
    }

    fun statLine(text: String, size: Float = 50f, color: Int = pal.ink, rowH: Float = 82f) = push(rowH) { c, top ->
        bold.textSize = size; bold.color = color; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        c.drawText(cardEllipsize(text, bold, contentW), left, top + size * 0.86f, bold)
    }

    fun paragraph(text: String, size: Float = 42f, italic: Boolean = true, maxLines: Int = 6) {
        val face = Typeface.create(Typeface.DEFAULT, if (italic) Typeface.ITALIC else Typeface.BOLD)
        bold.textSize = size; bold.typeface = face
        val lines = cardWrap(text, bold, contentW).take(maxLines)
        if (lines.isEmpty()) return
        val lineH = size * 1.38f
        push(lines.size * lineH) { c, top ->
            bold.textSize = size; bold.color = pal.ink; bold.typeface = face
            var y = top + size
            lines.forEach { ln -> c.drawText(ln, left, y, bold); y += lineH }
            bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    /** A labelled plain-text list, capped by the height budget with a "+N more" tail. */
    fun listSection(labelText: String, items: List<String>, rowH: Float = 60f, size: Float = 42f) {
        if (items.isEmpty()) return
        label(labelText)
        val shown = capItems(items, rowH)
        shown.forEach { row ->
            push(rowH) { c, top ->
                bold.textSize = size; bold.color = pal.ink; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(cardEllipsize(row, bold, contentW), left, top + size * 0.9f, bold)
            }
        }
        moreTail(items.size - shown.size, rowH, left)
    }

    /** A labelled list where each row carries the app's modern completion mark (a filled accent disc +
     *  white check when kept, an open ring when not) — the DoneTick look — instead of a raw ✓/○ glyph. */
    fun markListSection(labelText: String, items: List<Pair<Boolean, String>>, rowH: Float = 56f, size: Float = 40f) {
        if (items.isEmpty()) return
        label(labelText)
        val shown = capItems(items, rowH)
        val markR = size * 0.36f
        val textX = left + markR * 2f + 22f
        shown.forEach { (kept, text) ->
            push(rowH) { c, top ->
                drawMark(c, left + markR + 2f, top + rowH / 2f - 4f, markR, kept, pal)
                bold.textSize = size; bold.color = pal.ink; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(cardEllipsize(text, bold, contentW - (textX - left)), textX, top + size * 0.9f, bold)
            }
        }
        moreTail(items.size - shown.size, rowH, textX)
    }

    /** A row (or wrapped rows) of rounded-rectangle tonal tiles — the app's StatTile box language. */
    fun tiles(items: List<ShareTile>) {
        if (items.isEmpty()) return
        val cols = when { items.size <= 4 -> items.size; items.size <= 6 -> 3; else -> 4 }
        val gapPx = 20f
        val tileW = (contentW - gapPx * (cols - 1)) / cols
        val tileH = 150f
        items.chunked(cols).forEach { row ->
            push(tileH) { c, top ->
                row.forEachIndexed { i, t -> drawTile(c, left + i * (tileW + gapPx), top, tileW, tileH, t, pal) }
            }
            gap(14f)
        }
    }

    /** The footer credibility line. */
    fun footerLine(text: String) = push(70f) { c, top ->
        reg.textSize = 32f; reg.color = pal.muted; reg.typeface = Typeface.DEFAULT
        c.drawText(cardEllipsize(text, reg, contentW), left, top + 40f, reg)
    }

    private fun <T> capItems(items: List<T>, rowH: Float): List<T> {
        val budget = (SHARE_MAX_H - used - 240f).coerceAtLeast(rowH)
        val maxRows = (budget / rowH).toInt().coerceAtLeast(1)
        return if (items.size <= maxRows) items else items.take((maxRows - 1).coerceAtLeast(1))
    }

    private fun moreTail(remaining: Int, rowH: Float, x: Float) {
        if (remaining <= 0) return
        push(rowH) { c, top ->
            reg.textSize = 34f; reg.color = pal.muted; reg.typeface = Typeface.DEFAULT
            c.drawText("+$remaining more", x, top + 30f, reg)
        }
    }

    fun paint(): Bitmap {
        val h = used.roundToInt().coerceIn(360, SHARE_MAX_H)
        val bmp = Bitmap.createBitmap(SHARE_W, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(pal.bg)
        var y = 0f
        for (b in blocks) { runCatching { b.draw(c, y) }; y += b.h }
        return bmp
    }
}

/** Draw the app's modern completion mark centred at (cx, cy): a filled accent disc + a white check when
 *  [kept] (the DoneTick look), else an open muted ring. */
private fun drawMark(c: Canvas, cx: Float, cy: Float, r: Float, kept: Boolean, pal: SharePalette) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    if (kept) {
        p.color = pal.accent; p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, r, p)
        p.color = pal.onAccent; p.style = Paint.Style.STROKE
        p.strokeWidth = (r * 0.26f).coerceAtLeast(3f); p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val path = android.graphics.Path().apply {
            moveTo(cx - r * 0.42f, cy + r * 0.02f)
            lineTo(cx - r * 0.08f, cy + r * 0.38f)
            lineTo(cx + r * 0.46f, cy - r * 0.40f)
        }
        c.drawPath(path, p)
    } else {
        p.color = pal.ring; p.style = Paint.Style.STROKE; p.strokeWidth = (r * 0.20f).coerceAtLeast(3f)
        c.drawCircle(cx, cy, r, p)
    }
}

/** Draw one rounded-rectangle tonal metric tile: an icon/emoji, a big value and a small label. */
private fun drawTile(c: Canvas, x: Float, y: Float, w: Float, h: Float, t: ShareTile, pal: SharePalette) {
    val rect = android.graphics.RectF(x, y, x + w, y + h)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.tileFill; style = Paint.Style.FILL }
    c.drawRoundRect(rect, 28f, 28f, fill)
    if (pal.tileStroke != 0) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.tileStroke; style = Paint.Style.STROKE; strokeWidth = 2f }
        c.drawRoundRect(rect, 28f, 28f, stroke)
    }
    val cx = x + w / 2f
    val inner = w - 24f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    p.color = pal.muted; p.textSize = 38f; p.typeface = Typeface.DEFAULT
    c.drawText(cardEllipsize(t.icon, p, inner), cx, y + 52f, p)
    p.color = pal.ink; p.textSize = 50f; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    c.drawText(cardEllipsize(t.value, p, inner), cx, y + 106f, p)
    p.color = pal.muted; p.textSize = 30f; p.typeface = Typeface.DEFAULT
    c.drawText(cardEllipsize(t.label, p, inner), cx, y + 140f, p)
}

/** The footer: a tagline (personal) or a credibility "Generated … · private, on-device record" line
 *  (professional), or a little breathing space when disabled. */
private fun footer(sc: ShareCanvas, pal: SharePalette, enabled: Boolean, personalTag: String) {
    if (enabled) {
        sc.gap(24f)
        val text = if (pal.professional) "Generated ${shareTodayLabel()} · Kairo · private, on-device record" else personalTag
        sc.footerLine(text)
    } else {
        sc.gap(48f)
    }
}

/** A minimal safe fallback card so a share can never crash or produce a blank image. */
private fun fallbackCard(pal: SharePalette, title: String, subtitle: String): Bitmap {
    val bmp = Bitmap.createBitmap(SHARE_W, 360, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(pal.bg)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.ink; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 64f }
    c.drawText(title, 72f, 160f, p)
    p.textSize = 40f; p.color = pal.muted; p.typeface = Typeface.DEFAULT
    c.drawText(cardEllipsize(subtitle, p, (SHARE_W - 144).toFloat()), 72f, 230f, p)
    return bmp
}

private fun hmLabel(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

private fun oneDpStr(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

private fun shareTodayLabel(): String {
    val d = java.time.LocalDate.now()
    return "${d.dayOfMonth} ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${d.year}"
}

private fun cardEllipsize(s: String, p: Paint, maxW: Float): String {
    if (p.measureText(s) <= maxW) return s
    var t = s
    while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
    return "$t…"
}

private fun cardWrap(s: String, p: Paint, maxW: Float): List<String> {
    val words = s.split(" ")
    val lines = mutableListOf<String>()
    var cur = ""
    for (w in words) {
        val test = if (cur.isEmpty()) w else "$cur $w"
        if (p.measureText(test) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = test
    }
    if (cur.isNotEmpty()) lines.add(cur)
    return lines
}
