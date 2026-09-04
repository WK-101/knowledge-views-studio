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
import java.time.format.TextStyle
import java.util.Locale
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * R107 — the Occasion "Card Studio". A gift-card renderer that draws a shareable occasion card to a PNG
 * entirely on-device (android.graphics, no assets, no Compose capture, no network). Unlike the old single
 * generic card, this is a small template engine: the caller picks a [Template] skin, an aspect [Ratio], an
 * accent, a free-text message and a set of [Modules] (name · age · date · countdown · message · zodiac ·
 * milestone · life-in-weeks). Every ornament — daisies, watercolour blooms, roses, balloons, bunting, gift
 * boxes, confetti, the life-in-weeks grid — is drawn procedurally so there are no image assets to ship and
 * no permissions to request. Shared only if the user picks a target in the system sheet.
 *
 * The three built-in skins are birthday-first, drawn from real gift-card references:
 *   • BLOOM   — cream ground, a soft watercolour splash and scattered daisies, a graceful script headline.
 *   • FIESTA  — blush ground, bunting + balloons + gift boxes around a dashed-ring badge (party card).
 *   • ELEGANT — paper ground with a mint wash, pink rose corners, a thin serif caps headline + rule.
 *   • WEEKS   — the app's signature "life in weeks" grid, redesigned as a striking dark card.
 * Other occasion types (anniversary, memorial, holiday, countdown) reuse these skins with type-aware
 * wording; more type-specific skins arrive with future references.
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
        val ratio = if (spec.template == Template.WEEKS && spec.ratio == Ratio.SQUARE) Ratio.PORTRAIT else spec.ratio
        val w = ratio.w; val h = ratio.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val ct = content(c, spec, today)
        val rnd = Random(kotlin.math.abs(c.id.hashCode()).toLong() + spec.template.ordinal)
        when (spec.template) {
            Template.BLOOM -> bloom(cv, w, h, ct, spec, rnd)
            Template.FIESTA -> fiesta(cv, w, h, ct, spec, rnd)
            Template.ELEGANT -> elegant(cv, w, h, ct, spec, rnd)
            Template.WEEKS -> weeks(cv, w, h, ct, spec)
        }
        return bmp
    }

    /** Plain-text equivalent for the "As text" share path. */
    fun text(c: CountdownEntity, spec: Spec, today: LocalDate = LocalDate.now()): String {
        val ct = content(c, spec, today)
        return buildString {
            append(ct.head)
            if (spec.modules.name && ct.name.isNotBlank()) append(" ${ct.name}")
            append("\n")
            if (spec.modules.date) append("${ct.dateLong}\n")
            if (spec.modules.countdown && ct.countdown.isNotBlank()) append("${ct.countdown}\n")
            if (spec.modules.age && ct.ageLine != null) append("${ct.ageLine}\n")
            val chips = listOfNotNull(ct.milestone?.takeIf { spec.modules.milestone }, ct.zodiac?.takeIf { spec.modules.zodiac })
            if (chips.isNotEmpty()) append("${chips.joinToString(" · ")}\n")
            if (spec.modules.weeks && ct.weeksPct != null) append("▦ ${ct.weeksLived} weeks lived · ${ct.weeksPct}% of a ${LIFE_YEARS}-year life\n")
            if (spec.modules.message && ct.message.isNotBlank()) append("\n“${ct.message}”\n")
            append("\n— via Kairo")
        }
    }

    /** A friendly default line so a card with the message module on never looks empty. */
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
        val ageBadge: String?,     // "30" for a birthday medallion, else null
        val ageLine: String?,      // "turns 30" / "25 years"
        val dateLong: String,      // "6 January 2030"
        val dateCaps: String,      // "6TH JANUARY 2030"
        val countdown: String,     // "It's today!" / "In 5 days" / ""
        val message: String,
        val milestone: String?,
        val zodiac: String?,
        val weeksLived: String?,   // "2,184"
        val weeksPct: Int?,        // 42
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

    // ── Skin 1 · BLOOM ─────────────────────────────────────────────────────────────────────────────
    // Cream ground, a seafoam watercolour splash behind a graceful serif-italic headline, daisies in the
    // corners (some on stems), a soft message below. Accent themes the splash + headline ink.

    private fun bloom(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF7FCBB0.toInt()
        val cream = 0xFFFBF7E9.toInt()
        val ink = darken(accent, 0.55f)
        val muted = darken(accent, 0.35f)
        val petal = 0xFFF4EDD8.toInt(); val core = 0xFFF2B733.toInt(); val sage = 0xFF9BC0A5.toInt()
        cv.drawColor(cream)

        val cx = w / 2f
        // Corner daisy clusters (top-left, top-right) + stemmed daisies at the bottom corners.
        daisy(cv, w * 0.12f, h * 0.10f, w * 0.075f, petal, core)
        daisy(cv, w * 0.24f, h * 0.16f, w * 0.05f, petal, core)
        daisy(cv, w * 0.05f, h * 0.19f, w * 0.045f, petal, core)
        daisy(cv, w * 0.85f, h * 0.09f, w * 0.07f, petal, core)
        daisy(cv, w * 0.93f, h * 0.16f, w * 0.045f, petal, core)
        leaf(cv, w * 0.80f, h * 0.15f, w * 0.09f, w * 0.035f, sage, 40f)
        stemmedDaisy(cv, w * 0.10f, h.toFloat(), h * 0.30f, w * 0.075f, petal, core, sage)
        stemmedDaisy(cv, w * 0.20f, h.toFloat(), h * 0.24f, w * 0.055f, petal, core, sage)
        stemmedDaisy(cv, w * 0.90f, h.toFloat(), h * 0.30f, w * 0.075f, petal, core, sage)
        stemmedDaisy(cv, w * 0.80f, h.toFloat(), h * 0.24f, w * 0.055f, petal, core, sage)

        // Watercolour splash behind the headline.
        watercolorBlob(cv, cx, h * 0.46f, min(w, h) * 0.34f, accent, rnd)
        // A few darker speckles like the reference.
        val speck = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0xAA) }
        for (i in 0 until 6) cv.drawCircle(cx - w * 0.02f + rnd.nextFloat() * w * 0.12f, h * 0.24f + rnd.nextFloat() * h * 0.05f, 4f + rnd.nextFloat() * 7f, speck)

        // Headline: "Happy Birthday" (+ name) in a graceful serif italic, stacked and centred on the splash.
        val script = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = script; textAlign = Paint.Align.CENTER }
        val lines = ArrayList<String>()
        lines += ct.head.split(" ")   // "Happy" / "Birthday"
        if (spec.modules.name && ct.name.isNotBlank()) lines += ellipsize(ct.name, hp.apply { textSize = 118f }, w * 0.82f)
        hp.textSize = fitText(lines, hp, w * 0.80f, 128f)
        var y = h * 0.40f
        val lh = hp.textSize * 1.02f
        lines.forEach { cv.drawText(it, cx, y, hp); y += lh }

        // Optional age medallion tucked at the splash top.
        if (spec.modules.age && ct.ageBadge != null) {
            val by = h * 0.235f
            val br = min(w, h) * 0.062f
            cv.drawCircle(cx, by, br, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent })
            cv.drawCircle(cx, by, br, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x55FFFFFF })
            val ap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            ap.textSize = br * 0.9f; cv.drawText(ct.ageBadge, cx, by + br * 0.32f, ap)
        }

        // Chips (milestone / zodiac) as soft pills under the headline.
        var chipY = y + h * 0.01f
        chipY = drawChips(cv, cx, chipY, chipsFor(ct, spec), accent, ink, cream)

        // Date + countdown line.
        val meta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.SERIF; textAlign = Paint.Align.CENTER; textSize = 40f }
        val metaLine = buildList {
            if (spec.modules.date) add(ct.dateLong)
            if (spec.modules.countdown && ct.countdown.isNotBlank()) add(ct.countdown)
        }.joinToString("  ·  ")
        if (metaLine.isNotEmpty()) { chipY += 60f; cv.drawText(ellipsize(metaLine, meta, w * 0.86f), cx, chipY, meta) }

        // Message near the bottom, above the flowers.
        if (spec.modules.message && ct.message.isNotBlank()) {
            val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.SERIF; textAlign = Paint.Align.CENTER; textSize = 42f }
            val wraps = wrap(ct.message, mp, w * 0.72f).take(3)
            var my = h * (if (spec.modules.weeks) 0.72f else 0.85f)
            wraps.forEach { cv.drawText(it, cx, my, mp); my += 54f }
        }

        if (spec.modules.weeks && ct.weeksPct != null) drawWeeksStrip(cv, w, h * 0.80f, h, ct, accent, ink, muted, light = true)
        footer(cv, w, h, muted)
    }

    // ── Skin 2 · FIESTA ─────────────────────────────────────────────────────────────────────────────
    // Blush ground, bunting across the top, balloons + gift boxes ringing a white dashed-ring badge that
    // carries the words. Confetti + stars scattered. Accent themes the ring + badge ink + some balloons.

    private fun fiesta(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF2E4A6B.toInt()
        val blush = 0xFFF9DBCE.toInt()
        val ink = darken(accent, 0.15f)
        val coral = 0xFFF28C8C.toInt(); val blue = 0xFF8FB8DB.toInt(); val yellow = 0xFFF3C24E.toInt(); val gold = 0xFFD9A64E.toInt()
        val party = intArrayOf(coral, blue, yellow, gold, lighten(accent, 0.25f))
        cv.drawColor(blush)
        val cx = w / 2f; val cy = h / 2f

        // Confetti + stars in the margins.
        confetti(cv, w, h, party, rnd, count = if (h > w) 70 else 55)

        // Bunting strung across the top, two strings meeting near the centre.
        bunting(cv, w * 0.02f, h * 0.07f, w * 0.5f, h * 0.05f, party, if (w >= h) 8 else 7)
        bunting(cv, w * 0.5f, h * 0.05f, w * 0.98f, h * 0.09f, party, if (w >= h) 8 else 7)

        // The dashed-ring badge — sized to the frame, leaving room for balloons.
        val badgeR = min(w, h) * 0.365f
        // Balloons clustered around the top arc of the badge.
        val balloonSpots = listOf(-0.9f to 0.85f, -0.55f to 1.02f, -0.2f to 1.08f, 0.2f to 1.08f, 0.55f to 1.02f, 0.9f to 0.85f, -1.05f to 0.5f, 1.05f to 0.5f)
        balloonSpots.forEachIndexed { i, (ax, ay) ->
            val bx = cx + ax * badgeR * 1.05f
            val by = cy - badgeR * 0.55f - ay * badgeR * 0.35f
            balloon(cv, bx, by, badgeR * 0.17f, badgeR * 0.21f, party[i % party.size], cx + ax * badgeR * 0.4f, cy - badgeR * 0.2f)
        }
        // Gift boxes along the bottom arc.
        val giftW = badgeR * 0.34f
        for (i in -2..2) {
            val gx = cx + i * giftW * 1.15f - giftW / 2f
            val gy = cy + badgeR * 0.72f
            giftBox(cv, gx, gy, giftW, giftW * 0.9f, party[(i + 3) % party.size], gold)
        }

        // Badge.
        cv.drawCircle(cx, cy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        cv.drawCircle(cx, cy, badgeR, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f; color = gold })
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 4f; color = withAlpha(ink, 0x88)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 12f), 0f)
        }
        cv.drawCircle(cx, cy, badgeR - 26f, dash)

        // Words inside the badge, vertically composed.
        var y = cy - badgeR * 0.42f
        val happy = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); textAlign = Paint.Align.CENTER; letterSpacing = 0.28f }
        happy.textSize = badgeR * 0.16f
        cv.drawText("HAPPY", cx, y, happy); y += badgeR * 0.28f
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER; letterSpacing = 0.06f }
        big.textSize = fitOne("BIRTHDAY", big, badgeR * 1.5f, badgeR * 0.34f)
        cv.drawText("BIRTHDAY", cx, y, big); y += badgeR * 0.20f
        if (spec.modules.name && ct.name.isNotBlank()) {
            val toYou = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            toYou.textSize = badgeR * 0.085f; cv.drawText("To You", cx, y, toYou); y += badgeR * 0.16f
            val nm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(accent, 0.05f); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textAlign = Paint.Align.CENTER }
            nm.textSize = badgeR * 0.14f
            cv.drawText("“${ellipsize(ct.name, nm, badgeR * 1.5f)}”", cx, y, nm); y += badgeR * 0.16f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0xCC); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; letterSpacing = 0.14f }
        small.textSize = badgeR * 0.06f
        val subBits = buildList {
            if (spec.modules.date) add(ct.dateCaps)
            if (spec.modules.age && ct.ageLine != null) add(ct.ageLine.uppercase())
            if (spec.modules.countdown && ct.countdown.isNotBlank()) add(ct.countdown.uppercase())
        }
        if (subBits.isNotEmpty()) { cv.drawText(ellipsize(subBits.joinToString("  ·  "), small, badgeR * 1.6f), cx, y, small); y += badgeR * 0.14f }
        if (spec.modules.milestone && ct.milestone != null) {
            val ms = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(accent, 0.05f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            ms.textSize = badgeR * 0.075f; cv.drawText(ct.milestone, cx, y, ms); y += badgeR * 0.13f
        }
        if (spec.modules.message && ct.message.isNotBlank()) {
            val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0xB0); typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER; letterSpacing = 0.05f }
            mp.textSize = badgeR * 0.062f
            wrap(ct.message.uppercase(), mp, badgeR * 1.5f).take(3).forEach { cv.drawText(it, cx, y, mp); y += badgeR * 0.10f }
        }
        footer(cv, w, h, withAlpha(ink, 0x88))
    }

    // ── Skin 3 · ELEGANT ─────────────────────────────────────────────────────────────────────────────
    // Paper ground with a mint wash, watercolour rose clusters in opposite corners, a thin serif caps
    // headline with a rule, the message as a clean serif block, an optional attribution. Portrait-leaning.

    private fun elegant(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec, rnd: Random) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFFB9E0D2.toInt()
        val paper = 0xFFF4F1E8.toInt()
        val ink = 0xFF1C1B1A.toInt()
        val rose = 0xFFF0A6A6.toInt(); val roseDeep = 0xFFDD7E7E.toInt(); val foliage = 0xFF6E8B5A.toInt()
        cv.drawColor(paper)
        // Diagonal mint wash on the left, like the reference.
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, h.toFloat(), w.toFloat(), 0f, withAlpha(accent, 0x66), withAlpha(paper, 0x00), Shader.TileMode.CLAMP)
        })
        paperGrain(cv, w, h, rnd)

        val marginX = w * 0.075f
        // Rose clusters: top-right and bottom-left.
        roseCluster(cv, w * 0.86f, h * 0.14f, min(w, h) * 0.15f, rose, roseDeep, foliage, rnd)
        roseCluster(cv, w * 0.14f, h * 0.86f, min(w, h) * 0.17f, rose, roseDeep, foliage, rnd)
        leaf(cv, w * 0.06f, h * 0.52f, h * 0.10f, w * 0.03f, foliage, -20f)

        // Headline: thin serif caps, stacked, left-aligned.
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); textAlign = Paint.Align.LEFT; letterSpacing = 0.04f }
        val headLines = ct.head.uppercase().split(" ")
        hp.textSize = fitOneLeft(headLines, hp, w - marginX * 2 - min(w, h) * 0.2f, 150f)
        var y = h * 0.20f
        val lh = hp.textSize * 1.02f
        headLines.forEach { cv.drawText(it, marginX, y, hp); y += lh }
        // Rule under the headline.
        y += 6f
        cv.drawRect(marginX, y, marginX + w * 0.16f, y + 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
        y += 56f

        // Name (if separate from head) as a refined line.
        if (spec.modules.name && ct.name.isNotBlank()) {
            val np = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(accent, 0.5f); typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC); textAlign = Paint.Align.LEFT }
            np.textSize = 56f; cv.drawText(ellipsize(ct.name, np, w - marginX * 2 - min(w, h) * 0.28f), marginX, y, np); y += 66f
        }

        // Message as a serif paragraph.
        if (spec.modules.message && ct.message.isNotBlank()) {
            val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0xE0); typeface = Typeface.SERIF; textAlign = Paint.Align.LEFT }
            mp.textSize = 44f
            wrap(ct.message, mp, w - marginX * 2 - min(w, h) * 0.22f).take(7).forEach { cv.drawText(it, marginX, y, mp); y += 58f }
            y += 22f
        }

        // Meta: date · countdown · age.
        val meta = buildList {
            if (spec.modules.date) add(ct.dateLong)
            if (spec.modules.countdown && ct.countdown.isNotBlank()) add(ct.countdown)
            if (spec.modules.age && ct.ageLine != null) add(ct.ageLine)
        }
        if (meta.isNotEmpty()) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(ink, 0x99); typeface = Typeface.SERIF; textAlign = Paint.Align.LEFT; letterSpacing = 0.02f }
            sp.textSize = 34f; cv.drawText(ellipsize(meta.joinToString("   ·   "), sp, w - marginX * 2), marginX, y, sp); y += 50f
        }
        // Chips (milestone / zodiac).
        val chips = chipsFor(ct, spec)
        if (chips.isNotEmpty()) drawChipsLeft(cv, marginX, y, chips, darken(accent, 0.45f), ink, paper)

        if (spec.modules.weeks && ct.weeksPct != null) drawWeeksStrip(cv, w, h * 0.80f, h, ct, darken(accent, 0.4f), ink, withAlpha(ink, 0x99), light = true)
        footer(cv, w, h, withAlpha(ink, 0x77))
    }

    // ── Skin 4 · WEEKS ─────────────────────────────────────────────────────────────────────────────
    // The signature life-in-weeks, redesigned as a striking dark card: a header, a big "% lived" stat, a
    // decade-banded grid with age labels and a clear "now" marker.

    private fun weeks(cv: Canvas, w: Int, h: Int, ct: Content, spec: Spec) {
        val accent = (spec.accentArgb?.toInt()) ?: 0xFF7C6BE6.toInt()
        val accent2 = lighten(accent, 0.34f)
        val bg = 0xFF141019.toInt(); val onBg = 0xFFF4F1FA.toInt(); val muted = 0xFF9B93AC.toInt()
        cv.drawColor(bg)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), withAlpha(accent, 0x30), bg, Shader.TileMode.CLAMP)
        })
        val marginX = w * 0.075f
        // Header.
        val eyebrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.2f; textSize = 34f }
        cv.drawText("LIFE IN WEEKS", marginX, h * 0.075f, eyebrow)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onBg; typeface = Typeface.DEFAULT_BOLD; textSize = 66f }
        cv.drawText(ellipsize(if (spec.modules.name) ct.name else "A life", title, w - marginX * 2), marginX, h * 0.13f, title)

        // Big stat: % lived + weeks lived + years left.
        val pct = ct.weeksPct ?: 0
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2; typeface = Typeface.DEFAULT_BOLD; textSize = 150f }
        cv.drawText("$pct%", marginX, h * 0.235f, big)
        val statR = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = 36f }
        cv.drawText("of a ${LIFE_YEARS}-year life lived", marginX + big.measureText("$pct%") + 24f, h * 0.205f, statR)
        val livedWeeks = ct.weeksLived ?: "0"
        cv.drawText("$livedWeeks weeks lived", marginX + big.measureText("$pct%") + 24f, h * 0.235f, statR)

        // Grid area.
        val gridTop = h * 0.29f
        val gridBottom = h * 0.88f
        val labelW = 58f
        val gridLeft = marginX + labelW
        val gridRight = w - marginX
        val cols = 52
        val rows = LIFE_YEARS
        val step = min((gridRight - gridLeft) / cols, (gridBottom - gridTop) / rows)
        val cell = step * 0.74f; val rad = cell * 0.3f
        val lived = totalLivedWeeks(ct)
        val gridW = step * cols
        val gx = gridLeft + (gridRight - gridLeft - gridW) / 2f
        val filled = Paint(Paint.ANTI_ALIAS_FLAG)
        val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x18FFFFFF }
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = min(28f, step * 0.9f); textAlign = Paint.Align.RIGHT }
        val total = rows * cols
        for (i in 0 until total) {
            val col = i % cols; val row = i / cols
            val x = gx + col * step; val yy = gridTop + row * step
            filled.color = lerp(accent, accent2, row.toFloat() / rows)
            cv.drawRoundRect(RectF(x, yy, x + cell, yy + cell), rad, rad, if (i < lived) filled else empty)
        }
        // Decade age labels on the left.
        for (decade in 0..rows step 10) {
            val yy = gridTop + decade * step
            if (decade < rows) cv.drawText(decade.toString(), gx - 12f, yy + cell, lab)
        }
        // "Now" marker: a bright underline at the current week row, with a small dot label at the left.
        val nowRow = lived / cols
        if (nowRow < rows) {
            val ly = gridTop + (nowRow + 1) * step
            cv.drawRect(gx, ly - 2.5f, gx + gridW, ly + 2.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
            cv.drawCircle(gx - 22f, ly, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
        }
        // Legend.
        val legY = h * 0.925f
        val lg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT; textSize = 26f; textAlign = Paint.Align.LEFT }
        cv.drawRoundRect(RectF(marginX, legY - 20f, marginX + 22f, legY + 2f), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent2 })
        cv.drawText("lived", marginX + 32f, legY, lg)
        cv.drawRoundRect(RectF(marginX + 150f, legY - 20f, marginX + 172f, legY + 2f), 5f, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF })
        cv.drawText("to come", marginX + 182f, legY, lg)
        footer(cv, w, h, muted)
    }

    private fun totalLivedWeeks(ct: Content): Int {
        val lived = ct.weeksLived?.replace(",", "")?.toIntOrNull() ?: 0
        return lived.coerceIn(0, LIFE_YEARS * 52)
    }

    // ── Shared modules ──────────────────────────────────────────────────────────────────────────────

    private fun chipsFor(ct: Content, spec: Spec): List<String> = buildList {
        if (spec.modules.milestone) ct.milestone?.let { add(it) }
        if (spec.modules.age && ct.ageLine != null && spec.template != Template.FIESTA) ct.ageLine.let { add(it) }
        if (spec.modules.zodiac) ct.zodiac?.let { add(it) }
    }

    /** Centred pill chips; returns the y just below them. */
    private fun drawChips(cv: Canvas, cx: Float, topY: Float, chips: List<String>, accent: Int, ink: Int, ground: Int): Float {
        if (chips.isEmpty()) return topY
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 34f; textAlign = Paint.Align.CENTER }
        val padX = 28f; val chipH = 66f; val gap = 16f
        val widths = chips.map { cp.measureText(it) + padX * 2 }
        var x = cx - (widths.sum() + gap * (chips.size - 1)) / 2f
        chips.forEachIndexed { i, label ->
            val cw = widths[i]; val rect = RectF(x, topY, x + cw, topY + chipH)
            cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x33) })
            cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = withAlpha(accent, 0x99) })
            cp.color = ink; cv.drawText(label, x + cw / 2f, topY + chipH * 0.66f, cp)
            x += cw + gap
        }
        return topY + chipH
    }

    private fun drawChipsLeft(cv: Canvas, leftX: Float, topY: Float, chips: List<String>, accent: Int, ink: Int, ground: Int) {
        val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 32f; textAlign = Paint.Align.LEFT }
        val padX = 24f; val chipH = 60f; val gap = 14f
        var x = leftX
        chips.forEach { label ->
            val cw = cp.measureText(label) + padX * 2; val rect = RectF(x, topY, x + cw, topY + chipH)
            cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(accent, 0x22) })
            cv.drawRoundRect(rect, chipH / 2f, chipH / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = withAlpha(accent, 0x88) })
            cp.color = ink; cv.drawText(label, x + padX, topY + chipH * 0.66f, cp)
            x += cw + gap
        }
    }

    /** A compact life-in-weeks strip embedded at the foot of a birthday skin. */
    private fun drawWeeksStrip(cv: Canvas, w: Int, top: Float, h: Int, ct: Content, accent: Int, ink: Int, muted: Int, light: Boolean) {
        val pct = ct.weeksPct ?: return
        val marginX = w * 0.075f
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; textSize = 30f }
        cv.drawText("▦  ${ct.weeksLived} weeks lived · $pct% of a ${LIFE_YEARS}-year life", w / 2f, top, tp)
        val cols = 52; val rows = 16   // a compact 16-year-per-view sampling row band
        val gTop = top + 20f
        val gBottom = h - 96f
        val step = min((w - marginX * 2) / cols, (gBottom - gTop) / rows)
        val cell = step * 0.72f; val rad = cell * 0.3f
        val gridW = step * cols; val gx = (w - gridW) / 2f
        val filled = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0x14000000 else 0x18FFFFFF }
        val total = rows * cols
        val livedInView = ((pct / 100f) * total).toInt()
        for (i in 0 until total) {
            val col = i % cols; val row = i / cols
            val x = gx + col * step; val yy = gTop + row * step
            cv.drawRoundRect(RectF(x, yy, x + cell, yy + cell), rad, rad, if (i < livedInView) filled else empty)
        }
    }

    private fun footer(cv: Canvas, w: Int, h: Int, color: Int) {
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.DEFAULT; textAlign = Paint.Align.CENTER; textSize = 28f }
        cv.drawText("Made with Kairo · 100% offline", w / 2f, h - 46f, fp)
    }

    // ── Ornament primitives ───────────────────────────────────────────────────────────────────────

    private fun daisy(cv: Canvas, cx: Float, cy: Float, r: Float, petal: Int, core: Int, petals: Int = 7, rot: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = petal }
        val petalLen = r; val petalW = r * 0.62f
        for (i in 0 until petals) {
            val ang = rot + i * (2.0 * Math.PI / petals).toFloat()
            val px = cx + cos(ang) * r * 0.5f; val py = cy + sin(ang) * r * 0.5f
            cv.save()
            cv.rotate(Math.toDegrees(ang.toDouble()).toFloat() + 90f, px, py)
            cv.drawOval(RectF(px - petalW / 2f, py - petalLen / 2f, px + petalW / 2f, py + petalLen / 2f), p)
            cv.restore()
        }
        cv.drawCircle(cx, cy, r * 0.34f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = core })
    }

    private fun stemmedDaisy(cv: Canvas, baseX: Float, baseY: Float, stemLen: Float, r: Float, petal: Int, core: Int, sage: Int) {
        val topY = baseY - stemLen
        val stem = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(4f, r * 0.12f); color = sage }
        val path = Path().apply { moveTo(baseX, baseY); quadTo(baseX + r * 0.4f, baseY - stemLen * 0.5f, baseX, topY + r) }
        cv.drawPath(path, stem)
        leaf(cv, baseX - r * 0.5f, baseY - stemLen * 0.45f, r * 1.1f, r * 0.5f, sage, -35f)
        leaf(cv, baseX + r * 0.5f, baseY - stemLen * 0.65f, r * 1.1f, r * 0.5f, sage, 35f)
        daisy(cv, baseX, topY + r * 0.2f, r, petal, core)
    }

    private fun leaf(cv: Canvas, cx: Float, cy: Float, len: Float, wd: Float, color: Int, angleDeg: Float) {
        cv.save(); cv.rotate(angleDeg, cx, cy)
        val path = Path().apply {
            moveTo(cx, cy - len / 2f)
            quadTo(cx + wd / 2f, cy, cx, cy + len / 2f)
            quadTo(cx - wd / 2f, cy, cx, cy - len / 2f)
            close()
        }
        cv.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.drawLine(cx, cy - len / 2f, cx, cy + len / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(2f, len * 0.02f); this.color = withAlpha(darken(color, 0.25f), 0x99) })
        cv.restore()
    }

    private fun rose(cv: Canvas, cx: Float, cy: Float, r: Float, base: Int, deep: Int) {
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(base, 0x8C) })
        cv.drawCircle(cx - r * 0.2f, cy - r * 0.15f, r * 0.74f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(base, 0xB4) })
        cv.drawCircle(cx + r * 0.16f, cy + r * 0.12f, r * 0.52f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(deep, 0xC8) })
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = r * 0.09f; color = withAlpha(deep, 0xCC) }
        var rr = r * 0.44f
        for (k in 0..3) { cv.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), 20f + k * 35f, 250f, false, sp); rr *= 0.64f }
        cv.drawCircle(cx, cy, r * 0.12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC77A2A.toInt() })
    }

    private fun roseCluster(cv: Canvas, cx: Float, cy: Float, r: Float, base: Int, deep: Int, foliage: Int, rnd: Random) {
        // Foliage sprays behind the blooms.
        for (i in 0 until 6) {
            val ang = rnd.nextFloat() * 360f
            val lx = cx + cos(Math.toRadians(ang.toDouble())).toFloat() * r * 1.3f
            val ly = cy + sin(Math.toRadians(ang.toDouble())).toFloat() * r * 1.3f
            leaf(cv, lx, ly, r * 0.9f, r * 0.34f, foliage, ang)
        }
        rose(cv, cx, cy, r, base, deep)
        rose(cv, cx - r * 1.1f, cy + r * 0.5f, r * 0.72f, base, deep)
        rose(cv, cx + r * 0.7f, cy + r * 0.9f, r * 0.6f, lighten(base, 0.1f), deep)
        // A couple of buds.
        for (i in 0 until 3) {
            val bx = cx + (rnd.nextFloat() - 0.5f) * r * 3f; val by = cy + (rnd.nextFloat() - 0.5f) * r * 3f
            cv.drawCircle(bx, by, r * 0.16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(deep, 0xCC) })
        }
    }

    private fun balloon(cv: Canvas, cx: Float, cy: Float, rw: Float, rh: Float, color: Int, toX: Float, toY: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        cv.drawOval(RectF(cx - rw, cy - rh, cx + rw, cy + rh), p)
        val knot = Path().apply { moveTo(cx - rw * 0.14f, cy + rh); lineTo(cx + rw * 0.14f, cy + rh); lineTo(cx, cy + rh + rh * 0.14f); close() }
        cv.drawPath(knot, p)
        cv.drawOval(RectF(cx - rw * 0.6f, cy - rh * 0.66f, cx - rw * 0.05f, cy - rh * 0.05f), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(0xFFFFFFFF.toInt(), 0x70) })
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; this.color = 0x44000000 }
        cv.drawPath(Path().apply { moveTo(cx, cy + rh + rh * 0.14f); quadTo(cx + (toX - cx) * 0.3f, cy + rh * 1.8f, toX, toY) }, s)
    }

    private fun bunting(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, count: Int) {
        val midX = (x0 + x1) / 2f; val sag = max(y0, y1) + 46f
        cv.drawPath(Path().apply { moveTo(x0, y0); quadTo(midX, sag, x1, y1) }, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = 0x66000000 })
        for (i in 0 until count) {
            val t = (i + 0.5f) / count
            val px = (1 - t) * (1 - t) * x0 + 2 * (1 - t) * t * midX + t * t * x1
            val py = (1 - t) * (1 - t) * y0 + 2 * (1 - t) * t * sag + t * t * y1
            val tw = 34f; val th = 46f
            cv.drawPath(Path().apply { moveTo(px - tw / 2f, py); lineTo(px + tw / 2f, py); lineTo(px, py + th); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[i % colors.size] })
        }
    }

    private fun giftBox(cv: Canvas, x: Float, y: Float, w: Float, h: Float, box: Int, ribbon: Int) {
        cv.drawRoundRect(RectF(x, y, x + w, y + h), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = box })
        cv.drawRoundRect(RectF(x - 5f, y - h * 0.14f, x + w + 5f, y + h * 0.16f), 10f, 10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = darken(box, 0.12f) })
        val r = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ribbon }
        cv.drawRect(x + w * 0.42f, y - h * 0.14f, x + w * 0.58f, y + h, r)
        cv.drawCircle(x + w * 0.42f, y - h * 0.12f, w * 0.11f, r); cv.drawCircle(x + w * 0.58f, y - h * 0.12f, w * 0.11f, r)
    }

    private fun confetti(cv: Canvas, w: Int, h: Int, colors: IntArray, rnd: Random, count: Int) {
        val avoid = RectF(w * 0.16f, h * 0.16f, w * 0.84f, h * 0.84f)
        var placed = 0; var guard = 0
        while (placed < count && guard < count * 4) {
            guard++
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            if (avoid.contains(x, y)) continue
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[rnd.nextInt(colors.size)] }
            when (rnd.nextInt(3)) {
                0 -> cv.drawCircle(x, y, 5f + rnd.nextFloat() * 6f, p)
                1 -> { cv.save(); cv.rotate(rnd.nextFloat() * 90f, x, y); cv.drawRect(x - 7f, y - 3.5f, x + 7f, y + 3.5f, p); cv.restore() }
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
        for (k in 0 until 9) {
            val ox = (rnd.nextFloat() - 0.5f) * r * 0.8f; val oy = (rnd.nextFloat() - 0.5f) * r * 0.7f
            val rr = r * (0.5f + rnd.nextFloat() * 0.55f)
            cv.drawCircle(cx + ox, cy + oy, rr, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = withAlpha(color, 0x1E + rnd.nextInt(0x22)) })
        }
        // A soft radial core so the centre reads as denser pigment.
        cv.drawCircle(cx, cy, r * 0.9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(cx, cy, r * 0.9f, withAlpha(color, 0x40), withAlpha(color, 0x00), Shader.TileMode.CLAMP)
        })
    }

    private fun paperGrain(cv: Canvas, w: Int, h: Int, rnd: Random) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 900) {
            p.color = if (rnd.nextBoolean()) 0x06000000 else 0x08FFFFFF
            cv.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 0.8f + rnd.nextFloat() * 1.4f, p)
        }
    }

    // ── Text + colour helpers ───────────────────────────────────────────────────────────────────────

    private fun ordinalSuffix(n: Int): String = when {
        n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th"
    }

    /** Shrink [start] px until every line fits [maxW]; used for centred stacked headlines. */
    private fun fitText(lines: List<String>, p: Paint, maxW: Float, start: Float): Float {
        var s = start
        while (s > 40f && lines.any { p.apply { textSize = s }.measureText(it) > maxW }) s -= 3f
        p.textSize = s; return s
    }

    private fun fitOne(s: String, p: Paint, maxW: Float, start: Float): Float {
        var t = start
        while (t > 30f && p.apply { textSize = t }.measureText(s) > maxW) t -= 2f
        p.textSize = t; return t
    }

    private fun fitOneLeft(lines: List<String>, p: Paint, maxW: Float, start: Float): Float = fitText(lines, p, maxW, start)

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
        return out
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
}
