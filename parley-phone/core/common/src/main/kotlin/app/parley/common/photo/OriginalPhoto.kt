package app.parley.common.photo

/**
 * The original contact photo Parley keeps beside Android's copy. Android's contacts provider stores a contact photo
 * scaled so its longer side is at most 720 px (480 px on low-memory phones) and re-encoded as JPEG at quality 75,
 * plus a 96 px thumbnail (AOSP ContactsProvider `PhotoProcessor`; it keeps the aspect ratio, only stream-item photos
 * are cropped square). Parley keeps the picture as picked, for its own contact page and photo viewer.
 */
object OriginalPhoto {
    /**
     * Largest original kept as it is without asking (a phone camera's photos are far smaller). Above it the user
     * chooses: the whole file, or a high-quality JPEG ([Question.LARGE]).
     */
    const val ASK_ABOVE_BYTES = 40L shl 20

    /** Largest original kept as it is at all: a private contact's is opened whole in memory to show it. */
    const val MAX_BYTES = 100L shl 20

    /** Pixels of an original that has to be re-encoded: about 16 MP, ~64 MB decoded. */
    const val MAX_REENCODE_PIXELS = 16_000_000L

    /** JPEG quality of a re-encoded original: visually lossless. */
    const val REENCODE_QUALITY = 95

    /** Formats whose location tags Parley removes without touching the picture (an EXIF rewrite). */
    val locationStrippable: Set<ImageFiles.Format> = setOf(ImageFiles.Format.JPEG, ImageFiles.Format.PNG, ImageFiles.Format.WEBP)

    /** Formats whose location Parley can't remove without re-encoding the picture. */
    val locationFixed: Set<ImageFiles.Format> = setOf(ImageFiles.Format.HEIC, ImageFiles.Format.HEIF, ImageFiles.Format.AVIF)

    enum class Keep {
        /** The file as it is, in its own format (location tags removed where [Plan.stripLocation]). */
        COPY,

        /** Decoded upright and written as a high-quality JPEG, without location. */
        REENCODE,
    }

    /**
     * A picked picture as Parley sees it before keeping it: its [format] from its first bytes (null: not one Parley
     * names), its size in [bytes], and whether it carries a location (null: couldn't tell).
     */
    data class Probe(val format: ImageFiles.Format?, val bytes: Long, val hasLocation: Boolean?)

    /** What to ask before keeping a picture. */
    enum class Question {
        /** Over [ASK_ABOVE_BYTES]: keep the whole file, or a high-quality JPEG? */
        LARGE,

        /** A HEIC/HEIF/AVIF with a location: keep it with its location, or a JPEG without? */
        LOCATION,
    }

    /** The answers given; null: not asked, or the question was dismissed (then the safer choice is taken). */
    data class Answers(val keepWhole: Boolean? = null, val keepLocation: Boolean? = null)

    /** How to keep a picture: [keep], and whether its location tags are removed from the copy. */
    data class Plan(val keep: Keep, val stripLocation: Boolean)

    private fun large(p: Probe) = p.bytes > ASK_ABOVE_BYTES

    /** The next question [p] needs, given [a]; null when there is none left. */
    fun nextQuestion(p: Probe, a: Answers): Question? {
        if (p.format == null || p.bytes > MAX_BYTES) return null
        if (large(p) && a.keepWhole == null) return Question.LARGE
        if (large(p) && a.keepWhole == false) return null
        if (p.format in locationFixed && p.hasLocation != false && a.keepLocation == null) return Question.LOCATION
        return null
    }

    /**
     * How [p] is kept with answers [a]: as it is whenever Parley can (every format it names, up to [MAX_BYTES]), with
     * its location tags removed where that leaves the picture untouched. A JPEG instead when the file is over
     * [ASK_ABOVE_BYTES] and wasn't wanted whole, when a HEIC/HEIF/AVIF's location wasn't wanted kept, or when the format
     * isn't one Parley knows.
     */
    fun plan(p: Probe, a: Answers): Plan {
        val f = p.format ?: return Plan(Keep.REENCODE, false)
        if (p.bytes <= 0 || p.bytes > MAX_BYTES) return Plan(Keep.REENCODE, false)
        if (large(p) && a.keepWhole != true) return Plan(Keep.REENCODE, false)
        if (f in locationFixed && p.hasLocation != false && a.keepLocation != true) return Plan(Keep.REENCODE, false)
        return Plan(Keep.COPY, f in locationStrippable)
    }

    /** How a kept original was written, recorded beside it so the viewer says it truthfully. */
    enum class Kept(val key: String) {
        /** Byte for byte; it carried no location. */
        AS_PICKED("picked"),

        /** Byte for byte except its location tags. */
        LOCATION_REMOVED("noloc"),

        /** Byte for byte, location included (the user chose to keep it). */
        LOCATION_KEPT("loc"),

