package app.parley.data.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.RecordJson
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
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.sync.shared.SharedLabelMembership.Event
import app.parley.common.sync.shared.SharedLabelMembership.State
import app.parley.common.sync.shared.SharedLabelRoster
import app.parley.common.sync.shared.SharedLabelRules
import app.parley.common.sync.shared.SharedLabelRules.Action
import app.parley.common.sync.shared.SharedLabelRules.Local
import app.parley.common.sync.shared.SharedLabelRules.Remote
import app.parley.common.sync.shared.SharedLabelUpdates

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

    private fun header(listing: Map<String, String?>, labelId: String): SharedLabelCrypto.Header? = headerBytes(listing)?.let { parse(it, labelId) }

    private fun headerBytes(listing: Map<String, String?>): ByteArray? =
        if (SharedLabelCrypto.HEADER_NAME in listing) folder.read(SharedLabelCrypto.HEADER_NAME) else null

    private fun parse(bytes: ByteArray, labelId: String): SharedLabelCrypto.Header? =
        runCatching { SharedLabelCrypto.parseHeader(bytes) }.getOrNull()?.takeIf { it.labelId == labelId }

    /** What the folder's header means for this phone (M3). */
    private sealed interface HeaderCheck {
        /** This phone's key opens it at its epoch: go on, and keep it as the last good header. */
        class Ok(val bytes: ByteArray) : HeaderCheck

        /** A member signed a change of key to [epoch]: nothing syncs here until a new invitation. */
        data class Changed(val epoch: Int) : HeaderCheck

        /**
         * Not this phone's header, and no member signed a key change: the last good header stays, with a warning.
         * [restore]: the folder's is missing, damaged or not newer, which a real key change never leaves: the last good
         * one is written back (a newer one waits: its signed note may still be on its way).
         */
        data class Suspect(val restore: Boolean) : HeaderCheck

        /** No usable header, and none kept. */
        data object None : HeaderCheck
    }

    /**
     * M3: anyone who can write to the folder can replace `.parley-label`. A header this phone's key doesn't open is a
     * key change only when a member signed it ([SharedLabelCrypto.headerSigName], sealed with this phone's key, so
     * only someone who had it could write it); otherwise this phone keeps to the header it last accepted, and never
     * moves to a lower epoch. A state kept before headers were remembered follows the old rule once.
     */
    private fun checkHeader(s: SharedLabelState, listing: Map<String, String?>): HeaderCheck {
        val bytes = headerBytes(listing)
        val h = bytes?.let { parse(it, s.labelId) }
        if (h != null && h.epoch == s.epoch && SharedLabelCrypto.opens(h, s.key)) return HeaderCheck.Ok(bytes)
        if (h != null && h.epoch > s.epoch && signedKeyChange(s, listing, h.epoch, bytes)) return HeaderCheck.Changed(h.epoch)
        if (s.header == null) return if (h == null) HeaderCheck.None else HeaderCheck.Changed(h.epoch)
        return HeaderCheck.Suspect(restore = h == null || h.epoch <= s.epoch)
    }

    /** Whether a member this phone knows signed the key change away from this phone's epoch (and, when it is the header's own epoch, this very header). */
    private fun signedKeyChange(s: SharedLabelState, listing: Map<String, String?>, epoch: Int, header: ByteArray): Boolean {
        val name = SharedLabelCrypto.headerSigName(s.epoch)
        if (name !in listing) return false
        val body = folder.read(name)?.let { SharedLabelCrypto.open(s.key, s.labelId, name, it) } ?: return false
        val sig = SharedLabelFiles.readHeaderSig(s.labelId, body) ?: return false
        val signers = s.members.map { it.keyHex }.toSet() + SharedLabelFiles.keyHex(s.anchor)
        return sig.signerHex in signers && sig.epoch in (s.epoch + 1)..epoch && (sig.epoch < epoch || sig.headerHash == SharedLabelFiles.headerHash(header))
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
        labelId: String = SharedLabelFiles.newId(),
    ): SharedLabelState? {
        if (checkEmpty() != null) return null
        val (header, key) = SharedLabelCrypto.newHeader(labelId, 1, passphrase, kdf)
        folder.write(SharedLabelCrypto.HEADER_NAME, header) ?: return null
        return SharedLabelState(
            labelId = labelId, title = title, folderUri = folderUri, folderName = folderName, key = key,
            anchor = signer.publicKey, anchorName = myName, myName = myName, ticket = null, membership = State.Active(1), header = header,
        )
    }

    /** An invitation from this phone: a fresh ticket signed now. Null when the key can't sign right now. */
    fun invitation(s: SharedLabelState, folderHint: String): Invitation? {
        val epoch = (s.membership as? State.Active)?.epoch ?: return null
        val ticket = SharedLabelFiles.ticket(signer, s.labelId, epoch, SharedLabelFiles.newId(), clock() + SharedLabelInvites.TTL_MS) ?: return null
        return Invitation(s.labelId, s.title, folderHint, epoch, s.key.copyOf(), s.anchor, s.anchorName, signer.publicKey, s.myName, ticket)
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
        return Preview.Ready(SharedLabelRoster.members(i.labelId, i.epoch, i.anchor, i.anchorName, journals(listing, i.labelId, i.key), clock()))
    }

    /**
     * Joins with [i] (checked with [preview] first): a new state, or [existing] (the same label after its key changed,
     * or left before) brought back with the new key, keeping what it synced.
     */
    fun join(i: Invitation, folderUri: String, folderName: String, title: String, myName: String, existing: SharedLabelState?): SharedLabelState {
        val base = existing?.takeIf { it.labelId == i.labelId }
        val membership = SharedLabelMembership.next(base?.membership ?: State.Left, Event.InvitationOpened(i.epoch))
        val ticket = if (i.inviter.contentEquals(signer.publicKey)) null else i.ticket
        // The key is this state's own copy: a key change wipes the old one in memory (finishRotation).
        val key = i.key.copyOf()
        return (base ?: SharedLabelState(i.labelId, title, folderUri, folderName, key, i.anchor, i.anchorName, myName, ticket, membership = membership))
            .copy(
                folderUri = folderUri, folderName = folderName, key = key, anchor = i.anchor, anchorName = i.anchorName, myName = myName,
                ticket = ticket, membership = membership,
                // A new key: what was kept about the folder under the old one doesn't apply.
                header = null, headerWarning = false, junk = emptyMap(), unreadable = emptyMap(),
            )
    }

    // ---------------------------------------------------------------- reading the folder

    /**
     * The journals in the folder, checked. L7: members' own journals first, then at most [MAX_JOURNALS] in all, and a
     * file that couldn't be used before ([junk], same stamp) isn't opened again: a folder writer can't make every run
     * read thousands of files. [newJunk] collects what couldn't be used this time.
     */
    private fun journals(
        listing: Map<String, String?>,
        labelId: String,
        key: ByteArray,
        known: Collection<ByteArray> = emptyList(),
        junk: Map<String, String> = emptyMap(),
        newJunk: MutableMap<String, String>? = null,
    ): List<Journal> {
        val names = listing.keys.filter(SharedLabelFiles::isJournalName)
        val first = known.map(SharedLabelFiles::journalName).distinct().filter { it in listing }
        val ordered = (first + (names - first.toSet()).sorted()).take(MAX_JOURNALS)
        return ordered.mapNotNull { name ->
            val stamp = listing[name]
            if (stamp != null && junk[name] == stamp) {
                newJunk?.put(name, stamp)
                return@mapNotNull null
            }
            val j = folder.read(name)?.let { SharedLabelCrypto.open(key, labelId, name, it) }?.let { SharedLabelFiles.readJournal(labelId, name, it) }
            if (j == null && stamp != null) newJunk?.put(name, stamp)
            j
        }
    }

    private fun myJournal(s: SharedLabelState, left: Boolean = false) = Journal(
        signer.publicKey, s.myName, s.epoch, s.ticket, if (s.anchor.contentEquals(signer.publicKey)) s.carried else emptyList(), left, s.journal,
        at = clock(),
    )

    private fun writeJournal(s: SharedLabelState, left: Boolean = false): String? =
        SharedLabelFiles.writeJournal(signer, s.labelId, myJournal(s, left))?.let { body ->
            val name = SharedLabelFiles.journalName(signer.publicKey)
            folder.write(name, SharedLabelCrypto.seal(s.key, s.labelId, name, body))
        }

    /** A contact file written: its stamp (the content's hash when the provider gives none) and what it signed. */
    private class Written(val stamp: String, val hash: String)

    private fun writeCard(s: SharedLabelState, sid: String, version: Long, card: ContactRecord?, parent: Long?): Written? =
        writeCardText(s, sid, version, card?.let(SharedCards::encode), parent)

    private fun writeCardText(s: SharedLabelState, sid: String, version: Long, card: String?, parent: Long? = null): Written? {
        val body = SharedLabelFiles.writeCard(signer, s.labelId, sid, version, clock(), card, parent) ?: return null
        val name = SharedLabelFiles.cardName(sid)
        val sealed = SharedLabelCrypto.seal(s.key, s.labelId, name, body)
        val stamp = folder.write(name, sealed) ?: return null
        return Written(stamp.ifEmpty { contentStamp(sealed) }, SharedLabelFiles.bodyHash(body).orEmpty())
    }

    // ---------------------------------------------------------------- the run

    /**
     * A contact file as this run read it: [file] null when it couldn't be opened (or was junk before); [member] whether
     * its author is a member now (a file signed by anyone else is never applied).
     */
    private class Read(val name: String, val file: CardFile?, val member: Boolean)

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    suspend fun run(start: SharedLabelState, allowMassDelete: Boolean = false): Outcome {
        if (!SharedLabelMembership.syncs(start.membership)) return Outcome(start)
        if (signer.sign(PROBE) == null) return Outcome(start.done(SharedRunResult.CANT_SIGN))
        val (firstListing, problem) = listing()
        if (firstListing == null) return Outcome(start.done(problem!!))
        var s = start
        var files: Map<String, String?> = firstListing
        if (s.oldKey != null) {
            s = finishRotation(s, files) ?: return Outcome(start.done(SharedRunResult.FOLDER_GONE))
            // The folder as the key change left it.
            files = listing().first ?: return Outcome(s.done(SharedRunResult.FOLDER_GONE))
        }
        val listing = files
        val headerCheck = checkHeader(s, listing)
        when (headerCheck) {
            HeaderCheck.None -> return Outcome(s.done(SharedRunResult.NOT_A_LABEL))
            is HeaderCheck.Changed -> {
                val membership = SharedLabelMembership.next(s.membership, Event.HeaderRead(headerCheck.epoch, keyOpens = false))
                return Outcome(s.copy(membership = membership, headerWarning = false).done(SharedRunResult.KEY_CHANGED))
            }
            is HeaderCheck.Suspect -> if (headerCheck.restore) s.header?.let { folder.write(SharedLabelCrypto.HEADER_NAME, it) }
            is HeaderCheck.Ok -> Unit
        }
        val membership = s.membership
        // A label deleted or renamed elsewhere on this phone must not read as "everyone was removed".
        if (!local.labelExists(s.title)) return Outcome(s.done(SharedRunResult.LABEL_GONE))
        val labelMembers = local.members(s.title)
        val now = clock()
        var rep = SharedRunReport()

        // Members and their history.
        val junk = HashMap<String, String>()
        val knownKeys = s.members.map { it.key } + s.anchor + signer.publicKey
        val journals = journals(listing, s.labelId, s.key, knownKeys, s.junk, junk).filter { it.memberHex != me }
        val known = s.members.map { it.keyHex }.toSet() + me
        val members = SharedLabelRoster.members(s.labelId, s.epoch, s.anchor, s.anchorName, journals + myJournal(s), now, known)
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

        // Contact files: only those whose stamp moved are opened, while the members stay the same. When they change,
        // every file is read once: one from a member who left must be seen to be signed again (M1), and junk (L7,
        // remembered by stamp) can turn out to be a new member's once their journal has arrived.
        val rosterSame = memberKeys == s.members.map { it.keyHex }.toSet()
        val cardJunk = if (rosterSame) s.junk else emptyMap()
        val stamps = HashMap(s.stamps.filterKeys { it in listing })
        val reads = HashMap<String, Read>()

        // Read before, unchanged since, and known: nothing to open.
        fun unchanged(name: String, stamp: String?, sid: String) =
            rosterSame && stamp != null && s.stamps[name] == stamp && (sid in s.entries || sid in s.seen)
        for ((name, listed) in listing) {
            val sid = SharedLabelFiles.sidOf(name) ?: continue
            // A provider without modified time and size (M1): the content's hash stands in for the stamp.
            var bytes: ByteArray? = null
            val stamp = listed ?: folder.read(name)?.also { bytes = it }?.let(::contentStamp)
            if (unchanged(name, stamp, sid)) continue
            if (stamp != null && cardJunk[name] == stamp) {
                junk[name] = stamp
                reads[sid] = Read(name, null, member = false)
                continue
            }
            val file = (bytes ?: folder.read(name))?.let { SharedLabelCrypto.open(s.key, s.labelId, name, it) }
                ?.let { SharedLabelFiles.readCard(s.labelId, name, it) }
                // L7: a version far ahead of the clock would make every later write lose to it.
                ?.takeIf { SharedLabelRules.plausibleVersion(it.version, now) }
            val member = file != null && file.authorHex in memberKeys
            reads[sid] = Read(name, file, member)
            if (stamp != null) if (member) stamps[name] = stamp else junk[name] = stamp
        }

        // This phone's label members by id; each synced entry finds its contact again by id or key.
        val byId = labelMembers.associateBy { it.id }
        val byKey = labelMembers.associateBy { it.key }
        val entries = HashMap(s.entries)
        val seen = HashMap(s.seen)
        val pending = HashMap(s.pending)
        val unreadable = HashMap(s.unreadable)
        val mapped = HashMap<String, LabelContacts.Member>()
        for ((sid, e) in entries) {
            val m = byId[e.contactId]?.takeIf { it.key == e.key } ?: byKey[e.key] ?: local.idFor(e.key, e.contactId)?.let { byId[it] }
            if (m != null) mapped[sid] = m
        }

        // M1: a file this phone accepted, now signed by someone who isn't a member any more (they left, or their key
        // changed): signed again by this phone exactly as accepted, so it stays readable for everyone, later members
        // included. Only the very file accepted (same version, same signed body); anything else isn't vouched for.
        fun accepted(e: SharedLabelState.Entry, f: CardFile) = e.fileHash.isNotEmpty() && f.version == e.ver && f.bodyHash == e.fileHash
        for ((sid, e) in entries) {
            val r = reads[sid] ?: continue
            val f = r.file ?: continue
            if (r.member || !accepted(e, f)) continue
            val w = writeCardText(s, sid, f.version, f.card, f.parent) ?: continue
            stamps[r.name] = w.stamp
            junk.remove(r.name)
            entries[sid] = e.copy(fileHash = w.hash)
            reads.remove(sid)
        }

        fun remoteOf(sid: String, e: SharedLabelState.Entry): Pair<Remote, CardFile?> {
            val name = SharedLabelFiles.cardName(sid)
            if (name !in listing) return Remote.MISSING to null
            val r = reads[sid] ?: return Remote.UNCHANGED to null // stamp unchanged
            val f = r.file?.takeIf { r.member }
            if (f == null) {
                // M2: unusable for a while, then written again from here (anyone with folder access could freeze it).
                val u = unreadable.getOrPut(sid) { SharedLabelState.Unreadable(now, stranger = r.file != null) }
                return SharedLabelRules.unreadable(u.since, now, u.stranger) to null
            }
            unreadable.remove(sid)
            val prior = e.prior.map { it.ver }
            return SharedLabelRules.remote(f.version, f.deleted, readable = true, lastVersion = e.ver, seenVersion = seen[sid], f.parent, prior) to f
        }

        // First pass: decisions, then the guard against deleting a lot at once.
        val plan = LinkedHashMap<String, Triple<Action, Remote, CardFile?>>()
        for ((sid, e) in entries) {
            val m = mapped[sid]
            val localState = when {
                m == null -> Local.GONE
                SharedCards.hash(m.card) != e.baseHash -> Local.CHANGED
                else -> Local.UNCHANGED
            }
            val (remote, file) = remoteOf(sid, e)
            plan[sid] = Triple(SharedLabelRules.decide(localState, remote), remote, file)
        }
        val deletions = plan.values.count { it.first == Action.DELETE_LOCAL || it.first == Action.PUBLISH_TOMBSTONE }
        if (!allowMassDelete && SharedLabelRules.mustConfirm(deletions, entries.size)) {
            return Outcome(s.copy(members = members, history = history).done(SharedRunResult.PAUSED, deletions))
        }

        fun version(sid: String, vararg also: Long) = SharedLabelRules.nextVersion(maxOf(seen[sid] ?: 0L, entries[sid]?.ver ?: 0L, also.maxOrNull() ?: 0L), now)

        fun written(sid: String, w: Written) {
            val name = SharedLabelFiles.cardName(sid)
            stamps[name] = w.stamp
            junk.remove(name)
            unreadable.remove(sid)
        }

        suspend fun settle(sid: String, id: Long, f: CardFile, imported: Boolean): Boolean {
            val after = local.card(id) ?: return false
            val prior = entries[sid]?.priorForNext().orEmpty()
            entries[sid] = SharedLabelState.Entry(after.key, id, f.version, SharedCards.encode(after.card), SharedCards.hash(after.card), imported, f.bodyHash, prior)
            seen[sid] = maxOf(seen[sid] ?: 0L, f.version)
            return true
        }

        /**
         * Writes [m] as the sid's next version. [parent]: the version it was made from (this phone's synced one, or the
         * member's version merged in); [theirs]: that merged version, remembered as one this phone held.
         */
        @Suppress("LongParameterList")
        fun publish(
            sid: String,
            m: LabelContacts.Member,
            base: ContactRecord?,
            imported: Boolean,
            kind: ChangeKind,
            also: Long = 0L,
            parent: Long? = entries[sid]?.ver,
            theirs: SharedLabelState.Prior? = null,
        ): Boolean {
            val ver = version(sid, also)
            val w = writeCard(s, sid, ver, m.card, parent) ?: return false
            written(sid, w)
            val prior = entries[sid]?.priorForNext(theirs) ?: listOfNotNull(theirs)
            entries[sid] = SharedLabelState.Entry(m.key, m.id, ver, SharedCards.encode(m.card), SharedCards.hash(m.card), imported, w.hash, prior)
            seen[sid] = ver
            log(sid, kind, SharedCards.changedFields(base, m.card), m.card.displayName)
            rep = rep.copy(written = rep.written + 1)
            return true
        }

        fun authorName(f: CardFile) = names[f.authorHex] ?: ""

        // Applies [merged] here when it differs, then writes it when it differs from the folder's. [olderBase]: [base]'s
        // text when it is a version before the synced one (an edit made alongside), so a choice later uses it too.
        suspend fun mergeInto(sid: String, m: LabelContacts.Member, base: ContactRecord?, theirs: CardFile, imported: Boolean, olderBase: String = "") {
            val theirsText = theirs.card ?: return
            val theirsCard = SharedCards.decode(theirsText) ?: return
            val merge = SharedCards.merge(base, m.card, theirsCard)
            var id = m.id
            if (SharedCards.hash(merge.card) != SharedCards.hash(m.card)) id = local.apply(m.id, merge.card) ?: return
            if (merge.conflicts.isNotEmpty()) {
                // Waits for the user; the entry keeps the version in common, so the next run merges again.
                val by = authorName(theirs).ifEmpty { pending[sid]?.authorName.orEmpty() }
                pending[sid] = SharedLabelState.Pending(theirsText, theirs.version, by, merge.conflicts, olderBase)
                entries[sid] = (entries[sid] ?: SharedLabelState.Entry(m.key, id, 0, "", "", imported)).copy(contactId = id)
                seen[sid] = maxOf(seen[sid] ?: 0L, theirs.version)
                rep = rep.copy(conflicts = rep.conflicts + 1)
                return
            }
            pending.remove(sid)
            val after = local.card(id) ?: return
            if (SharedCards.hash(merge.card) != SharedCards.hash(theirsCard)) {
                val held = SharedLabelState.Prior(theirs.version, theirsText).takeIf { theirs.version > 0 }
                publish(sid, after, theirsCard, imported, ChangeKind.EDITED, theirs.version, parent = theirs.version.takeIf { it > 0 }, theirs = held)
            } else {
                settle(sid, id, theirs, imported)
            }
            rep = rep.copy(applied = rep.applied + 1)
        }

        // Second pass: act.
        for ((sid, p) in plan) {
            val (action, remote, file) = p
            val e = entries[sid] ?: continue
            val m = mapped[sid]
            val base = e.base.takeIf { it.isNotEmpty() }?.let(SharedCards::decode)
            // A contact waiting for a choice: merged again against the newest version from the folder.
            val waiting = pending[sid]
            if (waiting != null && m != null) {
                val newer = file?.takeIf { !it.deleted && it.version > waiting.ver }
                val theirs = newer ?: CardFile(sid, waiting.ver, 0, ByteArray(32), false, waiting.theirs, "", ByteArray(0))
                val older = waiting.base.takeIf { newer == null }.orEmpty()
                mergeInto(sid, m, older.takeIf { it.isNotEmpty() }?.let(SharedCards::decode) ?: base, theirs, e.imported, older)
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
                    if (settle(sid, id, f, e.imported)) rep = rep.copy(applied = rep.applied + 1)
                }
                Action.MERGE -> {
                    val f = file ?: continue
                    // An edit made alongside this phone's copy merges against the version both started from.
                    val older = if (remote == Remote.CONCURRENT) e.prior.firstOrNull { it.ver == f.parent }?.base.orEmpty() else ""
                    mergeInto(sid, m!!, older.takeIf { it.isNotEmpty() }?.let(SharedCards::decode) ?: base, f, e.imported, older)
                }
                Action.PUBLISH_TOMBSTONE -> {
                    val ver = version(sid, file?.version ?: 0L)
                    written(sid, writeCard(s, sid, ver, null, parent = e.ver) ?: continue)
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
                    if (id != null && settle(sid, id, f, e.imported || existing == null)) rep = rep.copy(imported = rep.imported + 1)
                }
                Action.FORGET -> {
                    entries.remove(sid)
                    seen[sid] = maxOf(seen[sid] ?: 0L, file?.version ?: 0L)
                }
            }
        }

        // Contacts new in the folder: added to the label as new contacts. H1: a card is only ever joined to a contact
        // already in this label and not shared yet (same number or e-mail), never to one elsewhere in the address
        // book: a member could otherwise pull any contact of yours into the label, and learn it from the copy published.
        val taken = HashSet(entries.values.map { it.contactId } + mapped.values.map { it.id })
        for ((sid, r) in reads) {
            if (sid in entries) continue
            val f = r.file?.takeIf { r.member } ?: continue
            if (!SharedLabelRules.isNewContact(f.version, f.deleted, seen[sid])) {
                seen[sid] = maxOf(seen[sid] ?: 0L, f.version)
                continue
            }
            val card = f.card?.let(SharedCards::decode) ?: continue
            val keys = SharedCards.matchKeys(card)
            val inLabel = labelMembers.firstOrNull { it.id !in taken && SharedCards.matchKeys(it.card).any { k -> k in keys } }
            if (inLabel != null) {
                taken += inLabel.id
                mergeInto(sid, inLabel, null, f, imported = false)
                if (sid in entries) rep = rep.copy(linked = rep.linked + 1)
            } else {
                val id = local.import(s.title, card) ?: continue
                taken += id
                if (settle(sid, id, f, imported = true)) rep = rep.copy(imported = rep.imported + 1)
            }
        }

        // Contacts new in the label here: shared under a new id.
        for (m in labelMembers) {
            if (m.id in taken) continue
            val sid = SharedLabelFiles.newId()
            publish(sid, m, null, imported = false, kind = ChangeKind.ADDED, parent = null)
            taken += m.id
        }

        var out = s.copy(
            membership = membership, entries = entries, seen = seen, pending = pending, journal = journal.takeLast(SharedLabelFiles.MAX_ENTRIES),
            members = members, stamps = stamps, privateLeftOut = local.privateMembers(s.title),
            unreadable = unreadable.filterKeys { it in entries }, junk = junk,
            header = (headerCheck as? HeaderCheck.Ok)?.bytes ?: s.header, headerWarning = headerCheck is HeaderCheck.Suspect,
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

    /** Whether the folder's header lets this phone write now: its own, or a suspect one it keeps ignoring (M3). */
    private fun writable(s: SharedLabelState, listing: Map<String, String?>): Boolean = when (checkHeader(s, listing)) {
        is HeaderCheck.Ok, is HeaderCheck.Suspect -> true
        else -> false
    }

    /** The user's choice for a contact changed on two phones: applied here, written to the folder. */
    suspend fun resolve(s: SharedLabelState, sid: String, picks: Map<CardField, Side>): SharedLabelState? {
        val p = s.pending[sid] ?: return null
        val e = s.entries[sid] ?: return null
        val listing = listing().first ?: return null
        if (!writable(s, listing)) return null
        val mine = local.idFor(e.key, e.contactId)?.let { local.card(it) } ?: return null
        val theirs = SharedCards.decode(p.theirs) ?: return null
        val from = p.base.ifEmpty { e.base }
        val merged = SharedCards.merge(from.takeIf { it.isNotEmpty() }?.let(SharedCards::decode), mine.card, theirs, picks)
        val id = local.apply(mine.id, merged.card) ?: return null
        val after = local.card(id) ?: return null
        val ver = SharedLabelRules.nextVersion(maxOf(s.seen[sid] ?: 0L, p.ver), clock())
        val w = writeCard(s, sid, ver, after.card, parent = p.ver.takeIf { it > 0 }) ?: return null
        val prior = e.priorForNext(SharedLabelState.Prior(p.ver, p.theirs).takeIf { p.ver > 0 })
        val entry = SharedLabelState.Entry(after.key, id, ver, SharedCards.encode(after.card), SharedCards.hash(after.card), e.imported, w.hash, prior)
        val nextId = (s.journal.maxOfOrNull { it.id } ?: 0L) + 1
        val journal = s.journal + JournalEntry(nextId, sid, ChangeKind.EDITED, SharedCards.changedFields(theirs, after.card), after.card.displayName, clock())
        val out = s.copy(
            entries = s.entries + (sid to entry), seen = s.seen + (sid to ver), pending = s.pending - sid, journal = journal,
            stamps = s.stamps + (SharedLabelFiles.cardName(sid) to w.stamp),
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
     * Brings the folder to the new key. H2: the contact files are rebuilt from what this phone itself accepted (its
     * synced entries and the deletions it saw), signed by this phone; nothing is taken from the folder's files. A file
     * nobody here accepted (planted by someone who still has the old key, the removed member included, before or
     * during the change) stays sealed with the old key: unreadable from now on, and ignored. On a retry this works
     * from the same record, not from what the folder holds by then. Then the signed note of the key change (sealed with
     * the old key, so members who still hold it can tell a real change from a swapped header, M3), the new header, and
     * this phone's journal; other members' old journals go. Idempotent; null when the folder couldn't be written.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "ReturnCount")
    private fun finishRotation(s: SharedLabelState, listing: Map<String, String?>): SharedLabelState? {
        val old = s.oldKey ?: return s
        val header = s.newHeader ?: return null
        val entries = HashMap(s.entries)
        val stamps = HashMap<String, String>()
        for ((sid, e) in s.entries) {
            // Nothing accepted yet (a first match waiting for a choice): written when the choice is made.
            if (e.base.isEmpty()) continue
            val w = writeCardText(s, sid, e.ver, e.base) ?: return null
            stamps[SharedLabelFiles.cardName(sid)] = w.stamp
            entries[sid] = e.copy(fileHash = w.hash)
        }
        for ((sid, ver) in s.seen) {
            if (sid in s.entries) continue
            // A deletion this phone saw: written again, so members who come back with the new key still delete it.
            val w = writeCardText(s, sid, ver, null) ?: return null
            stamps[SharedLabelFiles.cardName(sid)] = w.stamp
        }
        for (name in listing.keys.filter(SharedLabelFiles::isJournalName)) {
            if (name == SharedLabelFiles.journalName(signer.publicKey)) continue
            val bytes = folder.read(name) ?: continue
            if (SharedLabelCrypto.open(old, s.labelId, name, bytes) != null) folder.delete(name)
        }
        val oldEpoch = s.epoch - 1
        val sigName = SharedLabelCrypto.headerSigName(oldEpoch)
        val sig = SharedLabelFiles.writeHeaderSig(signer, s.labelId, s.epoch, header) ?: return null
        folder.write(sigName, SharedLabelCrypto.seal(old, s.labelId, sigName, sig)) ?: return null
        folder.write(SharedLabelCrypto.HEADER_NAME, header) ?: return null
        writeJournal(s) ?: return null
        old.fill(0)
        return s.copy(
            oldKey = null, newHeader = null, entries = entries, stamps = stamps, header = header, headerWarning = false,
            junk = emptyMap(), unreadable = emptyMap(),
        )
    }

    // ---------------------------------------------------------------- update files

    /** Writes this phone's journal into the label's files: a member who joined by file sends it with their first update. */
    fun introduce(s: SharedLabelState): Boolean = writeJournal(s) != null

    /**
     * An update file with the label's files as this phone's folder holds them (docs/SHARED_LABELS.md, "Sharing by
     * file"): the header, the key-change notes, and the contact files and journals sealed with the current key (junk
     * stays out). Null when the folder can't be listed, the label isn't active here, or the key can't sign now.
     */
    suspend fun updateFile(s: SharedLabelState, sentAt: Long = clock()): ByteArray? {
        val epoch = (s.membership as? State.Active)?.epoch ?: return null
        val listing = listing().first ?: return null
        val files = LinkedHashMap<String, ByteArray>()
        for (name in listing.keys.filter(SharedLabelUpdates::isLabelFile).sorted()) {
            if (files.size >= SharedLabelUpdates.MAX_FILES) break
            val bytes = folder.read(name) ?: continue
            val current = name == SharedLabelCrypto.HEADER_NAME || name.startsWith(SIG_PREFIX) || SharedLabelCrypto.open(s.key, s.labelId, name, bytes) != null
            if (current) files[name] = bytes
        }
        return SharedLabelUpdates.write(signer, s.key, s.labelId, epoch, s.myName, sentAt, files)?.takeIf { it.size <= SharedLabelUpdates.MAX_SEALED }
    }

    /** Why an update file wasn't merged, or that it was. */
    enum class UpdateResult {
        MERGED,

        /** Not an update file. */
        NOT_AN_UPDATE,

        /** For another shared label. */
        OTHER_LABEL,

        /** Sealed with another key than this phone's (a forged or altered file). */
        WRONG_KEY,

        /** Made before the label's key changed: ask for a new one. */
        OLDER_KEY,

        /** Made after the label's key changed: this phone needs a new invitation first. */
        NEWER_KEY,

        /** Altered, or not signed by the member it names. */
        DAMAGED,

        /** Opened before, or older than one opened from the same member: a copy put back. */
        ALREADY_OPENED,

        /** This phone's own update. */
        OWN,

        /** The label doesn't sync here now (left, or its folder can't be read). */
        UNAVAILABLE,
    }

    class UpdateOutcome(val state: SharedLabelState, val result: UpdateResult, val fromName: String = "", val report: SharedRunReport = SharedRunReport())

    /**
     * Merges an update file into the label as if a sync app had brought its files: each file is taken when it is
     * newer than the folder's (an edit made alongside one here is merged by the run instead), then one run applies
     * them with the usual rules, history and replay checks. The update itself must be sealed with this label's key,
     * signed by its sender, and newer than the last one opened from them.
     */
    @Suppress("ReturnCount")
    suspend fun openUpdate(s: SharedLabelState, bytes: ByteArray, allowMassDelete: Boolean = false): UpdateOutcome {
        fun no(r: UpdateResult) = UpdateOutcome(s, r)
        val peek = SharedLabelUpdates.peek(bytes) ?: return no(UpdateResult.NOT_AN_UPDATE)
        if (peek.labelId != s.labelId) return no(UpdateResult.OTHER_LABEL)
        if (peek.epoch > s.epoch) return no(UpdateResult.NEWER_KEY)
        if (!SharedLabelMembership.syncs(s.membership)) return no(UpdateResult.UNAVAILABLE)
        if (peek.epoch < s.epoch) return no(UpdateResult.OLDER_KEY)
        val u = when (val o = SharedLabelUpdates.open(bytes, s.key, s.labelId, s.epoch)) {
            is SharedLabelUpdates.Opened.Ok -> o.update
            SharedLabelUpdates.Opened.WrongKey -> return no(UpdateResult.WRONG_KEY)
            SharedLabelUpdates.Opened.NotAnUpdate -> return no(UpdateResult.NOT_AN_UPDATE)
            SharedLabelUpdates.Opened.Damaged -> return no(UpdateResult.DAMAGED)
        }
        if (u.fromHex == me) return no(UpdateResult.OWN)
        if (!SharedLabelUpdates.fresh(u.sentAt, s.exchanged[u.fromHex], clock())) return no(UpdateResult.ALREADY_OPENED)
        val listing = listing().first ?: return no(UpdateResult.UNAVAILABLE)
        val (shown, kept) = arrivals(s, listing, u.files)
        val overlay = OverlayFolder(folder, shown)
        val out = SharedLabelEngine(overlay, local, signer, clock).run(s, allowMassDelete)
        // What the run didn't write over stays in the folder when it is newer than the folder's.
        for (name in overlay.untouched) if (name in kept) shown[name]?.let { folder.write(name, it) }
        val exchanged = (out.state.exchanged + (u.fromHex to u.sentAt)).entries.sortedByDescending { it.value }.take(MAX_EXCHANGED).associate { it.toPair() }
        return UpdateOutcome(out.state.copy(exchanged = exchanged), UpdateResult.MERGED, u.fromName, out.report)
    }

    /**
     * Which of an update's [files] the run sees ([SharedLabelUpdates.takesCard] and the like), and which of those stay
     * in the folder afterwards. Files that don't open with the label's key, and this phone's own journal, are left out.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun arrivals(s: SharedLabelState, listing: Map<String, String?>, files: Map<String, ByteArray>): Pair<Map<String, ByteArray>, Set<String>> {
        val shown = LinkedHashMap<String, ByteArray>()
        val kept = HashSet<String>()
        fun headerEpoch(bytes: ByteArray?) = bytes?.let { runCatching { SharedLabelCrypto.parseHeader(it) }.getOrNull() }?.takeIf { it.labelId == s.labelId }?.epoch
        fun existing(name: String) = if (name in listing) folder.read(name) else null
        for ((name, bytes) in files) {
            val sid = SharedLabelFiles.sidOf(name)
            val take = when {
                name == SharedLabelCrypto.HEADER_NAME -> headerEpoch(bytes)?.let { SharedLabelUpdates.takesHeader(it, headerEpoch(existing(name))) } == true
                name.startsWith(SIG_PREFIX) -> name !in listing
                name == SharedLabelFiles.journalName(signer.publicKey) -> false
                sid != null -> {
                    val incoming = openCard(s, name, bytes) ?: continue
                    val there = existing(name)?.let { openCard(s, name, it) }
                    if (SharedLabelUpdates.keepsCard(incoming, there)) kept += name
                    SharedLabelUpdates.takesCard(incoming, there)
                }
                else -> {
                    val incoming = openJournal(s, name, bytes) ?: continue
                    SharedLabelUpdates.takesJournal(incoming, existing(name)?.let { openJournal(s, name, it) })
                }
            }
            if (take) {
                shown[name] = bytes
                if (sid == null) kept += name
            }
        }
        return shown to kept
    }

    private fun openCard(s: SharedLabelState, name: String, bytes: ByteArray): CardFile? =
        SharedLabelCrypto.open(s.key, s.labelId, name, bytes)?.let { SharedLabelFiles.readCard(s.labelId, name, it) }

    private fun openJournal(s: SharedLabelState, name: String, bytes: ByteArray): Journal? =
        SharedLabelCrypto.open(s.key, s.labelId, name, bytes)?.let { SharedLabelFiles.readJournal(s.labelId, name, it) }

    /** Leaving: this phone's journal says so, so the others see it; the caller then forgets the label. */
    suspend fun leave(s: SharedLabelState): Boolean {
        if (s.membership !is State.Active) return true
        val listing = listing().first ?: return false
        if (!writable(s, listing)) return true
        return writeJournal(s, left = true) != null
    }

    private companion object {
        val PROBE = "PARLEY-LABEL-PROBE".toByteArray()

        /** The key-change notes' names start so ([SharedLabelCrypto.headerSigName]). */
        const val SIG_PREFIX = ".parley-label-sig-"

        /** Senders whose last update time is kept (the members a label can count, and some to spare). */
        const val MAX_EXCHANGED = 2 * SharedLabelFiles.MAX_MEMBERS

        /** L7: journals opened per run at most (members' own first): twice the members a label can count. */
        const val MAX_JOURNALS = 2 * SharedLabelFiles.MAX_MEMBERS

        /** The stamp of a file whose provider gives no modified time or size: its content's hash. */
        fun contentStamp(bytes: ByteArray): String = "h:" + RecordJson.sha256Hex(bytes)
    }
}
