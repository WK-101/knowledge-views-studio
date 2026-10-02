package app.parley.common.ux

/**
 * "Coming from…" (P7): where people switch from, and which of Parley's existing importers takes each file. Nothing is
 * fetched: the person exports on the old phone (or a computer), copies the file over and picks it here.
 */
object ComingFrom {
    /** Parley's importers, each already on its own screen or row. */
    enum class Importer {
        /** Contacts: .vcf or any CSV (Settings › Contacts › Import from .vcf or .csv file). */
        CONTACTS_FILE,

        /** Call history from a CSV (Recents & history › Import call history from CSV). */
        CALL_HISTORY_CSV,

        /** Block lists (Blocking & screening › Import & share). */
        BLOCK_LIST,
    }

    enum class Source(val importer: Importer) {
        GOOGLE(Importer.CONTACTS_FILE),
        IPHONE(Importer.CONTACTS_FILE),
        SAMSUNG(Importer.CONTACTS_FILE),
        CALL_LOG_CSV(Importer.CALL_HISTORY_CSV),
        CALL_BLOCKER(Importer.BLOCK_LIST),
        YACB(Importer.BLOCK_LIST),
        NO_PHONE_SPAM(Importer.BLOCK_LIST),
    }

    /** Sources grouped as the page lists them: contacts first, then call history, then block lists. */
    fun grouped(): List<Pair<Importer, List<Source>>> =
        Importer.entries.map { i -> i to Source.entries.filter { it.importer == i } }.filter { it.second.isNotEmpty() }
}
