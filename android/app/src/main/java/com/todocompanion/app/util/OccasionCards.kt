package com.todocompanion.app.util

import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
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
import java.time.format.TextStyle
import java.util.Locale
import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * R108 — the Occasion "Card Studio". A gift-card renderer that draws a shareable occasion card to a PNG
 * entirely on-device (android.graphics, no assets, no Compose capture, no network). The caller picks a
 * [Template] skin, an aspect [Ratio], an accent, a free-text message and a set of [Modules]; the card is
 * composed with a measured vertical-layout engine (every block is measured, stacked with gaps and
 * auto-scaled to fit its region) so items never overlap regardless of which modules are on or which shape
 * is chosen. Ornaments — soft-edged watercolour, daisies, layered roses, gradient balloons, bunting, gift
 * boxes, confetti and the life-in-weeks grid — are drawn procedurally (no assets, no permissions).
 *
 * Built-in skins (birthday-first, from real gift-card references):
 *   • BLOOM   — cream ground, a soft watercolour splash and daisies, a graceful headline (centre column).
 *   • FIESTA  — blush ground, bunting + balloons + gift boxes around a dashed-ring badge (party card).
 *   • ELEGANT — paper ground + mint wash, pink rose corners, a thin serif caps headline + rule + message.
 *   • WEEKS   — the signature "life in weeks" grid, a full-bleed dark card that fills the chosen shape.
 */
object OccasionCards {

    // ── Public spec ───────────────────────────────────────────────────────────────────────────────

    enum class Ratio(val label: String, val w: Int, val h: Int) {
        SQUARE("Square", 1080, 1080),
        PORTRAIT("Portrait", 1080, 1350),
        STORY("Story", 1080, 1920),
    }

    enum class Template(val id: String, val label: String) {
        BLOOM("bloom", "Bloom"),
        FIESTA("fiesta", "Fiesta"),
        ELEGANT("elegant", "Elegant"),
        WEEKS("weeks", "Life in weeks");

        companion object { fun from(id: String?): Template = entries.firstOrNull { it.id == id } ?: BLOOM }
    }

    /** Which optional items appear on the card. Templates honour every flag they can place. */
    data class Modules(
        val name: Boolean = true,
        val age: Boolean = true,
        val date: Boolean = true,
        val countdown: Boolean = false,
        val message: Boolean = true,
        val zodiac: Boolean = false,
        val milestone: Boolean = true,
        val weeks: Boolean = false,
        val footer: Boolean = true,
    )

    data class Spec(
        val template: Template = Template.BLOOM,
        val ratio: Ratio = Ratio.SQUARE,
        val accentArgb: Long? = null,
        val message: String = "",
        val modules: Modules = Modules(),
    )

    private const val LIFE_YEARS = 80

    // ── Entry points ────────────────────────────────────────────────────────────────────────────

    fun render(c: CountdownEntity, spec: Spec, today: LocalDate = LocalDate.now()): Bitmap {
        val w = spec.ratio.w; val h = spec.ratio.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val ct = content(c, spec, today)
        val rnd = Random(kotlin.math.abs(c.id.hashCode()).toLong() + spec.template.ordinal)
        // A card must never come back blank: if any skin throws mid-draw, fall back to a clean simple card.
        try {
            when (spec.template) {
                Template.BLOOM -> bloom(cv, w, h, ct, spec, rnd)
                Template.FIESTA -> fiesta(cv, w, h, ct, spec, rnd)
                Template.ELEGANT -> elegant(cv, w, h, ct, spec, rnd)
                Template.WEEKS -> weeks(cv, w, h, ct, spec)
            }
        } catch (t: Throwable) {
            fallbackCard(cv, w, h, ct, spec)
        }
        return bmp
    }

