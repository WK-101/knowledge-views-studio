package app.parley.data.people

import android.provider.ContactsContract.Contacts
import app.parley.common.NotificationPrivacy
import app.parley.common.calls.NetworkName
import app.parley.common.catching
import app.parley.common.people.ArchivedCard
import app.parley.common.security.PrivacyView
import app.parley.data.CallerInfo
import app.parley.data.DataContainer
import app.parley.data.PhoneEnv
import app.parley.data.changes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach

/**
 * The one answer to "who owns this number?": a contact, then a private contact, then an archived contact, then the
 * name the network sent (only while "Remember names from the network" is on, and only for a number known not to be a
 * private contact's), then nobody. Every feature that names a number asks here, so a new kind of owner reaches all of
 * them at once, the region is always the SIM's, and the privacy rule ([PrivacyView]) is applied the same way: a hidden
 * private contact reads exactly like a number nobody saved.
 *
 * One ringing call asks from many places (screening, the call screen, "never calls you", the agenda, number memory…):
 * what was found is kept in memory for [MEMO_MS] per number and SIM region, and found once when several ask at the
 * same moment. The private-contact lookup costs Keystore operations; this is why it is done once per ring. The memory is
 * dropped whenever contacts, private contacts or archived contacts change. Nothing is written anywhere.
 */
