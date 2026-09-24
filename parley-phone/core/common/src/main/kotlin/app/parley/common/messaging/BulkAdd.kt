package app.parley.common.messaging

import app.parley.common.NumberText
import app.parley.common.PhoneNumbers

/**
 * M11 "Add several numbers…": the pure part. Numbers are found in pasted or shared text, each one is checked against
 * contacts, private contacts and the rest of the list, and named with a pattern. Nothing here touches storage.
 */
object BulkAdd {
    /** Largest list reviewed at once; longer texts are cut (and the user told). */
    const val MAX_NUMBERS = 500

    enum class Status(val label: String) {
        NEW("New"),
        CONTACT("Already a contact"),
        PRIVATE("Already a private contact"),
        DUPLICATE("Repeated in this list"),
        INVALID("Not a valid number"),
    }

    /**
     * One number of the list. [number] is what gets saved (international form when known), [raw] how it was
     * written. [checked] is the default: only new, valid numbers are ticked.
     */
    data class Candidate(val raw: String, val number: String, val e164: String?, val status: Status, val existingName: String? = null) {
        val checked: Boolean get() = status == Status.NEW
        /** Repeats can't be ticked: the first occurrence stands for them. */
        val selectable: Boolean get() = status != Status.DUPLICATE
    }

    /**
     * Reviews [found] (from [NumberText.find] with `distinct = false`). [contactName] and [privateName] return the
     * name of an existing contact or private contact with that number, or null. [countryIso] keys the numbers.
     */
    fun review(
        found: List<NumberText.Found>,
        countryIso: String?,
        contactName: (String) -> String?,
        privateName: (String) -> String?,
    ): List<Candidate> {
        val seen = HashSet<String>()
        return found.take(MAX_NUMBERS).map { f ->
            val number = f.e164 ?: PhoneNumbers.clean(f.raw)
            val key = PhoneNumbers.lineKey(number, countryIso).ifEmpty { number }
            val valid = f.e164 != null && NumberText.isValid(f.e164)
            when {
                !seen.add(key) -> Candidate(f.raw, number, f.e164, Status.DUPLICATE)
                !valid -> Candidate(f.raw, number, f.e164, Status.INVALID)
                else -> {
                    val c = contactName(number)
                    val p = if (c == null) privateName(number) else null
                    when {
                        c != null -> Candidate(f.raw, number, f.e164, Status.CONTACT, c)
                        p != null -> Candidate(f.raw, number, f.e164, Status.PRIVATE, p)
                        else -> Candidate(f.raw, number, f.e164, Status.NEW)
                    }
                }
            }
        }
    }

    /** "3 new · 1 already a contact · 1 repeated". */
    fun summary(list: List<Candidate>): String =
        Status.entries.mapNotNull { s -> list.count { it.status == s }.takeIf { it > 0 }?.let { n -> "$n ${s.label.lowercase()}" } }.joinToString(" · ")

    enum class Pattern(val label: String, val template: String) {
        NUMBERED("Prefix and a number", "{prefix} {n}"),
        WITH_NUMBER("Prefix and the phone number", "{prefix} · {number}"),
        CUSTOM("Custom", ""),
    }

    /**
     * The name of the [n]th saved number (1-based, counting only the ticked ones). [template] may use `{prefix}`,
     * `{n}` (zero-padded to the width of [total]) and `{number}` ([shownNumber], e.g. "+92 300 1234567"). A blank
     * result falls back to the number.
     */
    fun name(template: String, prefix: String, n: Int, total: Int, shownNumber: String): String {
        val width = total.coerceAtLeast(1).toString().length
        val out = template
            .replace("{prefix}", prefix.trim())
            .replace("{n}", n.toString().padStart(width, '0'))
            .replace("{number}", shownNumber)
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('·', '-', ',', ' ')
            .trim()
        return out.ifEmpty { shownNumber }
    }

    /** Template for [pattern], or [custom] for [Pattern.CUSTOM] (a custom text without placeholders gets " {n}"). */
    fun template(pattern: Pattern, custom: String): String = when (pattern) {
        Pattern.CUSTOM -> custom.trim().let { if (it.isEmpty()) Pattern.NUMBERED.template else if ('{' !in it) "$it {n}" else it }
        else -> pattern.template
    }

    /** Batch tag stored with each saved batch, so "Delete this batch" finds its contacts later. */
    fun batchTag(now: Long): String = "bulk-$now"
}
