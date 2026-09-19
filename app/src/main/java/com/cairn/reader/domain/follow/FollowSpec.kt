package com.cairn.reader.domain.follow

/**
 * A followed author or topic (Content Engine P5). A Follow is a *live* stream: rather than a
 * subscription to one site, it is a standing query resolved against the whole local archive, so it
 * surfaces matching articles no matter which feed, extract, or archive capture they arrived through.
 *
 * Persisted as a single encoded string in DataStore (see [encode]) so it slots into the same
 * `Set<String>` mechanism as saved searches, with no schema change. Pure and unit-testable.
 */
data class FollowSpec(val kind: Kind, val value: String) {

    enum class Kind { AUTHOR, TOPIC }

    /** The stored form: a one-char kind tag, a unit-separator, then the raw value. The separator is a
     *  control char that never appears in a byline or keyword, so the value round-trips intact. */
    fun encode(): String = "${if (kind == Kind.AUTHOR) 'A' else 'T'}$SEP$value"

    companion object {
        private const val SEP = '\u001F'

        fun author(name: String) = FollowSpec(Kind.AUTHOR, name.trim())
        fun topic(keyword: String) = FollowSpec(Kind.TOPIC, keyword.trim())

        /** Parse a stored entry back into a spec, or null if malformed / blank. */
        fun decode(encoded: String): FollowSpec? {
            val i = encoded.indexOf(SEP)
            if (i <= 0) return null
            val value = encoded.substring(i + 1).trim()
            if (value.isBlank()) return null
            return when (encoded[0]) {
                'A' -> FollowSpec(Kind.AUTHOR, value)
                'T' -> FollowSpec(Kind.TOPIC, value)
                else -> null
            }
        }

        /** Decode a whole stored set, dropping any malformed entries, newest-stable ordered by value. */
        fun decodeAll(encoded: Set<String>): List<FollowSpec> =
            encoded.mapNotNull { decode(it) }.sortedBy { it.value.lowercase() }
    }
}