class NumberOwners internal constructor(
    private val scope: CoroutineScope,
    private val sources: Sources,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(c: DataContainer) : this(c.scope, Sources.of(c)) {
        // Any change to who is saved drops what was found (the first value of each is the current state, skipped).
        merge(
            c.appContext.contentResolver.changes(Contacts.CONTENT_URI).drop(1),
            // Private contacts saved, changed or deleted: the rows only, nothing opened.
            c.db.vaultDao().callerRows().map { }.drop(1),
            c.archive.cards.drop(1).map { },
        ).onEach { forget() }.launchIn(c.scope)
    }

    /** Where a name will be seen: it decides how the privacy rule and "Caller on the lock screen" apply. */
    enum class Use {
        /** Parley's own screens (unlocked). */
        SCREEN,

        /** A notification, which may also show on the lock screen. */
        NOTIFICATION,

        /** Something shown over the lock screen now. */
        LOCK_SCREEN,

        /** The call path while it rings (screening, the call screen): memory only, nothing kept. */
        CALL_PATH,
    }

    /** Who owns a number, once the privacy rule is applied. */
    sealed interface Owner {
        /** The name to show, before "Caller on the lock screen" shortens it; null for nobody. */
        val name: String?

        /** One of yours: a contact, a private contact who may show, or an archived contact. */
        val saved: Boolean get() = false

        data class Contact(val info: CallerInfo) : Owner {
            override val name: String? get() = info.name.takeIf { it.isNotBlank() }
            override val saved: Boolean get() = true
        }

        data class Private(val id: Long, val info: CallerInfo) : Owner {
            override val name: String? get() = info.name.takeIf { it.isNotBlank() }
            override val saved: Boolean get() = true
        }

        data class Archived(val card: ArchivedCard) : Owner {
            override val name: String? get() = card.name.takeIf { it.isNotBlank() }
            override val saved: Boolean get() = true
        }

        /** Nobody saved it; this is what the network sent with a call from it. */
        data class Network(override val name: String) : Owner

        /** Nobody saved it (or a private contact who is hidden now). */
        data object Unknown : Owner {
            override val name: String? get() = null
        }
    }

    /**
     * What was found for a number, before the privacy rule (the network's name isn't kept here: [ownerOf] reads it). A
     * lookup that failed is null with its flag set: callers that must fail open (screening) or closed (notifications)
     * can tell "nobody" from "couldn't tell".
     */
    data class Found(
        /** The number asked about, and the region it was read with (the call's SIM's). */
        val number: String,
        val region: String,
        val contact: CallerInfo?,
        val contactFailed: Boolean,
        val private: Pair<Long, CallerInfo>?,
        val privateFailed: Boolean,
        val archived: ArchivedCard?,
        val archivedFailed: Boolean,
    ) {
        /** Known not to be a private contact's: the private lookup worked and found none. */
        val notPrivate: Boolean get() = private == null && !privateFailed

        /** Saved by you in any way: a contact, a private contact (hidden or not) or an archived contact. */
        val saved: Boolean get() = contact != null || private != null || archived != null

        /** Some lookup failed: "nobody saved it" can't be said for sure. */
        val unsure: Boolean get() = contactFailed || privateFailed || archivedFailed

        /**
         * [saved] as the call path must read it: true or false when that is known, null when nobody was found but a
         * lookup failed. A caller that silences or warns about unknown numbers treats null as saved (it fails open).
         */
        val savedOrUnknown: Boolean? get() = if (saved) true else if (unsure) null else false
    }

    /** The lookups behind [Found] (tests give their own). */
    class Sources(
        val contact: suspend (String) -> CallerInfo?,
        val private: suspend (String, String) -> Pair<Long, CallerInfo>?,
        val archived: suspend (String, String) -> ArchivedCard?,
        val network: suspend (String, String) -> String?,
        val region: (String?) -> String,
        val rememberNetworkNames: suspend () -> Boolean,
        val privacy: suspend () -> PrivacyView,
    ) {
        companion object {
            fun of(c: DataContainer) = Sources(
                contact = { n -> c.contacts.lookup(n) },
                private = { n, r -> c.vault.lookup(n, r) },
                archived = { n, r -> c.archive.lookup(n, r) },
                network = { n, r -> c.networkNames.latest(n, r)?.name },
                region = { accountId -> PhoneEnv.countryIso(c.appContext, accountId) },
                rememberNetworkNames = { c.settings.current().rememberNetworkNames },
                privacy = { c.privacy.now() },
            )
        }
    }

    /**
     * One kind of lookup, kept for [MEMO_MS] per number and region and done once when several ask at the same moment.
     * A lookup that failed (the Keystore busy just after a cold start) isn't kept: the next one asks again.
     */
    private inner class Memo<T>(private val load: suspend (String, String) -> T) {
        private inner class Kept(val at: Long, val answer: Deferred<Result<T>>)

        private val kept = HashMap<String, Kept>()

        fun clear() = synchronized(kept) { kept.clear() }

        /** [reuse]: an answer found less than [MEMO_MS] ago will do (a ring); otherwise it is looked up again. */
        suspend fun get(number: String, region: String, reuse: Boolean = true): Result<T> {
            val key = "$region|$number"
            val pending = synchronized(kept) {
                val now = clock()
                kept[key]?.takeIf { reuse && now - it.at < MEMO_MS && !it.answer.isCancelled }?.answer
                    ?: scope.async(Dispatchers.IO) { catching { load(number, region) } }.also { d ->
                        if (kept.size >= MAX_KEPT) kept.entries.removeIf { now - it.value.at >= MEMO_MS }
                        if (kept.size >= MAX_KEPT) kept.clear()
                        kept[key] = Kept(now, d)
                    }
            }
            val answer = pending.await()
            if (answer.isFailure) synchronized(kept) { if (kept[key]?.answer === pending) kept.remove(key) }
            return answer
        }
    }

    private val contacts = Memo { n, _ -> sources.contact(n) }
    private val privates = Memo { n, r -> sources.private(n, r) }
    private val archived = Memo { n, r -> sources.archived(n, r) }

    /** Forgets what was found (who is saved changed). */
    fun forget() {
        contacts.clear()
        privates.clear()
        archived.clear()
    }

    /** The region a national [number] is read with for a call on [accountId]'s SIM. */
    fun region(accountId: String?): String = sources.region(accountId)

    /**
     * What [number] is saved as, read as the SIM [accountId] reads it. For the call path and notifications ([use]) an
     * answer found less than [MEMO_MS] ago does: one ring asks many times. Parley's own screens ([Use.SCREEN]) always
     * look it up again (and keep the fresh answer), so a contact saved a moment ago is never missed there.
     */
    suspend fun find(number: String, accountId: String?, use: Use = Use.SCREEN): Found = findIn(number, region(accountId), use)

    /**
     * [find] with the region already known. The private contact is looked up even for a contact's number: a line saved
     * both ways must leave no trace outside the vault ("Private call history"), whichever name shows.
     */
    suspend fun findIn(number: String, region: String, use: Use = Use.SCREEN): Found = coroutineScope {
        if (number.isBlank()) return@coroutineScope Found(number, region, null, false, null, false, null, false)
        val reuse = use != Use.SCREEN
        val contactAsked = async { contacts.get(number, region, reuse) }
        val privateAsked = async { privates.get(number, region, reuse) }
        val contact = contactAsked.await()
        val private = privateAsked.await()
        val saved = contact.getOrNull() != null || private.getOrNull() != null
        val archive = if (!saved) archived.get(number, region, reuse) else Result.success(null)
        Found(number, region, contact.getOrNull(), contact.isFailure, private.getOrNull(), private.isFailure, archive.getOrNull(), archive.isFailure)
    }

    /**
     * Only the private contact behind [number] (screening, which looks the address book up its own way): the same
     * answer [find] gives, found once per ring. A failure is the result's.
     */
    suspend fun privateIn(number: String, region: String): Result<Pair<Long, CallerInfo>?> = privates.get(number, region)

    /** Only the archived contact behind [number] (screening), found once per ring. */
    suspend fun archivedIn(number: String, region: String): Result<ArchivedCard?> = archived.get(number, region)

    /**
     * Who owns [number] for [use], with the privacy rule applied ([privacy]: the view now when not given). A private
     * contact who is hidden reads as [Owner.Unknown], with no network name either (it would tell them apart). The
     * network's name shows only while "Remember names from the network" is on and, for a notification or the lock
     * screen, only where "Caller on the lock screen" shows names in full.
     */
    suspend fun owner(number: String, accountId: String?, use: Use, privacy: PrivacyView? = null): Owner =
        ownerOf(find(number, accountId, use), use, privacy ?: catching { sources.privacy() }.getOrDefault(PrivacyView.CLOSED))

    /** [owner] for what was already [found]. */
    suspend fun ownerOf(found: Found, use: Use, privacy: PrivacyView): Owner {
        found.contact?.let { return Owner.Contact(it) }
        found.private?.let { (id, info) -> return if (privacy.privateShown) Owner.Private(id, info) else Owner.Unknown }
        found.archived?.let { return Owner.Archived(it) }
        // Read fresh, not kept with the rest: the name a call just sent is written as it ends (the missed-call notice
        // waits for it), after the ring already asked who this is.
        val network = if (found.notPrivate && catching { sources.rememberNetworkNames() }.getOrDefault(false)) {
            catching { sources.network(found.number, found.region) }.getOrNull()?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        val shown = when (use) {
            Use.NOTIFICATION, Use.LOCK_SCREEN -> NetworkName.inNotification(network, privacy.lockScreen)
            Use.SCREEN, Use.CALL_PATH -> network
        }
        return shown?.let { Owner.Network(it) } ?: Owner.Unknown
    }

    /**
     * The name a notification about a call from [number] gives (see [NotificationPrivacy.screenedCallName]): the
     * owner's, shortened by "Caller on the lock screen" while [locked], else the number (or nothing, with "Nothing").
     */
    suspend fun notificationName(number: String, accountId: String?, locked: Boolean, privacy: PrivacyView? = null): String? {
        val view = privacy ?: catching { sources.privacy() }.getOrDefault(PrivacyView.CLOSED)
        val owner = owner(number, accountId, Use.NOTIFICATION, view)
        return NotificationPrivacy.screenedCallName(
            owner.name.takeIf { owner.saved }, null, view.privateHidden, (owner as? Owner.Network)?.name, number, view.lockScreen, locked,
        )
    }

    companion object {
        /** How long what was found for a number is kept: one ring and the minute after it. */
        const val MEMO_MS = 60_000L

        /** Numbers kept at most (a ring asks about one or two; a list screen about a few dozen). */
        private const val MAX_KEPT = 256
    }
}
