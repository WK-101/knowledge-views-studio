package app.parley.data.people

import android.content.Context
import android.util.Log
import app.parley.common.people.ContactRef
import app.parley.common.people.RelationLinks
import app.parley.common.people.RelationMirror
import app.parley.common.people.RelationTypes
import app.parley.data.ContactChangedElsewhereException
import app.parley.data.ContactDetails
import app.parley.data.ContactsRepository
import app.parley.data.DataItem
import app.parley.data.db.ContactMetaEntity
import app.parley.data.db.MetaDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Two-way relations between saved contacts ([RelationMirror]): after a contact is saved, each contact its relations
 * point to (by the links the editor's picker made) gets the opposite relation back, and a removed or retyped relation
 * takes back only what Parley added there. The other contact is written like any edit in Parley: version-checked,
 * journaled for History & undo, and never in a read-only account. What Parley added is remembered in
 * `relation_mirrors`, so a row the user wrote or changed on the other contact is never touched.
 */
class RelationMirrors(context: Context, private val contacts: ContactsRepository, private val meta: MetaDao) {
    private val prefs = context.applicationContext.getSharedPreferences("relation_mirrors", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val _version = MutableStateFlow(0)

    /** Grows with every correction remembered, so pages showing relations read them again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** A change made on [targetName]'s contact; [relation] is the relation on the contact that was saved. */
    data class Done(val targetKey: String, val targetId: Long, val targetName: String, val step: RelationMirror.Step, val relation: RelationMirror.Row?)

    enum class Reason {
        /** Its copies are all in read-only accounts (or the row is read-only). */
        READ_ONLY,

        /** It changed elsewhere while Parley was writing (a sync): left as it is. */
        CHANGED,
    }

    data class Skipped(val targetName: String, val reason: Reason)

    data class Report(val done: List<Done>, val skipped: List<Skipped>) {
        val isEmpty: Boolean get() = done.isEmpty() && skipped.isEmpty()
    }

    private class Target(val key: String, val id: Long, val all: ContactDetails, val editable: ContactDetails?)

    /**
     * Mirrors the relations of contact [selfId] (as just saved, [relations]) whose contact is known through [links]
     * (relation name key → linked contact). [before]: its relations before this save, so a contact that can't be
     * written is mentioned only when a relation to it is new.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements") // Plan, write, record: one pass under the lock.
    suspend fun mirror(
        selfId: Long,
        relations: List<DataItem>,
        links: Map<String, RelationLinks.Link>,
        before: List<DataItem> = emptyList(),
    ): Report = withContext(Dispatchers.IO) {
        lock.withLock {
            val selfKey = contacts.lookupKeyOf(selfId) ?: return@withLock Report(emptyList(), emptyList())
            val self = contacts.details(selfId)
            val selfName = self?.displayName.orEmpty()
            // The other side says son or daughter, husband or wife, only when this contact's pronouns say which.
            val selfGender = RelationMirror.genderOf(self?.pronouns)
            val all = RelationMirror.decode(prefs.getString(KEY, null))
            // A row Parley wrote here for another contact that the user has since changed: remembered, so that contact
            // shows and writes it their way from now on.
            val corrections = learnCorrections(selfId, selfKey, relations, all)
            val targets = HashMap<String, Target?>()
            suspend fun target(key: String, id: Long?): Target? {
                val (tid, tkey) = contacts.currentOf(key, id) ?: return null
                if (tid == selfId) return null
                if (tkey !in targets) targets[tkey] = load(tkey, tid)
                return targets[tkey]
            }
            // What each linked contact should have now.
            val wanted = LinkedHashMap<String, RelationMirror.Row>()
            val relationFor = HashMap<String, RelationMirror.Row>()
            for (r in relations) {
                if (r.value.isBlank()) continue
                val link = links[RelationLinks.nameKey(r.value)] ?: continue
                // A private contact is never written to from here, nor looked up in the address book: its page shows the
                // relation from Parley's own links instead (docs/CONTACT_MODEL.md, "Relations with private contacts").
                if (ContactRef.isPrivateKey(link.lookupKey)) continue
                val t = target(link.lookupKey, link.contactId) ?: continue
                if (t.key in wanted) continue
                val type = RelationTypes.fromAndroid(r.type, r.label)
                val computed = RelationMirror.reciprocal(type?.key, if (type == null) r.label else null, selfName, selfGender) ?: continue
                val row = RelationMirror.corrected(computed, selfKey, t.key, corrections)
                wanted[t.key] = row
                relationFor[t.key] = RelationMirror.Row(r.value, type?.key, if (type == null) r.label else null)
            }
            // What Parley added earlier for this contact, under the other contacts' current keys.
            val mine = all.filter { c -> c.from == selfKey || contacts.currentOf(c.from, c.fromId)?.first == selfId }
            val created = LinkedHashMap<String, RelationMirror.Row>()
            for (c in mine) target(c.target, c.targetId)?.let { created[it.key] = c.row }

            fun rowsOf(key: String): List<RelationMirror.Row>? {
                val t = targets[key] ?: return null
                if (t.editable?.editRawId == null) return null
                return t.all.relations.map(::rowOf)
            }
            val steps = RelationMirror.plan(wanted, created, ::rowsOf)
            val done = ArrayList<Done>()
            val skipped = ArrayList<Skipped>()
            // Linked contacts that can't be written: said when the relation is new, unless they already show it.
            val had = before.map(::rowOf)
            for ((key, row) in wanted) {
                val t = targets[key] ?: continue
                val isNew = relationFor[key]?.let { r -> had.none { it.sameAs(r) } } ?: false
                val readOnly = t.editable?.editRawId == null && t.all.relations.none { rowOf(it).sameAs(row) }
                if (isNew && readOnly) skipped += Skipped(t.all.displayName, Reason.READ_ONLY)
            }
            for (s in steps) {
                val t = targets[s.target] ?: continue
                when (val r = write(t, s)) {
                    null -> done += Done(t.key, t.id, t.all.displayName, s, relationFor[t.key])
                    else -> skipped += Skipped(t.all.displayName, r)
                }
            }
            // The other contacts as they are now, for what Parley still owns there.
            val fresh = HashMap<String, List<RelationMirror.Row>>()
            for (key in wanted.keys + created.keys) targets[key]?.let { t -> contacts.details(t.id)?.let { fresh[key] = it.relations.map(::rowOf) } }
            val record = RelationMirror.record(wanted, created, { fresh[it] }, done.map { it.step })
            val others = all - mine.toSet()
            val next = others + record.mapNotNull { (key, row) -> targets[key]?.let { RelationMirror.Created(selfKey, selfId, key, it.id, row) } }
            prefs.edit().putString(KEY, RelationMirror.encode(next).ifEmpty { null }).apply()
            done.forEach { d -> linkBack(d.targetKey, d.step, selfKey, selfId) }
            Report(done, skipped)
        }
    }

    /** Takes back [done] (the snackbar's Undo): each change is reversed where the other contact still shows it. */
    // One pass over the changes, skipping those the other contact no longer shows.
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    suspend fun undo(selfId: Long, done: List<Done>): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val selfKey = contacts.lookupKeyOf(selfId)
            var n = 0
            var all = RelationMirror.decode(prefs.getString(KEY, null))
            for (d in done.asReversed()) {
                val (tid, tkey) = contacts.currentOf(d.targetKey, d.targetId) ?: continue
                val t = load(tkey, tid) ?: continue
                val back = RelationMirror.undo(d.step)
                val present = t.all.relations.map(::rowOf)
                val applies = when (back) {
                    is RelationMirror.Step.Add -> present.none { it.sameAs(back.row) }
                    is RelationMirror.Step.Change -> present.any { it.sameAs(back.old) }
                    is RelationMirror.Step.Remove -> present.any { it.sameAs(back.old) }
                }
                if (!applies || write(t, back) != null) continue
                n++
                // The record follows: what Parley added there is what it had before.
                all = all.filterNot { it.target == d.targetKey && (it.from == selfKey || it.fromId == selfId) }
                val restored = when (back) {
                    is RelationMirror.Step.Add -> back.row
                    is RelationMirror.Step.Change -> back.row
                    is RelationMirror.Step.Remove -> null
                }
                if (restored != null && selfKey != null) all = all + RelationMirror.Created(selfKey, selfId, tkey, tid, restored)
                if (selfKey != null) linkBack(tkey, back, selfKey, selfId)
            }
            prefs.edit().putString(KEY, RelationMirror.encode(all).ifEmpty { null }).apply()
            n
        }
    }

    /**
     * Contact [selfId] ([selfKey]) is leaving the address book (made private, or deleted): the rows Parley added for it
     * on other contacts go back where they are still as Parley left them (a row the user changed there is theirs and
     * stays), and the record forgets every line about it, so neither its name nor its key stays behind in
     * `relation_mirrors`. Returns how many rows were taken back.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // Resolve, plan, write, record: one pass under the lock.
    suspend fun takeBack(selfId: Long, selfKey: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            val all = RelationMirror.decode(prefs.getString(KEY, null))
            val mine = all.filter { it.from == selfKey || it.fromId == selfId }
            val about = all.filter { it.target == selfKey || it.targetId == selfId }
            if (mine.isEmpty() && about.isEmpty()) return@withLock 0
            val targets = HashMap<String, Target>()
            val created = LinkedHashMap<String, RelationMirror.Row>()
            for (c in mine) {
                val (tid, tkey) = contacts.currentOf(c.target, c.targetId) ?: continue
                if (tid == selfId || tkey in targets) continue
                load(tkey, tid)?.let { targets[tkey] = it; created[tkey] = c.row }
            }
            fun rowsOf(key: String): List<RelationMirror.Row>? = targets[key]?.takeIf { it.editable?.editRawId != null }?.all?.relations?.map(::rowOf)
            // Nothing is wanted any more: only Parley's own, unchanged rows are removed.
            var n = 0
            for (step in RelationMirror.plan(emptyMap(), created, ::rowsOf)) {
                val t = targets[step.target] ?: continue
                if (write(t, step) == null) {
                    n++
                    linkBack(t.key, step, selfKey, selfId)
                }
            }
            prefs.edit().putString(KEY, RelationMirror.encode(all - (mine + about).toSet()).ifEmpty { null }).apply()
            n
        }
    }

    /** What the user said other contacts' relations really are on this side ([RelationMirror.Correction]). */
    fun corrections(): List<RelationMirror.Correction> = RelationMirror.decodeCorrections(prefs.getString(KEY_CORRECTIONS, null))

    /**
     * The user corrected how contact [from]'s relation shows on contact [to]: [computed] (what Parley worked out) is
     * [corrected] from now on. Correcting it back to [computed] forgets it.
     */
    fun correct(from: String, to: String, computed: RelationMirror.Row, corrected: RelationMirror.Row) {
        val next = RelationMirror.remember(corrections(), RelationMirror.Correction(from, to, computed, corrected))
        prefs.edit().putString(KEY_CORRECTIONS, RelationMirror.encodeCorrections(next).ifEmpty { null }).apply()
        _version.value++
    }

    /** A contact's key changed ([ContactKeys]): what was corrected about it follows. */
    fun rekeyCorrections(from: String, to: String) = editCorrections { list ->
        list.map { c -> c.copy(from = if (c.from == from) to else c.from, to = if (c.to == from) to else c.to) }
    }

    /** A contact is gone for good: what was corrected about it goes too. */
    fun forgetCorrections(key: String) = editCorrections { list -> list.filterNot { it.from == key || it.to == key } }

    private fun editCorrections(change: (List<RelationMirror.Correction>) -> List<RelationMirror.Correction>) {
        val before = corrections()
        val next = change(before)
        if (next == before) return
        prefs.edit().putString(KEY_CORRECTIONS, RelationMirror.encodeCorrections(next).ifEmpty { null }).apply()
        _version.value++
    }

    /**
     * Rows Parley wrote on contact [selfId] for other contacts ([all]) that its relations ([relations], as just saved)
     * now name another way: each is remembered as a correction. Returns every correction known now.
     */
    @Suppress("LoopWithTooManyJumpStatements") // Skip rows unchanged or no longer named, in one pass.
    private fun learnCorrections(
        selfId: Long,
        selfKey: String,
        relations: List<DataItem>,
        all: List<RelationMirror.Created>,
    ): List<RelationMirror.Correction> {
        var list = corrections()
        val before = list
        for (c in all.filter { it.target == selfKey || it.targetId == selfId }) {
            val now = relations.firstOrNull { RelationLinks.nameKey(it.value) == RelationLinks.nameKey(c.row.name) }?.let(::rowOf) ?: continue
            if (now.sameType(c.row)) continue
            // What Parley would work out, also when what it wrote was an earlier correction.
            val computed = list.firstOrNull { it.from == c.from && it.to == selfKey && it.corrected.sameType(c.row) }?.computed ?: c.row
            list = RelationMirror.remember(list, RelationMirror.Correction(c.from, selfKey, computed, now))
        }
        if (list != before) prefs.edit().putString(KEY_CORRECTIONS, RelationMirror.encodeCorrections(list).ifEmpty { null }).apply()
        return list
    }

    /** Whether Parley added any relation rows at all (a cheap check before [takeBack] for many contacts). */
    fun any(): Boolean = !prefs.getString(KEY, null).isNullOrEmpty()

    private suspend fun load(key: String, id: Long): Target? {
        val all = contacts.details(id) ?: return null
        val editable = runCatching { contacts.editable(id) }.getOrNull()
        return Target(key, id, all, editable)
    }

    /** Writes [step] on [t]'s editable copy; null when it went through, else why not. */
    // Any provider failure leaves the other contact as it was, and is said as "left as it is".
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun write(t: Target, step: RelationMirror.Step): Reason? {
        var ed = t.editable ?: return Reason.READ_ONLY
        repeat(2) { attempt ->
            if (ed.editRawId == null) return Reason.READ_ONLY
            val old = (step as? RelationMirror.Step.Change)?.old ?: (step as? RelationMirror.Step.Remove)?.old
            if (old != null) {
                val row = ed.relations.firstOrNull { rowOf(it).sameAs(old) } ?: return Reason.CHANGED
                if (row.id != null && row.id in ed.readOnlyDataIds) return Reason.READ_ONLY
            }
            val next = RelationMirror.apply(step, ed.relations, ::rowOf) { r, before -> itemOf(r, before) }
            try {
                contacts.save(ed, ed.copy(relations = next), null, null, false)
                return null
            } catch (e: ContactChangedElsewhereException) {
                // A sync wrote meanwhile: read it again once, then leave it.
                if (attempt == 1) return Reason.CHANGED
                ed = runCatching { contacts.editable(t.id) }.getOrNull() ?: return Reason.CHANGED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't write the relation on the other contact", e)
                return Reason.CHANGED
            }
        }
        return Reason.CHANGED
    }

    /** The other contact's relation to this one links back here, so it opens this contact too. */
    private suspend fun linkBack(targetKeyBefore: String, step: RelationMirror.Step, selfKey: String, selfId: Long) {
        runCatching {
            val (tid, key) = contacts.currentOf(targetKeyBefore, null) ?: return
            val m = meta.meta(key)
            val links = RelationLinks.decode(m?.relationLinks).toMutableMap()
            when (step) {
                is RelationMirror.Step.Add -> links[RelationLinks.nameKey(step.row.name)] = RelationLinks.Link(selfKey, selfId)
                is RelationMirror.Step.Change -> {
                    if (links[RelationLinks.nameKey(step.old.name)]?.lookupKey == selfKey) links.remove(RelationLinks.nameKey(step.old.name))
                    links[RelationLinks.nameKey(step.row.name)] = RelationLinks.Link(selfKey, selfId)
                }
                is RelationMirror.Step.Remove -> {
                    val k = RelationLinks.nameKey(step.old.name)
                    if (links[k]?.lookupKey == selfKey) links.remove(k)
                }
            }
            val encoded = RelationLinks.encode(links).ifEmpty { null }
            if (m == null) {
                if (encoded != null) meta.setMeta(ContactMetaEntity(key).copy(contactId = tid, relationLinks = encoded))
            } else {
                meta.setRelationLinks(key, encoded)
            }
        }.onFailure { Log.w(TAG, "Couldn't link the relation back", it) }
    }

    companion object {
        private const val TAG = "RelationMirrors"
        private const val KEY = "created"
        private const val KEY_CORRECTIONS = "corrections"

        fun rowOf(d: DataItem): RelationMirror.Row {
            val t = RelationTypes.fromAndroid(d.type, d.label)
            return RelationMirror.Row(d.value, t?.key, if (t == null) d.label else null)
        }

        /** The Data row for [r], keeping [before]'s id (an edit in place) when there is one. */
        fun itemOf(r: RelationMirror.Row, before: DataItem?): DataItem {
            val (type, label) = r.typeKey?.let(RelationTypes::byKey)?.let(RelationTypes::toAndroid) ?: (0 to r.label)
            return DataItem(id = before?.id, value = r.name, type = type, label = label)
        }
    }
}
