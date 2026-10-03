package app.parley.common.photo

/**
 * What a picture handed out by Save or Share is: its format read from the bytes themselves (never from a stored name
 * or a guess), so the file keeps the type it really has, and a file name built from a person's name that every
 * file system and app accepts.
 */
object ImageFiles {
    enum class Format(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpg"),
        PNG("image/png", "png"),
        WEBP("image/webp", "webp"),
        GIF("image/gif", "gif"),
        HEIC("image/heic", "heic"),
        HEIF("image/heif", "heif"),
        AVIF("image/avif", "avif"),
    }

    /** ISO base media brands (the `ftyp` box) of HEIF pictures coded with HEVC: Apple's and Android's camera photos. */
    private val heicBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs")

    /** Brands of other HEIF pictures (the generic image brands, without a coding Parley can name). */
    private val heifBrands = setOf("mif1", "msf1", "mif2")
    private val avifBrands = setOf("avif", "avis")

    /** The picture's format from its first bytes, or null when they aren't one of [Format]. */
    fun detect(bytes: ByteArray): Format? {
        fun at(i: Int, b: IntArray) = bytes.size >= i + b.size && b.indices.all { bytes[i + it].toInt() and 0xFF == b[it] }
        fun ascii(i: Int, s: String) = at(i, IntArray(s.length) { s[it].code })
        return when {
            at(0, JPEG_START) -> Format.JPEG
            at(0, PNG_SIGNATURE) -> Format.PNG
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Format.WEBP
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> Format.GIF
            ascii(4, "ftyp") -> isoBrand(bytes)
            else -> null
        }
    }

    /**
     * An ISO base media file's kind, from its major brand and then its compatible brands (a phone may write `mif1`
     * first and `heic` after it, or the other way round).
     */
    private fun isoBrand(bytes: ByteArray): Format? {
        if (bytes.size < 12) return null
        val boxSize = ((bytes[0].toInt() and 0xFF) shl 24) or ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val end = boxSize.coerceIn(12, minOf(bytes.size, MAX_FTYP))
        // The major brand at 8, the minor version at 12, then the compatible brands, four bytes each.
        val brands = buildList {
            add(String(bytes, 8, 4, Charsets.ISO_8859_1))
            var i = 16
            while (i + 4 <= end) {
                add(String(bytes, i, 4, Charsets.ISO_8859_1))
                i += 4
            }
        }
        return when {
            brands.any { it in avifBrands } -> Format.AVIF
            brands.any { it in heicBrands } -> Format.HEIC
            brands.any { it in heifBrands } -> Format.HEIF
            else -> null
        }
    }

    /**
     * "Ana Lima.jpg": [base] (a person's name, or what the picture is) with the format's extension. Characters that
     * file systems or apps refuse (`/ \ : * ? " < > |`, control characters) become spaces, runs of spaces one, and a
     * name longer than [MAX_NAME] characters is cut; an empty one becomes [fallback].
     */
    fun fileName(base: String?, format: Format, fallback: String): String {
        val cleaned = clean(base).ifEmpty { clean(fallback) }.ifEmpty { "Picture" }
        return cleaned + "." + format.extension
    }

    private fun clean(s: String?): String {
        if (s == null) return ""
        val replaced = s.map { c -> if (c.code < 0x20 || c.code == 0x7F || c in FORBIDDEN) ' ' else c }.joinToString("")
        val squeezed = replaced.replace(Regex("\\s+"), " ").trim().trim('.').trim()
        if (squeezed.length <= MAX_NAME) return squeezed
        // Never cut a surrogate pair (an emoji in a name) in half.
        var cut = MAX_NAME
        if (Character.isHighSurrogate(squeezed[cut - 1])) cut--
        return squeezed.substring(0, cut).trim()
    }

    private val JPEG_START = intArrayOf(0xFF, 0xD8, 0xFF)
    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** Longest name kept (before the extension): well under every file system's 255 bytes, even in UTF-8. */
    const val MAX_NAME = 80

    /** How far into an `ftyp` box brands are read. */
    private const val MAX_FTYP = 64
}
