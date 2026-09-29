package app.parley.common.ux

import kotlin.math.roundToInt

/**
 * Settings › Calls › "Call screen background": the caller's colour as a soft tint at the top (the default), or the
 * theme's plain background. A contact's own call-screen picture is a separate, per-contact choice and shows either way.
 */
enum class CallScreenBackground { CALLER_COLOUR, PLAIN }

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

    /** What the call screen draws behind the caller: a tint, and whether the contact's picture goes over it. */
    data class Plan(val tint: Tint, val picture: Boolean)

    /**
     * The background for a call. [warn] is a ringing call screened as likely spam: its red wash is a warning, not
     * decoration, so it stays with a plain background too. [hasPicture] is the contact's call-screen picture, which
     * the user set for that person on purpose and which shows whatever the style; [allowPicture] is off where a
     * picture doesn't fit (the picture-in-picture window).
     */
    fun plan(style: CallScreenBackground, warn: Boolean, hasPicture: Boolean, allowPicture: Boolean = true): Plan {
        val tint = when {
            warn -> Tint.WARNING
            style == CallScreenBackground.PLAIN -> Tint.NONE
            else -> Tint.CALLER
        }
        return Plan(tint, picture = hasPicture && allowPicture)
    }
}
