package app.parley.common.vcard

import app.parley.common.PhoneIdentity
import app.parley.common.record.Col
import app.parley.common.record.ContactRecord
import app.parley.common.record.Mime
import ezvcard.VCard
import ezvcard.property.Note
import ezvcard.property.RawProperty
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * What Parley keeps about a person beside their card (the note for calls, call notes, logged moments, the keep-in-touch
 * rhythm, promises, and whether the contact is private), as an open export carries it: in vCard properties of Parley's
 * own, which other apps keep or ignore, and, for people reading the card in another app, in one readable NOTE.
 *
 * Properties (values are vCard text; times are RFC 3339 instants in UTC in the `X-WHEN` parameter):
 * - `X-PARLEY-PRIVATE:1`: a private contact; Parley imports it as private again.
 * - `X-PARLEY-NOTE-FOR-CALLS:<text>`: the note shown when they call.
 * - `X-PARLEY-CONTEXT:<text>`: a private contact's "who is this" line.
 * - `X-PARLEY-KEEP-IN-TOUCH:<days>`: in the Circle, every so many days.
 * - `X-PARLEY-CALL-NOTE;X-WHEN=…;X-LINE=<line key>:<text>`: a note written on one of their calls.
 * - `X-PARLEY-MOMENT;X-WHEN=…;X-KIND=meet|message|video|other:<note>`: a moment logged in the Circle.
 * - `X-PARLEY-PROMISE:<text>`: an open promise, read from the notes (not imported: the notes bring it back).
 * - `NOTE;X-PARLEY-SUMMARY=1:<text>`: all of the above in words, for other apps; Parley drops it on import.
 *
 * An import keeps them only as far as [forImport] allows.
 */
