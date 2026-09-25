package app.parley.common.photo

/**
 * C1 (G2): the arithmetic behind the contact photo processor, kept free of Android types so it can be tested.
 * The processor reads the image size first, decodes at a reduced size ([sampleSize] or [scaledSize]), turns it
 * upright ([ExifTransform]), crops the centre square ([centerSquare]) and writes a [TARGET] px JPEG.
 */
object PhotoMath {
    /** Edge of the square written to the contact (the size Android's contacts provider keeps as display photo). */
    const val TARGET = 720

    /** JPEG quality of the written photo. */
    const val QUALITY = 88

    /** A crop rectangle; [right] and [bottom] are exclusive, like android.graphics.Rect. */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * The largest power-of-two `inSampleSize` that still leaves the shorter side at least [target] px, so the
     * centre square can be cut at full [target] quality. 1 for images that are already small (or invalid).
     */
    fun sampleSize(width: Int, height: Int, target: Int = TARGET): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val shorter = minOf(width, height)
        var s = 1
        while (shorter / (s * 2) >= target) s *= 2
        return s
    }

    /**
     * Size to decode an image of [width]×[height] at so its shorter side is [target] px, keeping the aspect ratio.
     * Never enlarges: images already smaller are decoded as they are.
     */
    fun scaledSize(width: Int, height: Int, target: Int = TARGET): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val shorter = minOf(width, height)
        if (shorter <= target) return width to height
        val scale = target.toDouble() / shorter
        // The shorter side lands exactly on target; the longer one rounds, never below target.
        return if (width <= height) target to maxOf(target, Math.round(height * scale).toInt())
        else maxOf(target, Math.round(width * scale).toInt()) to target
    }

    /** The centred square of a [width]×[height] image (the whole image when it is already square). */
    fun centerSquare(width: Int, height: Int): Crop {
        val side = minOf(width, height).coerceAtLeast(0)
        val left = (width - side) / 2
        val top = (height - side) / 2
        return Crop(left, top, left + side, top + side)
    }

    /**
     * How to turn an image upright for EXIF orientation [orientation] (1–8, ExifInterface.ORIENTATION_*):
     * rotate clockwise by [degrees] first, then mirror horizontally when [flipX] (the order of
     * `Matrix.setRotate(degrees); postScale(-1f, 1f)`). Unknown values (0, undefined) mean "as stored".
     */
    data class ExifTransform(val degrees: Int, val flipX: Boolean) {
        val isIdentity get() = degrees == 0 && !flipX

        /** Whether width and height swap. */
        val swapsSides get() = degrees == 90 || degrees == 270

        /** The upright size of a stored [width]×[height] image. */
        fun uprightSize(width: Int, height: Int): Pair<Int, Int> = if (swapsSides) height to width else width to height

        /**
         * Where pixel ([x], [y]) of a stored [width]×[height] image lands in the upright image (for tests and
         * for checking the matrix the processor builds).
         */
        fun map(x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
            val (rx, ry) = when (degrees) {
                90 -> (height - 1 - y) to x
                180 -> (width - 1 - x) to (height - 1 - y)
                270 -> y to (width - 1 - x)
                else -> x to y
            }
            val (w, _) = uprightSize(width, height)
            return if (flipX) (w - 1 - rx) to ry else rx to ry
        }
    }

    fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
        2 -> ExifTransform(0, flipX = true) // FLIP_HORIZONTAL
        3 -> ExifTransform(180, flipX = false) // ROTATE_180
        4 -> ExifTransform(180, flipX = true) // FLIP_VERTICAL = rotate 180 + mirror
        5 -> ExifTransform(90, flipX = true) // TRANSPOSE
        6 -> ExifTransform(90, flipX = false) // ROTATE_90
        7 -> ExifTransform(270, flipX = true) // TRANSVERSE
        8 -> ExifTransform(270, flipX = false) // ROTATE_270
        else -> ExifTransform(0, flipX = false) // NORMAL, UNDEFINED
    }
}
