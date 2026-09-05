package com.todocompanion.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.todocompanion.app.domain.DayShareConfig
import com.todocompanion.app.domain.HabitDetail
import com.todocompanion.app.domain.TaskDetail
import com.todocompanion.app.domain.TimeDetail
import kotlin.math.roundToInt

/**
 * R106 — renders a shareable "My day" card to a PNG entirely on-device (android.graphics, no Compose
 * capture, no network) and builds a plain-text equivalent. Privacy by construction: the caller decides
 * what goes in (task titles / reflection can be omitted), and the image is only shared if the user
 * picks a target. Reuses ProgressCard.saveAndShareUri + share for the file/FileProvider/ACTION_SEND path.
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
    // out top-to-bottom on a VARIABLE-HEIGHT bitmap (width fixed at 1080, height measured from the content
    // and clamped to a sane maximum), keeping the existing dark palette + visual language (accent eyebrow,
    // bold headline, accent section labels, footer). The old fixed-height [render]/[text] paths are
    // untouched, so week/year/recap keep working. A block never renders when its data is empty, so an
    // enabled-but-empty section can never leave a gap in the card or its preview.

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

    /** One laid-out block: how much vertical space it takes, and how to draw itself with its top at [y]. */
    private class Block(val h: Float, val draw: (Canvas, Float) -> Unit)

    private const val SHARE_MAX_H = 6000

    /** Render the day card honouring [cfg], drawing only the enabled, non-empty blocks on a variable-height
     *  bitmap. Never throws / never blank — guarded end-to-end with a minimal fallback. */
    fun renderShare(d: DayShareData, cfg: DayShareConfig): Bitmap {
        val accent = d.accentArgb?.toInt() ?: 0xFF6650A4.toInt()
        val bg = 0xFF16121F.toInt()
        val onBg = 0xFFEDE8F5.toInt()
        val muted = 0xFF9B93AC.toInt()
        try {
            val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }
            val left = 72f
            val contentW = (W - 144).toFloat()

            val blocks = mutableListOf<Block>()
            var used = 0f
            fun add(b: Block) { blocks.add(b); used += b.h }
            fun gap(h: Float) = add(Block(h) { _, _ -> })

            fun label(text: String) = add(Block(52f) { c, top ->
                reg.textSize = 34f; reg.color = accent; reg.typeface = Typeface.DEFAULT
                c.drawText(text, left, top + 38f, reg)
            })
            fun statLine(text: String, size: Float = 50f, color: Int = onBg, rowH: Float = 82f) = add(Block(rowH) { c, top ->
                bold.textSize = size; bold.color = color; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(ellipsize(text, bold, contentW), left, top + size * 0.86f, bold)
            })
            fun paragraph(text: String, size: Float = 42f, italic: Boolean = true, maxLines: Int = 6) {
                val face = Typeface.create(Typeface.DEFAULT, if (italic) Typeface.ITALIC else Typeface.BOLD)
                bold.textSize = size; bold.typeface = face
                val lines = wrap(text, bold, contentW).take(maxLines)
                if (lines.isEmpty()) return
                val lineH = size * 1.38f
                add(Block(lines.size * lineH) { c, top ->
                    bold.textSize = size; bold.color = onBg; bold.typeface = face
                    var y = top + size
                    lines.forEach { ln -> c.drawText(ln, left, y, bold); y += lineH }
                    bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                })
            }
            // A labelled list: as many rows as fit under the height cap, then a "+N more" line for the rest.
            fun listSection(labelText: String, items: List<String>, rowH: Float = 60f, size: Float = 42f) {
                if (items.isEmpty()) return
                label(labelText)
                val budget = (SHARE_MAX_H - used - 240f).coerceAtLeast(rowH)
                val maxRows = (budget / rowH).toInt().coerceAtLeast(1)
                val shown = if (items.size <= maxRows) items else items.take((maxRows - 1).coerceAtLeast(1))
                shown.forEach { row ->
                    add(Block(rowH) { c, top ->
                        bold.textSize = size; bold.color = onBg; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        c.drawText(ellipsize(row, bold, contentW), left, top + size * 0.9f, bold)
                    })
                }
                val remaining = items.size - shown.size
                if (remaining > 0) add(Block(rowH) { c, top ->
                    reg.textSize = 34f; reg.color = muted; reg.typeface = Typeface.DEFAULT
                    c.drawText("+$remaining more", left, top + 30f, reg)
                })
            }

            // ── Header ──
            gap(72f)
            label("MY DAY")
            add(Block(96f) { c, top ->
                bold.textSize = 72f; bold.color = onBg; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText(ellipsize(d.dateLabel, bold, contentW), left, top + 74f, bold)
            })
            gap(24f)

            // ── Felt state ──
            if (cfg.rating && d.rating in 1..5) add(Block(96f) { c, top ->
                bold.textSize = 80f; bold.color = accent; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                c.drawText("★".repeat(d.rating) + "☆".repeat(5 - d.rating), left, top + 74f, bold)
            })
            if (cfg.moodEnergyEmotion) {
                val moodEmotion = listOf(d.moodEmoji, d.emotion).filter { it.isNotBlank() }.joinToString("   ")
                if (moodEmotion.isNotBlank()) statLine(moodEmotion, size = 56f, rowH = 86f)
                if (d.energy in 1..5) add(Block(56f) { c, top ->
                    reg.textSize = 38f; reg.color = muted; reg.typeface = Typeface.DEFAULT
                    c.drawText("Energy  " + "◆".repeat(d.energy) + "◇".repeat(5 - d.energy), left, top + 40f, reg)
                })
            }
            gap(20f)

            // ── Compact counts (tasks / habits / time in their count-mode) ──
            val counts = buildList {
                if (cfg.tasks == TaskDetail.COUNT) add("✓  ${d.taskCount} done")
                if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add("🔁  ${d.habitsKept}/${d.habitsExpected} habits")
                if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add("⧗  ${hm(d.trackedMin)} tracked")
            }
            counts.forEach { statLine(it) }
            if (counts.isNotEmpty()) gap(14f)

            // ── Highlights ──
            if (cfg.wins && d.wins.isNotEmpty()) { listSection("WINS", d.wins.map { "⭐  $it" }, rowH = 64f, size = 44f); gap(12f) }
            if (cfg.highlight && d.highlight.isNotBlank()) { label("HIGHLIGHT"); paragraph(d.highlight, size = 42f, italic = false, maxLines = 3); gap(12f) }
            if (cfg.gratitude && d.gratitude.isNotEmpty()) { listSection("GRATEFUL FOR", d.gratitude.map { "🙏  $it" }, rowH = 64f, size = 42f); gap(12f) }
            if (cfg.lesson && d.lesson.isNotBlank()) { label("LESSON"); paragraph(d.lesson, size = 42f, italic = false, maxLines = 3); gap(12f) }

            // ── Reflection ──
            if (cfg.reflection && d.reflection.isNotBlank()) { label("REFLECTION"); paragraph(d.reflection, size = 42f, italic = true, maxLines = 6); gap(8f) }
            if (cfg.themes && d.themes.isNotEmpty()) {
                add(Block(60f) { c, top ->
                    reg.textSize = 36f; reg.color = accent; reg.typeface = Typeface.DEFAULT
                    c.drawText(ellipsize("Themes · " + d.themes.joinToString(" · "), reg, contentW), left, top + 40f, reg)
                })
                gap(12f)
            }

            // ── Tasks (FULL) · Habits (DETAILED) · Tracked time (DETAILED) ──
            if (cfg.tasks == TaskDetail.FULL && d.taskTitles.isNotEmpty()) {
                listSection("COMPLETED · ${d.taskCount}", d.taskTitles.map { "✓  $it" }, rowH = 56f, size = 40f); gap(12f)
            }
            if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
                val rows = d.habits.map { h -> (if (h.kept) "✓" else "○") + "  " + h.name + (if (h.detail.isNotBlank()) "   ${h.detail}" else "") }
                listSection("HABITS · ${d.habitsKept}/${d.habitsExpected}", rows, rowH = 56f, size = 40f); gap(12f)
            }
            if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
                val rows = d.activities.map { "${it.name}   ${hm(it.minutes)}" }
                listSection("TIME · ${hm(d.trackedMin)}", rows, rowH = 56f, size = 40f); gap(12f)
            }

            // ── Assessments ──
            if (cfg.dailyQuestions && d.questions.isNotEmpty()) {
                val rows = d.questions.map { q -> "★".repeat(q.score.coerceIn(0, 5)) + "  " + q.label }
                listSection("DAILY QUESTIONS", rows, rowH = 58f, size = 38f); gap(12f)
            }
            if (cfg.alignment && (d.goalsAdvanced.isNotEmpty() || d.valuesHonored.isNotEmpty())) {
                label("ALIGNMENT")
                if (d.goalsAdvanced.isNotEmpty()) statLine("🎯  " + d.goalsAdvanced.joinToString(", "), size = 40f, rowH = 60f)
                if (d.valuesHonored.isNotEmpty()) statLine("🧭  " + d.valuesHonored.joinToString(", "), size = 40f, rowH = 60f)
                gap(12f)
            }

            // ── Tomorrow ──
            val showTomorrow = (cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) ||
                (cfg.woop && (d.woopObstacle.isNotBlank() || d.woopPlan.isNotBlank()))
            if (showTomorrow) {
                label("TOMORROW")
                if (cfg.tomorrowFocus && d.tomorrowFocus.isNotBlank()) paragraph("🎯  ${d.tomorrowFocus}", size = 42f, italic = false, maxLines = 2)
                if (cfg.woop && d.woopObstacle.isNotBlank()) paragraph("🧱  ${d.woopObstacle}", size = 38f, italic = false, maxLines = 2)
                if (cfg.woop && d.woopPlan.isNotBlank()) paragraph("🧭  ${d.woopPlan}", size = 38f, italic = false, maxLines = 2)
                gap(12f)
            }

            // ── Insights ──
            if (cfg.pattern && d.pattern.isNotBlank()) { label("A PATTERN"); paragraph(d.pattern, size = 38f, italic = true, maxLines = 4); gap(8f) }

            // ── Footer ──
            if (cfg.footerTagline) {
                gap(24f)
                add(Block(70f) { c, top ->
                    reg.textSize = 32f; reg.color = muted; reg.typeface = Typeface.DEFAULT
                    c.drawText("Kairo · a day, closed · 100% offline", left, top + 40f, reg)
                })
            } else gap(48f)

            val h = used.roundToInt().coerceIn(360, SHARE_MAX_H)
            val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(bg)
            var y = 0f
            for (b in blocks) { runCatching { b.draw(c, y) }; y += b.h }
            return bmp
        } catch (t: Throwable) {
            // Minimal safe fallback so a share can never crash or produce a blank image.
            val bmp = Bitmap.createBitmap(W, 360, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(bg)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 64f }
            c.drawText("My day", 72f, 160f, p)
            p.textSize = 40f; p.color = muted; p.typeface = Typeface.DEFAULT
            c.drawText(ellipsize(d.dateLabel, p, (W - 144).toFloat()), 72f, 230f, p)
            return bmp
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
            if (cfg.tasks == TaskDetail.COUNT) add("✓ ${d.taskCount} done")
            if (cfg.habits == HabitDetail.COUNT && d.habitsExpected > 0) add("🔁 ${d.habitsKept}/${d.habitsExpected} habits")
            if (cfg.time == TimeDetail.TOTAL && d.trackedMin > 0) add("⧗ ${hm(d.trackedMin)} tracked")
        }
        if (counts.isNotEmpty()) append(counts.joinToString(" · ") + "\n")
        if (cfg.wins && d.wins.isNotEmpty()) { append("\nWins:\n"); d.wins.forEach { append("⭐ $it\n") } }
        if (cfg.highlight && d.highlight.isNotBlank()) append("\n✨ ${d.highlight}\n")
        if (cfg.gratitude && d.gratitude.isNotEmpty()) { append("\nGrateful for:\n"); d.gratitude.forEach { append("🙏 $it\n") } }
        if (cfg.lesson && d.lesson.isNotBlank()) append("\n💡 ${d.lesson}\n")
        if (cfg.reflection && d.reflection.isNotBlank()) append("\n“${d.reflection}”\n")
        if (cfg.themes && d.themes.isNotEmpty()) append("\nThemes: ${d.themes.joinToString(", ")}\n")
        if (cfg.tasks == TaskDetail.FULL && d.taskTitles.isNotEmpty()) { append("\nCompleted:\n"); d.taskTitles.forEach { append("✓ $it\n") } }
        if (cfg.habits == HabitDetail.DETAILED && d.habits.isNotEmpty()) {
            append("\nHabits:\n"); d.habits.forEach { append((if (it.kept) "✓" else "○") + " " + it.name + (if (it.detail.isNotBlank()) " · ${it.detail}" else "") + "\n") }
        }
        if (cfg.time == TimeDetail.DETAILED && d.activities.isNotEmpty()) {
            append("\nTime tracked:\n"); d.activities.forEach { append("• ${it.name} · ${hm(it.minutes)}\n") }
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

    // ── Phase F · the weekly roll-up share card ──────────────────────────────────────────────────────

    /** Everything the weekly card needs, already computed by the caller from the Week roll-up. */
    data class WeekData(
        val rangeLabel: String,     // e.g. "1–7 Sep"
        val reviewedDays: Int,
        val periodDays: Int,
        val avgRating: Double,      // 0 = none rated
        val streak: Int,           // current review streak
        val topWin: String,        // already privacy-safe; "" = omit
        val accentArgb: Long?,
    )

    /**
     * Render the week roll-up to a PNG in the same visual language as the day card (dark ground, accent
     * eyebrow, big headline, stat lines, footer). Never throws / never blank: the whole draw is guarded
     * and falls back to a minimal card, so a share can't crash or produce an empty image.
     */
    fun renderWeek(d: WeekData): Bitmap {
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
            c.drawText("MY WEEK", 72f, 130f, reg)
            bold.textSize = 72f; bold.color = onBg
            c.drawText(ellipsize(d.rangeLabel, bold, (W - 144).toFloat()), 72f, 210f, bold)

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

            val tiles = buildList {
                add("📆" to "${d.reviewedDays}/${d.periodDays} days reviewed")
                if (d.streak > 0) add("🔥" to "${d.streak}-day streak")
                if (d.avgRating > 0) add("★" to "${oneDp(d.avgRating)} avg rating")
            }
            bold.textSize = 52f; bold.color = onBg
            tiles.forEach { (icon, label) ->
                c.drawText("$icon  $label", 72f, y + 44f, bold)
                y += 86f
            }
            y += 24f

            if (d.topWin.isNotBlank()) {
                reg.textSize = 34f; reg.color = accent
                c.drawText("TOP WIN", 72f, y, reg); y += 56f
                bold.textSize = 44f; bold.color = onBg
                c.drawText(ellipsize("⭐  ${d.topWin}", bold, (W - 144).toFloat()), 72f, y, bold)
            }

            reg.textSize = 32f; reg.color = muted
            c.drawText("Kairo · a week, reviewed · 100% offline", 72f, (H - 60).toFloat(), reg)
        } catch (t: Throwable) {
            // Minimal safe fallback so the card is never blank.
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 64f }
            c.drawText("My week", 72f, 160f, p)
            p.textSize = 40f; p.color = muted
            c.drawText("${d.reviewedDays}/${d.periodDays} days reviewed", 72f, 240f, p)
        }
        return bmp
    }

    /** Plain-text equivalent for the weekly card's "share as text" path. */
    fun weekText(d: WeekData): String = buildString {
        append("My week — ${d.rangeLabel}\n")
        append("📆 ${d.reviewedDays}/${d.periodDays} days reviewed")
        if (d.streak > 0) append(" · 🔥 ${d.streak}-day streak")
        if (d.avgRating > 0) append(" · ★ ${oneDp(d.avgRating)} avg")
        append("\n")
        if (d.topWin.isNotBlank()) append("\n⭐ ${d.topWin}\n")
        append("\n— via Kairo")
    }

    // ── Wave 3 (feature B) · the "Year, reviewed" share card ───────────────────────────────────────────

    /** Everything the year card needs, already computed by the caller from the year recap. */
    data class YearData(
        val yearLabel: String,       // e.g. "My year" or "2026"
        val daysReviewed: Int,
        val avgRating: Double,       // 0 = none rated
        val trackedHours: Int,
        val longestStreak: Int,
        val winsCount: Int,
        val topActivity: String,     // "" = omit
        val topEmotion: String,      // "" = omit
        val highlight: String,       // already privacy-safe; "" = omit
        val accentArgb: Long?,
    )

    /**
     * Render the year recap to a PNG in the same visual language as the day/week cards. Never throws /
     * never blank: the whole draw is guarded and falls back to a minimal card, so a share can't crash or
     * produce an empty image.
     */
    fun renderYear(d: YearData): Bitmap {
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
            c.drawText("YEAR, REVIEWED", 72f, 130f, reg)
            bold.textSize = 72f; bold.color = onBg
            c.drawText(ellipsize(d.yearLabel, bold, (W - 144).toFloat()), 72f, 210f, bold)

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

            val tiles = buildList {
                add("📆" to "${d.daysReviewed} days reviewed")
                if (d.trackedHours > 0) add("⧗" to "${d.trackedHours}h tracked")
                if (d.longestStreak > 0) add("🔥" to "${d.longestStreak}-day longest streak")
                if (d.winsCount > 0) add("⭐" to "${d.winsCount} good things noticed")
                if (d.topActivity.isNotBlank()) add("🎯" to "most time on ${d.topActivity}")
                if (d.topEmotion.isNotBlank()) add("💗" to "often felt ${d.topEmotion.lowercase(java.util.Locale.getDefault())}")
            }
            bold.textSize = 50f; bold.color = onBg
            tiles.take(6).forEach { (icon, label) ->
                c.drawText(ellipsize("$icon  $label", bold, (W - 144).toFloat()), 72f, y + 44f, bold)
                y += 82f
            }
            y += 24f

            if (d.highlight.isNotBlank()) {
                reg.textSize = 34f; reg.color = accent
                c.drawText("A HIGHLIGHT", 72f, y, reg); y += 56f
                bold.textSize = 42f; bold.color = onBg; bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                wrap(d.highlight, bold, (W - 144).toFloat()).take(3).forEach { line ->
                    c.drawText(line, 72f, y, bold); y += 58f
                }
                bold.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            reg.textSize = 32f; reg.color = muted
            c.drawText("Kairo · a year, reviewed · 100% offline", 72f, (H - 60).toFloat(), reg)
        } catch (t: Throwable) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 64f }
            c.drawText("My year", 72f, 160f, p)
            p.textSize = 40f; p.color = muted
            c.drawText("${d.daysReviewed} days reviewed", 72f, 240f, p)
        }
        return bmp
    }

    /** Plain-text equivalent for the year card's "share as text" path. */
    fun yearText(d: YearData): String = buildString {
        append("${d.yearLabel}\n")
        append("📆 ${d.daysReviewed} days reviewed")
        if (d.avgRating > 0) append(" · ★ ${oneDp(d.avgRating)} avg")
        if (d.trackedHours > 0) append(" · ⧗ ${d.trackedHours}h")
        if (d.longestStreak > 0) append(" · 🔥 ${d.longestStreak}-day streak")
        append("\n")
        if (d.highlight.isNotBlank()) append("\n“${d.highlight}”\n")
        append("\n— via Kairo")
    }

    // ── Track 1.5 · the any-period recap share card ────────────────────────────────────────────────────

    /** Everything the recap card needs, already computed by the caller from the period recap. */
    data class RecapData(
        val title: String,           // e.g. "This week" / "Last month"
        val avgRating: Double,       // 0 = none rated
        val lines: List<String>,     // already-formatted stat lines ("✓ Tasks done · 12")
        val narrative: String,       // the one-paragraph story ("" = omit)
        val accentArgb: Long?,
    )

    /**
     * Render the any-period recap to a PNG in the same visual language as the day/week/year cards — accent
     * eyebrow, big title, optional rating stars, stat lines, the narrative, footer. Guarded end-to-end so a
     * share can never crash or produce a blank image.
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
