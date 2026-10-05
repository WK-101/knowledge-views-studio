package app.parley.common.circle

/**
 * The agenda: things to talk about with someone the next time you speak. It is not a store of its own. An agenda item
 * is a promise you owe yourself to raise: a promise line ("[ ] …", see [Promises]) in the person's note for calls, the
 * note that is about calls with them. A number that isn't saved has no such note, so its agenda is the open promises
 * of the notes written on that number. Ticking an item off is ticking that promise, so backups, Recall, case files and
 * the duress hiding treat items as the notes they live in.
 */
object Agenda {
    /** The longest item kept (a thing to raise, not a letter). */
    const val MAX_LENGTH = 200

    /** Items the call screen shows before "Show all". */
    const val COMPACT = 2

    private val leadingBox = Regex("""^\s*(?:[-*]\s+)?\[[ xX]?]\s*""")

    /** [text] as one item: one line, without a box of its own, at most [MAX_LENGTH]; null when nothing is left. */
    fun clean(text: String?): String? {
        val line = text.orEmpty().replace(Regex("\\s+"), " ").trim().replace(leadingBox, "").trim()
        return line.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    /** The open items of [note], in the order they were written. */
    fun open(note: String?): List<String> = Promises.open(note).map { it.text }

    /**
     * [note] with [text] added as an open item at its end. Unchanged when [text] is empty or already an open item
     * (whatever its case), so adding the same thing twice keeps one.
     */
    fun add(note: String?, text: String?): String? {
        val item = clean(text) ?: return note
        if (Promises.open(note).any { it.text.equals(item, ignoreCase = true) }) return note
        val base = note.orEmpty().trimEnd()
        return if (base.isEmpty()) Promises.OPEN + item else base + "\n" + Promises.OPEN + item
    }

    /**
     * [note] with the first item reading [text] ticked off ([done]) or open again; unchanged when no item reads [text]
     * in the other state. Items are found by their text, as the screens that tick them show only the text.
     */
    fun setDone(note: String, text: String, done: Boolean): String {
        val item = Promises.parse(note).firstOrNull { it.text == text && it.done != done } ?: return note
        return Promises.setDone(note, item.line, done)
    }

    /**
     * The note for calls without its items (open or ticked): what the call screen pins under the name, and what a
     * private contact's caller-ID copy keeps, so the items follow their own privacy rules. Null when nothing is left.
     */
    fun withoutItems(note: String?): String? {
        if (note.isNullOrBlank()) return null
        val kept = note.lines().filterNot(Promises::isBoxLine)
        // Blank lines the items left behind go; one between paragraphs stays.
        val out = kept.fold(mutableListOf<String>()) { acc, line ->
            if (line.isBlank() && (acc.isEmpty() || acc.last().isBlank())) acc else acc.apply { add(line) }
        }
        return out.joinToString("\n").trim().ifEmpty { null }
    }

    /** How much of the agenda the call screen may show. */
    enum class Shown {
        /** Nothing, not even that there is an agenda. */
        NOTHING,

        /** Only how many items there are ("2 things to talk about"): the phone is locked. */
        COUNT,

        /** The items themselves. */
        ITEMS,
    }

    /**
     * What the call screen shows of [count] open items. Unlocked: the items. On the lock screen: the items only when the
     * user lets notes show there ([textOnLockScreen]), else only how many; nothing for a call shown masked (initials,
     * or "Incoming call" in place of the name) or for a private contact, whose items show only once the phone and
     * private contacts are unlocked (they are read only then).
     */
    fun shown(count: Int, locked: Boolean, textOnLockScreen: Boolean, masked: Boolean, privateContact: Boolean): Shown = when {
        count <= 0 -> Shown.NOTHING
        !locked -> Shown.ITEMS
        masked || privateContact -> Shown.NOTHING
        textOnLockScreen -> Shown.ITEMS
        else -> Shown.COUNT
    }

    /** The items the compact card lists: the first [COMPACT], or all once expanded. */
    fun visible(items: List<String>, expanded: Boolean): List<String> = if (expanded) items else items.take(COMPACT)

    /** How many items "Show all" would add (0: none are left out). */
    fun moreThanShown(items: List<String>, expanded: Boolean): Int = if (expanded) 0 else (items.size - COMPACT).coerceAtLeast(0)

    /**
     * "Did you cover these?" after a call: the items that were open when the call began ([atStart]) and weren't ticked
     * off during it ([ticked]), still open now ([openNow]). Only after a call that connected; empty otherwise.
     */
    fun toAskAfter(atStart: List<String>, ticked: Set<String>, openNow: List<String>, connected: Boolean): List<String> {
        if (!connected) return emptyList()
        val open = openNow.toSet()
        return atStart.filter { it !in ticked && it in open }.distinct()
    }
}
