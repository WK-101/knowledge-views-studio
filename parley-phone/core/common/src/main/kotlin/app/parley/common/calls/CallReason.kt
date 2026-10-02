package app.parley.common.calls

import app.parley.common.PhoneNumbers

/** How a reason can reach the person called. */
enum class ReasonWay {
    /** The network carries it with the call (`TelecomManager.EXTRA_CALL_SUBJECT`); their phone shows it while it rings. */
    SUBJECT,

    /** "Text first": the messaging app opens with "Calling you about …" for the user to send, then Parley calls. */
    TEXT_FIRST,
}

/** What decides the ways a reason can go: one call about to be placed. */
data class ReasonFacts(
    /** An emergency number: never anything between the user and the call. */
    val emergency: Boolean,
    /** The SIM this call will use, when known (remembered, a label's, or the default); null: asked when calling. */
    val simId: String?,
    /** Each SIM that can call → whether its phone account has `CAPABILITY_CALL_SUBJECT`. */
    val subjectSims: Map<String, Boolean>,
    /** A messaging app can take a prefilled text to this number. */
    val canText: Boolean,
)

/**
 * I12 the call-subject bridge, sending side: "Call with a reason…" (long-press on the keypad's Call pill or a contact's
 * Call). When the SIM's network carries call subjects, the reason goes with the call; otherwise, or as a second choice,
 * "Text first" prefills "Calling you about …" in the messaging app. Nothing is sent without the user doing it.
 */
object CallReason {
    /** Whether "Call with a reason…" is offered for [number] at all (not for service codes or an empty number). */
    fun offered(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        val n = MenuMemory.dialled(number)
        return n.isNotEmpty() && !PhoneNumbers.isServiceCode(n) && !n.startsWith("*") && !n.startsWith("#") && n.any { it.isDigit() }
    }

    /**
     * Whether the subject travels with this call: the chosen SIM supports it, or, while the SIM is still to be chosen,
     * every SIM that can call does (so the choice can't silently drop the reason).
     */
    fun subjectSupported(simId: String?, subjectSims: Map<String, Boolean>): Boolean {
        if (simId != null) subjectSims[simId]?.let { return it }
        return subjectSims.isNotEmpty() && subjectSims.values.all { it }
    }

    /** The ways offered, the best first; empty for an emergency call (it is placed straight away). */
    fun ways(f: ReasonFacts): List<ReasonWay> {
        if (f.emergency) return emptyList()
        return buildList {
            if (subjectSupported(f.simId, f.subjectSims)) add(ReasonWay.SUBJECT)
            if (f.canText) add(ReasonWay.TEXT_FIRST)
        }
    }

    /**
     * The reason made safe to send: one line, no control or direction characters ([CallSubject.clean]), and no longer
     * than the network's own limit ([maxLength], `PhoneAccount.EXTRA_CALL_SUBJECT_MAX_LENGTH`) when it gives one.
     */
    fun clean(raw: String, maxLength: Int? = null): String? {
        val text = CallSubject.clean(raw) ?: return null
        val limit = maxLength?.takeIf { it > 0 } ?: return text
        if (text.length <= limit) return text
        var end = limit
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd().ifEmpty { null }
    }
}
