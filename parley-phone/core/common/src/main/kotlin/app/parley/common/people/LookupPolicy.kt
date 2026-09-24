package app.parley.common.people

import app.parley.common.PhoneNumbers

/** A per-app decision for the private-name lookup provider. */
enum class LookupApproval { ALLOWED, DENIED, PENDING }

/** What the provider did with one query (shown in the access log; the number itself is never stored). */
enum class LookupOutcome(val text: String) {
    ANSWERED("Showed a private name"),
    NOT_FOUND("Asked; no private contact has that number"),
    DENIED("Blocked: you said no"),
    ASKED("Waiting for your answer"),
    OFF("Blocked: sharing private names is off"),
    REJECTED("Rejected: not a single phone number"),
    RATE_LIMITED("Blocked: too many lookups"),
}

/**
 * Rules of the protected private-name lookup ("Let apps show private names"): one exact number per query,
 * never a list or a prefix, only for apps the user approved, and at most [maxPerHour] lookups per app.
 */
object LookupPolicy {
    const val MIN_DIGITS = 6
    const val MAX_DIGITS = 17

    /** The number in a query, or null if the query isn't a single plausible phone number (no wildcards). */
    fun parseNumber(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.any { it == '%' || it == '*' || it == '_' || it == '?' || it == ',' || it == ';' }) return null
        if (s.any { it.isLetter() }) return null
        val digits = PhoneNumbers.digits(s)
        if (digits.length !in MIN_DIGITS..MAX_DIGITS) return null
        return PhoneNumbers.clean(s)
    }

    fun decide(
        enabled: Boolean,
        approval: LookupApproval?,
        numberValid: Boolean,
        recentQueries: List<Long>,
        now: Long,
        maxPerHour: Int = 60,
    ): LookupOutcome = when {
        !enabled -> LookupOutcome.OFF
        !numberValid -> LookupOutcome.REJECTED
        approval == LookupApproval.DENIED -> LookupOutcome.DENIED
        approval != LookupApproval.ALLOWED -> LookupOutcome.ASKED
        recentQueries.count { now - it < 3_600_000L } >= maxPerHour -> LookupOutcome.RATE_LIMITED
        else -> LookupOutcome.ANSWERED // or NOT_FOUND once the vault was consulted
    }
}
