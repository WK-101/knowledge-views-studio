package com.todocompanion.app.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.Integrity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 5 — a proof-of-work receipt: a self-contained, timestamped card minted locally from a finished
 * item and shared as an image. Pure formatting over data already on the device (completedAt, durationMin,
 * outcome) — no network, no upload. The fingerprint ties it back to the on-device integrity chain.
 */
object ReceiptRenderer {
    fun render(a: Accomplishment, listName: String?, zone: ZoneId = ZoneId.systemDefault()): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = Color.parseColor("#0F172A")
        val card = Color.parseColor("#111C33")
        val accent = a.colorArgb?.let { (it and 0xFFFFFFFF).toInt() }?.let { if (Color.alpha(it) == 0) it or (0xFF shl 24) else it } ?: Color.parseColor("#12A594")
        val ink = Color.parseColor("#F1F5F9")
        val muted = Color.parseColor("#94A3B8")

        c.drawColor(bg)
        val pad = 84f
        val cardRect = RectF(pad, pad, w - pad, h - pad)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card }
        c.drawRoundRect(cardRect, 44f, 44f, fill)
        // Accent header bar.
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawRoundRect(RectF(cardRect.left, cardRect.top, cardRect.right, cardRect.top + 20f), 44f, 44f, barPaint)
        c.drawRect(RectF(cardRect.left, cardRect.top + 14f, cardRect.right, cardRect.top + 24f), fill)

        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val reg = Typeface.DEFAULT
        val x = cardRect.left + 64f
        val right = cardRect.right - 64f
        var y = cardRect.top + 128f

        // "PROOF OF WORK" eyebrow.
        val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; typeface = bold; textSize = 34f; letterSpacing = 0.28f }
        c.drawText("PROOF OF WORK", x, y, eyebrow)
        y += 26f

        // Kind badge.
        val kindText = when (a.kind) {
            DoneKind.GOAL -> "GOAL ACHIEVED"; DoneKind.PROJECT -> "PROJECT DONE"
            DoneKind.HABIT -> "HABIT KEPT"; DoneKind.FOCUS -> "FOCUS SESSION"; else -> "TASK COMPLETED"
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 30f; letterSpacing = 0.1f }
        y += 44f
        c.drawText((if (a.isWin) "⭐ " else "") + kindText, x, y, badgePaint)

        // Title, wrapped.
        y += 84f
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = bold; textSize = 78f }
        val maxW = right - x
        val lines = wrap(a.title.ifBlank { "Untitled" }, titlePaint, maxW).take(4)
        for (line in lines) { c.drawText(line, x, y, titlePaint); y += 92f }

        // Meta rows.
        y += 20f
        val metaKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 36f }
        val metaVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = bold; textSize = 40f }
        val date = Instant.ofEpochMilli(a.whenMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()))
        fun metaRow(k: String, v: String) {
            c.drawText(k, x, y, metaKey); y += 48f
            c.drawText(v, x, y, metaVal); y += 74f
        }
        metaRow("Completed", date)
        if (a.durationMin > 0) {
            val hm = if (a.durationMin >= 60) "${a.durationMin / 60}h ${a.durationMin % 60}m" else "${a.durationMin}m"
            metaRow("Effort", hm)
        }
        listName?.takeIf { it.isNotBlank() && it != "Inbox" }?.let { metaRow("In", it) }

        // Outcome quote.
        a.outcome?.takeIf { it.isNotBlank() }?.let { out ->
            y += 8f
            val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC); textSize = 40f }
            val ol = wrap("“$out”", quotePaint, maxW).take(3)
            // Accent rule beside the quote.
            val ruleTop = y - 40f
            for (line in ol) { c.drawText(line, x + 28f, y, quotePaint); y += 54f }
            c.drawRect(RectF(x, ruleTop, x + 8f, y - 40f), barPaint)
        }

        // Footer: fingerprint + attribution, pinned to the card bottom.
        val fpKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 28f; letterSpacing = 0.06f }
        val fpVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; typeface = Typeface.MONOSPACE; textSize = 34f; letterSpacing = 0.06f }
        val footY = cardRect.bottom - 96f
        c.drawText("VERIFICATION", x, footY - 44f, fpKey)
        c.drawText(Integrity.fingerprint(a), x, footY, fpVal)
        val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 28f }
        val attrText = "ToDo Companion · sealed on-device"
        c.drawText(attrText, right - attr.measureText(attrText), footY, attr)

        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (word in words) {
            val candidate = if (sb.isEmpty()) word else "$sb $word"
            if (paint.measureText(candidate) <= maxWidth) {
                sb.setLength(0); sb.append(candidate)
            } else {
                if (sb.isNotEmpty()) out.add(sb.toString())
                // Hard-break a single over-long word.
                if (paint.measureText(word) > maxWidth) {
                    var chunk = StringBuilder()
                    for (ch in word) {
                        if (paint.measureText(chunk.toString() + ch) > maxWidth) { out.add(chunk.toString()); chunk = StringBuilder() }
                        chunk.append(ch)
                    }
                    sb.setLength(0); sb.append(chunk)
                } else { sb.setLength(0); sb.append(word) }
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