    /** A clean, dependency-light card used if a skin ever throws — so the share never produces a blank. */
    private fun fallbackCard(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF6D5AE6.toInt()
        val bg = lighten(accent, 0.86f); val ink = darken(accent, 0.5f); val s = min(w, h).toFloat()
        cv.drawColor(bg)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), withAlpha(accent, 0x24), withAlpha(bg, 0x00), Shader.TileMode.CLAMP)
        })
        val blocks = ArrayList<Block>()
        blocks += TextB(ct.head, s * 0.11f, Typeface.create(Typeface.SERIF, Typeface.BOLD), ink, lineSp = 1.0f)
        if (spec.modules.name && ct.name.isNotBlank()) blocks += TextB(ct.name, s * 0.075f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), accent)
        metaLine(ct, spec.modules)?.let { blocks += TextB(it, s * 0.036f, Typeface.DEFAULT, darken(accent, 0.25f)) }
        if (spec.modules.message && ct.message.isNotBlank()) blocks += TextB(quote(ct.message), s * 0.04f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), darken(accent, 0.3f), maxLines = 4)
        layoutColumn(cv, w / 2f, w * 0.82f, h * 0.2f, h * 0.82f, Paint.Align.CENTER, VAlign.CENTER, s * 0.022f, blocks)
        if (spec.modules.footer) footer(cv, w, h, withAlpha(ink, 0xAA), Paint.Align.CENTER)
    }

    /** Plain-text equivalent for the "As text" share path. */
    fun text(c: CountdownEntity, spec: Spec, today: LocalDate = LocalDate.now()): String {
        val ct = content(c, spec, today)
        val m = spec.modules
        return buildString {
            append(ct.head)
            if (m.name && ct.name.isNotBlank()) append(" ${ct.name}")
            append("\n")
            if (m.date) append("${ct.dateLong}\n")
            if (m.countdown && ct.countdown.isNotBlank()) append("${ct.countdown}\n")
            if (m.age && ct.ageLine != null) append("${ct.ageLine}\n")
            val chips = listOfNotNull(ct.milestone?.takeIf { m.milestone }, ct.zodiac?.takeIf { m.zodiac })
            if (chips.isNotEmpty()) append("${chips.joinToString(" · ")}\n")
            if (m.weeks && ct.weeksPct != null) append("▦ ${ct.weeksLived} weeks lived · ${ct.weeksPct}% of an ${LIFE_YEARS}-year life\n")
            if (m.message && ct.message.isNotBlank()) append("\n“${ct.message}”\n")
            if (m.footer) append("\n— Made with Kairo")
        }
    }

    private fun defaultMessage(type: LifeEvent.EventType): String = when (type) {
        LifeEvent.EventType.BIRTHDAY -> "Wishing you all the best on your special day."
        LifeEvent.EventType.ANNIVERSARY -> "Here's to you both — and to many more years together."
        LifeEvent.EventType.MEMORIAL -> "Loved, remembered, and missed — always."
        LifeEvent.EventType.HOLIDAY, LifeEvent.EventType.NAME_DAY -> "Wishing you a joyful day."
        else -> "Counting down to the big day."
    }

    // ── Content assembly ──────────────────────────────────────────────────────────────────────────

    private data class Content(
        val type: LifeEvent.EventType,
        val head: String,
        val name: String,
        val ageBadge: String?,
        val ageLine: String?,
        val dateLong: String,
        val dateCaps: String,
        val countdown: String,
        val message: String,
        val milestone: String?,
        val zodiac: String?,
        val weeksLived: String?,
        val weeksPct: Int?,
    )

    private fun content(c: CountdownEntity, spec: Spec, today: LocalDate): Content {
        val type = LifeEvent.type(c)
        val name = LifeEvent.displayName(c)
        val date = if (c.yearly && !c.countUp) LifeEvent.nextOccurrence(c, today) else LifeEvent.originDate(c)
        val ageAtNext = LifeEvent.ageAtNext(c, today)
        val days = LifeEvent.daysUntil(c, today)
        val head = when (type) {
            LifeEvent.EventType.BIRTHDAY -> "Happy Birthday"
            LifeEvent.EventType.ANNIVERSARY -> "Happy Anniversary"
            LifeEvent.EventType.MEMORIAL -> "In Loving Memory"
            LifeEvent.EventType.NAME_DAY -> "Happy Name Day"
            LifeEvent.EventType.HOLIDAY -> "Happy ${name}"
            else -> name
        }
        val countdown = when {
            c.countUp -> "${LifeEvent.daysSince(c, today)} days and counting"
            days == 0L -> "It's today!"
            days == 1L -> "Tomorrow"
            days > 0L -> "In $days days"
            else -> "${kotlin.math.abs(days)} days ago"
        }
        val msg = spec.message.ifBlank { c.notes.ifBlank { if (spec.modules.message) defaultMessage(type) else "" } }
        return Content(
            type = type,
            head = head,
            name = name,
            ageBadge = ageAtNext?.takeIf { type == LifeEvent.EventType.BIRTHDAY }?.toString(),
            ageLine = LifeEvent.ageChip(c, today),
            dateLong = "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${date.year}",
            dateCaps = "${date.dayOfMonth}${ordinalSuffix(date.dayOfMonth).uppercase()} ${date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).uppercase()} ${date.year}",
            countdown = countdown,
            message = msg,
            milestone = LifeEvent.milestone(c, today),
            zodiac = LifeEvent.zodiac(c),
            weeksLived = if (c.yearKnown) "%,d".format(LifeEvent.weeksLived(c, today)) else null,
            weeksPct = LifeEvent.lifeSpentPct(c, LIFE_YEARS, today),
        )
    }

    /** milestone + zodiac only — age is placed by each template so it never appears twice. */
    private fun chipsFor(ct: Content, m: Modules): List<String> = buildList {
        if (m.milestone) ct.milestone?.let { add(it) }
        if (m.zodiac) ct.zodiac?.let { add(it) }
    }

    // ── Skin 1 · BLOOM ──────────────────────────────────────────────────────────────────────────────

    private fun bloom(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF7FCBB0.toInt()
        val cream = 0xFFFCF8EC.toInt()
        val ink = darken(accent, 0.58f)
        val muted = darken(accent, 0.32f)
        val petal = 0xFFFBF3DC.toInt(); val core = 0xFFF2B531.toInt(); val sage = 0xFF9AC1A4.toInt()
        cv.drawColor(cream)
        // Soft vignette so the ground has depth.
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w / 2f, h * 0.44f, max(w, h) * 0.75f, withAlpha(cream, 0x00), withAlpha(darken(accent, 0.2f), 0x1A), Shader.TileMode.CLAMP)
        })

        // Watercolour splash behind the centre column.
        watercolorBlob(cv, w / 2f, h * 0.42f, min(w, h) * 0.40f, accent, rnd)

        // Corner daisy clusters + stemmed daisies at the bottom.
        val s = min(w, h).toFloat()
        daisyCluster(cv, w * 0.13f, h * 0.11f, s * 0.075f, petal, core, sage, rnd)
        daisyCluster(cv, w * 0.88f, h * 0.10f, s * 0.075f, petal, core, sage, rnd)
        stemmedDaisy(cv, w * 0.10f, h.toFloat(), h * 0.24f, s * 0.07f, petal, core, sage)
        stemmedDaisy(cv, w * 0.19f, h.toFloat(), h * 0.18f, s * 0.05f, petal, core, sage)
        stemmedDaisy(cv, w * 0.90f, h.toFloat(), h * 0.24f, s * 0.07f, petal, core, sage)
        stemmedDaisy(cv, w * 0.81f, h.toFloat(), h * 0.18f, s * 0.05f, petal, core, sage)

        val script = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val blocks = ArrayList<Block>()
        if (spec.modules.age && ct.ageBadge != null) blocks += AgeBadge(ct.ageBadge, s * 0.13f, accent)
        blocks += TextB(ct.head, s * 0.115f, script, ink, lineSp = 0.98f, shadow = 0x22000000)
        if (spec.modules.name && ct.name.isNotBlank()) blocks += TextB(ct.name, s * 0.10f, script, darken(accent, 0.42f), lineSp = 1.0f)
        chipsFor(ct, spec.modules).takeIf { it.isNotEmpty() }?.let { blocks += Chips(it, accent, ink, s * 0.034f) }
        metaLine(ct, spec.modules)?.let { blocks += TextB(it, s * 0.036f, serif, muted, lineSp = 1.1f) }
        if (spec.modules.message && ct.message.isNotBlank()) blocks += TextB(quote(ct.message), s * 0.040f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), muted, lineSp = 1.2f, maxLines = 4)
        if (spec.modules.weeks && ct.weeksPct != null) blocks += WeeksStrip(ct, accent, muted, light = true)

        layoutColumn(cv, w / 2f, w * 0.80f, h * 0.15f, h * 0.83f, Paint.Align.CENTER, VAlign.CENTER, s * 0.02f, blocks)
        if (spec.modules.footer) footer(cv, w, h, withAlpha(muted, 0xC0), Paint.Align.CENTER)
    }

    // ── Skin 2 · FIESTA ─────────────────────────────────────────────────────────────────────────────

    private fun fiesta(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF2E4A6B.toInt()
        val blush = 0xFFF9DCCE.toInt()
        val ink = darken(accent, 0.05f)
        val coral = 0xFFF28C8C.toInt(); val blue = 0xFF8FB8DB.toInt(); val yellow = 0xFFF3C24E.toInt(); val gold = 0xFFD9A64E.toInt()
        val party = intArrayOf(coral, blue, yellow, lighten(accent, 0.3f), 0xFFEF9F76.toInt())
        cv.drawColor(blush)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h.toFloat(), lighten(blush, 0.25f), blush, Shader.TileMode.CLAMP)
        })
        val cx = w / 2f; val cy = h / 2f
        val tall = h.toFloat() / w.toFloat()
        confetti(cv, w, h, party, rnd, count = if (tall > 1.4f) 120 else if (tall > 1.05f) 90 else 62)

        // Badge grows for taller frames so the balloon+badge+gift ensemble fills the height instead of
        // floating in the middle of empty bands.
        val badgeR = (min(w, h) * (0.36f + (tall - 1f).coerceIn(0f, 1f) * 0.11f)).coerceAtMost(w * 0.44f)
        // Bunting across the top, two strings meeting near the centre.
        bunting(cv, w * 0.02f, h * 0.06f, cx, h * 0.045f, party, 8)
        bunting(cv, cx, h * 0.045f, w * 0.98f, h * 0.08f, party, 8)

        // Balloons around the upper arc of the badge (varied sizes, gradient shaded).
        val spots = listOf(-1.02f to 0.55f, -0.78f to 0.92f, -0.42f to 1.12f, -0.02f to 1.2f, 0.4f to 1.12f, 0.76f to 0.92f, 1.02f to 0.55f, -0.62f to 0.25f, 0.62f to 0.25f)
        spots.forEachIndexed { i, (ax, ay) ->
            val bx = cx + ax * badgeR * 1.06f
            val by = cy - badgeR * 0.5f - ay * badgeR * 0.42f
            val sc = 0.15f + (i % 3) * 0.02f
            balloon(cv, bx, by, badgeR * sc, badgeR * (sc + 0.05f), party[i % party.size], cx + ax * badgeR * 0.35f, cy - badgeR * 0.15f)
        }
        // Gift boxes along the bottom.
        val giftW = badgeR * 0.36f
        for (i in -2..2) {
            val gx = cx + i * giftW * 1.16f - giftW / 2f
            val gy = cy + badgeR * 0.7f
            giftBox(cv, gx, gy, giftW, giftW * 0.92f, party[(i + 4) % party.size], gold)
        }

        // Badge with a soft drop shadow, gold ring and inner dashed ring.
        cv.drawCircle(cx, cy + 8f, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000; maskFilter = BlurMaskFilter(28f, BlurMaskFilter.Blur.NORMAL) })
        cv.drawCircle(cx, cy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(cx, cy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = gold })
        cv.drawCircle(cx, cy, badgeR - 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = withAlpha(ink, 0x66); pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f)
        })

        // Words, measured to fit inside the badge's inscribed rectangle.
        val boxW = badgeR * 1.42f
        val boxHalf = sqrt((badgeR - 40f) * (badgeR - 40f) - (boxW / 2f) * (boxW / 2f)).coerceAtLeast(badgeR * 0.5f)
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        val head = ct.head.uppercase().split(" ")
        val blocks = ArrayList<Block>()
        blocks += TextB(head.first(), badgeR * 0.155f, serif, ink, letterSp = 0.28f)
        if (head.size > 1) blocks += TextB(head.drop(1).joinToString(" "), badgeR * 0.3f, serifBold, ink, letterSp = 0.04f)
        if (spec.modules.name && ct.name.isNotBlank()) {
            blocks += TextB("To You", badgeR * 0.085f, serifBold, ink)
            blocks += TextB(quote(ct.name), badgeR * 0.14f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), darken(accent, 0.05f), lineSp = 1.0f)
        }
        subCaps(ct, spec.modules)?.let { blocks += TextB(it, badgeR * 0.058f, serifBold, withAlpha(ink, 0xCC), letterSp = 0.14f) }
        if (spec.modules.milestone && ct.milestone != null) blocks += TextB(ct.milestone, badgeR * 0.072f, serifBold, darken(accent, 0.05f))
        if (spec.modules.message && ct.message.isNotBlank()) blocks += TextB(ct.message.uppercase(), badgeR * 0.056f, Typeface.DEFAULT, withAlpha(ink, 0xB0), lineSp = 1.25f, letterSp = 0.04f, maxLines = 3)

        layoutColumn(cv, cx, boxW, cy - boxHalf, cy + boxHalf, Paint.Align.CENTER, VAlign.CENTER, badgeR * 0.05f, blocks)
        if (spec.modules.footer) footer(cv, w, h, withAlpha(ink, 0x99), Paint.Align.CENTER)
    }

    // ── Skin 3 · ELEGANT ────────────────────────────────────────────────────────────────────────────

    private fun elegant(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFFBADFD1.toInt()
        val paper = 0xFFF5F2E9.toInt()
        val ink = 0xFF1C1B1A.toInt()
        val rose = 0xFFF0A6A6.toInt(); val roseDeep = 0xFFDB7B7B.toInt(); val foliage = 0xFF6E8B5A.toInt()
        cv.drawColor(paper)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h.toFloat(), w.toFloat(), 0f, withAlpha(accent, 0x6E), withAlpha(paper, 0x00), Shader.TileMode.CLAMP)
        })
        paperGrain(cv, w, h, rnd)
        val s = min(w, h).toFloat()
        // Rose clusters: top-right and bottom-left (kept out of the left text column).
        roseCluster(cv, w * 0.9f, h * 0.1f, s * 0.16f, rose, roseDeep, foliage, rnd)
        roseCluster(cv, w * 0.1f, h * 0.92f, s * 0.19f, rose, roseDeep, foliage, rnd)

        val marginX = w * 0.075f
        val colW = w * 0.60f
        val display = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val serif = Typeface.SERIF
        val blocks = ArrayList<Block>()
        blocks += TextB(ct.head.uppercase(), s * 0.115f, display, ink, lineSp = 1.0f, letterSp = 0.03f)
        blocks += Rule(w * 0.16f, ink)
        if (spec.modules.name && ct.name.isNotBlank()) blocks += TextB(ct.name, s * 0.055f, Typeface.create(Typeface.SERIF, Typeface.ITALIC), darken(accent, 0.5f), lineSp = 1.05f)
        if (spec.modules.message && ct.message.isNotBlank()) blocks += TextB(ct.message, s * 0.041f, serif, withAlpha(ink, 0xE0), lineSp = 1.32f, maxLines = 7)
        metaLine(ct, spec.modules)?.let { blocks += TextB(it, s * 0.032f, serif, withAlpha(ink, 0x99), lineSp = 1.15f, letterSp = 0.02f) }
        chipsFor(ct, spec.modules).takeIf { it.isNotEmpty() }?.let { blocks += Chips(it, darken(accent, 0.45f), ink, s * 0.03f, Paint.Align.LEFT) }
        if (spec.modules.weeks && ct.weeksPct != null) blocks += WeeksStrip(ct, darken(accent, 0.4f), withAlpha(ink, 0x99), light = true, align = Paint.Align.LEFT)

        // Centre the column in the upper-middle so the content is balanced (not top-packed) in every
        // shape, while the rose clusters anchor the opposite corners. Bigger gaps give an editorial feel.
        layoutColumn(cv, marginX, colW, h * 0.14f, h * 0.72f, Paint.Align.LEFT, VAlign.CENTER, s * 0.03f, blocks)
        if (spec.modules.footer) footer(cv, w, h, withAlpha(ink, 0x77), Paint.Align.LEFT, marginX)
    }

    // ── Skin 4 · WEEKS — full-bleed, fills the chosen shape ───────────────────────────────────────────

    private fun weeks(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF7C6BE6.toInt()
        val accent2 = lighten(accent, 0.36f)
        val bg = 0xFF141019.toInt(); val onBg = 0xFFF4F1FA.toInt(); val muted = 0xFF9A93AC.toInt()
        cv.drawColor(bg)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), withAlpha(accent, 0x33), bg, Shader.TileMode.CLAMP)
        })
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(w * 0.5f, h * 0.5f, max(w, h) * 0.7f, 0x00000000, 0x40000000, Shader.TileMode.CLAMP)
        })
        val marginX = w * 0.08f
        val s = min(w, h).toFloat()

        // Header.
        val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.24f; textSize = s * 0.032f }
        var y = h * 0.085f
        cv.drawText("LIFE IN WEEKS", marginX, y, eyebrow)
        y += s * 0.075f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.DEFAULT_BOLD; textSize = s * 0.062f }
        cv.drawText(ellipsize(if (spec.modules.name) ct.name else "A life in weeks", title, w - marginX * 2), marginX, y, title)

        // Big % stat with supporting lines.
        val pct = ct.weeksPct ?: 0
        y += s * 0.14f
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2; typeface = Typeface.DEFAULT_BOLD; textSize = s * 0.15f }
        cv.drawText("$pct%", marginX, y, big)
        val statR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = s * 0.034f }
        val statX = marginX + big.measureText("$pct%") + s * 0.03f
        cv.drawText("of an ${LIFE_YEARS}-year life", statX, y - s * 0.055f, statR)
        cv.drawText("lived so far", statX, y - s * 0.015f, statR)
        cv.drawText("${ct.weeksLived ?: "0"} weeks", statX, y + s * 0.025f, statR.apply { color = onBg })

        // Grid — fills from here to the legend, using the whole width; cell scales with the shape.
        val gridTop = y + s * 0.05f
        val gridBottom = h - s * 0.09f
        val labelW = s * 0.05f
        val gridLeft = marginX + labelW
        val gridRight = w - marginX
        val cols = 52; val rows = LIFE_YEARS
        val step = min((gridRight - gridLeft) / cols, (gridBottom - gridTop) / rows)
        val cell = step * 0.78f; val rad = cell * 0.32f
        val lived = totalLivedWeeks(ct)
        val gridW = step * cols
        val gx = gridLeft + (gridRight - gridLeft - gridW) / 2f
        val gridH = step * rows
        val filled = Paint(Paint.ANTI_ALIAS_FLAG)
        val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x16FFFFFF }
        // Faint decade bands for structure.
        for (d in 0 until rows step 20) {
            cv.drawRect(gx - labelW * 0.3f, gridTop + d * step, gx + gridW, gridTop + (d + 10).coerceAtMost(rows) * step,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x08FFFFFF })
        }
        val total = rows * cols
        for (i in 0 until total) {
            val col = i % cols; val row = i / cols
            val x = gx + col * step; val yy = gridTop + row * step
            filled.color = lerp(accent, accent2, row.toFloat() / rows)
            cv.drawRoundRect(RectF(x, yy, x + cell, yy + cell), rad, rad, if (i < lived) filled else empty)
        }
        // Decade age labels.
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = min(s * 0.026f, step * 1.1f); textAlign = Paint.Align.RIGHT }
        for (d in 0..rows step 10) if (d < rows) cv.drawText(d.toString(), gx - 12f, gridTop + d * step + cell, lab)
        // "Now" marker.
        val nowRow = lived / cols
        if (nowRow < rows) {
            val ly = gridTop + (nowRow + 1) * step
            cv.drawRect(gx, ly - 2.5f, gx + gridW, ly + 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
            cv.drawCircle(gx - 20f, ly, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
        }
        // Legend + footer share the bottom strip.
        val legY = gridTop + gridH + s * 0.045f
        val lg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = s * 0.026f; textAlign = Paint.Align.LEFT }
        cv.drawRoundRect(RectF(marginX, legY - s * 0.02f, marginX + s * 0.022f, legY), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
        cv.drawText("lived", marginX + s * 0.032f, legY, lg)
        cv.drawRoundRect(RectF(marginX + s * 0.14f, legY - s * 0.02f, marginX + s * 0.162f, legY), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF })
        cv.drawText("to come", marginX + s * 0.172f, legY, lg)
        if (spec.modules.footer) footer(cv, w, h, muted, Paint.Align.RIGHT, w - marginX)
    }

    private fun totalLivedWeeks(ct: Content): Int =
        (ct.weeksLived?.replace(",", "")?.toIntOrNull() ?: 0).coerceIn(0, LIFE_YEARS * 52)

    // ── Content line helpers ──────────────────────────────────────────────────────────────────────

    private fun metaLine(ct: Content, m: Modules): String? = buildList {
        if (m.date) add(ct.dateLong)
        if (m.countdown && ct.countdown.isNotBlank()) add(ct.countdown)
        if (m.age && ct.ageLine != null) add(ct.ageLine)
    }.takeIf { it.isNotEmpty() }?.joinToString("   ·   ")

    private fun subCaps(ct: Content, m: Modules): String? = buildList {
        if (m.date) add(ct.dateCaps)
        if (m.age && ct.ageLine != null) add(ct.ageLine.uppercase())
        if (m.countdown && ct.countdown.isNotBlank()) add(ct.countdown.uppercase())
    }.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")

    private fun quote(s: String) = "“$s”"

    private fun footer(cv: Canvas, w: Int, h: Int, color: Int, align: Paint.Align, x: Float = w / 2f) {
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.DEFAULT; textAlign = align; textSize = min(w, h) * 0.026f; letterSpacing = 0.04f }
        cv.drawText("Made with Kairo · 100% offline", x, h - min(w, h) * 0.04f, fp)
    }

    // ── Measured layout engine ────────────────────────────────────────────────────────────────────

    private enum class VAlign { TOP, CENTER }

    /**
     * Stack [blocks] vertically inside [ax]-anchored column of [width] within [top]..[bottom]. Every block
     * is measured; if the stack is taller than the region the whole column is scaled down to fit, so items
     * never overlap and never spill past the region — for any module set or shape.
     */
    private fun layoutColumn(
        cv: Canvas, ax: Float, width: Float, top: Float, bottom: Float,
        align: Paint.Align, vAlign: VAlign, gap: Float, blocks: List<Block>,
    ) {
        if (blocks.isEmpty()) return
        val regionH = (bottom - top).coerceAtLeast(1f)
        fun totalAt(scale: Float): Float {
            var t = 0f
            blocks.forEachIndexed { i, b -> t += b.measure(width, scale); if (i < blocks.size - 1) t += gap * scale }
            return t
        }
        val raw = totalAt(1f)
        val scale = if (raw > regionH) (regionH / raw).coerceIn(0.42f, 1f) else 1f
        val total = totalAt(scale)
        var y = if (vAlign == VAlign.CENTER) top + max(0f, (regionH - total) / 2f) else top
        blocks.forEach { b ->
            val hh = b.measure(width, scale)
            b.render(cv, ax, y, width, align, scale)
            y += hh + gap * scale
        }
    }

    private interface Block {
        fun measure(width: Float, scale: Float): Float
        fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float)
    }

    /** A wrapped text block. Auto multi-line; additionally shrinks its size so the widest line always fits
     *  the column width (so long unbroken words / names never spill sideways). */
    private class TextB(
        val str: String, val baseSize: Float, val tf: Typeface, val col: Int,
        val lineSp: Float = 1.1f, val letterSp: Float = 0f, val maxLines: Int = 6, val shadow: Int = 0,
    ) : Block {
        private fun paintAt(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = col; typeface = tf; textSize = size; letterSpacing = letterSp
            if (shadow != 0) setShadowLayer(size * 0.06f, 0f, size * 0.03f, shadow)
        }
        private fun lines(p: Paint, width: Float): List<String> {
            val ls = wrap(str, p, width)
            return if (ls.size <= maxLines) ls
            else ls.take(maxLines).toMutableList().also { it[maxLines - 1] = ellipsize(it[maxLines - 1] + " " + ls[maxLines], p, width) }
        }
        /** Size after the column scale AND a width-fit shrink for the widest resulting line. */
        private fun fit(width: Float, scale: Float): Triple<Float, List<String>, Paint> {
            var size = baseSize * scale
            var p = paintAt(size)
            val ls = lines(p, width)
            val maxW = ls.maxOfOrNull { p.measureText(it) } ?: 0f
            if (maxW > width && maxW > 0f) { size *= width / maxW; p = paintAt(size) }
            return Triple(size, ls, p)
        }
        override fun measure(width: Float, scale: Float): Float {
            val (_, ls, p) = fit(width, scale); val fm = p.fontMetrics
            return ls.size * (fm.descent - fm.ascent) * lineSp
        }
        override fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float) {
            val (_, ls, p) = fit(width, scale); p.textAlign = align
            val fm = p.fontMetrics; val lineH = (fm.descent - fm.ascent) * lineSp
            var baseline = top - fm.ascent
            ls.forEach { cv.drawText(it, ax, baseline, p); baseline += lineH }
        }
    }

    /** A number in a filled circle (birthday age medallion). */
    private class AgeBadge(val n: String, val diameter: Float, val accent: Int) : Block {
        override fun measure(width: Float, scale: Float) = diameter * scale
        override fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float) {
            val r = diameter * scale / 2f
            val cx = if (align == Paint.Align.LEFT) ax + r else ax
            val cy = top + r
            cv.drawCircle(cx, cy + 4f, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E000000; maskFilter = BlurMaskFilter(14f, BlurMaskFilter.Blur.NORMAL) })
            cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(cx - r * 0.3f, cy - r * 0.3f, r * 1.5f, lighten(accent, 0.25f), accent, Shader.TileMode.CLAMP)
            })
            cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.06f; color = 0x66FFFFFF })
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = r * 0.95f }
            val fm = p.fontMetrics
            cv.drawText(n, cx, cy - (fm.ascent + fm.descent) / 2f, p)
        }
    }

    /** A row of soft pill chips (single row). */
    private class Chips(val labels: List<String>, val accent: Int, val ink: Int, val baseSize: Float, val rowAlign: Paint.Align = Paint.Align.CENTER) : Block {
        private fun tp(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = baseSize * scale }
        override fun measure(width: Float, scale: Float) = baseSize * scale * 2.0f
        override fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float) {
            val p = tp(scale); val chipH = baseSize * scale * 1.95f; val padX = baseSize * scale * 0.8f; val gap = baseSize * scale * 0.5f
            val widths = labels.map { p.measureText(it) + padX * 2 }
            val totalW = widths.sum() + gap * (labels.size - 1)
            var x = when (rowAlign) { Paint.Align.LEFT -> ax; Paint.Align.RIGHT -> ax - totalW; else -> ax - totalW / 2f }
            labels.forEachIndexed { i, label ->
                val cw = widths[i]; val rect = RectF(x, top, x + cw, top + chipH)
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x30) })
                cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = withAlpha(accent, 0x99) })
                val tpc = p.apply { color = ink; textAlign = Paint.Align.CENTER }; val fm = tpc.fontMetrics
                cv.drawText(label, x + cw / 2f, top + chipH / 2f - (fm.ascent + fm.descent) / 2f, tpc)
                x += cw + gap
            }
        }
    }

    private class Rule(val ruleW: Float, val col: Int) : Block {
        override fun measure(width: Float, scale: Float) = 8f * scale
        override fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float) {
            val x0 = when (align) { Paint.Align.LEFT -> ax; Paint.Align.RIGHT -> ax - ruleW; else -> ax - ruleW / 2f }
            cv.drawRect(x0, top, x0 + ruleW, top + 6f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col })
        }
    }

    /** A compact life-in-weeks band embedded in a birthday skin. */
    private class WeeksStrip(val ct: Content, val accent: Int, val muted: Int, val light: Boolean, val align: Paint.Align = Paint.Align.CENTER) : Block {
        override fun measure(width: Float, scale: Float) = width * 0.34f * scale
        override fun render(cv: Canvas, ax: Float, top: Float, width: Float, align: Paint.Align, scale: Float) {
            val pct = ct.weeksPct ?: return
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT_BOLD; textAlign = align; textSize = width * 0.03f * scale }
            val tx = if (align == Paint.Align.LEFT) ax else ax
            cv.drawText("▦  ${ct.weeksLived} weeks · $pct% of an ${LIFE_YEARS}-yr life", tx, top + width * 0.03f * scale, tp)
            val cols = 52; val rows = 12
            val gTop = top + width * 0.05f * scale
            val step = width / cols
            val cell = step * 0.72f; val rad = cell * 0.3f
            val gx = if (align == Paint.Align.LEFT) ax else ax - width / 2f
            val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0x14000000 else 0x18FFFFFF }
            val totalCells = cols * rows; val livedCells = ((pct / 100f) * totalCells).toInt()
            for (i in 0 until totalCells) {
                val col = i % cols; val row = i / cols
                cv.drawRoundRect(RectF(gx + col * step, gTop + row * step * scale, gx + col * step + cell * scale, gTop + row * step * scale + cell * scale), rad, rad, if (i < livedCells) filled else empty)
            }
        }
    }

    // ── Ornament primitives ───────────────────────────────────────────────────────────────────────

    private fun daisy(cv: Canvas, cx: Float, cy: Float, r: Float, petal: Int, core: Int, petals: Int = 8, rot: Float = 0f) {
        // Soft shadow.
        cv.drawCircle(cx, cy + r * 0.1f, r * 0.95f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x14000000; maskFilter = BlurMaskFilter(r * 0.25f, BlurMaskFilter.Blur.NORMAL) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = petal }
        val petalLen = r; val petalW = r * 0.58f
        for (i in 0 until petals) {
            val ang = rot + i * (2.0 * Math.PI / petals).toFloat()
            val px = cx + cos(ang) * r * 0.48f; val py = cy + sin(ang) * r * 0.48f
            cv.save(); cv.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f, px, py)
            cv.drawRoundRect(RectF(px - petalW / 2f, py - petalLen / 2f, px + petalW / 2f, py + petalLen / 2f), petalW / 2f, petalW / 2f, p)
            cv.restore()
        }
        cv.drawCircle(cx, cy, r * 0.36f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 0.4f, lighten(core, 0.2f), core, Shader.TileMode.CLAMP)
        })
    }

    private fun daisyCluster(cv: Canvas, cx: Float, cy: Float, r: Float, petal: Int, core: Int, sage: Int, rnd: Random) {
        leaf(cv, cx + r * 0.9f, cy + r * 0.7f, r * 1.3f, r * 0.5f, sage, 40f)
        daisy(cv, cx, cy, r, petal, core, rot = rnd.nextFloat())
        daisy(cv, cx + r * 1.35f, cy + r * 0.55f, r * 0.66f, petal, core, rot = rnd.nextFloat())
        daisy(cv, cx - r * 1.05f, cy + r * 0.75f, r * 0.5f, petal, core, rot = rnd.nextFloat())
        // Tiny buds.
        cv.drawCircle(cx + r * 0.6f, cy - r * 1.05f, r * 0.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = core })
    }

    private fun stemmedDaisy(cv: Canvas, baseX: Float, baseY: Float, stemLen: Float, r: Float, petal: Int, core: Int, sage: Int) {
        val topY = baseY - stemLen
        cv.drawPath(Path().apply { moveTo(baseX, baseY); quadTo(baseX + r * 0.4f, baseY - stemLen * 0.5f, baseX, topY + r) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(4f, r * 0.12f); color = sage; strokeCap = Paint.Cap.ROUND })
        leaf(cv, baseX - r * 0.5f, baseY - stemLen * 0.45f, r * 1.1f, r * 0.5f, sage, -35f)
        leaf(cv, baseX + r * 0.5f, baseY - stemLen * 0.65f, r * 1.1f, r * 0.5f, sage, 35f)
        daisy(cv, baseX, topY + r * 0.2f, r, petal, core)
    }

    private fun leaf(cv: Canvas, cx: Float, cy: Float, len: Float, wd: Float, color: Int, angleDeg: Float) {
        cv.save(); cv.rotate(angleDeg, cx, cy)
        cv.drawPath(Path().apply {
            moveTo(cx, cy - len / 2f); quadTo(cx + wd / 2f, cy, cx, cy + len / 2f); quadTo(cx - wd / 2f, cy, cx, cy - len / 2f); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.drawLine(cx, cy - len / 2f, cx, cy + len / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(2f, len * 0.02f); this.color = withAlpha(darken(color, 0.25f), 0x99) })
        cv.restore()
    }

    private fun rose(cv: Canvas, cx: Float, cy: Float, r: Float, base: Int, deep: Int) {
        // A layered-petal bloom (rounded oval petals in two offset rings) rather than concentric rings.
        // Base cup.
        cv.drawCircle(cx, cy, r * 0.98f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - r * 0.25f, cy - r * 0.3f, r * 1.35f, lighten(base, 0.22f), deep, Shader.TileMode.CLAMP)
        })
        fun petalRing(count: Int, ringR: Float, petalW: Float, petalH: Float, col: Int, phase: Float) {
            for (i in 0 until count) {
                val a = phase + i * (2.0 * Math.PI / count).toFloat()
                val px = cx + cos(a) * ringR; val py = cy + sin(a) * ringR
                cv.save(); cv.rotate(Math.toDegrees(a.toDouble()).toFloat() + 90f, px, py)
                cv.drawRoundRect(RectF(px - petalW, py - petalH, px + petalW, py + petalH), petalW, petalW, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(px, py - petalH, px, py + petalH, lighten(col, 0.14f), darken(col, 0.06f), Shader.TileMode.CLAMP)
                })
                // A soft crease down the petal.
                cv.drawLine(px, py - petalH * 0.7f, px, py + petalH * 0.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.02f; color = withAlpha(darken(col, 0.2f), 0x55) })
                cv.restore()
            }
        }
        petalRing(7, r * 0.58f, r * 0.34f, r * 0.44f, lighten(base, 0.08f), 0.1f)
        petalRing(5, r * 0.30f, r * 0.26f, r * 0.34f, deep, 0.7f)
        // Furled centre.
        cv.drawCircle(cx, cy, r * 0.2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(deep, 0.12f) })
        cv.drawArc(RectF(cx - r * 0.14f, cy - r * 0.14f, cx + r * 0.14f, cy + r * 0.14f), 40f, 260f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.05f; color = withAlpha(0xFFF3D6C0.toInt(), 0x99); strokeCap = Paint.Cap.ROUND })
    }

    private fun roseCluster(cv: Canvas, cx: Float, cy: Float, r: Float, base: Int, deep: Int, foliage: Int, rnd: Random) {
        for (i in 0 until 7) {
            val ang = rnd.nextFloat() * 360f
            val lx = cx + cos(Math.toRadians(ang.toDouble())).toFloat() * r * 1.35f
            val ly = cy + sin(Math.toRadians(ang.toDouble())).toFloat() * r * 1.35f
            leaf(cv, lx, ly, r * 0.95f, r * 0.34f, if (i % 2 == 0) foliage else darken(foliage, 0.15f), ang)
        }
        rose(cv, cx, cy, r, base, deep)
        rose(cv, cx - r * 1.15f, cy + r * 0.55f, r * 0.72f, base, deep)
        rose(cv, cx + r * 0.75f, cy + r * 0.95f, r * 0.6f, lighten(base, 0.1f), deep)
        rose(cv, cx + r * 0.4f, cy - r * 1.0f, r * 0.42f, base, deep)
        for (i in 0 until 3) {
            val bx = cx + (rnd.nextFloat() - 0.5f) * r * 3f; val by = cy + (rnd.nextFloat() - 0.5f) * r * 3f
            cv.drawCircle(bx, by, r * 0.14f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(deep, 0xCC) })
        }
    }

    private fun balloon(cv: Canvas, cx: Float, cy: Float, rw: Float, rh: Float, color: Int, toX: Float, toY: Float) {
        // String first (behind).
        cv.drawPath(Path().apply { moveTo(cx, cy + rh); quadTo(cx + (toX - cx) * 0.3f, cy + rh * 1.9f, toX, toY) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; this.color = 0x55000000 })
        cv.drawOval(RectF(cx - rw, cy - rh, cx + rw, cy + rh), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx - rw * 0.3f, cy - rh * 0.35f, rw * 1.7f, lighten(color, 0.4f), darken(color, 0.12f), Shader.TileMode.CLAMP)
        })
        cv.drawPath(Path().apply { moveTo(cx - rw * 0.14f, cy + rh); lineTo(cx + rw * 0.14f, cy + rh); lineTo(cx, cy + rh + rh * 0.14f); close() },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = darken(color, 0.1f) })
        cv.drawOval(RectF(cx - rw * 0.55f, cy - rh * 0.62f, cx - rw * 0.12f, cy - rh * 0.1f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(0xFFFFFFFF.toInt(), 0x7A) })
    }

    private fun bunting(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, count: Int) {
        val midX = (x0 + x1) / 2f; val sag = max(y0, y1) + 50f
        cv.drawPath(Path().apply { moveTo(x0, y0); quadTo(midX, sag, x1, y1) },
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x66000000 })
        for (i in 0 until count) {
            val t = (i + 0.5f) / count
            val px = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * midX + t * t * x1
            val py = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * sag + t * t * y1
            val tw = 34f; val th = 46f
            cv.drawPath(Path().apply { moveTo(px - tw / 2f, py); lineTo(px + tw / 2f, py); lineTo(px, py + th); close() },
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i % colors.size] })
        }
    }

    private fun giftBox(cv: Canvas, x: Float, y: Float, w: Float, h: Float, box: Int, ribbon: Int) {
        cv.drawRoundRect(RectF(x, y - h * 0.14f, x + w, y + h), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(x, y, x, y + h, lighten(box, 0.12f), box, Shader.TileMode.CLAMP)
        })
        cv.drawRoundRect(RectF(x - 5f, y - h * 0.16f, x + w + 5f, y + h * 0.1f), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(box, 0.12f) })
        val r = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ribbon }
        cv.drawRect(x + w * 0.42f, y - h * 0.16f, x + w * 0.58f, y + h, r)
        cv.drawCircle(x + w * 0.42f, y - h * 0.14f, w * 0.11f, r); cv.drawCircle(x + w * 0.58f, y - h * 0.14f, w * 0.11f, r)
    }

    private fun confetti(cv: Canvas, w: Int, h: Int, colors: IntArray, rnd: Random, count: Int) {
        val avoid = RectF(w * 0.14f, h * 0.16f, w * 0.86f, h * 0.84f)
        val footerTop = h - min(w, h) * 0.09f   // keep the "Made with Kairo" line clear
        var placed = 0; var guard = 0
        while (placed < count && guard < count * 4) {
            guard++
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            if (avoid.contains(x, y) || y > footerTop) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[rnd.nextInt(colors.size)] }
            when (rnd.nextInt(3)) {
                0 -> cv.drawCircle(x, y, 5f + rnd.nextFloat() * 6f, p)
                1 -> { cv.save(); cv.rotate(rnd.nextFloat() * 90f, x, y); cv.drawRoundRect(RectF(x - 7f, y - 3.5f, x + 7f, y + 3.5f), 2f, 2f, p); cv.restore() }
                else -> star(cv, x, y, 7f + rnd.nextFloat() * 5f, p)
            }
            placed++
        }
    }

    private fun star(cv: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.45f
            val ang = Math.toRadians((i * 36 - 90).toDouble())
            val x = cx + (cos(ang) * rr).toFloat(); val y = cy + (sin(ang) * rr).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); cv.drawPath(path, p)
    }

    private fun watercolorBlob(cv: Canvas, cx: Float, cy: Float, r: Float, color: Int, rnd: Random) {
        // Layered low-alpha circles build a soft cloud without a heavy mask-filter (which could fail /
        // dominate on some devices). Each layer is faint so overlaps read as watercolour bleed.
        val hue2 = lighten(color, 0.28f)
        for (k in 0 until 16) {
            val ox = (rnd.nextFloat() - 0.5f) * r * 0.95f; val oy = (rnd.nextFloat() - 0.5f) * r * 0.85f
            val rr = r * (0.40f + rnd.nextFloat() * 0.62f)
            cv.drawCircle(cx + ox, cy + oy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = withAlpha(if (k % 2 == 0) color else hue2, 0x10 + rnd.nextInt(0x12))
            })
        }
        cv.drawCircle(cx, cy, r * 0.9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 0.9f, withAlpha(color, 0x33), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
        })
    }

    private fun paperGrain(cv: Canvas, w: Int, h: Int, rnd: Random) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 900) {
            p.color = if (rnd.nextBoolean()) 0x06000000 else 0x09FFFFFF
            cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.8f + rnd.nextFloat() * 1.4f, p)
        }
    }
}

