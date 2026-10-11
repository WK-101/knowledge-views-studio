package app.parley.common.cases

/**
 * The booking pocket's reader: the codes in a booking email or SMS shared to Parley ("Booking reference: QX7R2B",
 * "Order no. 114-2286-90"), found on the phone, offline, for the person to tick before any goes into an
 * organisation's case file. Never offered: a payment card number (13–19 digits passing the Luhn check), a phone
 * number, a date or a time. A code named by the words before it ("Booking reference") comes ticked, labelled by
 * its [Kind]; one found by its shape alone (letters and digits mixed, like an airline's) comes unticked. English
 * words only, as the app is.
 */
object BookingCodes {
    /** At most this many codes offered from one text. */
    const val MAX = 8

    /** The longest text read (a long email's codes are near its top). */
    const val MAX_TEXT = 20_000

    /** What the words before a code call it; the app gives each its label. */
    enum class Kind { BOOKING, CONFIRMATION, RESERVATION, ORDER, TICKET, POLICY, CLAIM, CUSTOMER, MEMBERSHIP, CASE, TRACKING, REFERENCE }

    /** One code found: [kind] from the words before it (null when found by its shape alone, then it comes unticked). */
    data class Found(val kind: Kind?, val value: String) {
        val named: Boolean get() = kind != null
    }

    /**
     * The words that name a code (letters and spaces only, so they go into the pattern as they are), longest first so
     * "booking reference" wins over "booking", each with its [Kind].
     */
    private val NAMES = listOf(
        "booking reference" to Kind.BOOKING, "booking ref" to Kind.BOOKING, "booking number" to Kind.BOOKING, "booking code" to Kind.BOOKING,
        "booking id" to Kind.BOOKING, "confirmation number" to Kind.CONFIRMATION, "confirmation code" to Kind.CONFIRMATION,
        "reservation number" to Kind.RESERVATION, "reservation code" to Kind.RESERVATION, "order number" to Kind.ORDER, "order no" to Kind.ORDER,
        "order id" to Kind.ORDER, "ticket number" to Kind.TICKET, "policy number" to Kind.POLICY, "claim number" to Kind.CLAIM,
        "customer number" to Kind.CUSTOMER, "membership number" to Kind.MEMBERSHIP, "case number" to Kind.CASE,
        "tracking number" to Kind.TRACKING, "reference number" to Kind.REFERENCE, "reference" to Kind.REFERENCE, "ref" to Kind.REFERENCE,
        "pnr" to Kind.BOOKING, "booking" to Kind.BOOKING, "confirmation" to Kind.CONFIRMATION, "reservation" to Kind.RESERVATION,
        "order" to Kind.ORDER, "ticket" to Kind.TICKET,
    )

    private val NAMED = Regex(
        "(?i)\\b(" + NAMES.joinToString("|") { it.first.replace(" ", "\\s+") } + ")\\b\\.?" +
            "(?:\\s*(?:no\\.?|nr\\.?|number|code|#))?\\s*[:#.\\-–]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]{2,30}[A-Za-z0-9])",
    )

    /** A code by its shape: 5 to 10 capital letters and digits, both. */
    private val SHAPED = Regex("(?<![A-Za-z0-9\\-/])([A-Z0-9]{5,10})(?![A-Za-z0-9\\-/])")

    private val DATE = Regex("^\\d{1,4}[\\-/.]\\d{1,2}[\\-/.]\\d{1,4}$")
    private val TIME = Regex("^\\d{1,2}[:.]\\d{2}$")

    /** The codes in [text], those named first, each once; nothing secret-looking ([offered]). */
    fun find(text: String, phoneNumbers: Collection<String> = emptyList()): List<Found> {
        val t = text.take(MAX_TEXT)
        val phones = phoneNumbers.map(::digits).filter { it.length >= MIN_PHONE_DIGITS }.toSet()
        val named = NAMED.findAll(t).mapNotNull { m ->
            val key = m.groupValues[1].lowercase().replace(Regex("\\s+"), " ")
            val kind = NAMES.firstOrNull { it.first == key }?.second ?: return@mapNotNull null
            m.groupValues[2].trimEnd('.', '-', '/').takeIf { offered(it, phones) && (it.any(Char::isDigit) || lettersOnly(it)) }
                ?.let { Found(kind, it) }
        }
        val shaped = SHAPED.findAll(t).map { it.groupValues[1] }.filter { looksShaped(it) && offered(it, phones) }.map { Found(null, it) }
        return (named + shaped).distinctBy { norm(it.value) }.take(MAX).toList()
    }

    /**
     * [value] grouped to be read out on the phone: letters and digits in threes ("QX7 R2B"), four for a long run
     * ("1142 2869 0"); what was already split by - or / keeps its own groups.
     */
    fun grouped(value: String): String {
        val parts = value.split('-', '/', ' ').filter { it.isNotEmpty() }
        if (parts.size > 1) return parts.joinToString(" ")
        val size = if (value.length > LONG_RUN) 4 else 3
        return value.chunked(size).joinToString(" ")
    }

    /** Letters and digits mixed, in capitals: an airline's or a hotel's code ("QX7R2B"), not a word or a plain number. */
    private fun looksShaped(v: String): Boolean =
        v.length in SHAPE_LENGTH && v.all { it in 'A'..'Z' || it in '0'..'9' } && v.any { it in 'A'..'Z' } && v.any { it in '0'..'9' }

    /** A code of capitals alone after the words that name it ("Booking reference: QXRTZB"). */
    private fun lettersOnly(v: String): Boolean = v.length in LETTERS_LENGTH && v.all { it in 'A'..'Z' }

    /** Whether [v] may be offered at all: not a card number, a phone number in the text, a date or a time. */
    internal fun offered(v: String, phones: Set<String>): Boolean {
        val d = digits(v)
        val digitsOnly = v.all { it.isDigit() || it == '-' }
        if (digitsOnly && d.length in CARD_DIGITS && CaseFiles.looksLikeCard(d)) return false
        val phoneLike = d.length >= MIN_PHONE_DIGITS
        if (phoneLike && phones.any { it.endsWith(d) || d.endsWith(it) }) return false
        if (DATE.matches(v) || TIME.matches(v)) return false
        return CaseFiles.cleanReference(v) != null
    }

    private fun digits(s: String) = s.filter { it in '0'..'9' }

    private fun norm(s: String) = s.uppercase().filter { it.isLetterOrDigit() }

    private val CARD_DIGITS = 13..19
    private val SHAPE_LENGTH = 5..10
    private val LETTERS_LENGTH = 5..8
    private const val MIN_PHONE_DIGITS = 7
    private const val LONG_RUN = 9
}
