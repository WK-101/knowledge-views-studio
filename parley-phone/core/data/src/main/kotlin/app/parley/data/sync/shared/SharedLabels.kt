package app.parley.data.sync.shared

import android.Manifest
import android.content.Context
import android.net.Uri
import app.parley.common.people.ContactRef
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.Invitation
import app.parley.common.sync.shared.MemberSigner
import app.parley.common.sync.shared.SharedLabelFiles
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelMembership
import app.parley.common.sync.shared.SharedLabelTitles
import app.parley.common.sync.shared.SharedLabelUpdates
import app.parley.data.AccountRef
import app.parley.data.ContactsRepository
import app.parley.data.Permissions
import app.parley.data.people.LabelsRepository
import app.parley.data.people.MyCardIdentity
import app.parley.data.records.ContactRecordStore
import app.parley.data.security.RecordCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shared labels on this phone (docs/SHARED_LABELS.md): creating one, inviting, joining, the runs on the folder sync's
 * cadence, choices for contacts changed on two phones, removing members and leaving. Each label's state is one
 * sealed file; its key never leaves it.
 */
class SharedLabels(
    private val context: Context,
    private val contacts: ContactsRepository,
    private val records: ContactRecordStore,
    private val labels: LabelsRepository,
    private val identity: MyCardIdentity,
    private val defaultAccount: () -> AccountRef?,
    /** The folder of "Sync between your phones": a shared label keeps to a folder of its own. */
    private val syncFolder: () -> String?,
    private val dir: File = File(context.noBackupFilesDir, "shared_labels"),
    sealer: StateSealer = RecordSealer(context),
) {
    private val store = SharedLabelStateStore(dir, sealer)
    private val mutex = Mutex()
    private val _states = MutableStateFlow<List<SharedLabelState>>(emptyList())

    @Volatile private var loaded = false

    /** Every shared label on this phone (empty until [load]). */
    val states: StateFlow<List<SharedLabelState>> = _states.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!loaded) {
            _states.value = store.all()
            loaded = true
        }
    }

    /** Whether any label wants runs (the worker's question; cheap enough for the main thread: no file is opened). */
    fun wantsRuns(): Boolean = store.isNotEmpty()

    fun forTitle(title: String): SharedLabelState? = _states.value.firstOrNull { it.title == title }

    private fun signer(): MemberSigner? {
        val key = identity.publicKey() ?: return null
        return object : MemberSigner {
            override val publicKey = key

            override fun sign(message: ByteArray) = identity.signMessage(message)
        }
    }

    private fun localContacts() = ProviderLabelContacts(context, contacts, records, labels, defaultAccount)

    /** Where a label shared by update files only keeps its files on this phone. */
    private fun localFolder(labelId: String) = LocalLabelFolder(File(dir, "files-$labelId"))

    private fun folderOf(folderUri: String, labelId: String): LabelFolder =
        if (folderUri.isEmpty()) localFolder(labelId) else SafLabelFolder(context, Uri.parse(folderUri))

    private fun engine(s: SharedLabelState, signer: MemberSigner) = engine(s.folderUri, s.labelId, signer)

    private fun engine(folderUri: String, labelId: String, signer: MemberSigner) =
        SharedLabelEngine(folderOf(folderUri, labelId), localContacts(), signer)

    private fun save(s: SharedLabelState): Boolean {
        val ok = store.put(s)
        _states.value = store.all()
        return ok
    }

    private fun hasContacts() =
        Permissions.has(context, Manifest.permission.READ_CONTACTS) && Permissions.has(context, Manifest.permission.WRITE_CONTACTS)

    enum class Created { READY, SAME_AS_SYNC, FOLDER_IN_USE, FOLDER_UNAVAILABLE, CANT_SIGN, NO_PERMISSION, FAILED }

    /**
     * Shares the label [title] through the empty folder [folderUri], or by update files only when it is null, with
     * its own [passphrase]; then runs once.
     */
    suspend fun create(title: String, folderUri: Uri?, folderName: String, passphrase: CharArray, myName: String): Created = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext Created.NO_PERMISSION
            if (folderUri != null && folderUri.toString() == syncFolder()) return@withContext Created.SAME_AS_SYNC
            val signer = signer() ?: return@withContext Created.CANT_SIGN
            folderUri?.let { SafLabelFolder.keep(context, it) }
            val labelId = SharedLabelFiles.newId()
            val engine = engine(folderUri?.toString().orEmpty(), labelId, signer)
            when (engine.checkEmpty()) {
                null -> Unit
                SharedRunResult.NOT_A_LABEL -> return@withContext Created.FOLDER_IN_USE
                else -> return@withContext Created.FOLDER_UNAVAILABLE
            }
            val s = engine.create(title, folderUri?.toString().orEmpty(), folderName, passphrase, myName, labelId = labelId)
                ?: return@withContext Created.FAILED
            if (!save(s)) return@withContext Created.FAILED
            identity.markShared()
            save(engine.run(s).state)
            Created.READY
        }
    }

    /** A QR invitation's link with its one-time [passcode], or null when the key can't sign right now. */
    suspend fun inviteLink(labelId: String, passcode: String): String? = withContext(Dispatchers.Default) {
        val s = store.get(labelId) ?: return@withContext null
        val signer = signer() ?: return@withContext null
        engine(s, signer).invitation(s, s.folderName)?.let { SharedLabelInvites.qrLink(it, passcode) }
    }

    enum class InviteFile { READY, WRONG_PASSPHRASE, FAILED }

    /** An invitation file sealed with the label's [passphrase] (checked against the folder first), written by [write]. */
    suspend fun inviteFile(labelId: String, passphrase: CharArray, write: (ByteArray) -> Boolean): InviteFile = withContext(Dispatchers.IO) {
        val s = store.get(labelId) ?: return@withContext InviteFile.FAILED
        val signer = signer() ?: return@withContext InviteFile.FAILED
        val engine = engine(s, signer)
        if (!engine.checkPassphrase(s, passphrase)) return@withContext InviteFile.WRONG_PASSPHRASE
        val i = engine.invitation(s, s.folderName) ?: return@withContext InviteFile.FAILED
        if (runCatching { write(SharedLabelInvites.file(i, passphrase)) }.getOrDefault(false)) InviteFile.READY else InviteFile.FAILED
    }

    /** What joining [i] through [folderUri] would show (members and fingerprints), or why it can't. */
    suspend fun preview(i: Invitation, folderUri: Uri): SharedLabelEngine.Preview = withContext(Dispatchers.IO) {
        val signer = signer() ?: return@withContext SharedLabelEngine.Preview.Unavailable(SharedRunResult.CANT_SIGN)
        SafLabelFolder.keep(context, folderUri)
        engine(folderUri.toString(), i.labelId, signer).preview(i)
    }

    /**
     * The name a label joined from an invitation titled [base] gets here (M4): [base] when no label has it, else the
     * first of [suffixed] that is free ("Family (shared)"). Joining never lands in a label because the names match.
     */
    suspend fun titleForJoin(base: String, suffixed: (Int) -> String): String =
        SharedLabelTitles.fresh(base, labels.labels().map { it.title }, suffixed)

    /** The labels on this phone that a joined label could go into instead (the user's explicit choice). */
    suspend fun labelTitles(): List<String> = labels.labels().map { it.title }.filter { forTitle(it) == null }.distinct().sorted()

    /** How many of [title]'s contacts the first run would share if the label joined went into it (private ones never). */
    suspend fun wouldPublish(title: String): Int = labels.members(title).count { ContactRef.ofNavId(it) !is ContactRef.Private }

    /**
     * Joins [i] through [folderUri] as [myName], as the label [title]: a new label made here, or, with [intoExisting],
     * the label of that name the user picked (after being told how many contacts the first run shares). Then the first
     * run brings its contacts. A label this phone left or whose key changed comes back where it was, with what it
     * synced. With no folder ([folderUri] null) the label is shared by update files: its contacts arrive with the
     * first update opened. The title the label has here, or null when it couldn't be joined.
     */
    suspend fun join(i: Invitation, folderUri: Uri?, folderName: String, myName: String, title: String, intoExisting: Boolean): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext null
            val signer = signer() ?: return@withContext null
            val existing = store.get(i.labelId)
            val t = existing?.title ?: title.trim()
            val there = labels.label(t) != null
            // M4: a new label's name was free when the screen offered it; taken since, it is not merged into.
            if (existing == null && there && !intoExisting) return@withContext null
            if (!there) {
                val account = defaultAccount() ?: contacts.accounts().firstOrNull() ?: return@withContext null
                labels.create(t, account) ?: return@withContext null
            }
            val engine = engine(folderUri?.toString().orEmpty(), i.labelId, signer)
            val s = engine.join(i, folderUri?.toString().orEmpty(), folderName, t, myName, existing)
            if (!save(s)) return@withContext null
            identity.markShared()
            // By file, nothing is there to sync until the first update: this phone's journal waits in its files for the
            // update it sends back, so the others count it.
            if (s.byFile) engine.introduce(s) else save(engine.run(s).state)
            t
        }
    }

    /** One run of every shared label that syncs (the worker's). */
    suspend fun syncAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext
            val signer = signer() ?: return@withContext
            for (s in store.all()) {
                // A label shared by file syncs when an update is sent or opened.
                if (!SharedLabelMembership.syncs(s.membership) || s.byFile) continue
                runCatching { engine(s, signer).run(s) }.getOrNull()?.let { save(it.state) }
            }
            _states.value = store.all()
        }
    }

    /** One run of one label; [allowMassDelete] after the user confirmed a paused run. */
    suspend fun sync(labelId: String, allowMassDelete: Boolean = false): SharedLabelState? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext null
            if (!hasContacts()) return@withContext s.copy(lastResult = SharedRunResult.NO_PERMISSION).also { save(it) }
            val signer = signer() ?: return@withContext s.copy(lastResult = SharedRunResult.CANT_SIGN).also { save(it) }
            // Shared by file and no update opened yet: there is nothing here to sync with.
            if (s.byFile && s.header == null) return@withContext s
            val out = engine(s, signer).run(s, allowMassDelete).state
            save(out)
            out
        }
    }

    /** The user's choice for a contact changed on two phones. */
    suspend fun resolve(labelId: String, sid: String, picks: Map<CardField, Side>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext false
            val signer = signer() ?: return@withContext false
            engine(s, signer).resolve(s, sid, picks)?.let { save(it) } ?: false
        }
    }

    /** Removes members (key hashes) by changing the label's key to one from [passphrase]. */
    suspend fun removeMembers(labelId: String, members: Set<String>, passphrase: CharArray): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext false
            val signer = signer() ?: return@withContext false
            engine(s, signer).removeMembers(s, members, passphrase)?.let { save(it) } ?: false
        }
    }

    /** Leaves the label: the others see it, this phone forgets the key; the contacts stay here. */
    suspend fun leave(labelId: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext true
            val signer = signer()
            if (signer != null) runCatching { engine(s, signer).leave(s) }
            store.remove(labelId)
            if (s.byFile) localFolder(labelId).clear() else SafLabelFolder.release(context, Uri.parse(s.folderUri))
            _states.value = store.all()
            true
        }
    }

    // ---------------------------------------------------------------- update files

    /** An update file made now, and the label as it is after the run that wrote this phone's changes into it. */
    class Update(val bytes: ByteArray, val state: SharedLabelState)

    /**
     * "Send an update": a run writes this phone's changes into the label's files (when there is something to sync
     * with), then they all go into one update file to send by any app. Null when the label isn't active here or the
     * key can't sign now.
     */
    suspend fun sendUpdate(labelId: String): Update? = mutex.withLock {
        withContext(Dispatchers.IO) {
            var s = store.get(labelId) ?: return@withContext null
            if (!SharedLabelMembership.syncs(s.membership)) return@withContext null
            val signer = signer() ?: return@withContext null
            val engine = engine(s, signer)
            if (hasContacts() && !(s.byFile && s.header == null)) s = engine.run(s).state
            val now = System.currentTimeMillis()
            val bytes = engine.updateFile(s, now) ?: return@withContext null
            s = s.copy(lastSentAt = now)
            save(s)
            Update(bytes, s)
        }
    }

    /** What opening an update did: the label (null when none here matches) and the engine's outcome. */
    class Opened(val state: SharedLabelState?, val result: SharedLabelEngine.UpdateResult, val fromName: String = "", val report: SharedRunReport = SharedRunReport())

    /**
     * "Open an update": the update file's label is found by the id it names, and its files are merged
     * ([SharedLabelEngine.openUpdate]). A label not on this phone asks for its invitation first.
     */
    suspend fun openUpdate(bytes: ByteArray): Opened = mutex.withLock {
        withContext(Dispatchers.IO) {
            val peek = SharedLabelUpdates.peek(bytes) ?: return@withContext Opened(null, SharedLabelEngine.UpdateResult.NOT_AN_UPDATE)
            val s = store.get(peek.labelId) ?: return@withContext Opened(null, SharedLabelEngine.UpdateResult.OTHER_LABEL)
            if (!hasContacts()) return@withContext Opened(s, SharedLabelEngine.UpdateResult.UNAVAILABLE)
            val signer = signer() ?: return@withContext Opened(s, SharedLabelEngine.UpdateResult.UNAVAILABLE)
            val out = engine(s, signer).openUpdate(s, bytes)
            if (out.result == SharedLabelEngine.UpdateResult.MERGED) save(out.state)
            Opened(out.state, out.result, out.fromName, out.report)
        }
    }

    /**
     * Before leaving a label shared by file: a last update whose journal says this phone left, so the others see it
     * once they open it. Null when it can't be made (leaving still works).
     */
    suspend fun farewell(labelId: String): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId)?.takeIf { it.byFile && SharedLabelMembership.syncs(it.membership) } ?: return@withContext null
            val signer = signer() ?: return@withContext null
            val engine = engine(s, signer)
            if (!engine.leave(s)) return@withContext null
            engine.updateFile(s)
        }
    }

    /**
     * M4: whether renaming [old] to [new] would merge it with another label while either is shared: refused, since
     * the merged label's contacts would all be shared (renaming onto an existing name merges the two).
     */
    suspend fun renameWouldMerge(old: String, new: String): Boolean {
        val t = new.trim()
        if (t == old || labels.label(t) == null) return false
        load()
        return forTitle(old) != null || forTitle(t) != null
    }

    /** The label was renamed on this phone: its share follows. */
    suspend fun renamed(old: String, new: String) = mutex.withLock {
        withContext(Dispatchers.IO) { store.all().filter { it.title == old }.forEach { save(it.copy(title = new)) } }
    }

    /**
     * Private contacts among [ids] (list ids) that would be added to [title] while it is shared: they are refused, since
     * private contacts are never shared.
     */
    fun refusedPrivate(title: String, ids: Collection<Long>): Set<Long> {
        val shared = forTitle(title)?.let { SharedLabelMembership.syncs(it.membership) } == true
        return if (shared) ids.filter { ContactRef.ofNavId(it) is ContactRef.Private }.toSet() else emptySet()
    }
}

/** Seals a label's state with the small-records key; nothing is stored while it can't be sealed. */
class RecordSealer(private val context: Context) : StateSealer {
    private val crypto by lazy { RecordCrypto.get(context) }

    override fun seal(text: String): String? = crypto.sealText(text)?.takeIf(crypto::isSealed)

    override fun open(text: String): String? = if (crypto.isSealed(text)) crypto.openText(text) else null
}
