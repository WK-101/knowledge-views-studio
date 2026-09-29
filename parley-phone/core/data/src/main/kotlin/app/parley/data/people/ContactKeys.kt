package app.parley.data.people

import android.content.SharedPreferences
import app.parley.common.circle.CarriedInteraction
import app.parley.common.circle.InteractionChannel
import app.parley.common.circle.InteractionType
import app.parley.common.circle.Interactions
import app.parley.common.people.ContactRef
import app.parley.common.people.KeySweep
import app.parley.common.people.MetaRekey
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryExpiry
import androidx.room.withTransaction
import app.parley.data.ContactsRepository
import app.parley.data.calltime.CallingRepository
import app.parley.data.circle.InteractionStore
import app.parley.common.calltime.ContactCallTime
import app.parley.common.calltime.LimitRule
import app.parley.common.calltime.LimitScope
import app.parley.data.db.AppDatabase
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.MetaDao
import app.parley.data.extras.ExtrasStore
import app.parley.data.security.SealedMetaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Keeps Parley's per-contact data attached to the right person when lookup keys change: pinned notes, the
 * preferred messenger, keep-in-touch, relation links (contact_meta), call backgrounds, and the key of temporary
 * contacts. Keys change when contacts are linked or unlinked, a Google contact first syncs, a phone-only contact is
 * renamed, or a copy moves to another account.
 *
 * - [carry] runs right after Parley links, unlinks or moves contacts, with the keys from before.
 * - [sweep] re-resolves every stored key (`lookupContact(getLookupUri(id, key))`) and re-keys what moved; it runs
 *   after contact changes and daily, so changes made by other apps and sync adapters are followed too.
 */
