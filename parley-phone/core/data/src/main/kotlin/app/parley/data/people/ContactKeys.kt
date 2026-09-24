package app.parley.data.people

import app.parley.common.people.MetaRekey
import app.parley.common.people.RelationLinks
import app.parley.common.people.TemporaryExpiry
import app.parley.data.ContactsRepository
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.MetaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps Parley's per-contact data attached to the right person when lookup keys change (F8): pinned notes, the
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
) {
    private val mutex = Mutex()

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

    /** Re-resolves every stored key; returns how many rows moved. */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            var moved = 0
            val rows = meta.allMetaNow()
            val bg = runCatching { backgrounds() }.getOrNull()
            val keys = LinkedHashMap<String, Long?>()
            rows.forEach { keys[it.lookupKey] = it.contactId }
            bg?.indexedKeys()?.forEach { keys.putIfAbsent(it, null) }
            // Only moves that are plausibly the same person (no namesake takes over a deleted contact's note).
            val resolved = keys.mapValues { (key, id) ->
                contacts.currentOf(key, id)?.takeIf { (newId, newKey) -> newKey == key || MetaRekey.plausible(key, newKey, id, newId) }
            }
            for (m in MetaRekey.plan(resolved.mapValues { it.value?.second })) {
                moveLocked(m.from, m.to, resolved[m.from]?.first)
                moved++
            }
            // Remember ids for keys that didn't move, so the next change can be resolved from (id, key).
            for (r in rows) {
                val now = resolved[r.lookupKey] ?: continue
                if (now.second == r.lookupKey && r.contactId != now.first) meta.setMeta(r.copy(contactId = now.first))
            }
            moved += rekeyTemporaries()
            fixRelationLinks()
            // Index backgrounds made before the index existed (their keys are still current).
            if (bg != null) contacts.contacts.value?.forEach { c -> if (bg.forLookupKey(c.lookupKey) != null) bg.remember(c.lookupKey) }
            moved
        }
    }

    private suspend fun moveLocked(from: String, to: String, toId: Long?) {
        if (from == to) return
        meta.meta(from)?.let { src ->
            val dst = meta.meta(to)
            val merged = MetaRekey.merge(dst?.values(), src.values())
            meta.setMeta(merged.toEntity(to, toId ?: dst?.contactId ?: src.contactId))
            meta.deleteMeta(from)
        }
        runCatching { backgrounds().move(from, to) }
        meta.temporary(from)?.let { t ->
            val existing = meta.temporary(to)
            val ids = (TemporaryExpiry.decodeIds(t.rawIds).orEmpty() + TemporaryExpiry.decodeIds(existing?.rawIds).orEmpty()).takeIf { it.isNotEmpty() }
            meta.clearTemporary(from)
            meta.setTemporary(
                t.copy(
                    lookupKey = to, contactId = toId ?: t.contactId,
                    expiresAt = minOf(t.expiresAt, existing?.expiresAt ?: Long.MAX_VALUE),
                    rawIds = ids?.let(TemporaryExpiry::encodeIds) ?: t.rawIds,
                ),
            )
        }
        // Relation links in other contacts that pointed at the old key.
        for (r in meta.allMetaNow()) {
            val links = RelationLinks.decode(r.relationLinks)
            if (links.values.none { it.lookupKey == from }) continue
            val updated = links.mapValues { (_, l) -> if (l.lookupKey == from) RelationLinks.Link(to, toId ?: l.contactId) else l }
            meta.setMeta(r.copy(relationLinks = RelationLinks.encode(updated)))
        }
    }

    /** Temporary contacts follow their raw contacts, whose ids never change. */
    private suspend fun rekeyTemporaries(): Int {
        var n = 0
        for (t in meta.allTemporary()) {
            val raws = TemporaryExpiry.decodeIds(t.rawIds) ?: continue
            val owner = contacts.contactsOfRaws(raws).values.firstOrNull() ?: continue
            val key = contacts.lookupKeyOf(owner) ?: continue
            if (key == t.lookupKey && owner == t.contactId) continue
            meta.clearTemporary(t.lookupKey)
            meta.setTemporary(t.copy(lookupKey = key, contactId = owner))
            n++
        }
        return n
    }

    private suspend fun fixRelationLinks() {
        for (r in meta.allMetaNow()) {
            val links = RelationLinks.decode(r.relationLinks)
            if (links.isEmpty()) continue
            var changed = false
            val updated = links.mapValues { (_, l) ->
                val now = contacts.currentOf(l.lookupKey, l.contactId)
                    ?.takeIf { (id, key) -> key == l.lookupKey || MetaRekey.plausible(l.lookupKey, key, l.contactId, id) }
                if (now != null && (now.second != l.lookupKey || now.first != l.contactId)) { changed = true; RelationLinks.Link(now.second, now.first) } else l
            }
            if (changed) meta.setMeta(r.copy(relationLinks = RelationLinks.encode(updated)))
        }
    }
}

internal fun ContactMetaEntity.values() = MetaRekey.Values(pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, relationLinks)

internal fun MetaRekey.Values.toEntity(key: String, contactId: Long?) =
    ContactMetaEntity(key, pinnedNote, preferredMessenger, reachOutDays, lastNudgedAt, contactId, relationLinks)
