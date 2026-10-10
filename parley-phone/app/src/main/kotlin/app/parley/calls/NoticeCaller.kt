package app.parley.calls

import app.parley.common.NotificationPrivacy
import app.parley.common.calls.LockScreenCaller
import app.parley.common.security.PrivacyView
import app.parley.data.CallerInfo
import app.parley.data.DataContainer
import app.parley.data.people.NumberOwners

/**
 * Who a notification about a call is about, found one way for every such notification (missed calls, and blocked,
 * silenced and quiet-hours calls): [NumberOwners], the one answer to "who owns this number", for a notification. A
 * contact, then a private contact, then an archived contact, then the name the network sent, which shows only for a
 * number known not to be a private contact's. A lookup that fails says nothing about who it is.
 *
 * [privateHidden] is the privacy view's (discreet mode, or a duress unlock hiding things): a private contact's name is
 * left out, and the caller reads like any number nobody saved.
 */
internal class NoticeCaller(
    /** The device contact, if any (its photo is the missed-call notification's). */
    val contact: CallerInfo?,
    /** A contact's or an archived contact's name. */
    val savedName: String?,
    /** A private contact's name, whatever [privateHidden] says ([NotificationPrivacy] leaves it out). */
    val vaultName: String?,
    /** The network's name, where it may show at all. */
    val network: String?,
    val privateHidden: Boolean,
) {
    /** The name as the missed-call notification shows it (without the number); null when nobody is known. */
    val name: String? get() = NotificationPrivacy.missedCallName(savedName, vaultName, privateHidden, null) ?: network

    /**
     * One of yours, as far as a notification may tell: a contact, an archived contact, or a private contact while they
     * show. A hidden private contact counts as nobody's, so nothing a notification offers sets them apart.
     */
    val isContact: Boolean get() = contact != null || savedName != null || (vaultName != null && !privateHidden)

    companion object {
        /**
         * "Caller on the lock screen" as call notifications read it: the privacy view's, which says "Nothing" when the
         * settings can't be read, so a notification never shows more than the rule would.
         */
        fun lockScreenRule(privacy: PrivacyView): LockScreenCaller = privacy.lockScreen

        /**
         * Finds who [number] (null: a hidden number) is; [simRegion] reads a national number as the SIM it came on.
         * [privacy]: the view now (the notifier read it once for all its callers).
         */
        suspend fun find(c: DataContainer, number: String?, simRegion: String?, privacy: PrivacyView): NoticeCaller {
            if (number == null) return NoticeCaller(null, null, null, null, privacy.privateHidden)
            val owners = c.numberOwners
            val found = owners.findIn(number, simRegion ?: owners.region(null))
            val owner = owners.ownerOf(found, NumberOwners.Use.NOTIFICATION, privacy)
            val savedName = found.contact?.name?.takeIf { it.isNotBlank() } ?: (owner as? NumberOwners.Owner.Archived)?.name
            return NoticeCaller(found.contact, savedName, found.private?.second?.name, (owner as? NumberOwners.Owner.Network)?.name, privacy.privateHidden)
        }
    }
}
