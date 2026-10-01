package app.parley.common.people

/**
 * Temporary contacts whose time is up, when "Ask before deleting temporary contacts" is on (the default): nothing is
 * deleted until you say so. The daily upkeep finds the ones that are due and asks once, with one notification and the
 * same choice in the app; a contact you don't answer for stays, with a gentle reminder every few days.
 *
 * Contacts are named by their Parley key here (a device contact's lookup key, [ContactRef.privateKey] for a private
 * one) so the rules don't depend on where each is stored.
 */
object TemporaryDue {
    /** The answer to "N temporary contacts are due to be deleted". */
    enum class Decision { DELETE, KEEP_LONGER, KEEP }

    /** "Keep 7 more days". */
    const val KEEP_LONGER_DAYS = 7

    /** A due contact left unanswered is mentioned again after this many days, never more often. */
    const val REMIND_AFTER_DAYS = 3

    /** Whether the upkeep deletes what expired straight away (the setting off: the behaviour before it existed). */
    fun deletesWithoutAsking(askFirst: Boolean): Boolean = !askFirst

    /** The contacts that are due at [now]: expired ones ([expiresAt] by key; null: not temporary). */
    fun due(expiresAt: Map<String, Long?>, now: Long): Set<String> =
        expiresAt.filterValues { it != null && it <= now }.keys

    /**
     * Whether to post the notification now: a contact became due that wasn't in the last one ([notified]), or the
     * ones still waiting were last mentioned at least [REMIND_AFTER_DAYS] ago ([notifiedAt]). Nothing due, nothing said.
     */
    fun shouldNotify(due: Set<String>, notified: Set<String>, notifiedAt: Long, now: Long): Boolean = when {
        due.isEmpty() -> false
        (due - notified).isNotEmpty() -> true
        else -> now - notifiedAt >= REMIND_AFTER_DAYS * TemporaryChoice.DAY_MS
    }

    /**
     * The contacts a [decision] may act on: those the question was about ([asked]) that are still due now ([due]). One
     * that became due since, or that you already kept on its page, is left for its own question.
     */
    fun targets(asked: Set<String>, due: Set<String>): Set<String> = asked intersect due

    /** The new expiry after [decision] (null: kept for good); [Decision.DELETE] has none, the contact goes. */
    fun newExpiry(decision: Decision, now: Long): Long? = when (decision) {
        Decision.KEEP_LONGER -> now + KEEP_LONGER_DAYS * TemporaryChoice.DAY_MS
        Decision.KEEP, Decision.DELETE -> null
    }

    /** What the notification remembers after a decision: the contacts it still waits on. */
    fun stillWaiting(notified: Set<String>, decided: Set<String>): Set<String> = notified - decided
}
