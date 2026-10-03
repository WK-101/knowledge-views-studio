package app.parley.common.photo

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The square of a contact photo that Android and Parley's lists show as the avatar and thumbnail ("Frame photo").
 * The picture itself is kept whole for the contact page, the photo viewer and the call screen; only the avatar is
 * cut from it. Kept as fractions of the upright picture, so the same frame fits any decoded size of that picture and
 * can be adjusted again later without loss.
 *
 * [left] and [top]: the square's top-left corner as fractions of the upright width and height. [side]: its side as a
 * fraction of the shorter side (1: as large as the picture allows).
 */
data class PhotoFrame(val left: Double, val top: Double, val side: Double) {
    /** Stored form, "left,top,side" with [Locale.ROOT] digits. */
    fun encode(): String = String.format(Locale.ROOT, "%.5f,%.5f,%.5f", left, top, side)

    companion object {
        /** A frame written by [encode], or null for anything else. */
        fun decode(s: String?): PhotoFrame? {
            val parts = s?.split(',')?.takeIf { it.size == 3 } ?: return null
            val (l, t, side) = parts.map { it.trim().toDoubleOrNull() ?: return null }
            if (listOf(l, t, side).any { it.isNaN() || it.isInfinite() } || side <= 0.0) return null
            return PhotoFrame(l, t, side)
        }
    }
}

/**
 * The arithmetic of framing a photo: clamping a square inside the picture, the pinch-and-drag gesture in the framing
 * screen, its button alternatives (zoom, move, centre on a face), the pixel crop for a decoded size and the region
 * of the stored (not yet upright) file. Free of Android types so it can be tested.
 */
object FrameMath {
    /** The furthest the framing screen zooms in: the square is at least 1/8 of the shorter side. */
    const val MAX_ZOOM = 8.0

    /** A square smaller than this (picture pixels) would make a blurry avatar. */
    const val MIN_SIDE_PX = 48

    /** One zoom button press. */
    const val ZOOM_STEP = 1.25

    /** One move button press, as a fraction of the square's side. */
    const val MOVE_STEP = 0.1

    /** The largest square, centred: what Android's own thumbnail shows. */
    fun centred(width: Int, height: Int): PhotoFrame {
        if (width <= 0 || height <= 0) return PhotoFrame(0.0, 0.0, 1.0)
        val s = min(width, height).toDouble()
        return PhotoFrame((width - s) / 2 / width, (height - s) / 2 / height, 1.0)
    }

    /** The smallest side allowed, as a fraction of the shorter side. */
    fun minSide(width: Int, height: Int): Double {
        val shorter = min(width, height).toDouble()
        if (shorter <= 0) return 1.0
        val px = max(shorter / MAX_ZOOM, min(MIN_SIDE_PX.toDouble(), shorter))
        return (px / shorter).coerceIn(0.0, 1.0)
    }

    /** How far [f] is zoomed in: 1 at the largest square. */
    fun zoomOf(f: PhotoFrame): Double = 1.0 / f.side.coerceAtLeast(1e-9)

    /** [f] kept square and inside a [width]×[height] picture, no smaller than [minSide]. */
    fun clamp(f: PhotoFrame, width: Int, height: Int): PhotoFrame {
        if (width <= 0 || height <= 0) return PhotoFrame(0.0, 0.0, 1.0)
        val shorter = min(width, height).toDouble()
        val side = f.side.takeUnless { it.isNaN() }?.coerceIn(minSide(width, height), 1.0) ?: 1.0
        val sidePx = side * shorter
        val maxLeft = (width - sidePx) / width
        val maxTop = (height - sidePx) / height
        val left = f.left.takeUnless { it.isNaN() }?.coerceIn(0.0, maxLeft.coerceAtLeast(0.0)) ?: 0.0
        val top = f.top.takeUnless { it.isNaN() }?.coerceIn(0.0, maxTop.coerceAtLeast(0.0)) ?: 0.0
        return PhotoFrame(left, top, side)
    }

    /**
     * [f] in pixels of the picture decoded at [width]×[height]: a square inside it (right and bottom exclusive). A
     * picture too small for any square gives the whole of its shorter side.
     */
    fun toCrop(f: PhotoFrame, width: Int, height: Int): PhotoMath.Crop {
        if (width <= 0 || height <= 0) return PhotoMath.Crop(0, 0, 0, 0)
        val c = clamp(f, width, height)
        val shorter = min(width, height)
        val side = (c.side * shorter).roundToInt().coerceIn(1, shorter)
        val left = (c.left * width).roundToInt().coerceIn(0, width - side)
        val top = (c.top * height).roundToInt().coerceIn(0, height - side)
        return PhotoMath.Crop(left, top, left + side, top + side)
    }

    /** The frame for a square [crop] of a [width]×[height] picture (the inverse of [toCrop]). */
    fun fromCrop(crop: PhotoMath.Crop, width: Int, height: Int): PhotoFrame {
        if (width <= 0 || height <= 0) return PhotoFrame(0.0, 0.0, 1.0)
        val side = min(crop.width, crop.height).toDouble() / min(width, height)
        return clamp(PhotoFrame(crop.left.toDouble() / width, crop.top.toDouble() / height, side), width, height)
    }

