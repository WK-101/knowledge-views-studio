package com.todocompanion.app.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File

/**
 * Renders a habit's progress — strength ring, streaks and a heatmap — to a shareable PNG entirely
 * on-device with android.graphics (no Compose capture, no network). The "show off your streak" feature
 * that stays private by construction: the image is generated locally and only shared if the user picks
 * a target. (M4.)
 */
object ProgressCard {

    /** A finished progress image plus a note of where a copy was saved (or null if the save failed). */
    data class Result(val shareUri: android.net.Uri?, val savedLocation: String?)

    private const val W = 1080
    private const val H = 1080

    fun render(
        emoji: String?, name: String, accentArgb: Long?,
        strength: Int, currentStreak: Int, bestStreak: Int, unit: String?, totalDone: Int,
        doneDays: Set<Long>, skipDays: Set<Long>, today: Long,
    ): Bitmap {
        val accent = accentArgb?.toInt() ?: 0xFF6650A4.toInt()
        val bg = 0xFF16121F.toInt()
        val onBg = 0xFFEDE8F5.toInt()
        val muted = 0xFF9B93AC.toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)

        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }

        // Header: emoji + habit name.
        bold.textSize = 68f
        val head = ((emoji?.plus("  ")) ?: "") + name
        c.drawText(ellipsize(head, bold, (W - 120).toFloat()), 72f, 130f, bold)

        // Strength ring (top-left).
        val ringCx = 250f; val ringCy = 380f; val ringR = 150f; val stroke = 34f
        val ringRect = RectF(ringCx - ringR, ringCy - ringR, ringCx + ringR, ringCy + ringR)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = 0x33FFFFFF; strokeCap = Paint.Cap.ROUND }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = accent; strokeCap = Paint.Cap.ROUND }
        c.drawArc(ringRect, -90f, 360f, false, track)
        c.drawArc(ringRect, -90f, strength / 100f * 360f, false, arc)
        bold.textSize = 92f; bold.textAlign = Paint.Align.CENTER
        c.drawText(strength.toString(), ringCx, ringCy + 20f, bold)
        reg.textSize = 34f; reg.textAlign = Paint.Align.CENTER
        c.drawText("STRENGTH", ringCx, ringCy + 74f, reg)
        bold.textAlign = Paint.Align.LEFT; reg.textAlign = Paint.Align.LEFT

        // Streaks (top-right).
        val rx = 540f
        bold.textSize = 96f; c.drawText(currentStreak.toString(), rx, 340f, bold)
        reg.textSize = 34f; c.drawText("day streak 🔥", rx, 390f, reg)
        bold.textSize = 56f; c.drawText("Best  $bestStreak", rx, 470f, bold)
        reg.textSize = 34f
        val totalLabel = if (unit != null) "$totalDone $unit logged" else "$totalDone days done"
        c.drawText(totalLabel, rx, 520f, reg)

        // Heatmap: last 26 weeks × 7 days.
        val weeks = 26
        val cell = 30f; val gap = 8f
        val gridW = weeks * (cell + gap) - gap
        val startX = (W - gridW) / 2f
        val startY = 620f
        reg.textSize = 34f; c.drawText("Last ${weeks * 7 / 7} weeks", startX, startY - 24f, reg)
        // today is at the bottom-right; walk back weeks*7 days.
        val totalDays = weeks * 7
        for (i in 0 until totalDays) {
            val day = today - (totalDays - 1 - i)
            val col = i / 7
            val row = i % 7
            val x = startX + col * (cell + gap)
            val y = startY + row * (cell + gap)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = when {
                day in doneDays -> accent
                day in skipDays -> 0x55FFFFFF
                else -> 0x1AFFFFFF
            }
            c.drawRoundRect(RectF(x, y, x + cell, y + cell), 7f, 7f, p)
        }

        // Footer.
        reg.textSize = 32f; reg.color = muted
        c.drawText("ToDo Companion · 100% offline", 72f, (H - 60).toFloat(), reg)
        return bmp
    }

    /** N4: a generic on-device recap card — a heading, a subtitle and up to 6 big stat tiles. */
    fun renderStatsCard(heading: String, subtitle: String, stats: List<Pair<String, String>>, accentArgb: Long? = null): Bitmap {
        val accent = accentArgb?.toInt() ?: 0xFF6650A4.toInt()
        val bg = 0xFF16121F.toInt(); val onBg = 0xFFEDE8F5.toInt(); val muted = 0xFF9B93AC.toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(bg)
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val reg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT }
        bold.textSize = 66f; c.drawText(ellipsize(heading, bold, (W - 120).toFloat()), 72f, 130f, bold)
        reg.textSize = 36f; c.drawText(ellipsize(subtitle, reg, (W - 120).toFloat()), 72f, 185f, reg)
        // 2-column tile grid.
        val cols = 2; val gap = 28f; val padX = 72f
        val tileW = (W - padX * 2 - gap) / cols; val tileH = 210f
        stats.take(6).forEachIndexed { i, (label, value) ->
            val col = i % cols; val row = i / cols
            val x = padX + col * (tileW + gap); val y = 250f + row * (tileH + gap)
            val bgp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14FFFFFF }
            c.drawRoundRect(RectF(x, y, x + tileW, y + tileH), 22f, 22f, bgp)
            bold.textSize = 88f; bold.color = accent
            c.drawText(value, x + 28f, y + 118f, bold); bold.color = onBg
            reg.textSize = 32f; c.drawText(ellipsize(label, reg, tileW - 40f), x + 28f, y + 165f, reg)
        }
        reg.textSize = 32f; reg.color = muted
        c.drawText("ToDo Companion · 100% offline", 72f, (H - 60).toFloat(), reg)
        return bmp
    }

    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (p.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }

    /**
     * Write [bmp] to a private cache file the FileProvider can grant, plus a keeper copy in Downloads.
     * Returns a content:// uri to share (or null if even the cache write failed).
     */
    fun saveAndShareUri(context: Context, bmp: Bitmap, fileName: String): Result {
        val bytes = java.io.ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        // 1) The grantable copy the share sheet reads.
        val shareUri = runCatching {
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val f = File(dir, fileName)
            f.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        }.getOrNull()
        // 2) A keeper copy in Downloads (offline, no permission) so the user has it regardless of sharing.
        val saved = FileExport.saveToDownloads(context, fileName, "image/png", bytes)
        return Result(shareUri, saved)
    }

    /** Fire the system share sheet for a rendered card. Offline: it only shares a local file. */
    fun share(context: Context, uri: android.net.Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, "Share progress").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
