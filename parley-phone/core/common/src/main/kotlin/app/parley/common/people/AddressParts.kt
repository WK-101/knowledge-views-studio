package app.parley.common.people

/**
 * The address parts RFC 9554 added to vCard's ADR (room, floor, building, street number…), which Android's
 * StructuredPostal has no columns for. Parley keeps them in the address row's own DATA11 ([COLUMN]) as
 * `key=value` lines, so they stay with their address (deleted with it, moved with it) and nothing read from a card
 * is lost. Values have their line breaks escaped.
 */
object AddressParts {
    /** The postal row's column that holds the parts. StructuredPostal uses DATA1–DATA10 only. */
    const val COLUMN = "data11"

    /** In RFC 9554's order, which is also the order of ADR's components after the country. */
    enum class Part(val key: String) {
        ROOM("room"),
        APARTMENT("apartment"),
        FLOOR("floor"),
        STREET_NUMBER("streetnumber"),
        STREET_NAME("streetname"),
        BUILDING("building"),
        BLOCK("block"),
        SUBDISTRICT("subdistrict"),
        DISTRICT("district"),
        LANDMARK("landmark"),
        DIRECTION("direction"),
    }

    /** The stored form of [parts], or null when none holds anything. */
    fun encode(parts: Map<Part, String>): String? =
        Part.entries.mapNotNull { p -> parts[p]?.trim()?.takeIf { it.isNotEmpty() }?.let { "${p.key}=${escape(it)}" } }
            .joinToString("\n").ifEmpty { null }

    /** The parts of a stored value; lines that aren't `key=value` of a known part are ignored. */
    fun decode(stored: String?): Map<Part, String> {
        if (stored.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Part, String>()
        stored.lines().forEach { line ->
            val key = line.substringBefore('=', "")
            val part = Part.entries.firstOrNull { it.key == key.trim().lowercase() } ?: return@forEach
            unescape(line.substringAfter('=')).takeIf { it.isNotBlank() }?.let { out[part] = it }
        }
        return Part.entries.filter { it in out }.associateWith { out.getValue(it) }
    }

    /** ADR's eleven RFC 9554 components (empty where absent) from a stored value. */
    fun toComponents(stored: String?): List<String> = decode(stored).let { m -> Part.entries.map { m[it].orEmpty() } }

    /** The stored form of ADR's components after the country (RFC 9554 order); null when all are empty. */
    fun fromComponents(components: List<String>): String? =
        encode(Part.entries.withIndex().associate { (i, p) -> p to components.getOrNull(i).orEmpty() })

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
