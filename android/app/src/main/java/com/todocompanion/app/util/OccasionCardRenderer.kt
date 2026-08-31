package com.todocompanion.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.todocompanion.app.data.entity.CountdownEntity
import com.todocompanion.app.domain.LifeEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * R52 — personalised, shareable "occasion cards". Renders a countdown/birthday/anniversary to a
 * portrait PNG entirely on-device with android.graphics (no Compose capture, no network). The card is
 * designed per-occasion: the accent colour, the emoji, the milestone, the zodiac and the age all come
 * from the row, so "Sara turns 30 in 12 days" looks hand-made rather than templated. Shared only if the
 * user picks a target in the system sheet — the image is generated locally and never uploaded.
 */
object OccasionCardRenderer {

    private const val W = 1080
    private const val H = 1350

    private val DATE_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

    /** One of a few tasteful accent pairs used when the occasion has no colour of its own. */
    private val FALLBACK_ACCENTS = listOf(
        0xFF6D5AE6 to 0xFF9B7BFF, 0xFFE0577E to 0xFFFF8FB1, 0xFF1E9E8A to 0xFF57D6BE,
        0xFFEA8C2E to 0xFFF9C066, 0xFF3E7BFA to 0xFF7FB0FF, 0xFFB4436C to 0xFFE87BA6,
    )

