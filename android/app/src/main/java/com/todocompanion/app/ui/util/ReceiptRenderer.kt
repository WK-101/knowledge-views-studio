package com.todocompanion.app.ui.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.todocompanion.app.domain.done.Accomplishment
import com.todocompanion.app.domain.done.DoneKind
import com.todocompanion.app.domain.done.DoneStats
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

        // Footer: fingerprint + attribution + an offline-verify QR, pinned to the card bottom.
        val fpKey = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 28f; letterSpacing = 0.06f }
        val fpVal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; typeface = Typeface.MONOSPACE; textSize = 34f; letterSpacing = 0.06f }
        val footY = cardRect.bottom - 96f
        c.drawText("VERIFICATION", x, footY - 44f, fpKey)
        c.drawText(Integrity.fingerprint(a), x, footY, fpVal)
        val attr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = reg; textSize = 28f }
        c.drawText("ToDo Companion · sealed on-device", x, footY + 40f, attr)
        // QR of a self-verifying payload — anyone can re-derive the fingerprint and confirm the card is real.
        // Always dark-on-white (on its own white patch) so any scanner reads it, regardless of card colour.
        val qrSize = 168f
        drawQr(c, verifyPayload(a), right - qrSize, cardRect.bottom - qrSize - 40f, qrSize, Color.BLACK, Color.WHITE)

        return bmp
    }

    /** A self-describing verify token: format tag · ref · when · fingerprint. Offline-checkable by
     *  recomputing Integrity.fingerprint(ref, when) and comparing — no lookup, no server. */
    fun verifyPayload(a: Accomplishment): String = "TDC1|${a.refId}|${a.whenMillis}|${Integrity.fingerprint(a)}"

    /** A standalone QR bitmap for on-screen display (e.g. a co-sign token to hand to a peer). Null if the
     *  text is too long to encode. */
    fun qrBitmap(text: String, sizePx: Int = 512): Bitmap? {
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
        }.getOrNull() ?: return null
        val w = matrix.width; val h = matrix.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (yy in 0 until h) for (xx in 0 until w) bmp.setPixel(xx, yy, if (matrix.get(xx, yy)) Color.BLACK else Color.WHITE)
        return bmp
    }

    /** Draw a QR of [text] as a square of side [size] at (left,top), dark modules in [dark] on [light]. */
    private fun drawQr(c: Canvas, text: String, left: Float, top: Float, size: Float, dark: Int, light: Int) {
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 64, 64,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
        }.getOrNull() ?: return
        val n = matrix.width
        val cell = size / n
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = light }
        c.drawRect(RectF(left - 8f, top - 8f, left + size + 8f, top + size + 8f), bg)
        val fg = Paint().apply { color = dark }
        for (yy in 0 until n) for (xx in 0 until matrix.height) {
            if (matrix.get(xx, yy)) {
                c.drawRect(left + xx * cell, top + yy * cell, left + (xx + 1) * cell + 0.5f, top + (yy + 1) * cell + 0.5f, fg)
            }
        }
    }

    /** Frontier F2 — a sealed-year certificate: a printable, shareable summary of a whole year of finished
     *  work with the integrity chain head, rendered locally. */
    fun renderCertificate(year: Int, stats: DoneStats, chainHead: String, headCount: Int): Bitmap {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Color.parseColor("#0F172A"); val card = Color.parseColor("#111C33")
        val accent = Color.parseColor("#C9A24B"); val ink = Color.parseColor("#F1F5F9"); val muted = Color.parseColor("#94A3B8")
        c.drawColor(bg)
        val pad = 72f
        val cardRect = RectF(pad, pad, w - pad, h - pad)
        c.drawRoundRect(cardRect, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card })
        // Double border, certificate-style.
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accent; strokeWidth = 3f }
        c.drawRoundRect(RectF(cardRect.left + 18f, cardRect.top + 18f, cardRect.right - 18f, cardRect.bottom - 18f), 28f, 28f, border)
        val bold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val cx = w / 2f
        fun centered(s: String, y: Float, p: Paint) { c.drawText(s, cx - p.measureText(s) / 2f, y, p) }
        centered("CERTIFICATE OF WORK", cardRect.top + 128f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; typeface = bold; textSize = 40f; letterSpacing = 0.16f })
        centered("$year", cardRect.top + 250f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = bold; textSize = 130f })
        centered("This year you finished", cardRect.top + 330f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.SERIF; textSize = 36f })
        // Stat rows, centered.
        val rows = listOf(
            "${stats.totalTasks} tasks completed",
            "${stats.goalsAchieved} goals & projects achieved",
            "${stats.focusedMinutes / 60}h focused",
            "${stats.habitCheckins} habit days kept",
            "${stats.totalWins} wins · ${stats.activeDays} active days",
        )
        var y = cardRect.top + 430f
        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.SERIF; textSize = 46f }
        rows.forEach { centered(it, y, rowPaint); y += 74f }
        // Seal + chain head.
        val sealSize = 150f
        drawQr(c, "TDCY|$year|$headCount|$chainHead", cx - sealSize / 2f, cardRect.bottom - sealSize - 200f, sealSize, Color.BLACK, Color.WHITE)
        centered("sealed record · ${chainHead.take(16).uppercase()}", cardRect.bottom - 150f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.MONOSPACE; textSize = 26f; letterSpacing = 0.06f })
        centered("ToDo Companion — generated on-device, $year", cardRect.bottom - 96f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.SERIF; textSize = 28f })
        return bmp
    }

    /** R32 — a shareable "achievement" card for a single milestone, with an offline-verifiable QR that
     *  ties it back to the record's chain head. Square, screenshot-friendly, theme-independent. */
    fun renderMilestoneCard(emoji: String, headline: String, detail: String, qrPayload: String): Bitmap {
        val w = 1080; val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Color.parseColor("#0F172A"); val card = Color.parseColor("#111C33")
        val accent = Color.parseColor("#C9A24B"); val ink = Color.parseColor("#F1F5F9"); val muted = Color.parseColor("#94A3B8")
        c.drawColor(bg)
        val pad = 64f
        val cardRect = RectF(pad, pad, w - pad, h - pad)
        c.drawRoundRect(cardRect, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card })
        c.drawRoundRect(RectF(cardRect.left + 16f, cardRect.top + 16f, cardRect.right - 16f, cardRect.bottom - 16f), 28f, 28f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = accent; strokeWidth = 3f })
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val cx = w / 2f
        fun centered(s: String, y: Float, p: Paint) { c.drawText(s, cx - p.measureText(s) / 2f, y, p) }
        centered("MILESTONE", cardRect.top + 110f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent; typeface = bold; textSize = 38f; letterSpacing = 0.22f })
        centered(emoji, cardRect.top + 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 150f })
        centered(headline, cardRect.top + 430f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = bold; textSize = 78f })
        centered(detail, cardRect.top + 500f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.SANS_SERIF; textSize = 40f })
        val sealSize = 176f
        drawQr(c, qrPayload, cx - sealSize / 2f, cardRect.bottom - sealSize - 150f, sealSize, Color.BLACK, Color.WHITE)
        centered("verifiable · on-device · private", cardRect.bottom - 96f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.MONOSPACE; textSize = 26f; letterSpacing = 0.06f })
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
