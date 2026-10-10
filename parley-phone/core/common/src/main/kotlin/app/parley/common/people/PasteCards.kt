package app.parley.common.people

import app.parley.common.people.PasteParser.Card
import app.parley.common.people.PasteParser.Field
import app.parley.common.people.PasteParser.Kind
import app.parley.common.people.PasteParser.Label
import app.parley.common.people.PasteParser.LineKind
import app.parley.common.people.PasteParser.Name
import java.util.Locale

/**
 * Puts one person's pieces together: which name line is the person's (the one their email address names, the first
 * full name), which is the organisation, the title, and the types of the phones, emails and addresses (work when the
 * text names an organisation or a title). What isn't placed becomes the note.
 */
internal object PasteCards {
    private const val MAX_TITLES = 2
    private const val NOTE_SUGGESTED = 200
    private const val MIN_TOKEN = 3
    private const val MIN_KEY = 3

    private class Names(val lines: List<Piece>, val locals: List<String>, val roleDomains: Set<String>, val domains: Set<String>)

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One step per field of the card, in order.
    fun build(pieces: List<Piece>): Card {
        val values = pieces.mapNotNull { it.field }
        val emails = values.filter { it.kind == Kind.EMAIL }.distinctBy { it.value.lowercase(Locale.ROOT) }
        val websites = values.filter { it.kind == Kind.WEBSITE }.distinctBy { siteKey(it.value) }
        val lines = pieces.filter { it.role != Role.VALUE }
        val roleDomains = emails.filter { it.value.substringBefore('@').lowercase(Locale.ROOT) in PasteWords.ROLE_MAILBOXES }
            .map { PasteWords.domainKey(it.value.substringAfter('@')) }.filter { it.length >= MIN_KEY }.toSet()
        val domains = (emails.map { it.value.substringAfter('@') } + websites.mapNotNull { host(it.value) })
            .map(PasteWords::domainKey).filter { it.length >= MIN_KEY }.toSet()
        val ctx = Names(lines, emails.map { it.value.substringBefore('@').lowercase(Locale.ROOT) }, roleDomains, domains)

        val used = HashSet<Piece>()
        // The name: one the text labelled, else the best scored name line.
        var name: Piece? = lines.firstOrNull { it.role == Role.NAME && it.labeled }
        var orgFromName: Piece? = null
        if (name == null) {
            val candidates = lines.withIndex().filter { it.value.role == Role.NAME && it.value.name != null }
            val best = candidates.maxByOrNull { score(it.value, it.index, ctx) }
            if (best != null) {
                val s = score(best.value, best.index, ctx)
                val n = best.value.name!!
                when {
                    n.full && s > 0 -> name = best.value
                    !n.full && (s >= SINGLE_WITH_EMAIL || lone(best.index, candidates.size, lines.size, n.given)) -> name = best.value
                    s < 0 -> orgFromName = best.value
                }
            }
        }
        name?.let { used += it }

        // The organisation: labelled, a company line, a name line its domain names, or the line after the title.
        var org: Piece? = lines.firstOrNull { it.role == Role.ORG && it.labeled } ?: lines.firstOrNull { it.role == Role.ORG } ?: orgFromName
        if (org == null) {
            org = lines.firstOrNull {
                (it.role == Role.NAME || it.role == Role.OTHER) && it !== name && it.text.split(' ').size <= MAX_ORG_WORDS && domainMatch(it.text, ctx.domains)
            }
        }
        if (org == null) {
            val t = lines.indexOfFirst { it.role == Role.TITLE }
            org = lines.getOrNull(t + 1)?.takeIf { t >= 0 && it.role == Role.NAME && it !== name }
        }
        org?.let { used += it }

        val titles = (lines.filter { it.role == Role.TITLE && it.labeled }.ifEmpty { lines.filter { it.role == Role.TITLE } }).take(MAX_TITLES)
        used += titles
        // An organisation, a title, or an office or fax number: the card is someone's work details.
        val work = org != null || titles.isNotEmpty() || values.any { it.kind == Kind.PHONE && it.label in WORK_PHONES }

        val out = ArrayList<Field>()
        val personName = name?.let { it.name ?: Name(given = it.text) }
            ?: if (org == null) emails.firstNotNullOfOrNull { PasteNames.fromEmail(it.value) } else null
        personName?.let { out += Field(Kind.NAME, it.display, name = it) }
        org?.let { out += Field(Kind.ORGANISATION, it.text.trim()) }
        if (titles.isNotEmpty()) out += Field(Kind.JOB_TITLE, titles.joinToString(", ") { it.text.trim() })
        values.filter { it.kind == Kind.PHONE }.distinctBy { it.value }.forEach { out += it.copy(label = phoneLabel(it, work)) }
        emails.forEach { out += it.copy(label = if (PasteWords.isFreeMail(it.value)) Label.HOME else Label.WORK) }
        val addressLabel = if (work) Label.WORK else Label.HOME
        values.filter { it.kind == Kind.ADDRESS }.distinctBy { it.value.lowercase(Locale.ROOT) }.forEach { out += it.copy(label = addressLabel) }
        values.filter { it.kind == Kind.MAP_LINK }.distinctBy { it.value }.forEach { out += it.copy(label = addressLabel) }
        websites.forEach { out += it.copy(label = if (work) Label.WORK else Label.NONE) }
        values.filter { it.kind == Kind.PROFILE }.distinctBy { it.value }.forEach { out += it }
        values.firstOrNull { it.kind == Kind.BIRTHDAY }?.let { out += it }

        // What is left, one line of the note per line of the text.
        val left = lines.filter { it !in used && it.role != Role.ADDRESS }.groupBy { it.line }.values
            .map { same -> same.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(", ") }.filter { it.isNotEmpty() }.distinct()
        if (left.isNotEmpty()) {
            val note = left.joinToString("\n")
            out += Field(Kind.NOTE, note, suggested = note.length <= NOTE_SUGGESTED)
        }
        return Card(out)
    }