class ContactKeys(
    private val contacts: ContactsRepository,
    private val meta: MetaDao,
    private val backgrounds: () -> CallBackgrounds,
    /** Interactions are keyed like contact_meta and follow the same moves. */
    private val interactions: () -> InteractionStore? = { null },
    /** The record of contacts Parley starred for a label's Do Not Disturb choice follows the same moves. */
    private val extras: () -> ExtrasStore? = { null },
    /** The database behind [meta] and the interactions: each re-key's rows move in one transaction. */
    private val db: AppDatabase? = null,
    /** Original contact photos are keyed by lookup key too. */
    private val originals: () -> OriginalPhotos? = { null },
    /** A contact's own call time limit, reminder and "never limit" follow the same moves. */
    private val calling: () -> CallingRepository? = { null },
    /** Re-keys waiting for a contact's lookup key ([rekeyLater]); none are kept without it. */
    private val waiting: () -> SharedPreferences? = { null },
) {
    private val mutex = Mutex()

    /** Runs the database writes of one re-key together: a crash midway leaves the old rows, never half of them. */
    private suspend fun <T> tx(block: suspend () -> T): T = db?.withTransaction { block() } ?: block()

    /** After a link, unlink or move: re-key what belonged to the contacts in [before] (id, key). */
    suspend fun carry(before: List<Pair<Long, String>>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val resolved = before.associate { (id, key) -> key to contacts.currentOf(key, id) }
            MetaRekey.plan(resolved.mapValues { it.value?.second }).forEach { m ->
                moveLocked(m.from, m.to, resolved[m.from]?.first)
            }
        }
    }

    /** Explicitly re-keys [from] to the contact [toId] (e.g. after a move, when the old key may not resolve). */
    suspend fun moveTo(from: String, toId: Long) = withContext(Dispatchers.IO) {
        val to = contacts.lookupKeyOf(toId) ?: return@withContext
        mutex.withLock { moveLocked(from, to, toId) }
    }

    /**
     * Re-keys everything Parley keeps under [from] to [to] (contact [toId]) when a contact changes variant: device →
     * private ([ContactRef.privateKey]) or back. Every store of [app.parley.common.storage.ContactKeyedStores] follows
     * (contact_meta, interactions, a temporary flag, relation links pointing at it, the call-screen picture and the Do
     * Not Disturb record), merged with anything already under [to], so nothing is orphaned.
     */
    suspend fun rekey(from: String, to: String, toId: Long?) = withContext(Dispatchers.IO) {
        if (from.isEmpty() || to.isEmpty()) return@withContext
        mutex.withLock { moveLocked(from, to, toId) }
    }

    /**
     * A private contact just made visible (its raw contacts [rawIds], contact [contactId]) whose lookup key the address
     * book couldn't give yet: what Parley keeps under private key [from] waits (it is never forgotten) and is re-keyed
     * to the contact by the next [sweep] that finds it ([settleWaiting]). Right away when the key is readable now.
     */
    suspend fun rekeyLater(from: String, rawIds: Collection<Long>, contactId: Long) = withContext(Dispatchers.IO) {
        if (from.isEmpty() || rawIds.isEmpty()) return@withContext
        val prefs = waiting() ?: return@withContext
        mutex.withLock {
            prefs.edit().putString(from, "$contactId;" + TemporaryExpiry.encodeIds(rawIds)).apply()
            settleWaitingLocked()
        }
    }

    /** Private keys still waiting for their contact ([rekeyLater]). */
    fun waitingKeys(): Set<String> = waiting()?.all?.keys.orEmpty()

    /**
     * Re-keys what waits under private keys ([rekeyLater]) to the contact now holding their raw contacts, with its
     * name back on a call-time limit. An entry whose raw contacts are all gone (the contact was deleted meanwhile) is
     * forgotten with what waited. Returns how many were settled.
     */
    @Suppress("CyclomaticComplexMethod") // One pass over the waiting entries: settle, forget or wait.
    private suspend fun settleWaitingLocked(): Int {
        val prefs = waiting() ?: return 0
        var n = 0
        for ((from, value) in prefs.all) {
            val v = value as? String ?: continue
            val raws = TemporaryExpiry.decodeIds(v.substringAfter(';')) ?: continue
            val owner = contacts.contactsOfRaws(raws).values.firstOrNull()
            if (owner == null) {
                // Only when the address book could be read and the contact is really gone.
                if (contacts.lookupKeys() == null) continue
                forgetLocked(from)
            } else {
                val key = contacts.lookupKeyOf(owner)?.takeIf { it.isNotEmpty() } ?: continue
                moveLocked(from, key, owner)
                val title = contacts.details(owner)?.displayName.orEmpty()
                if (title.isNotEmpty()) {
                    runCatching { calling()?.update { cfg -> cfg.rule(LimitScope.CONTACT, key)?.let { r -> cfg.withRule(r.copy(title = title)) } ?: cfg } }
                }
            }
            prefs.edit().remove(from).apply()
            n++
        }
        return n
    }

    /**
     * What Parley keeps about private contact [key] beside its vault entry (Circle rhythm, relation links, dates
     * remembered yearly, logged moments and the call-screen picture), for the private-contacts section of a backup:
     * they are written only with the private contacts themselves, never in the sections every backup has.
     */
    suspend fun exportPrivate(key: String): JSONObject? = withContext(Dispatchers.IO) {
        if (!ContactRef.isPrivateKey(key)) return@withContext null
        val o = JSONObject()
        meta.meta(key)?.let { m ->
            m.reachOutDays?.let { o.put(X_DAYS, it) }
            m.rhythm?.let { o.put(X_RHYTHM, it) }
            m.lastNudgedAt?.let { o.put(X_NUDGED, it) }
            m.relationLinks?.let { o.put(X_LINKS, it) }
            m.yearlyEvents?.let { o.put(X_YEARLY, it) }
        }
        val carried = runCatching { interactions()?.interactionsFor(key) }.getOrNull().orEmpty()
            .map { CarriedInteraction(it.type.name, it.channel?.name, it.time, it.note, it.dedupeKey) }
        if (carried.isNotEmpty()) o.put(X_INTERACTIONS, Interactions.encodeCarried(carried))
        runCatching { backgrounds().read(key) }.getOrNull()?.let { o.put(X_BACKGROUND, android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) }
        calling()?.config?.value?.contactPart(key)?.takeIf { !it.isEmpty }?.let { o.put(X_CALL_TIME, encodeCallTime(it)) }
        o.takeIf { it.length() > 0 }
    }

    /**
     * The relation links other contacts have to [key] (their key, the relation's name key), which [forget] removes
     * with a deleted contact: "Recently deleted" keeps them so a restore links those relations back.
     */
    suspend fun incomingLinks(key: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        meta.allMetaNow().flatMap { r -> RelationLinks.decode(r.relationLinks).filterValues { it.lookupKey == key }.keys.map { r.lookupKey to it } }
    }

    /** Links [incomingLinks]' relations back to private contact [vaultId], where the other contact hasn't linked them since. */
    suspend fun restoreIncomingLinks(vaultId: Long, links: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        if (links.isEmpty()) return@withContext
        val key = ContactRef.privateKey(vaultId)
        mutex.withLock {
            tx {
                links.groupBy({ it.first }, { it.second }).forEach { (owner, names) ->
                    val row = meta.meta(owner) ?: return@forEach
                    val current = RelationLinks.decode(row.relationLinks)
                    val added = names.filter { it !in current }.associateWith { RelationLinks.Link(key, -vaultId) }
                    if (added.isNotEmpty()) meta.setRelationLinks(owner, RelationLinks.encode(current + added))
                }
            }
        }
    }

    /** Puts back [exportPrivate]'s [o] under private contact [vaultId] (a restore gives it a new id). */
    suspend fun importPrivate(vaultId: Long, o: JSONObject) = withContext(Dispatchers.IO) {
        val key = ContactRef.privateKey(vaultId)
        mutex.withLock {
            tx {
                val restored = MetaRekey.Values(
                    reachOutDays = o.optInt(X_DAYS).takeIf { o.has(X_DAYS) }, lastNudgedAt = o.optLong(X_NUDGED).takeIf { o.has(X_NUDGED) },
                    relationLinks = o.optString(X_LINKS).ifEmpty { null }, rhythm = o.optString(X_RHYTHM).ifEmpty { null },
                    yearlyEvents = o.optString(X_YEARLY).ifEmpty { null },
                )
                if (restored != MetaRekey.Values()) meta.setMeta(MetaRekey.merge(meta.meta(key)?.values(), restored).toEntity(key, -vaultId))
            }
            val store = interactions()
            if (store != null) Interactions.decodeCarried(o.optString(X_INTERACTIONS).ifEmpty { null }).forEach { i ->
                val type = InteractionType.entries.firstOrNull { it.name == i.t } ?: InteractionType.OTHER
                runCatching { store.log(key, -vaultId, type, InteractionChannel.decode(i.c), i.at, i.note, i.u) }
            }
            o.optString(X_BACKGROUND).ifEmpty { null }?.let { b ->
                runCatching { backgrounds().write(key, android.util.Base64.decode(b, android.util.Base64.NO_WRAP)); backgrounds().remember(key) }
            }
            o.optJSONObject(X_CALL_TIME)?.let { t -> runCatching { calling()?.update { it.withContactPart(key, decodeCallTime(t), "") } } }
        }
    }

    /**
     * Forgets everything Parley kept for [key] outside the contact itself, after it moved into the vault: its
     * contact_meta row (the pinned note travels in the vault entry), its logged interactions (carried, sealed, in the
     * vault entry by [app.parley.data.vault.VaultMoves.moveIn]), its call background, a temporary flag, and the
     * relation links other contacts had to it.
     */
    suspend fun forget(key: String) = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext
        mutex.withLock { forgetLocked(key) }
    }

    private suspend fun forgetLocked(key: String) {
        tx {
            meta.deleteMeta(key)
            meta.clearTemporary(key)
            // A private contact's interactions don't stay outside the vault (moveIn copied them into the entry).
            interactions()?.forget(key)
            for (r in meta.allMetaNow()) {
                val links = RelationLinks.decode(r.relationLinks)
                if (links.values.none { it.lookupKey == key }) continue
                meta.setRelationLinks(r.lookupKey, RelationLinks.encode(links.filterValues { it.lookupKey != key }))
            }
        }
        runCatching { backgrounds().clear(key) }
        runCatching { backgrounds().forgetPhotoChoice(key) }
        runCatching { originals()?.clear(key) }
        runCatching { extras()?.dndForget(key) }
        runCatching { calling()?.update { it.withoutContact(key) } }
    }

    /** The state of the last complete sweep, to skip the next one when nothing it depends on changed. */
    private var lastSweep: KeySweep.Snapshot? = null

    /**
     * Re-resolves every stored key; returns how many rows moved. Keys the address book still has resolve from one
     * listing of (key, id) pairs; only the others are looked up one by one. Skipped when neither that listing nor
     * the stored keys changed since the last sweep.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Without a listing (no permission) nothing can be resolved: leave everything where it is.
            val current = contacts.lookupKeys() ?: return@withLock 0
            val settled = runCatching { settleWaitingLocked() }.getOrDefault(0)
            var moved = 0
            val rows = meta.allMetaNow()
            val bg = runCatching { backgrounds() }.getOrNull()
            val keys = LinkedHashMap<String, Long?>()
            // Private contacts' keys are never looked up in the address book (a namesake must not take their data).
            rows.forEach { if (!ContactRef.isPrivateKey(it.lookupKey)) keys[it.lookupKey] = it.contactId }
            bg?.indexedKeys()?.forEach { if (!ContactRef.isPrivateKey(it)) keys.putIfAbsent(it, null) }
            bg?.photoChoiceKeys()?.forEach { if (!ContactRef.isPrivateKey(it)) keys.putIfAbsent(it, null) }
            // With the contact id they were logged with, so a key change without a shared segment (a rename of a
            // phone-only contact, a first sync) is still followed for contacts that have no contact_meta row.
            runCatching { interactions()?.keys() }.getOrNull()?.forEach { (k, id) -> if (keys[k] == null && !ContactRef.isPrivateKey(k)) keys[k] = id }
            runCatching { extras()?.dndKeys() }.getOrNull()?.forEach { if (!ContactRef.isPrivateKey(it)) keys.putIfAbsent(it, null) }
            runCatching { originals()?.keys() }.getOrNull()?.forEach { if (!ContactRef.isPrivateKey(it)) keys.putIfAbsent(it, null) }
            val temporaries = meta.allTemporary()
            val snapshot = KeySweep.Snapshot(current, keys + temporaries.associate { "t:" + it.lookupKey + ":" + it.rawIds to it.contactId })
            if (snapshot == lastSweep) return@withLock settled
            notesWaiting = false
            val split = KeySweep.split(keys, current)
            // Only moves that are plausibly the same person (no namesake takes over a deleted contact's note).
            val looked = split.needLookup.associateWith { key ->
                val id = keys[key]
                contacts.currentOf(key, id)?.takeIf { (newId, newKey) -> newKey == key || MetaRekey.plausible(key, newKey, id, newId) }
            }
            val resolved: Map<String, Pair<Long, String>?> = keys.keys.associateWith { k -> split.direct[k] ?: looked[k] }
            for (m in MetaRekey.plan(resolved.mapValues { it.value?.second })) {
                moveLocked(m.from, m.to, resolved[m.from]?.first)
                moved++
            }
            // Remember ids for keys that didn't move, so the next change can be resolved from (id, key).
            // Only the id column: a pinned note edited since the rows were read stays as it is now.
            for (r in rows) {
                val now = resolved[r.lookupKey] ?: continue
                if (now.second == r.lookupKey && r.contactId != now.first) meta.setMetaContactId(r.lookupKey, now.first)
            }
            moved += rekeyTemporaries(current.entries.associate { (k, id) -> id to k })
            fixRelationLinks(current)
            // Index backgrounds made before the index existed (their keys are still current).
            if (bg != null) current.keys.forEach { k -> if (bg.forLookupKey(k) != null) bg.remember(k) }
            // What moved changes the stored keys: the next sweep runs once more, finds nothing and settles.
            lastSweep = snapshot.takeUnless { notesWaiting || waitingKeys().isNotEmpty() }
            moved + settled
        }
    }

    /** (id, key) now for a stored [key]: from the listing when the address book still has it, else looked up. */
    private fun resolve(key: String, id: Long?, current: Map<String, Long>): Pair<Long, String>? =
        current[key]?.let { it to key } ?: contacts.currentOf(key, id)

    private suspend fun moveLocked(from: String, to: String, toId: Long?) {
        if (from == to) return
        // Metadata, interactions, the temporary flag and relation links move together or not at all.
        tx {
            meta.meta(from)?.let { src -> moveMetaRow(from, to, toId, src) }
            interactions()?.rekey(from, to, toId)
            meta.temporary(from)?.let { t ->
                val existing = meta.temporary(to)
                val (ids, expiresAt) = TemporaryExpiry.merge(t.rawIds to t.expiresAt, existing?.rawIds to (existing?.expiresAt ?: Long.MAX_VALUE))
                meta.clearTemporary(from)
                meta.setTemporary(t.copy(lookupKey = to, contactId = toId ?: t.contactId, expiresAt = expiresAt, rawIds = ids))
            }
            // Relation links in other contacts that pointed at the old key.
            for (r in meta.allMetaNow()) {
                val links = RelationLinks.decode(r.relationLinks)
                if (links.values.none { it.lookupKey == from }) continue
                val updated = links.mapValues { (_, l) -> if (l.lookupKey == from) RelationLinks.Link(to, toId ?: l.contactId) else l }
                meta.setRelationLinks(r.lookupKey, RelationLinks.encode(updated))
            }
        }
        // Files and preferences: outside the database, and self-healing on the next sweep.
        runCatching { backgrounds().move(from, to) }
        runCatching { originals()?.move(from, to) }
        runCatching { extras()?.dndRekey(from, to) }
        // A private contact's limit keeps no name (it would be the only copy of it outside the vault).
        runCatching {
            calling()?.update { cfg ->
                cfg.rekeyed(from, to, if (ContactRef.isPrivateKey(to)) "" else cfg.rule(LimitScope.CONTACT, from)?.title.orEmpty())
            }
        }
    }

    /**
     * Moves [from]'s contact_meta row ([src]) onto [to], merged with what [to] has. A pinned note that can't be opened
     * right now (sealed, key unavailable) moves as it is when the other side has no note; when both have one they
     * can't be merged, so both rows stay and a later sweep tries again.
     */
    private suspend fun moveMetaRow(from: String, to: String, toId: Long?, src: ContactMetaEntity) {
        val sealed = meta as? SealedMetaDao
        val srcHidden = sealed?.unreadableNote(from)
        val dstHidden = sealed?.unreadableNote(to) != null
        val dst = meta.meta(to)
        val srcBlocked = srcHidden != null && (dstHidden || dst?.pinnedNote != null)
        val dstBlocked = dstHidden && src.pinnedNote != null
        if (srcBlocked || dstBlocked) {
            notesWaiting = true
            return
        }
        val merged = MetaRekey.merge(dst?.values(), src.values())
        // A hidden note at [to] is kept by the write itself (the merged note is null there).
        meta.setMeta(merged.toEntity(to, toId ?: dst?.contactId ?: src.contactId))
        if (srcHidden != null) sealed?.keepSealedNote(to, srcHidden)
        meta.deleteMeta(from)
    }

    /** A move was held back by a note that can't be opened right now: the next sweep must not be skipped. */
    private var notesWaiting = false

    /** Temporary contacts follow their raw contacts, whose ids never change. */
    private suspend fun rekeyTemporaries(keyOf: Map<Long, String>): Int {
        var n = 0
        for (t in meta.allTemporary()) {
            val raws = TemporaryExpiry.decodeIds(t.rawIds) ?: continue
            val owner = contacts.contactsOfRaws(raws).values.firstOrNull() ?: continue
            val key = keyOfContact(owner, keyOf) ?: continue
            if (key == t.lookupKey && owner == t.contactId) continue
            // Another entry may already live under the new key (two temporaries linked by Android): merge, like
            // moveLocked, so neither entry's raw ids are forgotten.
            val existing = if (key != t.lookupKey) meta.temporary(key) else null
            val (ids, expiresAt) = existing?.let { TemporaryExpiry.merge(t.rawIds to t.expiresAt, it.rawIds to it.expiresAt) } ?: (t.rawIds to t.expiresAt)
            tx {
                meta.clearTemporary(t.lookupKey)
                meta.setTemporary(t.copy(lookupKey = key, contactId = owner, rawIds = ids, expiresAt = expiresAt))
            }
            n++
        }
        return n
    }

    private fun keyOfContact(id: Long, keyOf: Map<Long, String>): String? = keyOf[id] ?: contacts.lookupKeyOf(id)

    private suspend fun fixRelationLinks(current: Map<String, Long>) {
        for (r in meta.allMetaNow()) {
            val links = RelationLinks.decode(r.relationLinks)
            if (links.isEmpty()) continue
            var changed = false
            val updated = links.mapValues { (_, l) ->
                // A link to a private contact follows it by its own key (re-keyed with it), never through the address book.
                if (ContactRef.isPrivateKey(l.lookupKey)) return@mapValues l
                val now = resolve(l.lookupKey, l.contactId, current)
                    ?.takeIf { (id, key) -> key == l.lookupKey || MetaRekey.plausible(l.lookupKey, key, l.contactId, id) }
                if (now != null && (now.second != l.lookupKey || now.first != l.contactId)) { changed = true; RelationLinks.Link(now.second, now.first) } else l
            }
            if (changed) meta.setRelationLinks(r.lookupKey, RelationLinks.encode(updated))
        }
    }
}

