package com.wkhan.hexis.domain

/**
 * Wave 2 · Local semantic-ish similarity — model-free, on-device, deterministic. Character n-gram
 * shingling + a fixed-family MinHash signature gives an estimated Jaccard similarity that tolerates
 * paraphrase, word-order changes and typos far better than exact FTS, with no embedding model and no
 * network. Used to find near-duplicate notes (the Note-Garden review) and to broaden "find notes about X".
 */
object NoteSemantic {
    private const val K = 4          // shingle size, in characters
    private const val HASHES = 32    // signature length

    // A seed-free, deterministic hash family: odd multipliers + fixed adders (32-bit wraparound arithmetic).
    private val A = IntArray(HASHES) { ((it * 2 + 1) * 0x9E3779B1.toInt()) or 1 }
    private val B = IntArray(HASHES) { (it * 2 + 1) * 0x7F4A7C15.toInt() }

    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun shingles(text: String): Set<Int> {
        val s = normalize(text)
        if (s.length < K) return if (s.isBlank()) emptySet() else setOf(s.hashCode())
        val out = HashSet<Int>(s.length)
        for (i in 0..s.length - K) out.add(s.substring(i, i + K).hashCode())
        return out
    }

    /** A MinHash signature (one Int per hash function); empty text → all Int.MAX_VALUE. */
    fun signature(text: String): IntArray {
        val sh = shingles(text)
        val sig = IntArray(HASHES) { Int.MAX_VALUE }
        if (sh.isEmpty()) return sig
        for (x in sh) {
            for (h in 0 until HASHES) {
                val hv = A[h] * x + B[h]       // 32-bit wraparound
                if (hv < sig[h]) sig[h] = hv
            }
        }
        return sig
    }

    /** Estimated Jaccard similarity in [0,1] between two signatures. */
    fun similarity(a: IntArray, b: IntArray): Float {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0f
        var match = 0
        for (h in a.indices) if (a[h] == b[h]) match++
        return match.toFloat() / a.size
    }
}
