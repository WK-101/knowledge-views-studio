package app.parley.common.memory

import app.parley.common.PhoneIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Number memory's summary of the call archive, per line: how many calls, since when, the newest call and the newest
 * name the history showed ([NumberMemory.pastCalls] from it). Kept (sealed) between rebuilds with the point it was
 * read up to ([mark]), so a daily rebuild adds only the calls archived since, instead of opening every archived call.
 */
@Serializable
data class CallTally(
    /** Where reading stopped (the archive's own bookkeeping); empty for a tally that must be read again. */
    @SerialName("m") val mark: String = "",
    /** The region numbers were read with: a tally read with another one is read again. */
    @SerialName("r") val region: String? = null,
    @SerialName("l") val lines: Map<String, Line> = emptyMap(),
) {
    @Serializable
    data class Line(
        @SerialName("n") val number: String,
        @SerialName("c") val count: Int,
        @SerialName("f") val since: Long,
        @SerialName("t") val at: Long,
        @SerialName("a") val name: String? = null,
        @SerialName("w") val namedAt: Long = 0,
    )

    /** This tally with [calls] added; [mark] is where reading stopped now. */
    fun plus(calls: List<NumberMemory.PastCall>, mark: String): CallTally {
        val out = HashMap(lines)
        val keyed = calls.filter { it.number.isNotBlank() }.mapNotNull { c -> PhoneIdentity.key(c.number, region).takeIf { it.isNotEmpty() }?.let { it to c } }
        for ((line, c) in keyed) {
            val named = c.name?.trim()?.takeIf { it.isNotEmpty() }
            val prev = out[line]
            out[line] = if (prev == null) {
                Line(c.number, 1, c.date, c.date, named, if (named != null) c.date else 0)
            } else {
                val newer = c.date >= prev.at
                val newerName = named != null && (prev.name == null || c.date >= prev.namedAt)
                Line(
                    number = if (newer) c.number else prev.number,
                    count = prev.count + 1,
                    since = minOf(prev.since, c.date),
                    at = maxOf(prev.at, c.date),
                    name = if (newerName) named else prev.name,
                    namedAt = if (newerName) c.date else prev.namedAt,
                )
            }
        }
        return copy(mark = mark, lines = out)
    }

    /** The hints, as [NumberMemory.pastCalls] gives them for the same calls. */
    fun entries(): List<NumberMemory.Entry> = lines.values.flatMap { l ->
        listOfNotNull(
            NumberMemory.Entry(l.number, MemoryHint(MemorySource.CALLS, count = l.count, since = l.since, at = l.at)),
            l.name?.let { NumberMemory.Entry(l.number, MemoryHint(MemorySource.ARCHIVE_NAME, name = it, at = l.namedAt)) },
        )
    }

    fun encode(): ByteArray = json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun empty(region: String?) = CallTally(region = region)

        /** Null when [bytes] aren't a tally (then everything is read again). */
        fun decode(bytes: ByteArray): CallTally? = runCatching { json.decodeFromString(serializer(), bytes.toString(Charsets.UTF_8)) }.getOrNull()
    }
}