    /**
     * One step of the framing gesture. The square fills a [viewSide]-pixel viewport; the fingers moved by
     * ([panX], [panY]) screen pixels and pinched by [zoom] around ([centroidX], [centroidY]) in the viewport. The
     * picture follows the fingers: the point under them stays under them.
     */
    @Suppress("LongParameterList")
    fun gesture(
        f: PhotoFrame, width: Int, height: Int, viewSide: Double,
        panX: Double, panY: Double, zoom: Double, centroidX: Double, centroidY: Double,
    ): PhotoFrame {
        if (width <= 0 || height <= 0 || viewSide <= 0) return f
        val shorter = min(width, height).toDouble()
        val c = clamp(f, width, height)
        val sidePx = c.side * shorter
        val scale = viewSide / sidePx
        // The picture point under the fingers before this step.
        val ix = c.left * width + centroidX / scale
        val iy = c.top * height + centroidY / scale
        val z = if (zoom.isNaN() || zoom <= 0) 1.0 else zoom
        val side = (c.side / z).coerceIn(minSide(width, height), 1.0)
        val nextScale = viewSide / (side * shorter)
        // ...stays under the fingers where they are now.
        val left = ix - (centroidX + panX) / nextScale
        val top = iy - (centroidY + panY) / nextScale
        return clamp(PhotoFrame(left / width, top / height, side), width, height)
    }

    /** Zoom in ([factor] > 1) or out around the square's centre (the zoom buttons). */
    fun zoomBy(f: PhotoFrame, width: Int, height: Int, factor: Double): PhotoFrame {
        if (width <= 0 || height <= 0 || factor <= 0) return f
        val shorter = min(width, height).toDouble()
        val c = clamp(f, width, height)
        val cx = c.left * width + c.side * shorter / 2
        val cy = c.top * height + c.side * shorter / 2
        val side = (c.side / factor).coerceIn(minSide(width, height), 1.0)
        return centreOn(PhotoFrame(c.left, c.top, side), width, height, cx, cy)
    }

    /**
     * Moves the photo inside the square by ([dx], [dy]) square sides (the move buttons): positive moves the photo
     * right or down, showing more of its left or top, as dragging it would.
     */
    fun move(f: PhotoFrame, width: Int, height: Int, dx: Double, dy: Double): PhotoFrame {
        if (width <= 0 || height <= 0) return f
        val c = clamp(f, width, height)
        val sidePx = c.side * min(width, height)
        return clamp(PhotoFrame((c.left * width - dx * sidePx) / width, (c.top * height - dy * sidePx) / height, c.side), width, height)
    }

    /** [f] of the same size, centred on picture point ([x], [y]) as far as the picture allows ("Centre on face"). */
    fun centreOn(f: PhotoFrame, width: Int, height: Int, x: Double, y: Double): PhotoFrame {
        if (width <= 0 || height <= 0) return f
        val side = clamp(f, width, height).side
        val half = side * min(width, height) / 2
        return clamp(PhotoFrame((x - half) / width, (y - half) / height, side), width, height)
    }

    /** Whether [a] and [b] cut the same square of a [width]×[height] picture (to the pixel). */
    fun same(a: PhotoFrame, b: PhotoFrame, width: Int, height: Int): Boolean = toCrop(a, width, height) == toCrop(b, width, height)

    /**
     * The region of the stored file to decode for [f] when the stored image is [storedWidth]×[storedHeight] with EXIF
     * [orientation] (the frame is in upright terms; the decoder reads the file as stored).
     */
    fun storedCrop(f: PhotoFrame, orientation: Int, storedWidth: Int, storedHeight: Int): PhotoMath.Crop {
        val t = PhotoMath.exifTransform(orientation)
        val (uw, uh) = t.uprightSize(storedWidth, storedHeight)
        return OriginalPhoto.storedRegion(toCrop(f, uw, uh), t, storedWidth, storedHeight)
    }

    /**
     * The longer side to decode a picture at (its sides [longer] and [shorter] px, in any orientation) so a frame of
     * [side] still has at least [target] px, never more than the picture has or [cap].
     */
    fun decodeLongSide(longer: Int, shorter: Int, side: Double, target: Int = PhotoMath.TARGET, cap: Int = 4096): Int {
        if (longer <= 0 || shorter <= 0) return target
        val s = side.coerceIn(1e-3, 1.0)
        val needed = Math.ceil(target.toDouble() * longer / (s * shorter)).toInt()
        return minOf(needed, longer, cap).coerceAtLeast(1)
    }

    /** The avatar's side in px for a crop [cropSide] px wide: as it is, at most [target]. */
    fun outputSide(cropSide: Int, target: Int = PhotoMath.TARGET): Int = cropSide.coerceIn(1, target.coerceAtLeast(1))
}
