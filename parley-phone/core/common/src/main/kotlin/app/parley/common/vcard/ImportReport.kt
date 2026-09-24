package app.parley.common.vcard

/** A card (or CSV line) that could not be imported. [index] is 1-based in file order. */
data class CardFailure(val index: Int, val reason: String, val snippet: String)

/**
 * What happened during an import, shown to the user so nothing is lost silently.
 *
 * - [cardsParsed]: cards (or CSV rows) that were read and mapped to a contact.
 * - [imported]: contacts actually written to the address book.
 * - [skippedDuplicates]: parsed cards not written because a matching contact already existed.
 * - [failures]: cards that could not be read or written, with the reason and the start of the raw text.
 * - [unmappedProperties]: vCard properties (or CSV columns) that have no place in an Android contact, with counts.
 */
data class ImportReport(
    val cardsParsed: Int = 0,
    val imported: Int = 0,
    val skippedDuplicates: Int = 0,
    val failures: List<CardFailure> = emptyList(),
    val unmappedProperties: Map<String, Int> = emptyMap(),
) {
    val cardsFailed: Int get() = failures.size

    companion object {
        private const val CSV_COLUMN = "csv-column:"

        /**
         * The [unmappedProperties] key for a CSV column [name] (or 1-based number). Not text to show: the app turns
         * it into "CSV column “name”" in the user's language ([csvColumnName] tells such keys apart). vCard property
         * names never contain ':', so the keys can't collide.
         */
        fun csvColumn(name: String): String = CSV_COLUMN + name

        /** The column name of a [csvColumn] key, or null for a vCard property. */
        fun csvColumnName(key: String): String? = key.takeIf { it.startsWith(CSV_COLUMN) }?.removePrefix(CSV_COLUMN)
    }

    /** One-line summary, e.g. "Imported 12 of 14 · 1 duplicate skipped · 1 failed". */
    fun summary(): String = buildList {
        add("Imported $imported of ${cardsParsed + cardsFailed}")
        if (skippedDuplicates > 0) add("$skippedDuplicates duplicate${if (skippedDuplicates == 1) "" else "s"} skipped")
        if (cardsFailed > 0) add("$cardsFailed failed")
        val unmapped = unmappedProperties.values.sum()
        if (unmapped > 0) add("$unmapped field${if (unmapped == 1) "" else "s"} not mapped")
    }.joinToString(" · ")
}

/** Mutable accumulator used while streaming through a file. */
class ImportReportBuilder {
    var cardsParsed = 0
    var imported = 0
    var skippedDuplicates = 0
    val failures = ArrayList<CardFailure>()
    val unmapped = LinkedHashMap<String, Int>()

    fun unmapped(name: String, count: Int = 1) {
        unmapped[name] = (unmapped[name] ?: 0) + count
    }

    fun fail(index: Int, reason: String, raw: String) {
        failures += CardFailure(index, reason, snippet(raw))
    }

    fun build() = ImportReport(cardsParsed, imported, skippedDuplicates, failures.toList(), unmapped.toMap())

    companion object {
        const val SNIPPET_CHARS = 300

        fun snippet(raw: String): String {
            val s = raw.trim()
            return if (s.length <= SNIPPET_CHARS) s else s.take(SNIPPET_CHARS) + "…"
        }
    }
}
