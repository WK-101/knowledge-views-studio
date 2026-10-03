package app.parley.common.calls

import app.parley.common.PhoneNumbers

/**
 * "Send to another number": a ringing call goes on to another number without being answered (Telecom's
 * `Call.deflect`, where the network supports it: `Call.Details.CAPABILITY_SUPPORT_DEFLECT`). Never an emergency call, a
 * call during the emergency call-back window ([EmergencyPolicy.Safeguard.HAND_OFF]) or a conference, and never to an
 * emergency number: those stay with the phone.
 *
 * Transferring a connected call (`Call.transfer`, `Call.consultativeTransfer`) is hidden from apps in the public SDK
 * (system API only), so Parley doesn't offer it.
 */
object CallHandOff {
    data class Facts(
        val ringing: Boolean,
        /** An emergency call, or any call while the emergency call-back window runs ([EmergencyPolicy.bypasses]). */
        val emergency: Boolean,
        val conference: Boolean,
        val canDeflect: Boolean,
    )

    /** "Send to another number" on a ringing call. */
    fun deflectOffered(f: Facts): Boolean = f.ringing && f.canDeflect && !f.emergency && !f.conference

    /**
     * The number typed or picked, ready to send the call to: dial characters only (digits, a leading +, * and #; spaces,
     * punctuation and the pause and wait characters dropped), at least [MIN_DIGITS] digits; null when it isn't a number
     * to send a call to.
     */
    fun target(input: String?): String? {
        val raw = input?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val cleaned = buildString {
            raw.forEachIndexed { i, c ->
                when {
                    c.isDigit() -> append(PhoneNumbers.digits(c.toString()))
                    c == '+' && i == 0 -> append(c)
                    c == '*' || c == '#' -> append(c)
                }
            }
        }
        return cleaned.takeIf { s -> s.count { it.isDigit() } >= MIN_DIGITS }
    }

    /**
     * The saved numbers matching [query] for the list to pick from: by any word of the name starting with it, or by the
     * digits (at least two) anywhere in the number. Each number once, at most [limit]; all of them, sorted by name,
     * for an empty query.
     */
    fun matches(query: String, saved: List<VerifyCallBack.Saved>, limit: Int = LIST_LIMIT): List<VerifyCallBack.Saved> {
        val q = query.trim().lowercase()
        val digits = PhoneNumbers.digits(q).takeIf { it.length >= 2 && q.none(Char::isLetter) }
        val seen = HashSet<String>()
        return saved.asSequence()
            .filter { s -> PhoneNumbers.digits(s.number).isNotEmpty() && seen.add(s.name.lowercase() + "|" + PhoneNumbers.digits(s.number)) }
            .filter { s ->
                when {
                    q.isEmpty() -> true
                    digits != null -> PhoneNumbers.digits(s.number).contains(digits)
                    else -> s.name.lowercase().let { n -> n.startsWith(q) || n.split(' ', '-', '·').any { it.startsWith(q) } }
                }
            }
            .sortedBy { it.name.lowercase() }
            .take(limit)
            .toList()
    }

    const val MIN_DIGITS = 3
    const val LIST_LIMIT = 30
}
