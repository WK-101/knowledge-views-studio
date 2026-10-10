package app.parley.common.calls

import app.parley.common.CallType
import app.parley.common.spam.CallReputation
import app.parley.common.spam.RepKind

/**
 * "This number never calls you": a call from a saved organisation (a bank, a clinic, a school) on a line you have only
 * ever called. Caller ID can be faked, and the most damaging scams fake a bank's real number because the phone then
 * shows the bank's saved name. Parley can't tell whether this call is real, so it only says what your own calls show
 * and offers Check it's really them and the scam sheet. Offline and pure: the app gathers who the number is saved as
 * and your calls with the line; this decides.
 */
object NeverCallsYou {
    /**
     * Words in a label's title that mark the people in it as organisations ("Banks", "Clinics", "Utilities"), compared
     * whole and without a plural "s". Only institutions: people keep colleagues in "Office" or "Work", other parents
     * and teachers in "School", friends who run one in "Business" or "Shop", and a colleague who has only ever been
     * called must not be flagged when they ring. A false alarm on a real call costs more than a missed one here.
     */
    private val LABEL_WORDS = setOf(
        "bank", "clinic", "hospital", "pharmacy", "pharmacie", "insurance", "insurer", "utility", "utilitie", "government",
        "council", "tax",
    )

    /**
     * Words in a contact's own name that mark an organisation saved without a company ("Barclays Bank", "Riverside
     * Clinic"), compared whole and exactly: no plural, so a surname such as "Banks" isn't read as one.
     */
    private val NAME_WORDS = setOf(
        "bank", "clinic", "hospital", "surgery", "pharmacy", "school", "college", "university", "council", "insurance",
        "police", "helpline", "fraud",
    )

    /** One contact (or private contact) the calling number is saved for. */
    data class SavedAs(
        val name: String,
        /** The contact's company (the organisation field), blank when none. */
        val company: String = "",
        /** Titles of the labels it is in. */
        val labels: Set<String> = emptySet(),
        /** The number is saved with the "Company main" type. */
        val companyLine: Boolean = false,
    )

    /**
     * Whether [saved] looks like an organisation rather than a person: saved as a company (its name is the company's,
     * as the address book names a contact with only a company), a "Company main" number, a label for institutions
     * ([LABEL_WORDS]), or a name that says so. A person with a company ("Ana · Acme") is a person: colleagues do call.
     */
    fun organisation(saved: SavedAs): Boolean {
        val company = saved.company.trim()
        if (company.isNotEmpty() && saved.name.trim().contains(company, ignoreCase = true)) return true
        if (saved.companyLine) return true
        if (saved.labels.any { title -> words(title).any { it.removeSuffix("s") in LABEL_WORDS } }) return true
        return words(saved.name).any { it in NAME_WORDS }
    }

    /** One earlier call with the line: its kind and when. */
    data class PastCall(val type: CallType, val date: Long)

    /**
     * Whether the incoming call from [number] shows the notice. [line] is its E.164 form for the call's SIM (null for a
     * short code, a service code or a number too ambiguous to match safely, which never shows it); [savedAs] every contact
     * the number is saved for (each must look like an organisation, since a person sharing the line may call); [past]
     * your earlier calls with the line, from the call log and the archive; [keptSince] the time from which Parley's own
     * copy of your calls holds every call (null when it is off or can't be read now). Never for a hidden number, an
     * emergency number or a conference.
     */
    @Suppress("LongParameterList") // One argument per fact the rule weighs.
    fun shows(
        number: String?,
        line: String?,
        savedAs: List<SavedAs>,
        past: List<PastCall>,
        keptSince: Long?,
        emergency: Boolean,
        hidden: Boolean = false,
        conference: Boolean = false,
    ): Boolean {
        if (hidden || emergency || conference) return false
        if (number.isNullOrBlank() || line == null || EmergencyPolicy.isFallbackEmergencyNumber(number)) return false
        if (savedAs.isEmpty() || !savedAs.all(::organisation)) return false
        return onlyYouCalled(past, keptSince)
    }

    /**
     * You called the line at least once and it never called you: no answered, missed, declined, blocked or voicemail
     * call from it, and no call the log can't place (which might have been one). Said only when the history reaches
     * back past your first call to it ([keptSince] earlier than it): Android's log trims itself, so without Parley's
     * own copy an older call from them may simply be gone.
     */
    fun onlyYouCalled(past: List<PastCall>, keptSince: Long?): Boolean =
        past.isNotEmpty() && past.all { CallReputation.kindOf(it.type) == RepKind.OUTGOING } && reachesBack(keptSince, past.minOf { it.date })

    /**
     * For the number's history afterwards: the first call that came from the line after you had only ever called it
     * ("First call from them to you"), or null when it called you before you called it, never did, or the history
     * doesn't reach back past your first call ([keptSince], as [onlyYouCalled]). [calls] in any order; [date] and
     * [type] read each one.
     */
    fun <T> firstFromThem(calls: List<T>, keptSince: Long?, date: (T) -> Long, type: (T) -> CallType): T? {
        var firstCall: Long? = null
        for (c in calls.sortedBy(date)) {
            when (CallReputation.kindOf(type(c))) {
                RepKind.OUTGOING -> if (firstCall == null) firstCall = date(c)
                // A call the log can't place: from here on nobody can say it was the first.
                null -> return null
                else -> return c.takeIf { firstCall != null && reachesBack(keptSince, firstCall) }
            }
        }
        return null
    }

    private fun reachesBack(keptSince: Long?, firstCall: Long): Boolean = keptSince != null && keptSince < firstCall

    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
