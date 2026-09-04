package com.todocompanion.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

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
