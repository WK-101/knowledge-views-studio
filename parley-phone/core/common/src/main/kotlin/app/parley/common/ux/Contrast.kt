package app.parley.common.ux

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast between two opaque colours given as ARGB ints (alpha is ignored). Used to pick the ink for text
 * on fixed colours (avatars, call buttons) and to keep those colours honest in tests: normal text needs 4.5:1,
 * large or bold text and icons 3:1.
 */
object Contrast {
    const val TEXT = 4.5
    const val LARGE = 3.0

    fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Whichever of [light] and [dark] reads better on [background]. */
    fun inkFor(background: Int, light: Int, dark: Int): Int =
        if (ratio(background, light) >= ratio(background, dark)) light else dark
}

/**
 * The colours of avatars without a photo, and the ink their initials use. Every hue reaches 4.5:1 with its ink:
 * light hues (lime, orange, green, blue) take the dark ink, the others white.
 */
object AvatarPalette {
    const val WHITE_INK: Int = 0xFFFFFFFF.toInt()
    const val DARK_INK: Int = 0xFF1B1B1F.toInt()

    val colors: IntArray = intArrayOf(
        0xFF3F51B5.toInt(), 0xFF00796B.toInt(), 0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFFF4511E.toInt(),
        0xFF6D4C41.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt(), 0xFF5E35B1.toInt(), 0xFFC0CA33.toInt(),
    )

    fun colorFor(seed: String): Int = colors[(seed.hashCode() and 0x7fffffff) % colors.size]

    fun inkFor(color: Int): Int = Contrast.inkFor(color, WHITE_INK, DARK_INK)
}
