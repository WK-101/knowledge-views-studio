package app.parley.data.people

import app.parley.common.people.KeySweep
import app.parley.common.people.MetaRekey
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryExpiry
import androidx.room.withTransaction
import app.parley.data.ContactsRepository
import app.parley.data.circle.InteractionStore
import app.parley.data.db.AppDatabase
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.MetaDao
import app.parley.data.extras.ExtrasStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
     * Forgets everything Parley kept for [key] outside the contact itself, after it moved into the vault: its
     * contact_meta row (the pinned note travels in the vault entry), its logged interactions (carried, sealed, in the
     * vault entry by [app.parley.data.vault.VaultMoves.moveIn]), its call background, a temporary flag, and the
     * relation links other contacts had to it.
     */
    suspend fun forget(key: String) = withContext(Dispatchers.IO) {
        if (key.isEmpty()) return@withContext
        mutex.withLock {
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
            runCatching { extras()?.dndForget(key) }
        }
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
            var moved = 0
            val rows = meta.allMetaNow()
            val bg = runCatching { backgrounds() }.getOrNull()
            val keys = LinkedHashMap<String, Long?>()
            rows.forEach { keys[it.lookupKey] = it.contactId }
            bg?.indexedKeys()?.forEach { keys.putIfAbsent(it, null) }
            // With the contact id they were logged with, so a key change without a shared segment (a rename of a
            // phone-only contact, a first sync) is still followed for contacts that have no contact_meta row.
            runCatching { interactions()?.keys() }.getOrNull()?.forEach { (k, id) -> if (keys[k] == null) keys[k] = id }
            runCatching { extras()?.dndKeys() }.getOrNull()?.forEach { keys.putIfAbsent(it, null) }
            val temporaries = meta.allTemporary()
            val snapshot = KeySweep.Snapshot(current, keys + temporaries.associate { "t:" + it.lookupKey + ":" + it.rawIds to it.contactId })
            if (snapshot == lastSweep) return@withLock 0
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
            moved += rekeyTemporaries(current)
            fixRelationLinks(current)
            // Index backgrounds made before the index existed (their keys are still current).
            if (bg != null) current.keys.forEach { k -> if (bg.forLookupKey(k) != null) bg.remember(k) }
            // What moved changes the stored keys: the next sweep runs once more, finds nothing and settles.
            lastSweep = snapshot
            moved
        }
    }

    /** (id, key) now for a stored [key]: from the listing when the address book still has it, else looked up. */
    private fun resolve(key: String, id: Long?, current: Map<String, Long>): Pair<Long, String>? =
        current[key]?.let { it to key } ?: contacts.currentOf(key, id)

    private suspend fun moveLocked(from: String, to: String, toId: Long?) {
        if (from == to) return
        // Metadata, interactions, the temporary flag and relation links move together or not at all.
        tx {
            meta.meta(from)?.let { src ->
                val dst = meta.meta(to)
                val merged = MetaRekey.merge(dst?.values(), src.values())
                meta.setMeta(merged.toEntity(to, toId ?: dst?.contactId ?: src.contactId))
                meta.deleteMeta(from)
            }
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
        runCatching { extras()?.dndRekey(from, to) }
    }

    /** Temporary contacts follow their raw contacts, whose ids never change. */
    private suspend fun rekeyTemporaries(current: Map<String, Long>): Int {
        var n = 0
        val keyOf = HashMap<Long, String>(current.size).apply { current.forEach { (k, id) -> put(id, k) } }
        for (t in meta.allTemporary()) {
            val raws = TemporaryExpiry.decodeIds(t.rawIds) ?: continue
            val owner = contacts.contactsOfRaws(raws).values.firstOrNull() ?: continue
            val key = keyOf[owner] ?: contacts.lookupKeyOf(owner) ?: continue
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

    private suspend fun fixRelationLinks(current: Map<String, Long>) {
        for (r in meta.allMetaNow()) {
            val links = RelationLinks.decode(r.relationLinks)
            if (links.isEmpty()) continue
            var changed = false
            val updated = links.mapValues { (_, l) ->
                val now = resolve(l.lookupKey, l.contactId, current)
                    ?.takeIf { (id, key) -> key == l.lookupKey || MetaRekey.plausible(l.lookupKey, key, l.contactId, id) }
                if (now != null && (now.second != l.lookupKey || now.first != l.contactId)) { changed = true; RelationLinks.Link(now.second, now.first) } else l
            }
            if (changed) meta.setRelationLinks(r.lookupKey, RelationLinks.encode(updated))
        }
    }
}

internal fun ContactMetaEntity.values() = MetaRekey.Values(pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, relationLinks, rhythm, yearlyEvents)

internal fun MetaRekey.Values.toEntity(key: String, contactId: Long?) =
    ContactMetaEntity(key, pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, contactId, relationLinks, rhythm, yearlyEvents)
