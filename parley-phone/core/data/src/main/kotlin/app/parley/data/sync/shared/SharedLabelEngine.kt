package app.parley.data.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.record.ContactRecord
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.CardFile
import app.parley.common.sync.shared.ChangeKind
import app.parley.common.sync.shared.HistoryItem
import app.parley.common.sync.shared.Invitation
import app.parley.common.sync.shared.Journal
import app.parley.common.sync.shared.JournalEntry
import app.parley.common.sync.shared.LabelMember
import app.parley.common.sync.shared.MemberSigner
import app.parley.common.sync.shared.SharedCards
import app.parley.common.sync.shared.SharedLabelCrypto
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.SharedLabelHistory
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.sync.shared.SharedLabelMembership.Event
import app.parley.common.sync.shared.SharedLabelMembership.State
import app.parley.common.sync.shared.SharedLabelRoster
import app.parley.common.sync.shared.SharedLabelRules
import app.parley.common.sync.shared.SharedLabelRules.Action
import app.parley.common.sync.shared.SharedLabelRules.Local
import app.parley.common.sync.shared.SharedLabelRules.Remote

/** What one run did, for the status line and tests. */
data class SharedRunReport(
    val written: Int = 0,
    val applied: Int = 0,
    val imported: Int = 0,
    val linked: Int = 0,
    val deleted: Int = 0,
    val conflicts: Int = 0,
)

/**
 * One shared label's sync and membership changes (docs/SHARED_LABELS.md), over its [folder], this phone's [local]
 * contacts and this member's [signer]. Pure state in, state out: the caller stores what comes back. A run that can't
 * list the folder completely, or whose key no longer opens it, changes nothing.
 */