    fun render(c: CountdownEntity, today: LocalDate = LocalDate.now()): Bitmap {
        val type = LifeEvent.type(c)
        val accentPair = c.colorArgb?.let { it.toInt() to lighten(it.toInt(), 0.28f) }
            ?: FALLBACK_ACCENTS[abs(c.id.hashCode()) % FALLBACK_ACCENTS.size].let { it.first.toInt() to it.second.toInt() }
        val accent = accentPair.first
        val accent2 = accentPair.second
        val bg = 0xFF15121C.toInt()
        val onBg = 0xFFF4F1FA.toInt()
        val muted = 0xFFB4AEC4.toInt()

        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(bg)

        // Top accent banner with a soft diagonal gradient.
        val bannerH = 470f
        val banner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), bannerH, accent, accent2, Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, W.toFloat(), bannerH, banner)
        // A faint wave under the banner for depth.
        val wave = Path().apply {
            moveTo(0f, bannerH)
            cubicTo(W * 0.30f, bannerH - 70f, W * 0.70f, bannerH + 70f, W.toFloat(), bannerH - 20f)
            lineTo(W.toFloat(), bannerH + 60f); lineTo(0f, bannerH + 60f); close()
        }
        cv.drawPath(wave, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x33) })

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }
        val onAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

        // Emoji medallion.
        val emoji = c.emoji ?: type.emoji
        val medCx = W / 2f; val medCy = 200f; val medR = 108f
        cv.drawCircle(medCx, medCy, medR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        cv.drawCircle(medCx, medCy, medR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = 0x55FFFFFF })
        onAccent.textSize = 120f; onAccent.textAlign = Paint.Align.CENTER
        cv.drawText(emoji, medCx, medCy + 44f, onAccent)

        // Type label (BIRTHDAY / ANNIVERSARY …) in the banner.
        onAccent.textSize = 40f
        cv.drawText(type.label.uppercase(), medCx, 372f, onAccent)

        // Name (may wrap to two lines).
        bold.textAlign = Paint.Align.CENTER
        bold.textSize = 78f
        val name = LifeEvent.displayName(c)
        val nameLines = wrap(name, bold, (W - 120).toFloat(), 2)
        var y = 610f
        nameLines.forEach { cv.drawText(it, medCx, y, bold); y += 92f }

        // The hero countdown number.
        val days = LifeEvent.daysUntil(c, today)
        val countUp = c.countUp
        val heroNumber: String
        val heroUnit: String
        if (countUp) {
            val since = LifeEvent.daysSince(c, today)
            heroNumber = since.toString(); heroUnit = if (since == 1L) "day since" else "days since"
        } else when {
            days == 0L -> { heroNumber = "🎉"; heroUnit = "Today!" }
            days == 1L -> { heroNumber = "1"; heroUnit = "day to go" }
            days > 0L -> { heroNumber = days.toString(); heroUnit = "days to go" }
            days == -1L -> { heroNumber = "1"; heroUnit = "day ago" }
            else -> { heroNumber = abs(days).toString(); heroUnit = "days ago" }
        }
        y += 40f
        val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
            shader = LinearGradient(medCx - 260f, 0f, medCx + 260f, 0f, accent2, lighten(accent, 0.45f), Shader.TileMode.CLAMP)
        }
        heroPaint.textSize = if (heroNumber.length >= 4) 220f else 300f
        cv.drawText(heroNumber, medCx, y + 120f, heroPaint)
        reg.textAlign = Paint.Align.CENTER; reg.textSize = 46f; reg.color = onBg
        cv.drawText(heroUnit, medCx, y + 190f, reg)

        // The date line.
        val next = if (c.yearly && !countUp) LifeEvent.nextOccurrence(c, today) else LifeEvent.originDate(c)
        reg.textSize = 38f; reg.color = muted
        cv.drawText(next.format(DATE_FMT), medCx, y + 258f, reg)

        // Fact chips (age / zodiac / milestone / category) laid out centred.
        val chips = buildList {
            LifeEvent.ageChip(c, today)?.let { add(it) }
            LifeEvent.milestone(c, today)?.let { add(it) }
            LifeEvent.zodiac(c)?.let { add(it) }
            if (c.category.isNotBlank()) add(c.category)
        }.take(4)
        if (chips.isNotEmpty()) {
            val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT; textSize = 36f }
            val padX = 30f; val chipH = 74f; val gap = 18f
            val widths = chips.map { chipPaint.measureText(it) + padX * 2 }
            val totalW = widths.sum() + gap * (chips.size - 1)
            var cx = (W - totalW) / 2f
            val chipY = y + 320f
            chips.forEachIndexed { i, label ->
                val cw = widths[i]
                val rect = RectF(cx, chipY, cx + cw, chipY + chipH)
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x33) })
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = withAlpha(accent2, 0x99) })
                chipPaint.color = onBg; chipPaint.textAlign = Paint.Align.CENTER
                cv.drawText(label, cx + cw / 2f, chipY + 49f, chipPaint)
                cx += cw + gap
            }
        }

        // Footer.
        reg.textSize = 32f; reg.color = withAlpha(muted, 0xAA); reg.textAlign = Paint.Align.CENTER
        cv.drawText("ToDo Companion · Occasions · 100% offline", medCx, (H - 56).toFloat(), reg)
        return bmp
    }

    private fun wrap(text: String, p: Paint, maxW: Float, maxLines: Int): List<String> {
        if (p.measureText(text) <= maxW) return listOf(text)
        val words = text.split(" ")
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (w in words) {
            val candidate = if (cur.isEmpty()) w else "$cur $w"
            if (p.measureText(candidate) <= maxW || cur.isEmpty()) cur = StringBuilder(candidate)
            else { lines.add(cur.toString()); cur = StringBuilder(w) }
            if (lines.size == maxLines - 1 && p.measureText(cur.toString()) > maxW) break
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        // Ellipsize the last line if we overflow the line cap.
        return if (lines.size <= maxLines) lines else lines.take(maxLines).toMutableList().also {
            var last = it[maxLines - 1]
            while (last.isNotEmpty() && p.measureText("$last…") > maxW) last = last.dropLast(1)
            it[maxLines - 1] = "$last…"
        }
    }

    private fun lighten(argb: Int, f: Float): Int {
        val a = (argb ushr 24) and 0xFF
        val r = ((argb ushr 16) and 0xFF); val g = ((argb ushr 8) and 0xFF); val b = argb and 0xFF
        fun up(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
        return (a shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0x00FFFFFF)
}