    private const val SINGLE_WITH_EMAIL = 3
    private const val MAX_ORG_WORDS = 5
    private val WORK_PHONES = setOf(Label.WORK, Label.FAX, Label.MAIN)

    /** A lone one-word name first in the text: the person's when something else follows, or in a script without capitals. */
    private fun lone(index: Int, candidates: Int, lines: Int, given: String): Boolean = index == 0 && candidates == 1 && (lines > 1 || caseless(given))

    /** A name in a script without capitals (山田太郎, محمد), often written as one word. */
    private fun caseless(s: String): Boolean = s.length >= 2 && s.none { it.isUpperCase() || it.isLowerCase() }

    /** How likely a name line is the person's: a full name, named by an email address, first in the text; not the domain's name. */
    private fun score(p: Piece, index: Int, ctx: Names): Int {
        val n = p.name ?: return Int.MIN_VALUE
        var s = if (n.full) 2 else 0
        val tokens = listOf(n.given, n.middle, n.family).flatMap { it.split(' ') }
            .map { PasteWords.fold(it).filter(Char::isLetter) }.filter { it.length >= MIN_TOKEN }
        val named = tokens.any { t -> ctx.locals.any { it.contains(t) } }
        if (named) s += 3
        if (index == 0) s += 1
        if (!named) {
            if (domainMatch(p.text, ctx.roleDomains)) s -= 4 else if (domainMatch(p.text, ctx.domains)) s -= 2
        }
        return s
    }

    private fun domainMatch(text: String, keys: Set<String>): Boolean {
        val l = PasteWords.fold(text).filter { it.isLetterOrDigit() }
        if (l.length < MIN_KEY) return false
        return keys.any { k -> k == l || (l.length >= 4 && k.contains(l)) || (k.length >= 4 && l.startsWith(k)) }
    }

    /** A phone's type: what the text said, else what the number is (a landline in a signature is the office). */
    private fun phoneLabel(f: Field, work: Boolean): Label = when {
        f.label != Label.NONE -> f.label
        f.line == LineKind.MOBILE -> Label.MOBILE
        f.line == LineKind.TOLL_FREE -> Label.MAIN
        f.line == LineKind.FIXED -> if (work) Label.WORK else Label.HOME
        else -> if (work) Label.WORK else Label.MOBILE
    }

    private fun host(url: String): String? =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').takeIf { it.contains('.') }

    private fun siteKey(url: String): String = url.lowercase(Locale.ROOT).substringAfter("://").removePrefix("www.").trimEnd('/')
}
