package app.parley.common.sync.shared

/**
 * One line of a shared label's history ("Ana changed Dr Lee's number · 2 days ago"), from a member's journal. Kept on
 * each phone, so it outlives a key change, which removes the old journals from the folder.
 */
data class HistoryItem(
    val memberHex: String,
    val memberName: String,
    val entryId: Long,
    val sid: String,
    val contactName: String,
    val kind: ChangeKind,
    val fields: Set<CardField>,
    val at: Long,
)

object SharedLabelHistory {
    /** How many lines a phone keeps per label. */
    const val MAX_ITEMS = 1000

    /** What a line says, without names or time (the app words it). */
    sealed interface Phrase {
        data object Added : Phrase

        data object Removed : Phrase

        /** One field changed: "changed Dr Lee's number". */
        data class Changed(val field: CardField) : Phrase

        /** Two fields: "changed Dr Lee's number and e-mail". */
        data class ChangedTwo(val first: CardField, val second: CardField) : Phrase

        /** More, or unknown: "edited Dr Lee". */
        data object Edited : Phrase
    }

    fun phrase(item: HistoryItem): Phrase = when (item.kind) {
        ChangeKind.ADDED -> Phrase.Added
        ChangeKind.REMOVED -> Phrase.Removed
        ChangeKind.EDITED -> {
            val f = item.fields.sorted()
            when (f.size) {
                1 -> Phrase.Changed(f[0])
                2 -> Phrase.ChangedTwo(f[0], f[1])
                else -> Phrase.Edited
            }
        }
    }

    /** The journal's entries as history lines of its member. */
    fun of(journal: Journal, name: String = journal.name): List<HistoryItem> = journal.entries.map { e ->
        HistoryItem(journal.memberHex, name, e.id, e.sid, e.contactName, e.kind, e.fields, e.at)
    }

    /**
     * [kept] with [incoming] added: one line per member and entry (a member's newer name replaces the old one), newest
     * first, at most [MAX_ITEMS].
     */
    fun merge(kept: List<HistoryItem>, incoming: List<HistoryItem>): List<HistoryItem> {
        val names = incoming.associate { it.memberHex to it.memberName }
        val byId = LinkedHashMap<Pair<String, Long>, HistoryItem>()
        (kept + incoming).forEach { byId[it.memberHex to it.entryId] = it }
        return byId.values.map { i -> names[i.memberHex]?.let { n -> i.copy(memberName = n) } ?: i }
            .sortedWith(compareByDescending<HistoryItem> { it.at }.thenByDescending { it.entryId })
            .take(MAX_ITEMS)
    }

    /** Only [memberHex]'s lines (null: everyone's). */
    fun filter(items: List<HistoryItem>, memberHex: String?): List<HistoryItem> = if (memberHex == null) items else items.filter { it.memberHex == memberHex }
}
