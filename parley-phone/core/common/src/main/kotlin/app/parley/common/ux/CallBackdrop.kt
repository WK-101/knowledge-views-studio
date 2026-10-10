package app.parley.common.ux

import kotlin.math.roundToInt

/**
 * Settings › Calls › "Call screen background": the caller's colour as a soft tint at the top (the default), the
 * theme's plain background, or [POSTER]: like the caller's colour, but a contact's call-screen picture fills the
 * screen with the name set large over it. A contact's own call-screen picture is a separate, per-contact choice and
 * shows with every style.
 */
enum class CallScreenBackground { CALLER_COLOUR, PLAIN, POSTER }

/**
 * The call screen's background, kept readable: how strongly the caller's colour may tint the theme's surface, and
 * how opaque the scrim over a call-screen picture must be, so that the screen's text colours keep at least 4.5:1
 * in light, dark and black (AMOLED) themes. Colours are opaque ARGB ints; blending is per sRGB channel, like
 * drawing a translucent layer.
 */
object CallBackdrop {
    /** The strongest tint the top of the screen may take. */
    const val MAX_TINT = 0.32f

    private const val STEP = 0.01f

    /** [top] drawn over [bottom] with [alpha] (0..1). */
    fun blend(top: Int, bottom: Int, alpha: Float): Int {
        val a = alpha.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val t = (top shr shift) and 0xFF
            val b = (bottom shr shift) and 0xFF
            return (t * a + b * (1 - a)).roundToInt().coerceIn(0, 0xFF)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * The strongest tint of [accent] over [surface], at most [max], at which every colour in [inks] still reads at
     * [minRatio]. 0 when even the plain surface doesn't (then nothing is tinted).
     */
    fun tintStrength(surface: Int, accent: Int, inks: IntArray, max: Float = MAX_TINT, minRatio: Double = Contrast.TEXT): Float {
        var s = max
        while (s > 0f) {
            val bg = blend(accent, surface, s)
            if (inks.all { Contrast.ratio(it, bg) >= minRatio }) return s
            s -= STEP
        }
        return 0f
    }

    /**
     * The weakest scrim of [surface] over any picture at which every colour in [inks] reads at [minRatio]. Only a
     * black and a white pixel need checking: every other pixel blends to a colour between those two, and as long as
     * both stay on the surface's side of each ink, the contrast in between is never lower. 1 (an opaque scrim) when
     * nothing weaker works.
     */
    fun scrimAlpha(surface: Int, inks: IntArray, minRatio: Double = Contrast.TEXT): Float {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        var a = 0f
        while (a < 1f) {
            if (readable(surface, inks, minRatio, blend(surface, black, a), blend(surface, white, a))) return a
            a += STEP
        }
        return 1f
    }

    private fun readable(surface: Int, inks: IntArray, minRatio: Double, dark: Int, light: Int): Boolean = inks.all { ink ->
        val inkL = Contrast.luminance(ink)
        val surfaceDarker = Contrast.luminance(surface) < inkL
        // Both extremes on the surface's side of the ink, so no pixel in between can match the ink.
        val sameSide = (Contrast.luminance(light) < inkL) == surfaceDarker && (Contrast.luminance(dark) < inkL) == surfaceDarker
        sameSide && Contrast.ratio(ink, dark) >= minRatio && Contrast.ratio(ink, light) >= minRatio
    }

    /** What colour the top of the screen takes. */
    enum class Tint { CALLER, WARNING, NONE }

    /**
     * What the call screen draws behind the caller: a tint, whether the contact's picture goes over it, and whether
     * that picture may be a [poster] (the name set large over a mostly clear picture, see [posterLayout]).
     */
    data class Plan(val tint: Tint, val picture: Boolean, val poster: Boolean = false)

    /**
     * The background for a call. [warn] is a ringing call screened as likely spam: its red wash is a warning, not
     * decoration, so it stays with a plain background too. [hasPicture] is the contact's call-screen picture, which
     * the user set for that person on purpose and which shows whatever the style; [allowPicture] is off where a
     * picture doesn't fit (the picture-in-picture window). A poster needs a picture, and never covers a spam warning
     * or a call masked on the lock screen (which has no picture anyway).
     */
    fun plan(style: CallScreenBackground, warn: Boolean, hasPicture: Boolean, allowPicture: Boolean = true, masked: Boolean = false): Plan {
        val tint = when {
            warn -> Tint.WARNING
            style == CallScreenBackground.PLAIN -> Tint.NONE
            else -> Tint.CALLER
        }
        val picture = hasPicture && allowPicture
        return Plan(tint, picture, poster = picture && style == CallScreenBackground.POSTER && !warn && !masked)
    }

    /**
     * Whether the screen lays the caller out as a poster: only in the single-column layout with room for it, and only
     * once the picture is there to see ([pictureShown]: decoded; a picture that was deleted or can't be read never
     * is, and the caller keeps their photo, ringing frame and time ring). Two panes (landscape, tablets), a short
     * window, the open keypad and a waiting second call keep the classic layout over the same picture.
     */
    @Suppress("LongParameterList") // One flag per thing on screen that rules the poster out.
    fun posterLayout(plan: Plan, twoPane: Boolean, short: Boolean, keypadOpen: Boolean, callWaiting: Boolean, pictureShown: Boolean): Boolean =
        plan.poster && pictureShown && !twoPane && !short && !keypadOpen && !callWaiting

    /** The scrim's opacity behind the controls at the bottom of a picture. */
    const val OPAQUE_BOTTOM = 0.96f

    /**
     * A poster's scrim from top to bottom, as (position, alpha) stops over the screen's height (0..1): [scrim] at the
     * very top fading to clear by [guard] (the status bar's icons stay readable), clear down to [fade] above
     * [textTop], at least [scrim] from [textTop] on (the readable minimum, see [scrimAlpha]) and [OPAQUE_BOTTOM]
     * behind the controls. [open] is how clear the clear part is (1 clear, 0 the full scrim as in the classic layout),
     * so the screen can move between the two smoothly. With no clear room left, it is [scrim] all the way down.
     */
    fun posterStops(textTop: Float, guard: Float, fade: Float, scrim: Float, open: Float = 1f): List<Pair<Float, Float>> {
        val top = textTop.coerceIn(0f, 1f)
        val clearFrom = guard.coerceIn(0f, top)
        val clearTo = (top - fade.coerceAtLeast(0f)).coerceAtLeast(clearFrom)
        return buildList {
            add(0f to scrim)
            if (clearTo > clearFrom) {
                val clear = scrim * (1f - open.coerceIn(0f, 1f))
                add(clearFrom to clear)
                add(clearTo to clear)
            }
            add(top to scrim)
            add(1f to maxOf(scrim, OPAQUE_BOTTOM))
        }
    }
}
