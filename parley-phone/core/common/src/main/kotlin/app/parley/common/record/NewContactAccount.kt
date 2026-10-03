package app.parley.common.record

/**
 * Where a new contact may be written, given Android 16's default account for new contacts.
 *
 * From Android 16 (API 36) the user picks a default account for new contacts in the system settings. While that
 * default is a cloud account (Google, Samsung…), the Contacts Provider refuses to create a raw contact in the
 * phone-only or SIM account ("Cannot add contacts to local or SIM accounts when default account is set to cloud").
 * Parley used to fall back to the phone-only account everywhere, so saving, imports, restores and Make visible
 * would fail on those phones. These rules decide the account once, the same way for every insert path:
 *
 * - Before Android 16, or when the default is the phone, a SIM, not set or unreadable: nothing changes.
 * - When nobody chose the phone (no account given): follow the system default quietly.
 * - When the phone was chosen and Android refuses it: save to the system default and say so ([Decision.redirected]),
 *   rather than failing or pretending it was saved on the phone. Android gives apps no way to override the default,
 *   and the user can always move the contact later, so this is the honest choice that never loses what they typed.
 */
object NewContactAccount {
    /** First API level with a default account for new contacts. */
    const val FIRST_SDK = 36

    /** Android's `DefaultAccountAndState` states, plus [UNKNOWN] when it can't be read. */
    enum class State { NOT_SET, LOCAL, CLOUD, SIM, UNKNOWN }

    /** The system default: its [state] and, for cloud and SIM, the [account]. */
    data class SystemDefault<A>(val state: State, val account: A?)

    /** Where a new raw contact goes, and whether that differs from the account that was asked for. */
    data class Decision<A>(val account: A, val redirected: Boolean)

    /** Maps `DefaultAccountAndState.getState()` (DEFAULT_ACCOUNT_STATE_NOT_SET = 1 … SIM = 4). */
    fun state(platform: Int): State = when (platform) {
        1 -> State.NOT_SET
        2 -> State.LOCAL
        3 -> State.CLOUD
        4 -> State.SIM
        else -> State.UNKNOWN
    }

    /** The cloud account Android sends new contacts to instead of the phone, or null when the phone takes them. */
    fun <A> cloudInstead(sdk: Int, default: SystemDefault<A>?): A? =
        if (sdk >= FIRST_SDK && default?.state == State.CLOUD) default.account else null

    /**
     * The account a new raw contact is written to. [requested] is the account asked for (null: nobody chose), [device]
     * is where phone-only contacts go, and [isLocal] says whether an account is the phone's own (or a SIM).
     */
    fun <A> decide(sdk: Int, default: SystemDefault<A>?, requested: A?, device: A, isLocal: (A) -> Boolean): Decision<A> {
        val cloud = cloudInstead(sdk, default)
        val wanted = requested ?: device
        return when {
            cloud == null || !isLocal(wanted) -> Decision(wanted, redirected = false)
            requested == null -> Decision(cloud, redirected = false)
            else -> Decision(cloud, redirected = true)
        }
    }

    /**
     * Save targets as pickers offer them: while Android refuses the phone, the system default comes first and the
     * phone-only entries are left out (offering them would only fail); otherwise [targets] unchanged.
     */
    fun <A> offered(targets: List<A>, sdk: Int, default: SystemDefault<A>?, isLocal: (A) -> Boolean): List<A> {
        val cloud = cloudInstead(sdk, default) ?: return targets
        return listOf(cloud) + targets.filter { it != cloud && !isLocal(it) }
    }
}
