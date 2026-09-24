package app.parley.common.people

import app.parley.common.PhoneEntry
import app.parley.common.PhoneNumbers

/** One SIM phonebook (ADN) entry: a name and exactly one number. */
data class SimEntry(val name: String, val number: String)

data class SimFitResult(val entry: SimEntry?, val warnings: List<String>)

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
        val warnings = ArrayList<String>()
        val phone = phones.firstOrNull { it.isPrimary } ?: phones.firstOrNull { it.type == 2 } ?: phones.firstOrNull()
        if (phone == null) return SimFitResult(null, listOf("There's no phone number to copy."))
        val number = PhoneNumbers.clean(phone.number).filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (number.isEmpty()) return SimFitResult(null, listOf("The number can't be stored on a SIM."))
        if (number.length > numberMax) return SimFitResult(null, listOf("The number is longer than the SIM allows ($numberMax digits)."))
        if (phones.size > 1) warnings += "Only one number fits on a SIM: ${phones.size - 1} other number${if (phones.size > 2) "s are" else " is"} left out."
        var n = name.trim().ifEmpty { number }
        if (encodedLength(n) > nameMax) {
            while (n.isNotEmpty() && encodedLength(n) > nameMax) n = n.dropLast(1)
            n = n.trimEnd()
            warnings += "The name is shortened to “$n”."
        }
        if (otherFields > 0) warnings += "E-mails, addresses, photos and other details stay on the phone only."
        return SimFitResult(SimEntry(n, number), warnings)
    }

    private const val GSM_BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Bytes a name takes in a SIM record: 1 per GSM character, or UCS-2 (2 per char + 1) otherwise. */
    fun gsmLength(s: String): Int = if (s.all { it in GSM_BASIC }) s.length else 1 + s.length * 2
}