class SharedLabelEngine(
    private val folder: LabelFolder,
    private val local: LabelContacts,
    private val signer: MemberSigner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Outcome(val state: SharedLabelState, val report: SharedRunReport = SharedRunReport())

    private val me = SharedLabelFiles.keyHex(signer.publicKey)

    private fun SharedLabelState.done(result: SharedRunResult, pending: Int = 0) =
        copy(lastSyncAt = clock(), lastResult = result, pendingDeletions = pending)

    /** The folder's listing, or the reason there is none. */
    private suspend fun listing(): Pair<Map<String, String?>?, SharedRunResult?> = try {
        folder.list()?.let { it to null } ?: (null to SharedRunResult.FOLDER_GONE)
    } catch (_: LabelFolder.Incomplete) {
        null to SharedRunResult.FOLDER_LOADING
    }

    private fun header(listing: Map<String, String?>, labelId: String): SharedLabelCrypto.Header? {
        if (SharedLabelCrypto.HEADER_NAME !in listing) return null
        val bytes = folder.read(SharedLabelCrypto.HEADER_NAME) ?: return null
        return runCatching { SharedLabelCrypto.parseHeader(bytes) }.getOrNull()?.takeIf { it.labelId == labelId }
    }

    // ---------------------------------------------------------------- creating, inviting, joining

    /** Why a folder can't take a new shared label, or null when it can (it is listable and holds no label yet). */
    suspend fun checkEmpty(): SharedRunResult? {
        val (listing, problem) = listing()
        if (listing == null) return problem
        return if (SharedLabelCrypto.HEADER_NAME in listing) SharedRunResult.NOT_A_LABEL else null
    }

    /** Starts a shared label in an empty folder: its header and this phone as the anchor. Null when the folder refused. */
    suspend fun create(
        title: String,
        folderUri: String,
        folderName: String,
        passphrase: CharArray,
        myName: String,
        kdf: KdfParams = BackupCrypto.DEFAULT_KDF,
    ): SharedLabelState? {
        if (checkEmpty() != null) return null
        val labelId = SharedLabelFiles.newId()
        val (header, key) = SharedLabelCrypto.newHeader(labelId, 1, passphrase, kdf)
        folder.write(SharedLabelCrypto.HEADER_NAME, header) ?: return null
        return SharedLabelState(
            labelId = labelId, title = title, folderUri = folderUri, folderName = folderName, key = key,
            anchor = signer.publicKey, anchorName = myName, myName = myName, ticket = null, membership = State.Active(1),
        )
    }

    /** An invitation from this phone: a fresh ticket signed now. Null when the key can't sign right now. */
    fun invitation(s: SharedLabelState, folderHint: String): Invitation? {
        val epoch = (s.membership as? State.Active)?.epoch ?: return null
        val ticket = SharedLabelFiles.ticket(signer, s.labelId, epoch, SharedLabelFiles.newId()) ?: return null
        return Invitation(s.labelId, s.title, folderHint, epoch, s.key, s.anchor, s.anchorName, signer.publicKey, s.myName, ticket)
    }

    /** Whether [passphrase] is the label's current one (an invitation file is sealed with it). */
    suspend fun checkPassphrase(s: SharedLabelState, passphrase: CharArray): Boolean {
        val listing = listing().first ?: return false
        val h = header(listing, s.labelId) ?: return false
        return SharedLabelCrypto.unlock(h, passphrase)?.contentEquals(s.key) == true
    }

    /** What joining [i] in this folder would show, or why it can't be joined there. */
    sealed interface Preview {
        data class Ready(val members: List<LabelMember>) : Preview

        /** The folder holds no shared label, or another one. */
        data object WrongFolder : Preview

        /** The folder's key is newer than the invitation's: ask for a new one. */
        data object OldInvitation : Preview

        data class Unavailable(val reason: SharedRunResult) : Preview
    }

    suspend fun preview(i: Invitation): Preview {
        val (listing, problem) = listing()
        if (listing == null) return Preview.Unavailable(problem!!)
        val h = header(listing, i.labelId) ?: return Preview.WrongFolder
        if (h.epoch != i.epoch || !SharedLabelCrypto.opens(h, i.key)) return Preview.OldInvitation
        return Preview.Ready(SharedLabelRoster.members(i.labelId, i.epoch, i.anchor, i.anchorName, journals(listing, i.labelId, i.key)))
    }

    /**
     * Joins with [i] (checked with [preview] first): a new state, or [existing] (the same label after its key changed,
     * or left before) brought back with the new key, keeping what it synced.
     */
    fun join(i: Invitation, folderUri: String, folderName: String, title: String, myName: String, existing: SharedLabelState?): SharedLabelState {
        val base = existing?.takeIf { it.labelId == i.labelId }
        val membership = SharedLabelMembership.next(base?.membership ?: State.Left, Event.InvitationOpened(i.epoch))
        val ticket = if (i.inviter.contentEquals(signer.publicKey)) null else i.ticket
        return (base ?: SharedLabelState(i.labelId, title, folderUri, folderName, i.key, i.anchor, i.anchorName, myName, ticket, membership = membership))
            .copy(
                folderUri = folderUri, folderName = folderName, key = i.key, anchor = i.anchor, anchorName = i.anchorName, myName = myName,
                ticket = ticket, membership = membership,
            )
    }

    // ---------------------------------------------------------------- reading the folder

    private fun journals(listing: Map<String, String?>, labelId: String, key: ByteArray): List<Journal> =
        listing.keys.filter(SharedLabelFiles::isJournalName).mapNotNull { name ->
            folder.read(name)?.let { SharedLabelCrypto.open(key, labelId, name, it) }?.let { SharedLabelFiles.readJournal(labelId, name, it) }
        }

    private fun myJournal(s: SharedLabelState, left: Boolean = false) =
        Journal(signer.publicKey, s.myName, s.epoch, s.ticket, if (s.anchor.contentEquals(signer.publicKey)) s.carried else emptyList(), left, s.journal)

    private fun writeJournal(s: SharedLabelState, left: Boolean = false): String? =
        SharedLabelFiles.writeJournal(signer, s.labelId, myJournal(s, left))?.let { body ->
            val name = SharedLabelFiles.journalName(signer.publicKey)
            folder.write(name, SharedLabelCrypto.seal(s.key, s.labelId, name, body))
        }

    private fun writeCard(s: SharedLabelState, sid: String, version: Long, card: ContactRecord?): String? {
        val body = SharedLabelFiles.writeCard(signer, s.labelId, sid, version, clock(), card?.let(SharedCards::encode)) ?: return null
        val name = SharedLabelFiles.cardName(sid)
        return folder.write(name, SharedLabelCrypto.seal(s.key, s.labelId, name, body))
    }

    // ---------------------------------------------------------------- the run

    /** A contact file as this run read it: null [file] when it couldn't be opened or isn't signed by a member. */
    private class Read(val name: String, val file: CardFile?)

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    suspend fun run(start: SharedLabelState, allowMassDelete: Boolean = false): Outcome {
        if (!SharedLabelMembership.syncs(start.membership)) return Outcome(start)
        if (signer.sign(PROBE) == null) return Outcome(start.done(SharedRunResult.CANT_SIGN))
        val (listing, problem) = listing()
        if (listing == null) return Outcome(start.done(problem!!))
        var s = start
        if (s.oldKey != null) s = finishRotation(s, listing) ?: return Outcome(start.done(SharedRunResult.FOLDER_GONE))
        val h = header(listing, s.labelId) ?: return Outcome(s.done(SharedRunResult.NOT_A_LABEL))
        val membership = SharedLabelMembership.next(s.membership, Event.HeaderRead(h.epoch, SharedLabelCrypto.opens(h, s.key)))
        if (membership !is State.Active) return Outcome(s.copy(membership = membership).done(SharedRunResult.KEY_CHANGED))
        // A label deleted or renamed elsewhere on this phone must not read as "everyone was removed".
        if (!local.labelExists(s.title)) return Outcome(s.done(SharedRunResult.LABEL_GONE))
        val labelMembers = local.members(s.title)
        val now = clock()
        var rep = SharedRunReport()

        // Members and their history.
        val journals = journals(listing, s.labelId, s.key).filter { it.memberHex != me }
        val members = SharedLabelRoster.members(s.labelId, s.epoch, s.anchor, s.anchorName, journals + myJournal(s))
        val memberKeys = members.map { it.keyHex }.toSet()
        val names = members.associate { it.keyHex to it.name }
        val theirLines = journals.filter { it.memberHex in memberKeys }.flatMap { SharedLabelHistory.of(it, names[it.memberHex] ?: it.name) }
        var history = SharedLabelHistory.merge(s.history, theirLines)
        val journal = ArrayList(s.journal)
        var nextEntry = (journal.maxOfOrNull { it.id } ?: 0L) + 1
        fun log(sid: String, kind: ChangeKind, fields: Set<CardField>, name: String) {
            if (kind == ChangeKind.EDITED && fields.isEmpty()) return
            journal += JournalEntry(nextEntry++, sid, kind, fields, name, now)
        }

        // Contact files: only those whose stamp moved are opened.
        val stamps = HashMap(s.stamps.filterKeys { it in listing })
        val reads = HashMap<String, Read>()

        // Read before, unchanged since, and known: nothing to open.
        fun unchanged(name: String, stamp: String?, sid: String) = stamp != null && s.stamps[name] == stamp && (sid in s.entries || sid in s.seen)
        for ((name, stamp) in listing) {
            val sid = SharedLabelFiles.sidOf(name)?.takeIf { !unchanged(name, stamp, it) } ?: continue
            val file = folder.read(name)?.let { SharedLabelCrypto.open(s.key, s.labelId, name, it) }?.let { SharedLabelFiles.readCard(s.labelId, name, it) }
                ?.takeIf { it.authorHex in memberKeys }
            reads[sid] = Read(name, file)
            if (stamp != null && file != null) stamps[name] = stamp
        }

        // This phone's label members by id; each synced entry finds its contact again by id or key.
        val byId = labelMembers.associateBy { it.id }
        val byKey = labelMembers.associateBy { it.key }
        val entries = HashMap(s.entries)
        val seen = HashMap(s.seen)
        val pending = HashMap(s.pending)
        val mapped = HashMap<String, LabelContacts.Member>()
        for ((sid, e) in entries) {
            val m = byId[e.contactId]?.takeIf { it.key == e.key } ?: byKey[e.key] ?: local.idFor(e.key, e.contactId)?.let { byId[it] }
            if (m != null) mapped[sid] = m
        }

        fun remoteOf(sid: String, e: SharedLabelState.Entry): Pair<Remote, CardFile?> {
            val name = SharedLabelFiles.cardName(sid)
            if (name !in listing) return Remote.MISSING to null
            val r = reads[sid] ?: return Remote.UNCHANGED to null // stamp unchanged
            val f = r.file ?: return Remote.UNREADABLE to null
            return SharedLabelRules.remote(f.version, f.deleted, readable = true, lastVersion = e.ver, seenVersion = seen[sid]) to f
        }

        // First pass: decisions, then the guard against deleting a lot at once.
        val plan = LinkedHashMap<String, Triple<Action, Local, CardFile?>>()
        for ((sid, e) in entries) {
            val m = mapped[sid]
            val localState = when {
                m == null -> Local.GONE
                SharedCards.hash(m.card) != e.baseHash -> Local.CHANGED
                else -> Local.UNCHANGED
            }
            val (remote, file) = remoteOf(sid, e)
            plan[sid] = Triple(SharedLabelRules.decide(localState, remote), localState, file)
        }
        val deletions = plan.values.count { it.first == Action.DELETE_LOCAL || it.first == Action.PUBLISH_TOMBSTONE }
        if (!allowMassDelete && SharedLabelRules.mustConfirm(deletions, entries.size)) {
            return Outcome(s.copy(members = members, history = history).done(SharedRunResult.PAUSED, deletions))
        }

        fun version(sid: String, vararg also: Long) = SharedLabelRules.nextVersion(maxOf(seen[sid] ?: 0L, entries[sid]?.ver ?: 0L, also.maxOrNull() ?: 0L), now)

        suspend fun settle(sid: String, id: Long, ver: Long, imported: Boolean): Boolean {
            val after = local.card(id) ?: return false
            entries[sid] = SharedLabelState.Entry(after.key, id, ver, SharedCards.encode(after.card), SharedCards.hash(after.card), imported)
            seen[sid] = maxOf(seen[sid] ?: 0L, ver)
            return true
        }

        fun publish(sid: String, m: LabelContacts.Member, base: ContactRecord?, imported: Boolean, kind: ChangeKind, vararg also: Long): Boolean {
            val ver = version(sid, *also)
            val stamp = writeCard(s, sid, ver, m.card) ?: return false
            stamps[SharedLabelFiles.cardName(sid)] = stamp
            entries[sid] = SharedLabelState.Entry(m.key, m.id, ver, SharedCards.encode(m.card), SharedCards.hash(m.card), imported)
            seen[sid] = ver
            log(sid, kind, SharedCards.changedFields(base, m.card), m.card.displayName)
            rep = rep.copy(written = rep.written + 1)
            return true
        }

        fun authorName(f: CardFile) = names[f.authorHex] ?: ""

        // Applies [merged] here when it differs, then writes it when it differs from the folder's.
        suspend fun mergeInto(sid: String, m: LabelContacts.Member, base: ContactRecord?, theirs: CardFile, imported: Boolean) {
            val theirsText = theirs.card ?: return
            val theirsCard = SharedCards.decode(theirsText) ?: return
            val merge = SharedCards.merge(base, m.card, theirsCard)
            var id = m.id
            if (SharedCards.hash(merge.card) != SharedCards.hash(m.card)) id = local.apply(m.id, merge.card) ?: return
            if (merge.conflicts.isNotEmpty()) {
                // Waits for the user; the entry keeps the version in common, so the next run merges again.
                val by = authorName(theirs).ifEmpty { pending[sid]?.authorName.orEmpty() }
                pending[sid] = SharedLabelState.Pending(theirsText, theirs.version, by, merge.conflicts)
                entries[sid] = (entries[sid] ?: SharedLabelState.Entry(m.key, id, 0, "", "", imported)).copy(contactId = id)
                seen[sid] = maxOf(seen[sid] ?: 0L, theirs.version)
                rep = rep.copy(conflicts = rep.conflicts + 1)
                return
            }
            pending.remove(sid)
            val after = local.card(id) ?: return
            if (SharedCards.hash(merge.card) != SharedCards.hash(theirsCard)) {
                publish(sid, after, theirsCard, imported, ChangeKind.EDITED, theirs.version)
            } else {
                settle(sid, id, theirs.version, imported)
            }
            rep = rep.copy(applied = rep.applied + 1)
        }

        // Second pass: act.
        for ((sid, p) in plan) {
            val (action, _, file) = p
            val e = entries[sid] ?: continue
            val m = mapped[sid]
            val base = e.base.takeIf { it.isNotEmpty() }?.let(SharedCards::decode)
            // A contact waiting for a choice: merged again against the newest version from the folder.
            val waiting = pending[sid]
            if (waiting != null && m != null) {
                val theirs = file?.takeIf { !it.deleted && it.version > waiting.ver }
                    ?: CardFile(sid, waiting.ver, 0, ByteArray(32), false, waiting.theirs, "", ByteArray(0))
                mergeInto(sid, m, base, theirs, e.imported)
                continue
            }
            if (waiting != null) pending.remove(sid)
            when (action) {
                Action.NONE -> Unit
                Action.PUBLISH -> if (m != null) publish(sid, m, base, e.imported, ChangeKind.EDITED, file?.version ?: 0L)
                Action.APPLY -> {
                    val f = file ?: continue
                    val card = f.card?.let(SharedCards::decode) ?: continue
                    val id = local.apply(m!!.id, card) ?: continue
                    if (settle(sid, id, f.version, e.imported)) rep = rep.copy(applied = rep.applied + 1)
                }
                Action.MERGE -> mergeInto(sid, m!!, base, file ?: continue, e.imported)
                Action.PUBLISH_TOMBSTONE -> {
                    val ver = version(sid, file?.version ?: 0L)
                    writeCard(s, sid, ver, null)?.let { stamps[SharedLabelFiles.cardName(sid)] = it } ?: continue
                    entries.remove(sid)
                    seen[sid] = ver
                    log(sid, ChangeKind.REMOVED, emptySet(), base?.displayName.orEmpty())
                    rep = rep.copy(written = rep.written + 1)
                }
                Action.DELETE_LOCAL -> {
                    val id = m?.id ?: continue
                    // A contact the label brought goes (History & undo keeps it); one that was already yours only leaves the label.
                    if (e.imported) local.delete(id) else local.removeFromLabel(s.title, id)
                    entries.remove(sid)
                    seen[sid] = maxOf(seen[sid] ?: 0L, file?.version ?: 0L)
                    rep = rep.copy(deleted = rep.deleted + 1)
                }
                Action.IMPORT_AGAIN -> {
                    val f = file ?: continue
                    val card = f.card?.let(SharedCards::decode) ?: continue
                    // Still on this phone (only taken out of the label): back in, updated; else added again.
                    val existing = local.idFor(e.key, e.contactId)
                    val id = if (existing != null && local.addToLabel(s.title, existing)) local.apply(existing, card) else local.import(s.title, card)
                    if (id != null && settle(sid, id, f.version, e.imported || existing == null)) rep = rep.copy(imported = rep.imported + 1)
                }
                Action.FORGET -> {
                    entries.remove(sid)
                    seen[sid] = maxOf(seen[sid] ?: 0L, file?.version ?: 0L)
                }
            }
        }

        // Contacts new in the folder: joined to a contact with the same number or e-mail, else added.
        val taken = HashSet(entries.values.map { it.contactId })
        for ((sid, r) in reads) {
            if (sid in entries) continue
            val f = r.file ?: continue
            if (!SharedLabelRules.isNewContact(f.version, f.deleted, seen[sid])) {
                seen[sid] = maxOf(seen[sid] ?: 0L, f.version)
                continue
            }
            val card = f.card?.let(SharedCards::decode) ?: continue
            val keys = SharedCards.matchKeys(card)
            val inLabel = labelMembers.firstOrNull { it.id !in taken && SharedCards.matchKeys(it.card).any { k -> k in keys } }
            val match = inLabel ?: local.findMatch(card, taken)?.let { local.card(it) }
            if (match != null) {
                if (inLabel == null && !local.addToLabel(s.title, match.id)) continue
                taken += match.id
                mergeInto(sid, match, null, f, imported = false)
                if (sid in entries) rep = rep.copy(linked = rep.linked + 1)
            } else {
                val id = local.import(s.title, card) ?: continue
                taken += id
                if (settle(sid, id, f.version, imported = true)) rep = rep.copy(imported = rep.imported + 1)
            }
        }

        // Contacts new in the label here: shared under a new id.
        for (m in labelMembers) {
            if (m.id in taken || mapped.values.any { it.id == m.id }) continue
            val sid = SharedLabelFiles.newId()
            publish(sid, m, null, imported = false, kind = ChangeKind.ADDED)
            taken += m.id
        }

        var out = s.copy(
            membership = membership, entries = entries, seen = seen, pending = pending, journal = journal.takeLast(SharedLabelFiles.MAX_ENTRIES),
            members = members, stamps = stamps, privateLeftOut = local.privateMembers(s.title),
        )
        // This phone's journal: when it has news, or when it is missing from the folder.
        val journalName = SharedLabelFiles.journalName(signer.publicKey)
        if (journal.size != s.journal.size || journalName !in listing) writeJournal(out)
        history = SharedLabelHistory.merge(history, out.journal.map { HistoryItem(me, s.myName, it.id, it.sid, it.contactName, it.kind, it.fields, it.at) })
        out = out.copy(history = history)
        if (rep != SharedRunReport()) local.changed()
        return Outcome(out.done(SharedRunResult.SYNCED), rep)
    }

    // ---------------------------------------------------------------- choices, key changes, leaving

    /** The user's choice for a contact changed on two phones: applied here, written to the folder. */
    suspend fun resolve(s: SharedLabelState, sid: String, picks: Map<CardField, Side>): SharedLabelState? {
        val p = s.pending[sid] ?: return null
        val e = s.entries[sid] ?: return null
        val listing = listing().first ?: return null
        if (header(listing, s.labelId)?.let { SharedLabelCrypto.opens(it, s.key) } != true) return null
        val mine = local.idFor(e.key, e.contactId)?.let { local.card(it) } ?: return null
        val theirs = SharedCards.decode(p.theirs) ?: return null
        val merged = SharedCards.merge(e.base.takeIf { it.isNotEmpty() }?.let(SharedCards::decode), mine.card, theirs, picks)
        val id = local.apply(mine.id, merged.card) ?: return null
        val after = local.card(id) ?: return null
        val ver = SharedLabelRules.nextVersion(maxOf(s.seen[sid] ?: 0L, p.ver), clock())
        val stamp = writeCard(s, sid, ver, after.card) ?: return null
        val entry = SharedLabelState.Entry(after.key, id, ver, SharedCards.encode(after.card), SharedCards.hash(after.card), e.imported)
        val nextId = (s.journal.maxOfOrNull { it.id } ?: 0L) + 1
        val journal = s.journal + JournalEntry(nextId, sid, ChangeKind.EDITED, SharedCards.changedFields(theirs, after.card), after.card.displayName, clock())
        val out = s.copy(
            entries = s.entries + (sid to entry), seen = s.seen + (sid to ver), pending = s.pending - sid, journal = journal,
            stamps = s.stamps + (SharedLabelFiles.cardName(sid) to stamp),
        )
        writeJournal(out)
        local.changed()
        return out
    }

    /**
     * Removes [removed] (member key hashes) by changing the label's key to one from [passphrase]: this phone becomes
     * the anchor, carrying everyone else. The folder is re-encrypted by [finishRotation], now and, if this is cut short,
     * on the next run.
     */
    suspend fun removeMembers(s: SharedLabelState, removed: Set<String>, passphrase: CharArray, kdf: KdfParams = BackupCrypto.DEFAULT_KDF): SharedLabelState? {
        if (s.membership !is State.Active || s.oldKey != null) return null
        val rotation = SharedLabelMembership.rotation(s.members, me, removed)
        val epoch = (SharedLabelMembership.next(s.membership, Event.KeyRotated) as State.Active).epoch
        val (header, key) = SharedLabelCrypto.newHeader(s.labelId, epoch, passphrase, kdf)
        val rotated = s.copy(
            key = key, oldKey = s.key, newHeader = header, membership = State.Active(epoch), anchor = signer.publicKey, anchorName = s.myName,
            ticket = null, carried = rotation.carried.map { app.parley.common.sync.shared.Carried(it.key, it.name) },
            members = s.members.filter { it.keyHex !in removed }
                .map { if (it.keyHex == me) it.copy(anchor = true, invitedBy = null) else it.copy(awaitingKey = true, invitedBy = null, anchor = false) },
            stamps = emptyMap(),
        )
        val listing = listing().first ?: return rotated
        return finishRotation(rotated, listing) ?: rotated
    }

    /**
     * Brings the folder to the new key: every file still sealed with the old one is sealed again (contact files last
     * written by someone who isn't staying are signed again by this phone), the new header is written, and other
     * members' old journals go. Idempotent; null when the folder couldn't be written.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private fun finishRotation(s: SharedLabelState, listing: Map<String, String?>): SharedLabelState? {
        val old = s.oldKey ?: return s
        val staying = s.carried.map { SharedLabelFiles.keyHex(it.key) }.toSet() + me
        for (name in listing.keys) {
            val sid = SharedLabelFiles.sidOf(name)
            if (sid == null && !SharedLabelFiles.isJournalName(name)) continue
            val bytes = folder.read(name) ?: continue
            if (SharedLabelCrypto.open(s.key, s.labelId, name, bytes) != null) continue
            val body = SharedLabelCrypto.open(old, s.labelId, name, bytes) ?: continue
            if (sid == null) {
                if (name != SharedLabelFiles.journalName(signer.publicKey)) folder.delete(name)
                continue
            }
            val f = SharedLabelFiles.readCard(s.labelId, name, body) ?: continue
            val signed = if (f.authorHex in staying) body else SharedLabelFiles.writeCard(signer, s.labelId, sid, f.version, f.at, f.card) ?: continue
            folder.write(name, SharedLabelCrypto.seal(s.key, s.labelId, name, signed)) ?: return null
        }
        folder.write(SharedLabelCrypto.HEADER_NAME, s.newHeader ?: return null) ?: return null
        writeJournal(s) ?: return null
        old.fill(0)
        return s.copy(oldKey = null, newHeader = null, stamps = emptyMap())
    }

    /** Leaving: this phone's journal says so, so the others see it; the caller then forgets the label. */
    suspend fun leave(s: SharedLabelState): Boolean {
        if (s.membership !is State.Active) return true
        val listing = listing().first ?: return false
        if (header(listing, s.labelId)?.let { SharedLabelCrypto.opens(it, s.key) } != true) return true
        return writeJournal(s, left = true) != null
    }

    private companion object {
        val PROBE = "PARLEY-LABEL-PROBE".toByteArray()
    }
}
