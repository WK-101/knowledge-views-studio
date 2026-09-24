package app.parley.common

/**
 * What call notifications may say (F14). Notifications can appear on the lock screen and are readable by
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

    /** A number label that may be shown in a call notification ("Mobile", "Work"), or null. */
    fun shownLabel(label: String?): String? = label?.takeUnless { it.isBlank() || it.equals(VAULT_LABEL, ignoreCase = true) }
}
