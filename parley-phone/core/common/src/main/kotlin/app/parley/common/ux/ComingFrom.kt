package app.parley.common.ux

/**
 * "Coming from…": where people switch from, and which of Parley's existing importers takes each file. Nothing is
 * fetched: the person exports on the old phone (or a computer), copies the file over and picks it here.
 */
object ComingFrom {
    /** Parley's importers, each already on its own screen or row. */
    enum class Importer {
        /**
         * Another phone with Parley: its encrypted backup, restored on the Backup screen. First, because it brings
         * everything (private contacts, notes, Situations, settings) and overwrites what the first run set up.
         */
        PARLEY_BACKUP,

        /** Contacts: .vcf or any CSV (Settings › Contacts › Import from .vcf or .csv file). */
        CONTACTS_FILE,

        /** Call history from a CSV (Recents & history › Import call history from CSV). */
        CALL_HISTORY_CSV,

        /** Block lists (Blocking & screening › Import & share). */
        BLOCK_LIST,
    }

    enum class Source(val importer: Importer) {
        PARLEY(Importer.PARLEY_BACKUP),
        GOOGLE(Importer.CONTACTS_FILE),
        IPHONE(Importer.CONTACTS_FILE),
        SAMSUNG(Importer.CONTACTS_FILE),
        CALL_LOG_CSV(Importer.CALL_HISTORY_CSV),
        CALL_BLOCKER(Importer.BLOCK_LIST),
        YACB(Importer.BLOCK_LIST),
        NO_PHONE_SPAM(Importer.BLOCK_LIST),
    }

    /** Sources grouped as the page lists them: a Parley backup first, then contacts, call history and block lists. */
    fun grouped(): List<Pair<Importer, List<Source>>> =
        Importer.entries.map { i -> i to Source.entries.filter { it.importer == i } }.filter { it.second.isNotEmpty() }
}
