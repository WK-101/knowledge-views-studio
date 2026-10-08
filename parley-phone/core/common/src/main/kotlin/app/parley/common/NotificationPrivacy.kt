package app.parley.common

import app.parley.common.calls.LockScreenCaller

/**
 * What call notifications may say. Notifications can appear on the lock screen and are readable by
 * notification listeners, so private (vault) contacts need care.
 */
object NotificationPrivacy {
    /** The vault's caller-ID label. It never appears in a notification: it would reveal the caller is private. */
    const val VAULT_LABEL = "Private"

    /**
     * The name a missed-call notification shows. A private contact's name is left out in discreet mode (the number
     * is shown instead, as for any unknown caller); a regular contact wins over the vault.
     */
    fun missedCallName(contactName: String?, vaultName: String?, hideVault: Boolean, number: String?): String? =
        contactName?.takeIf { it.isNotBlank() }
            ?: vaultName?.takeIf { it.isNotBlank() && !hideVault }
            ?: number?.takeIf { it.isNotBlank() }

    /**
     * Who a notice about a screened call (blocked, silenced, likely spam, quiet hours) names. The same chain as the
     * missed-call notification: a saved name ([savedName]: a contact's or an archived contact's), then a private
     * contact's ([vaultName], never while [hideVault]: discreet mode or a duress unlock), then the name the network sent
     * ([network], where that may show at all), then the [number]. A private contact who is hidden, or whose status
     * couldn't be read (no [vaultName] then), reads exactly like a number nobody saved.
     *
     * While the phone is [locked], "Caller on the lock screen" ([lockScreen]) shortens it as it does for a ringing call:
     * a saved name to its initials or to nothing, a number to nothing only with "Nothing". Null: the notice says nothing
     * about who called.
     */
    fun screenedCallName(
        savedName: String?,
        vaultName: String?,
        hideVault: Boolean,
        network: String?,
        number: String?,
        lockScreen: LockScreenCaller,
        locked: Boolean,
    ): String? {
        val saved = missedCallName(savedName, vaultName, hideVault, null)
        if (saved != null) return if (locked && lockScreen.masks(saved = true)) lockScreen.shownName(saved) else saved
        val other = network?.takeIf { it.isNotBlank() } ?: number?.takeIf { it.isNotBlank() } ?: return null
        return if (locked && lockScreen.masks(saved = false)) null else other
    }

    /**
     * The rule or verdict a notice about a screened call gives as its reason ([reason]: "Blocked by rule 'Block
     * Arjun's new number'"), or null. Rule titles are your own words and often name people: none for a caller the
     * notice may not name ([unnamed]), and none while the phone is [locked] unless "Caller on the lock screen" shows
     * names in full ([lockScreen]).
     */
    fun screenedCallReason(reason: String?, unnamed: Boolean, lockScreen: LockScreenCaller, locked: Boolean): String? =
        reason?.takeUnless { unnamed || (locked && !lockScreen.showsName) }

    /** A number label that may be shown in a call notification ("Mobile", "Work"), or null. */
    fun shownLabel(label: String?): String? = label?.takeUnless { it.isBlank() || isVaultLabel(it) }

    /**
     * Whether [label] is the vault's marker. It's a marker, not text: the call screen shows it in the user's
     * language (and notifications never show it).
     */
    fun isVaultLabel(label: String?): Boolean = label != null && label.equals(VAULT_LABEL, ignoreCase = true)
}
