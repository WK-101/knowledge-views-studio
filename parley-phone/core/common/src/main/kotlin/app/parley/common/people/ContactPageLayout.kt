package app.parley.common.people

import app.parley.common.EventDate
import java.time.LocalDate

/** How a section of a contact's page starts: open, folded to its header, or not shown at all. */
enum class SectionMode(val code: Char) {
    OPEN('o'),
    FOLDED('f'),
    HIDDEN('h'),
    ;

    companion object {
        fun of(c: Char?): SectionMode? = entries.firstOrNull { it.code == c }
    }
}

/**
 * The sections of a contact's page, in their default order. [id] is what's stored: never rename one. The
 * details are open by default; the rarely used ones start folded.
 *
 * The default order puts the ways to reach someone first (the contact info), then the facts about them, then what
 * happened (timeline, call insights) and the per-contact settings last, like the phone's own contacts apps.
 * Neighbouring sections of one [family] are drawn as a single group under one header ([ContactPageLayout.blocks]).
 */
enum class ContactSection(val id: String, val defaultMode: SectionMode, val family: SectionFamily? = null) {
    STAY("stay", SectionMode.OPEN),
    PHONES("phones", SectionMode.OPEN, SectionFamily.CONTACT_INFO),
    EMAILS("emails", SectionMode.OPEN, SectionFamily.CONTACT_INFO),
    ADDRESSES("addresses", SectionMode.OPEN, SectionFamily.CONTACT_INFO),
    MESSENGERS("messengers", SectionMode.OPEN, SectionFamily.CONTACT_INFO),
    DATES("dates", SectionMode.OPEN, SectionFamily.ABOUT),
    ABOUT("about", SectionMode.OPEN, SectionFamily.ABOUT),
    NOTE("note", SectionMode.OPEN, SectionFamily.ABOUT),
    TIMELINE("timeline", SectionMode.OPEN),
    INSIGHTS("insights", SectionMode.FOLDED),
    OTHER("other", SectionMode.FOLDED),
    SETTINGS("settings", SectionMode.FOLDED),
    ;

    companion object {
        fun byId(id: String): ContactSection? = entries.firstOrNull { it.id == id }

        /**
         * The default order before the page was made more compact. A stored order equal to it was never chosen by
         * the user (it's written whenever any section is folded), so it's read as today's default.
         */
        internal val LEGACY_DEFAULT: List<String> =
            listOf("stay", "dates", "phones", "emails", "addresses", "messengers", "about", "other", "timeline", "insights", "note", "settings")
    }
}

/** Sections that read as one group when they sit next to each other: "Contact info" and "About <name>". */
enum class SectionFamily { CONTACT_INFO, ABOUT }

/**
 * Sections drawn together under one header and folded together. A block of one section is that section as it
 * always was; [family] is set only when several sections of one family were joined.
 */
data class PageBlock(val sections: List<ContactSection>) {
    val merged: Boolean get() = sections.size > 1
    val family: SectionFamily? get() = if (merged) sections.first().family else null

    /** A stable key for lists: the ids of its sections. */
    val key: String get() = sections.joinToString("+") { it.id }
}

/**
 * The order of a contact's page and how each section starts ([modes], from Settings › Contacts › Contact page
 * sections), plus the folds last chosen on a page ([folds], true = folded), which apply to every contact. Hiding a
 * section only stops it being drawn: its data, place and fold are kept for when it's shown again.
 *
 * [order] always holds every [ContactSection] once. Stored ids this version doesn't know (a newer version's
 * sections, restored from a backup) are kept in [unknown] so they survive being saved again.
 */