        /** Re-encoded as a high-quality JPEG, without location. */
        JPEG("jpeg"),
        ;

        companion object {
            /** Null for an original kept before this was recorded. */
            fun of(key: String?): Kept? = entries.firstOrNull { it.key == key }
        }
    }

    /** What a copy kept with [plan] ends up as: [hadLocation] before, [hasLocation] after removing it. */
    fun kept(plan: Plan, hadLocation: Boolean, hasLocation: Boolean): Kept = when {
        plan.keep == Keep.REENCODE -> Kept.JPEG
        hasLocation -> Kept.LOCATION_KEPT
        hadLocation -> Kept.LOCATION_REMOVED
        else -> Kept.AS_PICKED
    }

    /** The size to re-encode a [width]×[height] picture at: the same, or scaled to [maxPixels]. Never enlarges. */
    fun reencodeSize(width: Int, height: Int, maxPixels: Long = MAX_REENCODE_PIXELS): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val pixels = width.toLong() * height
        if (pixels <= maxPixels) return width to height
        val scale = Math.sqrt(maxPixels.toDouble() / pixels)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /**
     * How the contact page shows a [width]×[height] (upright) photo, as factors of the hero size: a circle for a
     * roughly square photo; otherwise a rounded rectangle at the photo's own shape, landscape up to 1.6 wide and
     * portrait up to 1.25 tall (anything more extreme is trimmed at the edges there; the viewer shows it whole).
     */
    data class Hero(val round: Boolean, val width: Float, val height: Float)

    fun hero(width: Int, height: Int): Hero {
        if (width <= 0 || height <= 0) return Hero(true, 1f, 1f)
        val aspect = width.toFloat() / height
        return when {
            aspect in 0.9f..1.11f -> Hero(true, 1f, 1f)
            aspect > 1f -> Hero(false, aspect.coerceAtMost(1.6f), 1f)
            else -> Hero(false, 1.25f * aspect.coerceAtLeast(0.6f), 1.25f)
        }
    }

    /**
     * The region of the stored (not yet upright) image to decode for [region] of the upright image, for an image
     * stored [width]×[height] with EXIF [t]. Regions are left/top inclusive, right/bottom exclusive.
     */
    fun storedRegion(region: PhotoMath.Crop, t: PhotoMath.ExifTransform, width: Int, height: Int): PhotoMath.Crop {
        if (t.isIdentity) return region
        val (a, b) = unmap(region.left, region.top, t, width, height)
        val (c, d) = unmap(region.right - 1, region.bottom - 1, t, width, height)
        return PhotoMath.Crop(minOf(a, c), minOf(b, d), maxOf(a, c) + 1, maxOf(b, d) + 1)
    }

    /** The stored pixel that lands on upright pixel ([x], [y]): the inverse of [PhotoMath.ExifTransform.map]. */
    fun unmap(x: Int, y: Int, t: PhotoMath.ExifTransform, width: Int, height: Int): Pair<Int, Int> {
        val (uw, _) = t.uprightSize(width, height)
        val rx = if (t.flipX) uw - 1 - x else x
        return when (t.degrees) {
            90 -> y to (height - 1 - rx)
            180 -> (width - 1 - rx) to (height - 1 - y)
            270 -> (width - 1 - y) to rx
            else -> rx to y
        }
    }

    /**
     * `inSampleSize` for a [regionWidth]×[regionHeight] part of a picture shown [viewWidth]×[viewHeight] px: the
     * largest power of two that still gives at least one picture pixel per screen pixel.
     */
    fun sampleFor(regionWidth: Int, regionHeight: Int, viewWidth: Int, viewHeight: Int): Int {
        if (minOf(regionWidth, regionHeight, viewWidth, viewHeight) <= 0) return 1
        var s = 1
        while (regionWidth / (s * 2) >= viewWidth && regionHeight / (s * 2) >= viewHeight) s *= 2
        return s
    }

    /**
     * Whether the kept original still belongs with the contact's photo. [before]: Android's photo URI before Parley
     * wrote the new photo; [bound]: the URI it was matched with (empty until Android has processed the new photo);
     * [current]: the URI now. Android gives a new photo a new URI, so a different one means another app (or a
     * sync) replaced the photo, and the original is out of date.
     */
    sealed interface Match {
        /** Show it. [bind]: remember this URI as the photo's from now on. */
        data class Show(val bind: String? = null) : Match

        /** Out of date: delete it. */
        data object Stale : Match
    }

    fun match(before: String?, bound: String?, current: String?): Match = when {
        !bound.isNullOrEmpty() -> if (current == bound) Match.Show() else Match.Stale
        // Android hasn't processed the new photo yet (or a private contact, which has no Android photo).
        current == null || current == before -> Match.Show()
        else -> Match.Show(bind = current)
    }
}