// ── File-level helpers (shared by the object and its Block classes) ─────────────────────────────────

private fun ordinalSuffix(n: Int): String = when {
    n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th"
}

private fun ellipsize(s: String, p: Paint, maxW: Float): String {
    if (p.measureText(s) <= maxW) return s
    var t = s
    while (t.isNotEmpty() && p.measureText("$t…") > maxW) t = t.dropLast(1)
    return "$t…"
}

private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
    val out = ArrayList<String>()
    for (segment in s.split("\n")) {
        val words = segment.split(" "); var cur = StringBuilder()
        for (word in words) {
            val cand = if (cur.isEmpty()) word else "$cur $word"
            if (p.measureText(cand) <= maxW || cur.isEmpty()) cur = StringBuilder(cand)
            else { out.add(cur.toString()); cur = StringBuilder(word) }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
    }
    return out.ifEmpty { listOf("") }
}

private fun lerp(a: Int, b: Int, t: Float): Int {
    val tt = t.coerceIn(0f, 1f)
    fun ch(sh: Int): Int { val av = (a ushr sh) and 0xFF; val bv = (b ushr sh) and 0xFF; return (av + (bv - av) * tt).toInt().coerceIn(0, 255) }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun lighten(argb: Int, f: Float): Int {
    val a = (argb ushr 24) and 0xFF; val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
    fun up(x: Int) = (x + (255 - x) * f).toInt().coerceIn(0, 255)
    return (a shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
}

private fun darken(argb: Int, f: Float): Int {
    val a = (argb ushr 24) and 0xFF; val r = (argb ushr 16) and 0xFF; val g = (argb ushr 8) and 0xFF; val b = argb and 0xFF
    fun dn(x: Int) = (x * (1 - f)).toInt().coerceIn(0, 255)
    return (a shl 24) or (dn(r) shl 16) or (dn(g) shl 8) or dn(b)
}

private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0x00FFFFFF)