data class CardNotes(
    val private: Boolean = false,
    val forCalls: String = "",
    val context: String = "",
    val keepInTouchDays: Int? = null,
    val callNotes: List<CallNote> = emptyList(),
    val moments: List<Moment> = emptyList(),
    val promises: List<String> = emptyList(),
) {
    /** A note on a call: [line] is the number's line key ("+4420…", call notes are kept by line), [time] the call's. */
    data class CallNote(val line: String, val time: Long, val text: String)

    /** A logged moment: [kind] is meet, message, video or other. */
    data class Moment(val kind: String, val time: Long, val note: String?)

    /** Nothing beyond the card (a private contact still says so). */
    val isEmpty: Boolean
        get() = !private && forCalls.isBlank() && context.isBlank() && keepInTouchDays == null && callNotes.isEmpty() && moments.isEmpty() &&
            promises.isEmpty()

    /**
     * What an import of [record] keeps of these notes. Only a file locked with a passphrase ([fromSealed], an encrypted
     * vCard) is known to come from Parley: any other card may come from anyone (a card sent in a messenger), so it keeps
     * no more than whether it is private, which only ever keeps it out of the address book. Even then, a call note stays
     * only on one of the card's own numbers: a note never lands on a stranger's calls. [region] reads numbers written
     * without a country code.
     */
    fun forImport(record: ContactRecord, fromSealed: Boolean, region: String?): CardNotes {
        if (!fromSealed) return CardNotes(private = private)
        val own = record.raws.flatMap { it.rows }.filter { it.mimeType == Mime.PHONE }.mapNotNull { it[Col.D1] }
            .flatMap { PhoneIdentity.lookupKeys(it, region) }.toSet()
        return copy(callNotes = callNotes.filter { it.line in own })
    }

    /** The worded parts of the readable summary, in the app's language. */
    class Words(
        val heading: String,
        val forCalls: String,
        val context: String,
        val keepInTouch: (Int) -> String,
        val callNote: String,
        val moment: (String) -> String,
        val promises: String,
        /** A time as people read it ("4 Oct 2026"). */
        val date: (Long) -> String,
    )

    /** The readable lines: one per note, newest first; empty when there is nothing to say. */
    fun summary(words: Words): String = buildList {
        if (forCalls.isNotBlank()) add("${words.forCalls}: ${forCalls.trim()}")
        if (context.isNotBlank()) add("${words.context}: ${context.trim()}")
        keepInTouchDays?.let { add(words.keepInTouch(it)) }
        if (promises.isNotEmpty()) add(words.promises + ": " + promises.joinToString("; ") { it.trim() })
        val dated = callNotes.map { Triple(it.time, words.callNote, it.text) } + moments.map { Triple(it.time, words.moment(it.kind), it.note.orEmpty()) }
        dated.sortedByDescending { it.first }.forEach { (t, what, text) ->
            add("${words.date(t)} · $what" + if (text.isNotBlank()) ": ${text.trim()}" else "") // l10n-ok (separator)
        }
    }.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = words.heading + "\n").orEmpty()

    companion object {
        const val X_PRIVATE = "X-PARLEY-PRIVATE"
        const val X_FOR_CALLS = "X-PARLEY-NOTE-FOR-CALLS"
        const val X_CONTEXT = "X-PARLEY-CONTEXT"
        const val X_KEEP_IN_TOUCH = "X-PARLEY-KEEP-IN-TOUCH"
        const val X_CALL_NOTE = "X-PARLEY-CALL-NOTE"
        const val X_MOMENT = "X-PARLEY-MOMENT"
        const val X_PROMISE = "X-PARLEY-PROMISE"
        const val P_WHEN = "X-WHEN"
        const val P_LINE = "X-LINE"
        const val P_KIND = "X-KIND"
        const val P_SUMMARY = "X-PARLEY-SUMMARY"

        /** Moment kinds as written ([Moment.kind]). */
        val KINDS = setOf("meet", "message", "video", "other")

        private val NAMES = setOf(X_PRIVATE, X_FOR_CALLS, X_CONTEXT, X_KEEP_IN_TOUCH, X_CALL_NOTE, X_MOMENT, X_PROMISE)

        /** A file's notes are bounded like its cards: a crafted card can't make thousands of notes. */
        private const val MAX_DATED = 5_000
        private const val MAX_DAYS = 3_650

        /** Adds [notes] to [card], with [summary] (from [CardNotes.summary]) as a readable NOTE when it isn't empty. */
        fun write(card: VCard, notes: CardNotes, summary: String? = null) {
            fun raw(name: String, value: String) = RawProperty(name, VCardMapper.escapeRaw(value)).also { card.addProperty(it) }
            if (notes.private) raw(X_PRIVATE, "1")
            if (notes.forCalls.isNotBlank()) raw(X_FOR_CALLS, notes.forCalls)
            if (notes.context.isNotBlank()) raw(X_CONTEXT, notes.context)
            notes.keepInTouchDays?.let { raw(X_KEEP_IN_TOUCH, it.toString()) }
            notes.promises.forEach { raw(X_PROMISE, it) }
            notes.callNotes.forEach { n ->
                raw(X_CALL_NOTE, n.text).apply { setParameter(P_WHEN, instant(n.time)); setParameter(P_LINE, n.line) }
            }
            notes.moments.forEach { m ->
                raw(X_MOMENT, m.note.orEmpty()).apply { setParameter(P_WHEN, instant(m.time)); setParameter(P_KIND, m.kind) }
            }
            if (!summary.isNullOrBlank()) card.addProperty(Note(summary).apply { setParameter(P_SUMMARY, "1") })
        }

        /**
         * Takes Parley's properties (and the readable summary NOTE) out of [card], so the card maps to a contact like any
         * other, and returns them; null when the card has none.
         */
        fun take(card: VCard): CardNotes? {
            val mine = card.extendedProperties.filter { it.propertyName.uppercase() in NAMES }
            card.notes.filter { it.getParameter(P_SUMMARY) != null }.forEach { card.removeProperty(it) }
            if (mine.isEmpty()) return null
            mine.forEach { card.removeProperty(it) }
            fun text(p: RawProperty) = VCardMapper.unescapeRaw(p.value.orEmpty())
            fun named(n: String) = mine.filter { it.propertyName.equals(n, ignoreCase = true) }
            fun first(n: String) = named(n).firstOrNull()?.let(::text).orEmpty()
            val callNotes = named(X_CALL_NOTE).take(MAX_DATED).mapNotNull { p ->
                val t = time(p.getParameter(P_WHEN)) ?: return@mapNotNull null
                // A line key: "+<E.164 digits>", or "~<digits>" for a number with no E.164 form.
                val line = p.getParameter(P_LINE).orEmpty().filter { it.isDigit() || it == '+' || it == '~' }
                text(p).takeIf { it.isNotBlank() && line.isNotEmpty() }?.let { CallNote(line, t, it) }
            }
            val moments = named(X_MOMENT).take(MAX_DATED).mapNotNull { p ->
                val t = time(p.getParameter(P_WHEN)) ?: return@mapNotNull null
                val kind = p.getParameter(P_KIND)?.lowercase()?.takeIf { it in KINDS } ?: "other"
                Moment(kind, t, text(p).ifBlank { null })
            }
            return CardNotes(
                private = first(X_PRIVATE).trim() == "1",
                forCalls = first(X_FOR_CALLS),
                context = first(X_CONTEXT),
                keepInTouchDays = first(X_KEEP_IN_TOUCH).trim().toIntOrNull()?.takeIf { it in 1..MAX_DAYS },
                callNotes = callNotes,
                moments = moments,
                promises = named(X_PROMISE).map(::text).filter { it.isNotBlank() },
            )
        }

        private fun instant(t: Long): String = Instant.ofEpochMilli(t).toString()

        private fun time(s: String?): Long? = try {
            s?.let { Instant.parse(it.trim()).toEpochMilli() }
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
