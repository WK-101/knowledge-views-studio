package com.todocompanion.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.domain.LifeEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil

/**
 * R53 — a highly-polished, personalised "occasion card". Renders a countdown / birthday / anniversary to
 * a portrait PNG entirely on-device with android.graphics (no Compose capture, no network). Every card is
 * bespoke: a warm greeting composed from the event ("Happy 30th, Sara!"), the accent colour, emoji,
 * milestone, zodiac, and — for a birthday with a known year — the app's signature "life in weeks" grid.
 * Shared only if the user picks a target in the system sheet; the image is generated locally and never
 * uploaded.
 */
object OccasionCardRenderer {

    private const val W = 1080
    private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
    private const val LIFE_YEARS = 90       // rows in the life grid (Wait-But-Why "4600 weeks")

    private val FALLBACK_ACCENTS = listOf(
        0xFF6D5AE6 to 0xFF9B7BFF, 0xFFE0577E to 0xFFFF8FB1, 0xFF1E9E8A to 0xFF57D6BE,
        0xFFEA8C2E to 0xFFF9C066, 0xFF3E7BFA to 0xFF7FB0FF, 0xFFB4436C to 0xFFE87BA6,
    )

    fun render(c: CountdownEntity, today: LocalDate = LocalDate.now()): Bitmap {
        val type = LifeEvent.type(c)
        val (accent, accent2) = c.colorArgb?.let { it.toInt() to lighten(it.toInt(), 0.30f) }
            ?: FALLBACK_ACCENTS[abs(c.id.hashCode()) % FALLBACK_ACCENTS.size].let { it.first.toInt() to it.second.toInt() }
        val bg = 0xFF141019.toInt(); val onBg = 0xFFF4F1FA.toInt(); val muted = 0xFFB4AEC4.toInt()

        val hasWeeks = type == LifeEvent.EventType.BIRTHDAY && c.yearKnown && !c.countUp
        val h = if (hasWeeks) 1560 else 1300

        val bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(bg)

        // ── Ambient background: a soft diagonal wash + a glow behind the medallion.
        cv.drawRect(0f, 0f, W.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), h.toFloat(), withAlpha(accent, 0x3A), bg, Shader.TileMode.CLAMP)
        })
        val bandH = 560f
        cv.drawRect(0f, 0f, W.toFloat(), bandH, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), bandH, accent, accent2, Shader.TileMode.CLAMP)
        })
        // A gentle wave joining the band to the body.
        cv.drawPath(Path().apply {
            moveTo(0f, bandH); cubicTo(W * 0.32f, bandH - 74f, W * 0.68f, bandH + 74f, W.toFloat(), bandH - 26f)
            lineTo(W.toFloat(), bandH + 70f); lineTo(0f, bandH + 70f); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        // Radial glow behind the medallion.
        cv.drawCircle(W / 2f, 210f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(W / 2f, 210f, 260f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
        val onAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER }
        val cx = W / 2f

        // ── Emoji medallion.
        val emoji = c.emoji ?: type.emoji
        val medR = 112f
        cv.drawCircle(cx, 210f, medR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2BFFFFFF })
        cv.drawCircle(cx, 210f, medR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0x66FFFFFF })
        onAccent.textSize = 124f; cv.drawText(emoji, cx, 254f, onAccent)
        onAccent.textSize = 40f; cv.drawText(type.label.uppercase(), cx, 388f, onAccent)

        // ── The personalised greeting (the hero of the card).
        val greeting = greeting(c, type, today)
        bold.textSize = 84f
        val gLines = wrap(greeting, bold, (W - 130).toFloat(), 3)
        var y = 640f
        gLines.forEach { cv.drawText(it, cx, y, bold); y += 98f }

        // ── When / how long line.
        val next = if (c.yearly && !c.countUp) LifeEvent.nextOccurrence(c, today) else LifeEvent.originDate(c)
        val days = LifeEvent.daysUntil(c, today)
        val whenLine = when {
            c.countUp -> "${LifeEvent.daysSince(c, today)} days and counting"
            days == 0L -> "It's today!"
            days == 1L -> "Tomorrow · ${next.format(DATE_FMT)}"
            days > 0L -> "In $days days · ${next.format(DATE_FMT)}"
            else -> "${abs(days)} days ago"
        }
        reg.textSize = 40f; reg.color = onBg
        y += 8f; cv.drawText(whenLine, cx, y, reg)

        // ── Fact chips.
        val chips = buildList {
            LifeEvent.ageChip(c, today)?.let { add(it) }
            LifeEvent.milestone(c, today)?.let { add(it) }
            LifeEvent.zodiac(c)?.let { add(it) }
            if (c.category.isNotBlank()) add(c.category)
        }.take(4)
        if (chips.isNotEmpty()) {
            val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textSize = 36f; textAlign = Paint.Align.CENTER }
            val padX = 30f; val chipH = 74f; val gap = 18f
            val widths = chips.map { chipPaint.measureText(it) + padX * 2 }
            var startX = (W - (widths.sum() + gap * (chips.size - 1))) / 2f
            val chipY = y + 44f
            chips.forEachIndexed { i, label ->
                val cw = widths[i]; val rect = RectF(startX, chipY, startX + cw, chipY + chipH)
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x3A) })
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = withAlpha(accent2, 0xAA) })
                chipPaint.color = onBg; cv.drawText(label, startX + cw / 2f, chipY + 49f, chipPaint)
                startX += cw + gap
            }
            y = chipY + chipH
        }

        // ── The signature: life in weeks (birthday with a known year).
        if (hasWeeks) {
            val lived = LifeEvent.weeksLived(c, today)
            val total = LifeEvent.totalLifeWeeks(LIFE_YEARS)
            val pct = LifeEvent.lifeSpentPct(c, LIFE_YEARS, today) ?: 0
            val who = LifeEvent.displayName(c)
            reg.textSize = 34f; reg.color = muted
            val title = "▦  ${who.take(24)}'s life in weeks · $pct% lived"
            val panelTop = y + 54f
            cv.drawText(title, cx, panelTop, reg)

            // Grid: 52 columns (weeks) × LIFE_YEARS rows (years). Square cells sized to fit.
            val cols = 52; val rows = ceil(total / cols.toDouble()).toInt()
            val gridTop = panelTop + 36f
            val availW = (W - 120).toFloat(); val availH = (h - gridTop - 96f)
            val step = minOf(availW / cols, availH / rows)
            val cell = step * 0.82f
            val gridW = step * cols; val gx0 = (W - gridW) / 2f
            val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 }
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF }
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x55) }
            for (i in 0 until total) {
                val col = i % cols; val row = i / cols
                val x = gx0 + col * step; val yy = gridTop + row * step
                cv.drawRect(x, yy, x + cell, yy + cell, if (i < lived) filled else empty)
            }
            // A subtle marker line at "now".
            val nowRow = lived / cols
            if (nowRow < rows) {
                val ly = gridTop + (nowRow + 1) * step
                cv.drawRect(gx0, ly - 1.5f, gx0 + gridW, ly + 1.5f, edge)
            }
        }

        // ── Footer.
        reg.textSize = 32f; reg.color = withAlpha(muted, 0xAA)
        cv.drawText("ToDo Companion · Occasions · 100% offline", cx, (h - 52).toFloat(), reg)
        return bmp
    }

    /** A warm, event-aware headline. */
    private fun greeting(c: CountdownEntity, type: LifeEvent.EventType, today: LocalDate): String {
        val name = LifeEvent.displayName(c)
        val days = LifeEvent.daysUntil(c, today)
        val n = LifeEvent.ageAtNext(c, today)
        return when (type) {
            LifeEvent.EventType.BIRTHDAY -> when {
                days == 0L && n != null -> "Happy ${ordinal(n)}\nBirthday, $name!"
                days == 0L -> "Happy Birthday,\n$name!"
                n != null -> "$name turns $n"
                else -> "$name's Birthday"
            }
            LifeEvent.EventType.ANNIVERSARY -> when {
                days == 0L && n != null -> "Happy ${ordinal(n)}\nAnniversary!"
                days == 0L -> "Happy Anniversary,\n$name!"
                n != null -> "$name · $n years"
                else -> name
            }
            LifeEvent.EventType.MEMORIAL -> if (n != null) "$name\nRemembered · $n years" else "Remembering\n$name"
            LifeEvent.EventType.HOLIDAY, LifeEvent.EventType.NAME_DAY -> if (days == 0L) "Happy $name!" else name
            else -> if (c.countUp) "$name" else name
        }
    }

    private fun ordinal(n: Int): String {
        val s = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th"
        }
        return "$n$s"
    }

    private fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
        // Honour explicit newlines first, then word-wrap each segment.
        val out = ArrayList<String>()
        for (segment in text.split("\n")) {
            if (p.measureText(segment) <= maxW) { out.add(segment); continue }
            val words = segment.split(" "); var cur = StringBuilder()
            for (w in words) {
                val cand = if (cur.isEmpty()) w else "$cur $w"
                if (p.measureText(cand) <= maxW || cur.isEmpty()) cur = StringBuilder(cand)
                else { out.add(cur.toString()); cur = StringBuilder(w) }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
        }
        if (out.size <= maxLines) return out
        val kept = out.take(maxLines).toMutableList()
        var last = kept[maxLines - 1]
        while (last.isNotEmpty() && p.measureText("$last…") > maxW) last = last.dropLast(1)
        kept[maxLines - 1] = "$last…"
        return kept
    }

    private fun lighten(argb: Int, f: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
        fun up(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0x00FFFFFF)
}
