package com.todocompanion.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import kotlin.math.max
import kotlin.math.min

/**
 * Classic-RemoteViews widgets can't host a Compose canvas, so anything richer than a coloured
 * TextView — a progress ring, a contribution heatmap, a strength line, a check circle — is drawn
 * here into a [Bitmap] and handed to `RemoteViews.setImageViewBitmap`. Everything is on-device and
 * allocation-light: bitmaps are sized to the widget's actual pixels and capped so a home screen
 * full of widgets never blows the ~12 MB RemoteViews transaction budget.
 */
object WidgetBitmaps {
    /** dp → px for the current display. */
    fun dp(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    /** Clamp a requested pixel edge so a single bitmap stays well under the transaction budget. */
    private fun cap(px: Int, maxEdge: Int = 1400): Int = px.coerceIn(1, maxEdge)

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * A progress ring: a full faint track with a rounded accent arc sweeping clockwise from 12
     * o'clock. Text (the count) is overlaid by a centered TextView, not drawn here.
     */
    fun ring(
        sizePx: Int,
        strokePx: Float,
        progress: Float,
        trackColor: Int,
        fillColor: Int,
    ): Bitmap {
        val size = cap(sizePx, 900)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pad = strokePx / 2f + 1f
        val rect = RectF(pad, pad, size - pad, size - pad)
        val p = paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.ROUND
        }
        p.color = trackColor
        c.drawArc(rect, 0f, 360f, false, p)
        val sweep = progress.coerceIn(0f, 1f) * 360f
        if (sweep > 0f) {
            p.color = fillColor
            c.drawArc(rect, -90f, sweep, false, p)
        }
        return bmp
    }

    /**
     * A per-row check indicator: a filled coloured disc with a white tick when done, or a hollow
     * ring in the habit's colour when not. Small (row-height) so a full list stays cheap.
     */
    fun checkCircle(sizePx: Int, color: Int, done: Boolean): Bitmap {
        val size = cap(sizePx, 180)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val stroke = max(2f, size * 0.09f)
        val r = size / 2f - stroke
        if (done) {
            paint().apply { style = Paint.Style.FILL; this.color = color }.let { c.drawCircle(cx, cx, r + stroke * 0.5f, it) }
            val tick = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2f, size * 0.11f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = 0xFFFFFFFF.toInt()
            }
            val path = Path().apply {
                moveTo(size * 0.30f, size * 0.52f)
                lineTo(size * 0.44f, size * 0.66f)
                lineTo(size * 0.72f, size * 0.34f)
            }
            c.drawPath(path, tick)
        } else {
            paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                this.color = color
            }.let { c.drawCircle(cx, cx, r, it) }
        }
        return bmp
    }

    /**
     * A GitHub-style contribution grid: [cols] weeks across, [rows] days down, each cell's colour
     * supplied by [colorAt]. Cells are rounded squares with a hairline gap.
     */
    fun heatmap(
        cols: Int,
        rows: Int,
        cellPx: Float,
        gapPx: Float,
        colorAt: (col: Int, row: Int) -> Int,
    ): Bitmap {
        val cell = max(1f, cellPx)
        val gap = max(0f, gapPx)
        val w = cap((cols * (cell + gap) - gap).toInt())
        val h = cap((rows * (cell + gap) - gap).toInt())
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = paint().apply { style = Paint.Style.FILL }
        val radius = cell * 0.28f
        for (col in 0 until cols) {
            for (row in 0 until rows) {
                val left = col * (cell + gap)
                val top = row * (cell + gap)
                p.color = colorAt(col, row)
                c.drawRoundRect(RectF(left, top, left + cell, top + cell), radius, radius, p)
            }
        }
        return bmp
    }

    /**
     * A Loop-style strength/score line: a smooth-ish polyline over [values] (each 0..1), a soft
     * gradient area beneath it, and an emphasised endpoint dot — the "where's this habit trending"
     * read that no consumer tracker shows on the home screen.
     */
    fun line(
        widthPx: Int,
        heightPx: Int,
        values: FloatArray,
        lineColor: Int,
        areaColor: Int,
        dotColor: Int,
    ): Bitmap {
        val w = cap(widthPx)
        val h = cap(heightPx, 700)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (values.isEmpty()) return bmp
        val padY = h * 0.12f
        val usableH = h - padY * 2f
        val n = values.size
        fun x(i: Int) = if (n == 1) w / 2f else i.toFloat() / (n - 1) * (w - 2f) + 1f
        fun y(v: Float) = padY + (1f - v.coerceIn(0f, 1f)) * usableH

        val linePath = Path()
        val areaPath = Path()
        values.forEachIndexed { i, v ->
            val px = x(i); val py = y(v)
            if (i == 0) { linePath.moveTo(px, py); areaPath.moveTo(px, h.toFloat()); areaPath.lineTo(px, py) }
            else { linePath.lineTo(px, py); areaPath.lineTo(px, py) }
        }
        areaPath.lineTo(x(n - 1), h.toFloat())
        areaPath.close()

        paint().apply {
            style = Paint.Style.FILL
            shader = LinearGradient(0f, padY, 0f, h.toFloat(), areaColor, (areaColor and 0x00FFFFFF), Shader.TileMode.CLAMP)
        }.let { c.drawPath(areaPath, it) }

        paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, h * 0.03f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = lineColor
        }.let { c.drawPath(linePath, it) }

        val dotR = max(3f, h * 0.05f)
        paint().apply { style = Paint.Style.FILL; this.color = dotColor }
            .let { c.drawCircle(x(n - 1), y(values.last()), dotR, it) }
        return bmp
    }

    /**
     * Blend two ARGB colours by [t] (0 → a, 1 → b). Used to shade heatmap cells from an empty
     * ground toward the habit's colour by that day's completion intensity.
     */
    fun blend(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        val ia = 1f - f
        val aa = (a ushr 24) and 0xFF; val ab = (b ushr 24) and 0xFF
        val ra = (a ushr 16) and 0xFF; val rb = (b ushr 16) and 0xFF
        val ga = (a ushr 8) and 0xFF; val gb = (b ushr 8) and 0xFF
        val ba = a and 0xFF; val bb = b and 0xFF
        val al = (aa * ia + ab * f).toInt()
        val r = (ra * ia + rb * f).toInt()
        val g = (ga * ia + gb * f).toInt()
        val bl = (ba * ia + bb * f).toInt()
        return (al shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /** Fit a square bitmap edge (px) to a widget's min dimension in dp, within sane bounds. */
    fun squareEdge(ctx: Context, minDimDp: Int, targetDp: Float, maxDp: Float): Int {
        val target = min(minDimDp.toFloat(), maxDp) * (targetDp / maxDp)
        return dp(ctx, max(48f, target)).toInt()
    }
}
