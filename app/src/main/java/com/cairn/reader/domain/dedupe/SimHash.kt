package com.cairn.reader.domain.dedupe

/**
 * A 64-bit [SimHash](https://en.wikipedia.org/wiki/SimHash) over an article's text — the fingerprint
 * that lets the Content Engine collapse *near*-duplicates, not just byte-identical ones.
 *
 * Why it matters here: the same story arrives through several channels (an RSS summary, the full
 * site-extract, and an archive.org capture) with small differences — a trailing "Read more", a
 * boilerplate footer, a re-ordered paragraph. Canonical-URL and exact-title matching (see
 * [ContentDeduper]) misses these; SimHash catches them because a handful of changed words flips only
 * a handful of the 64 bits. Two documents are treated as near-duplicates when their fingerprints are
 * within a small Hamming distance ([NEAR_DUP_HAMMING]).
 *
 * The construction is the standard Charikar one: hash every token to 64 bits, and for each bit
 * position keep a signed accumulator (+weight when the token's bit is 1, −weight when 0). The final
 * fingerprint sets a bit where its accumulator ended positive. Frequent, meaningful words therefore
 * dominate the signature while one-off noise cancels out.
 *
 * Pure and allocation-frugal (no Android types) so it runs on the sync/crawl worker threads and is
 * unit-testable off-device. A value of 0 is reserved to mean "not computed".
 */
object SimHash {

    /**
     * Max Hamming distance at which two fingerprints are treated as the same article. 7 of 64 bits
     * (≈11%) is the near-dup radius: article-length text with a boilerplate line, a "read more", or a
     * few re-ordered words lands within it, while genuinely different articles sit far away (~half the
     * bits). It is deliberately coupled to [BAND_COUNT] below — the LSH candidate generator only
     * *guarantees* it surfaces every pair within `BAND_COUNT − 1` bits.
     */
    const val NEAR_DUP_HAMMING = 7

    /**
     * LSH band width in bits. 64 / 8 = 8 bands of 8 bits (see [bands]). Eight bands guarantee that any
     * two fingerprints within `8 − 1 = 7` differing bits (= [NEAR_DUP_HAMMING]) agree exactly on at
     * least one band, so no near-duplicate is ever missed by the blocking step.
     */
    private const val BAND_BITS = 8
    const val BAND_COUNT = 64 / BAND_BITS // 8

    // Tokens are lower-cased alphanumeric runs. Very short tokens carry little signal and inflate the
    // accumulator with common stop fragments, so we drop 1–2 char tokens.
    private val TOKEN = Regex("[\\p{L}\\p{Nd}]+")
    private const val MIN_TOKEN_LEN = 3

    /**
     * Compute the 64-bit fingerprint of [text]. Returns 0 when the text has no usable tokens (which
     * the deduper reads as "unknown", never matching anything) so 0 stays a safe "not computed"
     * sentinel.
     */
    fun compute(text: String): Long {
        if (text.isEmpty()) return 0L
        // Signed accumulator per bit position.
        val bits = IntArray(64)
        var tokens = 0
        var start = -1
        // Manual tokenizer (cheaper than Regex.findAll over long article bodies): walk the string,
        // treating any maximal run of letters/digits as a token, matching TOKEN's class.
        val len = text.length
        var i = 0
        while (i <= len) {
            val isTok = i < len && Character.isLetterOrDigit(text[i])
            if (isTok) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if (i - start >= MIN_TOKEN_LEN) {
                    accumulate(text, start, i, bits)
                    tokens++
                }
                start = -1
            }
            i++
        }
        if (tokens == 0) return 0L
        var h = 0L
        for (b in 0 until 64) if (bits[b] > 0) h = h or (1L shl b)
        // Never hand back 0 for a real document — 0 is the "not computed" sentinel. Collisions onto
        // exactly 0 are astronomically unlikely, but nudge one bit if it happens.
        return if (h == 0L) 1L else h
    }

    private fun accumulate(text: String, from: Int, to: Int, bits: IntArray) {
        val h = hash64Lower(text, from, to)
        for (b in 0 until 64) {
            if ((h ushr b) and 1L == 1L) bits[b]++ else bits[b]--
        }
    }

    /**
     * FNV-1a 64-bit over the lower-cased characters of text[from,to). Lower-casing inline avoids
     * allocating a substring per token. Uses [Char.lowercaseChar] so tokens differing only in case
     * hash identically.
     */
    private fun hash64Lower(text: String, from: Int, to: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis (14695981039346656037)
        var i = from
        while (i < to) {
            val c = text[i].lowercaseChar().code
            // Fold the (up to 16-bit) char into the hash a byte at a time, low byte first.
            h = (h xor (c and 0xFF).toLong()) * 0x100000001b3L
            val hi = c ushr 8
            if (hi != 0) h = (h xor (hi and 0xFF).toLong()) * 0x100000001b3L
            i++
        }
        return h
    }

    /** Hamming distance between two fingerprints — the number of differing bits. */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** True when both fingerprints are computed (non-zero) and within [NEAR_DUP_HAMMING]. */
    fun isNearDuplicate(a: Long, b: Long): Boolean =
        a != 0L && b != 0L && hamming(a, b) <= NEAR_DUP_HAMMING

    /**
     * The [BAND_COUNT] 8-bit bands of a fingerprint, each tagged with its band index so equal chunks
     * in *different* positions don't collide. This is the LSH blocking key: by the pigeonhole
     * principle, two fingerprints within [NEAR_DUP_HAMMING] (≤ BAND_COUNT − 1) differing bits must
     * agree exactly on at least one of the 8 bands, so grouping items by shared band value is
     * guaranteed to surface every near-duplicate pair as a candidate — without the O(n²) all-pairs
     * comparison. Callers then confirm each candidate with [hamming].
     */
    fun bands(hash: Long): LongArray {
        val out = LongArray(BAND_COUNT)
        for (band in 0 until BAND_COUNT) {
            val chunk = (hash ushr (band * BAND_BITS)) and 0xFFL
            // Tag with the band index above the 8-bit chunk so band 0 == 0x12 never equals band 1 == 0x12.
            out[band] = (band.toLong() shl BAND_BITS) or chunk
        }
        return out
    }
}
