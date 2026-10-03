package app.parley.common.people

import app.parley.common.record.Mime

/** Text for contacts and addresses that Android itself leaves empty. */
object ContactText {
    /**
     * One-line postal address from its parts, as Android's own formatter would write it (street, PO box and
     * neighbourhood first, then postcode and city, region, country). No part is dropped.
     */
    fun postal(
        street: String,
        poBox: String = "",
        neighborhood: String = "",
        postcode: String = "",
        city: String = "",
        region: String = "",
        country: String = "",
    ): String = listOf(
        street,
        poBox.trim().takeIf { it.isNotEmpty() }?.let { if (it.any(Char::isLetter)) it else "PO Box $it" }.orEmpty(),
        neighborhood,
        listOf(postcode, city).filter { it.isNotBlank() }.joinToString(" ") { it.trim() },
        region,
        country,
    ).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")

    /** Priority of data kinds that can name a contact Android shows without a display name (a "blank" contact). */
    private val BLANK_ORDER = listOf(Mime.POSTAL, Mime.WEBSITE, Mime.IM, Mime.SIP, Mime.EVENT, Mime.RELATION, Mime.NOTE)

    const val NO_NAME = "(No name)"

    /**
     * Name to list a contact under when Android computed none (only an address, note, website… is stored).
     * [rows]: (mimetype, first value) of its data rows. Never empty, so such contacts are never hidden.
     */
    fun blankContactName(rows: List<Pair<String, String?>>): String {
        for (mime in BLANK_ORDER) {
            val v = rows.firstOrNull { it.first == mime && !it.second.isNullOrBlank() }?.second ?: continue
            val line = v.trim().lineSequence().first().trim()
            return if (line.length > 60) line.take(59) + "…" else line
        }
        return NO_NAME
    }
}

/**
 * Keeps ContentProvider batches under the provider's operation limit: ContactsProvider rejects a batch with more than
 * 500 operations between yield points, and one long transaction keeps every other reader of contacts waiting.
 */
object Batches {
    /** Operations per batch: under the provider's 500 even with no yield point at all. */
    const val MAX_OPS = 400

    /** A batch of independent operations lets the provider commit and let others in this often. */
    const val YIELD_EVERY = 100

    fun <T> chunks(ops: List<T>, max: Int = MAX_OPS): List<List<T>> = if (ops.isEmpty()) emptyList() else ops.chunked(max)

    /** Whether the operation at [index] of a batch may be a yield point (never the first: it has nothing to commit). */
    fun yieldsAt(index: Int): Boolean = index > 0 && index % YIELD_EVERY == 0

    /** Every unordered pair of [ids], as AggregationExceptions need for linking or separating raw contacts. */
    fun pairs(ids: List<Long>): List<Pair<Long, Long>> {
        val d = ids.distinct()
        val out = ArrayList<Pair<Long, Long>>(d.size * (d.size - 1) / 2)
        for (i in d.indices) for (j in i + 1 until d.size) out += d[i] to d[j]
        return out
    }
}