data class ContactPageLayout(
    val order: List<ContactSection> = ContactSection.entries,
    val modes: Map<ContactSection, SectionMode> = emptyMap(),
    val folds: Map<ContactSection, Boolean> = emptyMap(),
    val unknown: List<String> = emptyList(),
) {
    fun mode(s: ContactSection): SectionMode = modes[s] ?: s.defaultMode

    /** Shown sections, in order. */
    val visible: List<ContactSection> get() = order.filter { mode(it) != SectionMode.HIDDEN }

    /** Folded on the page: the last fold chosen there, else the section's start mode. */
    fun isFolded(s: ContactSection): Boolean = folds[s] ?: (mode(s) == SectionMode.FOLDED)

    /** Remembers a fold made on a page; folding back to the start mode forgets it. */
    fun withFold(s: ContactSection, folded: Boolean): ContactPageLayout =
        copy(folds = if (folded == (mode(s) == SectionMode.FOLDED)) folds - s else folds + (s to folded))

    /** A new start mode from Settings; it replaces a fold remembered for that section. */
    fun withMode(s: ContactSection, mode: SectionMode): ContactPageLayout =
        copy(modes = if (mode == s.defaultMode) modes - s else modes + (s to mode), folds = folds - s)

    /** Moves the section at [from] to [to] (indexes in [order]). */
    fun moved(from: Int, to: Int): ContactPageLayout {
        if (from !in order.indices || to !in order.indices || from == to) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    /**
     * The shown sections that have something to show ([present]), in order, joined into blocks: neighbours of one
     * [SectionFamily] that are folded alike share one header ("Contact info"), so a typical page has a few calm
     * groups instead of a header and a card per small section. A section the user moved away from its family, or
     * folded differently, stays on its own.
     */
    fun blocks(present: Set<ContactSection>): List<PageBlock> {
        val out = ArrayList<MutableList<ContactSection>>()
        for (s in visible.filter { it in present }) {
            val last = out.lastOrNull()?.last()
            if (last != null && joins(last, s)) out.last() += s
            else out += mutableListOf(s)
        }
        return out.map { PageBlock(it) }
    }

    /** Whether [next] joins the group of [prev], its neighbour: same family, folded alike. */
    private fun joins(prev: ContactSection, next: ContactSection): Boolean =
        next.family != null && next.family == prev.family && isFolded(next) == isFolded(prev)

    /** Whether [block] is folded (its sections always fold together). */
    fun isFolded(block: PageBlock): Boolean = block.sections.all { isFolded(it) }

    /** Folds or unfolds every section of [block]. */
    fun withFold(block: PageBlock, folded: Boolean): ContactPageLayout = block.sections.fold(this) { l, s -> l.withFold(s, folded) }

    /** Back to the default order and modes, forgetting remembered folds (unknown ids are kept). */
    fun reset(): ContactPageLayout = ContactPageLayout(unknown = unknown)

    val isDefault: Boolean get() = order == ContactSection.entries && modes.isEmpty() && folds.isEmpty()

    /** "stay:o,dates:o,…|timeline:1": order and start modes, then the remembered folds. */
    fun encode(): String {
        val sections = order.map { "${it.id}:${mode(it).code}" } + unknown
        val f = folds.entries.sortedBy { it.key.ordinal }.map { "${it.key.id}:${if (it.value) 1 else 0}" }
        return sections.joinToString(",") + "|" + f.joinToString(",")
    }

    companion object {
        /**
         * Reads [encode]'s form. Unknown ids are kept aside, repeated ones count once, and sections the stored order
         * lacks (new in this version) go back to their default place: right after the section that precedes them
         * by default, or first.
         */
        fun decode(raw: String?): ContactPageLayout {
            if (raw.isNullOrBlank()) return ContactPageLayout()
            val (orderPart, foldPart) = raw.split('|', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            val order = ArrayList<ContactSection>()
            val modes = HashMap<ContactSection, SectionMode>()
            val unknown = ArrayList<String>()
            orderPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
                val id = token.substringBefore(':')
                val s = ContactSection.byId(id)
                if (s == null) {
                    if (unknown.none { it.substringBefore(':') == id }) unknown += token
                } else if (s !in order) {
                    order += s
                    val m = SectionMode.of(token.substringAfter(':', "").firstOrNull())
                    if (m != null && m != s.defaultMode) modes[s] = m
                }
            }
            // An order nobody chose (the old default, saved along with a fold) follows the new default.
            if (order.map { it.id } == ContactSection.LEGACY_DEFAULT) {
                order.clear()
                order += ContactSection.entries
            }
            for (s in ContactSection.entries) {
                if (s in order) continue
                val before = ContactSection.entries.subList(0, s.ordinal).lastOrNull { it in order }
                order.add(if (before == null) 0 else order.indexOf(before) + 1, s)
            }
            val folds = HashMap<ContactSection, Boolean>()
            foldPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
                val s = ContactSection.byId(token.substringBefore(':')) ?: return@forEach
                when (token.substringAfter(':', "")) {
                    "1" -> folds[s] = true
                    "0" -> folds[s] = false
                }
            }
            return ContactPageLayout(order, modes, folds, unknown)
        }
    }
}

/** Texts for folded section headers. */
object ContactPage {
    /** The soonest of [dates] (index into the list and days until it), or null when there are none. */
    fun nextDate(dates: List<EventDate>, today: LocalDate): Pair<Int, Long>? =
        dates.withIndex().map { (i, d) -> i to d.daysUntil(today) }.minByOrNull { it.second }
}
