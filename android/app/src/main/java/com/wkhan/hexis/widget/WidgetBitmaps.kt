package com.wkhan.hexis.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import com.wkhan.hexis.R
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

    /** Scale a (w,h) down so w*h*4 (ARGB bytes) stays under the RemoteViews Binder budget. The real
     *  limit is the ~1 MB Binder buffer, not the multi-MB the class once assumed — a big square icon
     *  bitmap could otherwise blow it on a large placement. */
    private fun capBytes(w: Int, h: Int, maxBytes: Long = 900_000L): Pair<Int, Int> {
        val bytes = w.toLong() * h.toLong() * 4L
        if (bytes <= maxBytes) return w to h
        val s = kotlin.math.sqrt(maxBytes.toDouble() / bytes.toDouble())
        return max(1, (w * s).toInt()) to max(1, (h * s).toInt())
    }

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
     * The app's MLO-style priority checkbox, drawn for a widget row: a rounded *square* whose 2dp
     * border and fill are the task's priority colour. Unchecked shows a faint priority tint
     * ([restTint] by level); checked fills solid with a contrast tick. Matches
     * ui.components.PriorityCheckbox one-for-one so a home-screen row reads exactly like an in-app row.
     */
    fun priorityCheckbox(sizePx: Int, color: Int, done: Boolean, restTint: Float): Bitmap {
        val size = cap(sizePx, 180)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = max(2f, size * 0.09f)
        val radius = size * 0.27f            // 6dp on a 22dp box
        val inset = stroke / 2f + 0.5f
        val rect = RectF(inset, inset, size - inset, size - inset)
        // Fill: faint tint at rest, solid when done (matches the app's alpha ramp).
        val fillAlpha = ((restTint + (1f - restTint) * (if (done) 1f else 0f)) * 255f).toInt().coerceIn(0, 255)
        if (fillAlpha > 0) {
            paint().apply { style = Paint.Style.FILL; this.color = withAlpha(color, fillAlpha) }
                .let { c.drawRoundRect(rect, radius, radius, it) }
        }
        paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color }
            .let { c.drawRoundRect(rect, radius, radius, it) }
        if (done) {
            val ink = if (luminance(color) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            val tick = paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = max(2f, size * 0.11f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                this.color = ink
            }
            val path = Path().apply {
                moveTo(size * 0.30f, size * 0.52f)
                lineTo(size * 0.44f, size * 0.66f)
                lineTo(size * 0.72f, size * 0.34f)
            }
            c.drawPath(path, tick)
        }
        return bmp
    }

    /** The app's fixed priority colour + rest-tint for a task (importance/urgency → PriorityLevel).
     *  Fixed to the light palette so priority reads the same in light/dark/AMOLED, exactly as the app. */
    fun priorityColorAndTint(importance: Int, urgency: Int): Pair<Int, Float> =
        when (com.wkhan.hexis.domain.priority.PriorityLevel.from(importance, urgency)) {
            com.wkhan.hexis.domain.priority.PriorityLevel.HIGH -> 0xFFE5484D.toInt() to 0.18f
            com.wkhan.hexis.domain.priority.PriorityLevel.MEDIUM -> 0xFFEA9A16.toInt() to 0.14f
            com.wkhan.hexis.domain.priority.PriorityLevel.LOW -> 0xFF3E7BFA.toInt() to 0.10f
            com.wkhan.hexis.domain.priority.PriorityLevel.NONE -> 0xFF9AA3B2.toInt() to 0f
        }

    private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)
    private fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** A round icon button: a filled/tinted disc with a glyph drawn on it — a stop square or a play
     *  triangle or a clock — for widget action buttons and headers (RemoteViews can't tint a vector). */
    fun roundIcon(ctx: Context, sizePx: Int, discColor: Int, glyphColor: Int, glyph: String): Bitmap {
        val size = cap(sizePx, 220)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        if (discColor != 0) {
            paint().apply { style = Paint.Style.FILL; color = discColor }.let { c.drawCircle(cx, cx, cx, it) }
        }
        // The glyph is the app's own Material icon, tinted — not a hand-drawn shape. On a disc it fills
        // ~56%; free-standing (no disc) it fills a little more so it doesn't read as small.
        val res = when (glyph) {
            "plus" -> R.drawable.wic_add
            "calendar" -> R.drawable.wic_event
            "clock" -> R.drawable.wic_time
            "stop" -> R.drawable.wic_stop
            "play" -> R.drawable.wic_play
            "today" -> R.drawable.wic_today
            else -> 0
        }
        if (res != 0) {
            val half = if (discColor != 0) size * 0.28f else size * 0.34f
            drawVector(ctx, c, res, cx, cx, half, glyphColor)
        }
        return bmp
    }

    /**
     * The chosen action's centre in the widget as (xFrac, yFrac), one per slot, for a given count.
     * These are the single source of truth for the Quick-bar "island": the drawing places each disc
     * here, and the matching `widget_qb_cluster_N` layout's weighted tap cells are authored to land on
     * exactly the same fractions — so a tap always hits the disc under the finger. Counts run 4–7
     * (the configurable range); 1–3 are sane fallbacks. See each layout XML for the mirror weights.
     */
    fun clusterPositions(n: Int): List<Pair<Float, Float>> {
        val a = 0.206f; val b = 0.5f; val d = 0.794f   // 3×3 grid cell centres (matches the tap grids)
        return when (n.coerceIn(1, 9)) {
            1 -> listOf(b to b)
            2 -> listOf(a to b, d to b)
            3 -> listOf(b to b, a to a, d to a)                                   // centre + top pair
            4 -> listOf(b to b, a to a, d to a, b to d)                           // centre + TL,TR,B
            5 -> listOf(b to b, a to a, d to a, a to d, d to d)                   // centre + 4 corners
            6 -> listOf(b to b, a to a, d to a, a to b, d to b, b to d)           // centre + TL,TR,L,R,B
            7 -> listOf(b to b, a to a, d to a, a to b, d to b, a to d, d to d)   // centre + 6-way ring
            8 -> listOf(b to b, a to a, b to a, d to a, a to b, d to b, a to d, d to d) // centre + 7 (TL,T,TR,L,R,BL,BR)
            else -> listOf(b to b, a to a, b to a, d to a, a to b, d to b, a to d, b to d, d to d) // centre + full 8 (3×3)
        }
    }

    /** The Quick-bar face: the app's own Material icons on a clean grid over one flat rounded card —
     *  the modern launcher-shortcut look. Every glyph is a real vector drawable (the same icon language
     *  used across the app), tinted to the theme; the centre glyph is the app's brand mark by default,
     *  rendered in full colour. Positions come from [clusterPositions] so the overlaid
     *  `widget_qb_cluster_N` tap grid lines up action-for-action. */
    fun quickCluster(ctx: Context, wPx: Int, hPx: Int, keys: List<String>, cardColor: Int, glyphColor: Int, accentColor: Int): Bitmap {
        // Cap both edges the same so the bitmap keeps the widget's aspect (no fitXY squashing).
        val (w, h) = capBytes(cap(wPx, 1600), cap(hPx, 1600))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val n = keys.size.coerceIn(1, 9)
        val pos = clusterPositions(n)
        val wf = w.toFloat(); val hf = h.toFloat()
        // Draw a centred SQUARE so a portrait / landscape cell still shows a square island (the reference
        // look) instead of a stretched rectangle. The leftover margins stay transparent.
        val side = min(wf, hf)
        val ox = (wf - side) / 2f
        val oy = (hf - side) / 2f
        fun cx(i: Int) = ox + pos[i].first * side
        fun cy(i: Int) = oy + pos[i].second * side

        // The island: one flat, solid rounded square card (theme-coloured, faded to opacity by the caller).
        val cardR = side * 0.15f
        paint().apply { style = Paint.Style.FILL; color = cardColor }
            .let { c.drawRoundRect(RectF(ox, oy, ox + side, oy + side), cardR, cardR, it) }

        // Sized to the reference launcher-shortcut widgets: small, well-spaced corner glyphs with a lot
        // of breathing room, and a modestly larger brand mark at the centre (never crowding the card).
        val rCorner = side * 0.082f       // ring icon half-box — compact, generous margin from the edges
        val rCenter = side * 0.098f       // centre icon half-box — a touch larger, the primary

        // Ring: the app's Material icons, tinted to the theme ink; no backgrounds, no borders.
        for (i in 1 until n) drawActionIcon(ctx, c, keys[i], cx(i), cy(i), rCorner, glyphColor)
        // Centre (slot 0): the primary action. The brand mark renders in full colour and a shade larger
        // (its own art has more internal margin, so it needs a bigger box to read at the same weight);
        // any other assigned action is tinted in the accent to stay the point of emphasis.
        val centreKey = keys[0]
        if (centreKey == "app") {
            drawActionIcon(ctx, c, "app", cx(0), cy(0), side * 0.165f, null)
        } else {
            drawActionIcon(ctx, c, centreKey, cx(0), cy(0), rCenter, accentColor)
        }
        return bmp
    }

    /** The drawable resource that represents each Quick-bar action — the app's own icon set. */
    private fun actionIconRes(key: String): Int = when (key) {
        "app" -> R.drawable.ic_launcher_foreground
        "task" -> R.drawable.wic_task
        "note" -> R.drawable.wic_note
        "habit" -> R.drawable.wic_habit
        "time" -> R.drawable.wic_time
        "search" -> R.drawable.wic_search
        "dailynote" -> R.drawable.wic_dailynote
        "closeday" -> R.drawable.wic_closeday
        "weekreview" -> R.drawable.wic_review
        else -> R.drawable.ic_launcher_foreground
    }

    /** Render one action's vector drawable centred at ([cx],[cy]) into a square of half-side [half]. */
    private fun drawActionIcon(ctx: Context, c: Canvas, key: String, cx: Float, cy: Float, half: Float, tint: Int?) =
        drawVector(ctx, c, actionIconRes(key), cx, cy, half, tint)

    /** Draw a vector drawable centred at ([cx],[cy]) into a square of half-side [half]. A non-null [tint]
     *  recolours the (single-path) glyph to the theme; null keeps the drawable's own colours (used for
     *  the full-colour brand mark). */
    private fun drawVector(ctx: Context, c: Canvas, resId: Int, cx: Float, cy: Float, half: Float, tint: Int?) {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)?.mutate() ?: return
        if (tint != null) androidx.core.graphics.drawable.DrawableCompat.setTint(d, tint)
        else androidx.core.graphics.drawable.DrawableCompat.setTintList(d, null)
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(c)
    }

    /** The Day widget's week strip: seven day columns (weekday letter over the date number), the
     *  selected day filled with an accent pill, today ringed, and a dot under any day that has items.
     *  Drawn as one bitmap; seven invisible equal tap zones sit over it for per-day selection. */
    fun weekStrip(
        widthPx: Int, heightPx: Int,
        letters: List<String>, nums: List<Int>,
        selectedIdx: Int, todayIdx: Int, hasItems: List<Boolean>,
        accent: Int, onAccent: Int, textPrimary: Int, textSecondary: Int, dotColor: Int,
    ): Bitmap {
        val (w, h) = capBytes(cap(widthPx, 1600), cap(heightPx, 400))
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val n = 7
        val colW = w / n.toFloat()
        val letterSize = h * 0.22f
        val numSize = h * 0.28f
        // Cap the pill by height so a wide widget can't grow the circle up into the weekday letters.
        val pillR = min(colW * 0.42f, h * 0.24f)
        for (i in 0 until n) {
            val cxi = colW * i + colW / 2f
            // Weekday letter (top band, clear of the circle below).
            val lp = paint().apply { textAlign = Paint.Align.CENTER; color = textSecondary; textSize = letterSize; isFakeBoldText = i == todayIdx }
            c.drawText(letters.getOrElse(i) { "" }, cxi, h * 0.24f, lp)
            // Selected pill / today ring behind the number (well below the letter row).
            val numCy = h * 0.62f
            if (i == selectedIdx) {
                paint().apply { style = Paint.Style.FILL; color = accent }.let { c.drawCircle(cxi, numCy, pillR, it) }
            } else if (i == todayIdx) {
                paint().apply { style = Paint.Style.STROKE; strokeWidth = max(2f, h * 0.03f); color = accent }.let { c.drawCircle(cxi, numCy, pillR, it) }
            }
            // Date number.
            val np = paint().apply {
                textAlign = Paint.Align.CENTER
                color = when { i == selectedIdx -> onAccent; i == todayIdx -> accent; else -> textPrimary }
                textSize = numSize; isFakeBoldText = i == selectedIdx || i == todayIdx
            }
            val fm = np.fontMetrics
            c.drawText(nums.getOrElse(i) { 0 }.toString(), cxi, numCy - (fm.ascent + fm.descent) / 2f, np)
            // Item dot.
            if (hasItems.getOrElse(i) { false }) {
                val dc = if (i == selectedIdx) onAccent else dotColor
                paint().apply { style = Paint.Style.FILL; color = dc }.let { c.drawCircle(cxi, numCy + pillR + h * 0.075f, h * 0.03f, it) }
            }
        }
        return bmp
    }

    /** A tear-off calendar date tile: an accent header band over a body panel with the day-of-month
     *  number — the leading glyph the Agenda widget uses to echo the app's calendar. */
    fun calendarIcon(sizePx: Int, accent: Int, body: Int, ink: Int, day: Int): Bitmap {
        val size = cap(sizePx, 220)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size * 0.16f
        paint().apply { style = Paint.Style.FILL; color = accent }.let { c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), r, r, it) }
        val top = size * 0.30f
        paint().apply { style = Paint.Style.FILL; color = body }.let { c.drawRoundRect(RectF(0f, top, size.toFloat(), size.toFloat()), r, r, it) }
        val tp = paint().apply {
            textAlign = Paint.Align.CENTER; color = ink; isFakeBoldText = true
            textSize = size * 0.46f
        }
        val bodyMid = top + (size - top) / 2f
        val fm = tp.fontMetrics
        c.drawText(day.toString(), size / 2f, bodyMid - (fm.ascent + fm.descent) / 2f, tp)
        return bmp
    }

    /** A small solid dot — a calendar-event marker for a task row (distinct from the check circle). */
    fun dot(sizePx: Int, color: Int): Bitmap {
        val size = cap(sizePx, 180)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        paint().apply { style = Paint.Style.FILL; this.color = color }
            .let { c.drawCircle(size / 2f, size / 2f, size * 0.22f, it) }
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

    /**
     * A "habit dot" for the compact Habit Zero densities: a filled coloured disc with a soft top-left
     * sheen and the habit's emoji (or a short label like "+3") centred on it, optionally wrapped in a
     * progress ring for numeric / timed habits. Drawn to the dot's actual pixels so a grid of them stays
     * cheap. Text is baked in (RemoteViews can't overlay a TextView on each collectionless slot cleanly).
     */
    fun habitDot(
        sizePx: Int,
        discColor: Int,
        label: String,
        ringColor: Int? = null,
        ringTrack: Int = 0x22FFFFFF,
        progress: Float = 0f,
    ): Bitmap {
        val size = cap(sizePx, 400)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val ringStroke = if (ringColor != null) max(2f, size * 0.075f) else 0f
        val discR = size / 2f - ringStroke - if (ringColor != null) max(1.5f, size * 0.03f) else max(1f, size * 0.02f)
        // Disc + a subtle radial sheen so it reads as a raised token, not a flat circle.
        paint().apply { style = Paint.Style.FILL; color = discColor }.let { c.drawCircle(cx, cx, discR, it) }
        paint().apply {
            style = Paint.Style.FILL
            shader = android.graphics.RadialGradient(
                size * 0.36f, size * 0.32f, discR * 1.15f,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
            )
        }.let { c.drawCircle(cx, cx, discR, it) }
        // Progress ring for numeric / timed dots.
        if (ringColor != null) {
            val pad = ringStroke / 2f + 0.5f
            val rect = RectF(pad, pad, size - pad, size - pad)
            val rp = paint().apply { style = Paint.Style.STROKE; strokeWidth = ringStroke; strokeCap = Paint.Cap.ROUND }
            rp.color = ringTrack; c.drawArc(rect, 0f, 360f, false, rp)
            val sweep = progress.coerceIn(0f, 1f) * 360f
            if (sweep > 0f) { rp.color = ringColor; c.drawArc(rect, -90f, sweep, false, rp) }
        }
        // Emoji / label, centred on the disc.
        val tp = paint().apply {
            textAlign = Paint.Align.CENTER
            color = 0xFFFFFFFF.toInt()
            textSize = size * (if (label.length > 2) 0.34f else 0.46f)
        }
        val fm = tp.fontMetrics
        c.drawText(label, cx, cx - (fm.ascent + fm.descent) / 2f, tp)
        return bmp
    }
}
