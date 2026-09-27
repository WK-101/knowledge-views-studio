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
 */
enum class ContactSection(val id: String, val defaultMode: SectionMode) {
    STAY("stay", SectionMode.OPEN),
    DATES("dates", SectionMode.OPEN),
    PHONES("phones", SectionMode.OPEN),
    EMAILS("emails", SectionMode.OPEN),
    ADDRESSES("addresses", SectionMode.OPEN),
    MESSENGERS("messengers", SectionMode.OPEN),
    ABOUT("about", SectionMode.OPEN),
    OTHER("other", SectionMode.FOLDED),
    TIMELINE("timeline", SectionMode.OPEN),
    INSIGHTS("insights", SectionMode.FOLDED),
    NOTE("note", SectionMode.OPEN),
    SETTINGS("settings", SectionMode.FOLDED),
    ;

    companion object {
        fun byId(id: String): ContactSection? = entries.firstOrNull { it.id == id }
    }
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