private const val X_DAYS = "d"
private const val X_RHYTHM = "r"
private const val X_NUDGED = "nudged"
private const val X_LINKS = "rel"
private const val X_YEARLY = "y"
private const val X_INTERACTIONS = "i"
private const val X_BACKGROUND = "bg"
private const val X_CALL_TIME = "ct"

private fun encodeCallTime(p: ContactCallTime): JSONObject = JSONObject().apply {
    p.rule?.let { r ->
        put("per", r.perCallMinutes); put("day", r.dailyMinutes); put("week", r.weeklyMinutes); put("in", r.incoming); put("out", r.outgoing)
    }
    p.reminderMinutes?.let { put("rem", it) }
    if (p.neverLimit) put("never", true)
}

private fun decodeCallTime(o: JSONObject): ContactCallTime = ContactCallTime(
    rule = if (o.has("per") || o.has("day") || o.has("week")) {
        LimitRule(
            LimitScope.CONTACT, perCallMinutes = o.optInt("per"), dailyMinutes = o.optInt("day"), weeklyMinutes = o.optInt("week"),
            incoming = o.optBoolean("in", true), outgoing = o.optBoolean("out", true),
        )
    } else {
        null
    },
    reminderMinutes = if (o.has("rem")) o.optInt("rem") else null,
    neverLimit = o.optBoolean("never"),
)

internal fun ContactMetaEntity.values() = MetaRekey.Values(pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, relationLinks, rhythm, yearlyEvents)

internal fun MetaRekey.Values.toEntity(key: String, contactId: Long?) =
    ContactMetaEntity(key, pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, contactId, relationLinks, rhythm, yearlyEvents)
