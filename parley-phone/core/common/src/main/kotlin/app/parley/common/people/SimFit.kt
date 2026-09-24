package app.parley.common.people

import app.parley.common.PhoneEntry
import app.parley.common.PhoneNumbers

/** One SIM phonebook (ADN) entry: a name and exactly one number. */
data class SimEntry(val name: String, val number: String)

/** What a SIM copy leaves out or can't do; the app words each one. */
enum class SimIssue {
    /** There's no phone number to copy. */
    NO_NUMBER,
    /** The number can't be stored on a SIM. */
    NUMBER_INVALID,
    /** The number is longer than the SIM allows ([SimWarning.count] digits). */
    NUMBER_TOO_LONG,
    /** Only one number fits: [SimWarning.count] other numbers are left out. */
    OTHER_NUMBERS_LEFT_OUT,
    /** The name is shortened to [SimWarning.text]. */
    NAME_SHORTENED,
    /** E-mails, addresses, photos and other details stay on the phone only. */
    DETAILS_STAY,
}

data class SimWarning(val issue: SimIssue, val count: Int = 0, val text: String = "")

data class SimFitResult(val entry: SimEntry?, val warnings: List<SimWarning>)

/**
 * A SIM card stores only a short name and one number per entry. This decides what gets written and says
 * plainly what is left out, before anything is copied.
 */
object SimFit {
    /** Typical ADN limits when the SIM doesn't report its own (GSM 11.11: 14 alpha bytes, 20 digits). */
    const val DEFAULT_NAME_MAX = 14
    const val DEFAULT_NUMBER_MAX = 20

    fun fit(
        name: String,
        phones: List<PhoneEntry>,
        otherFields: Int = 0,
        nameMax: Int = DEFAULT_NAME_MAX,
        numberMax: Int = DEFAULT_NUMBER_MAX,
        /** Encoded length of a name on the SIM (UCS-2 names take twice the space); defaults to GSM 7-bit. */
        encodedLength: (String) -> Int = ::gsmLength,
    ): SimFitResult {
        val warnings = ArrayList<SimWarning>()
        val phone = phones.firstOrNull { it.isPrimary } ?: phones.firstOrNull { it.type == 2 } ?: phones.firstOrNull()
        if (phone == null) return SimFitResult(null, listOf(SimWarning(SimIssue.NO_NUMBER)))
        val number = PhoneNumbers.clean(phone.number).filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isEmpty()) return SimFitResult(null, listOf(SimWarning(SimIssue.NUMBER_INVALID)))
        if (number.length > numberMax) return SimFitResult(null, listOf(SimWarning(SimIssue.NUMBER_TOO_LONG, numberMax)))
        if (phones.size > 1) warnings += SimWarning(SimIssue.OTHER_NUMBERS_LEFT_OUT, phones.size - 1)
        var n = name.trim().ifEmpty { number }
        if (encodedLength(n) > nameMax) {
            while (n.isNotEmpty() && encodedLength(n) > nameMax) n = n.dropLast(1)
            n = n.trimEnd()
            warnings += SimWarning(SimIssue.NAME_SHORTENED, text = n)
        }
        if (otherFields > 0) warnings += SimWarning(SimIssue.DETAILS_STAY)
        return SimFitResult(SimEntry(n, number), warnings)
    }

    private const val GSM_BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Bytes a name takes in a SIM record: 1 per GSM character, or UCS-2 (2 per char + 1) otherwise. */
    fun gsmLength(s: String): Int = if (s.all { it in GSM_BASIC }) s.length else 1 + s.length * 2
}
