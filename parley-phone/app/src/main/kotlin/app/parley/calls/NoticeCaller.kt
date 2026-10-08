package app.parley.calls

import app.parley.common.NotificationPrivacy
import app.parley.common.calls.LockScreenCaller
import app.parley.common.calls.NetworkName
import app.parley.common.catching
import app.parley.common.suspendRunCatching
import app.parley.data.CallerInfo
import app.parley.data.DataContainer

/**
 * Who a notification about a call is about, found one way for every such notification (missed calls, and blocked,
 * silenced and quiet-hours calls): a contact, then a private contact, then an archived contact, then the name the
 * network sent, which shows only for a number known not to be a private contact's. A lookup that fails says nothing
 * about who it is: no private name and no network name then.
 *
 * [hideVault] is discreet mode as it holds now (a duress unlock forces it on): a private contact's name is left out,
 * and the caller reads like any number nobody saved.
 */
internal class NoticeCaller(
    /** The device contact, if any (its photo is the missed-call notification's). */
    val contact: CallerInfo?,
    /** A contact's or an archived contact's name. */
    val savedName: String?,
    /** A private contact's name, whatever [hideVault] says ([NotificationPrivacy] leaves it out). */
    val vaultName: String?,
    /** The network's name, where it may show at all. */
    val network: String?,
    val hideVault: Boolean,
) {
    /** The name as the missed-call notification shows it (without the number); null when nobody is known. */
    val name: String? get() = NotificationPrivacy.missedCallName(savedName, vaultName, hideVault, null) ?: network

    /**
     * One of yours, as far as a notification may tell: a contact, an archived contact, or a private contact while they
     * show. A hidden private contact counts as nobody's, so nothing a notification offers sets them apart.
     */
    val isContact: Boolean get() = contact != null || savedName != null || (vaultName != null && !hideVault)

    companion object {
        /**
         * "Caller on the lock screen" as call notifications read it: when the settings can't be read, "Nothing", so a
         * notification never shows more than the rule would.
         */
        suspend fun lockScreenRule(read: suspend () -> LockScreenCaller): LockScreenCaller =
            suspendRunCatching { read() }.getOrDefault(LockScreenCaller.NONE)

        /** Finds who [number] (null: a hidden number) is; [simRegion] reads a national number as the SIM it came on. */
        suspend fun find(c: DataContainer, number: String?, simRegion: String?, hideVault: Boolean): NoticeCaller {
            val contact = number?.let { catching { c.contacts.lookup(it) }.getOrNull() }
            val vaultHit = if (contact == null && number != null) catching { c.vault.lookup(number, simRegion) } else Result.success(null)
            val vaultName = vaultHit.getOrNull()?.second?.name
            // An archived contact is named like any saved one.
            val archivedName = if (contact == null && vaultName == null && number != null) catching { c.archive.lookup(number)?.name }.getOrNull() else null
            // A number nobody saved and known not to be a private contact's (whatever discreet mode says): the name the
            // network sent, where the lock-screen rule shows callers' names in full.
            val isPrivate = if (vaultHit.isFailure) null else vaultHit.getOrNull() != null
            val remember = catching { c.settings.current().rememberNetworkNames }.getOrDefault(false)
            val network = if (number != null && NetworkName.mayShow(remember, saved = contact != null || archivedName != null, private = isPrivate)) {
                val lockScreen = lockScreenRule { c.settings.current().lockScreenCaller }
                NetworkName.inNotification(catching { c.networkNames.latest(number, simRegion)?.name }.getOrNull(), lockScreen)
            } else {
                null
            }
            return NoticeCaller(contact, contact?.name?.takeIf { it.isNotBlank() } ?: archivedName, vaultName, network, hideVault)
        }
    }
}
