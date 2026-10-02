package app.parley.data.sync.shared

import android.Manifest
import android.content.Context
import android.net.Uri
import app.parley.common.people.ContactRef
import app.parley.common.people.ThreeWayMerge.Side
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.Invitation
import app.parley.common.sync.shared.MemberSigner
import app.parley.common.sync.shared.SharedLabelInvites
import app.parley.common.sync.shared.SharedLabelMembership
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
    dir: File = File(context.noBackupFilesDir, "shared_labels"),
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

    private fun engine(folderUri: String, signer: MemberSigner) = SharedLabelEngine(SafLabelFolder(context, Uri.parse(folderUri)), localContacts(), signer)

    private fun save(s: SharedLabelState): Boolean {
        val ok = store.put(s)
        _states.value = store.all()
        return ok
    }

    private fun hasContacts() =
        Permissions.has(context, Manifest.permission.READ_CONTACTS) && Permissions.has(context, Manifest.permission.WRITE_CONTACTS)

    enum class Created { READY, SAME_AS_SYNC, FOLDER_IN_USE, FOLDER_UNAVAILABLE, CANT_SIGN, NO_PERMISSION, FAILED }

    /** Shares the label [title] through the empty folder [folderUri], with its own [passphrase]; then runs once. */
    suspend fun create(title: String, folderUri: Uri, folderName: String, passphrase: CharArray, myName: String): Created = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext Created.NO_PERMISSION
            if (folderUri.toString() == syncFolder()) return@withContext Created.SAME_AS_SYNC
            val signer = signer() ?: return@withContext Created.CANT_SIGN
            SafLabelFolder.keep(context, folderUri)
            val engine = engine(folderUri.toString(), signer)
            when (engine.checkEmpty()) {
                null -> Unit
                SharedRunResult.NOT_A_LABEL -> return@withContext Created.FOLDER_IN_USE
                else -> return@withContext Created.FOLDER_UNAVAILABLE
            }
            val s = engine.create(title, folderUri.toString(), folderName, passphrase, myName) ?: return@withContext Created.FAILED
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
        engine(s.folderUri, signer).invitation(s, s.folderName)?.let { SharedLabelInvites.qrLink(it, passcode) }
    }

    enum class InviteFile { READY, WRONG_PASSPHRASE, FAILED }

    /** An invitation file sealed with the label's [passphrase] (checked against the folder first), written by [write]. */
    suspend fun inviteFile(labelId: String, passphrase: CharArray, write: (ByteArray) -> Boolean): InviteFile = withContext(Dispatchers.IO) {
        val s = store.get(labelId) ?: return@withContext InviteFile.FAILED
        val signer = signer() ?: return@withContext InviteFile.FAILED
        val engine = engine(s.folderUri, signer)
        if (!engine.checkPassphrase(s, passphrase)) return@withContext InviteFile.WRONG_PASSPHRASE
        val i = engine.invitation(s, s.folderName) ?: return@withContext InviteFile.FAILED
        if (runCatching { write(SharedLabelInvites.file(i, passphrase)) }.getOrDefault(false)) InviteFile.READY else InviteFile.FAILED
    }

    /** What joining [i] through [folderUri] would show (members and fingerprints), or why it can't. */
    suspend fun preview(i: Invitation, folderUri: Uri): SharedLabelEngine.Preview = withContext(Dispatchers.IO) {
        val signer = signer() ?: return@withContext SharedLabelEngine.Preview.Unavailable(SharedRunResult.CANT_SIGN)
        SafLabelFolder.keep(context, folderUri)
        engine(folderUri.toString(), signer).preview(i)
    }

    /**
     * Joins [i] through [folderUri] as [myName]: the label is made on this phone when it has none of that title, then
     * the first run brings its contacts. A label this phone left or whose key changed comes back with what it synced.
     */
    suspend fun join(i: Invitation, folderUri: Uri, folderName: String, myName: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext false
            val signer = signer() ?: return@withContext false
            val existing = store.get(i.labelId)
            val title = existing?.title ?: i.title
            if (labels.label(title) == null) {
                val account = defaultAccount() ?: contacts.accounts().firstOrNull() ?: return@withContext false
                labels.create(title, account) ?: return@withContext false
            }
            val engine = engine(folderUri.toString(), signer)
            val s = engine.join(i, folderUri.toString(), folderName, title, myName, existing)
            if (!save(s)) return@withContext false
            identity.markShared()
            save(engine.run(s).state)
            true
        }
    }

    /** One run of every shared label that syncs (the worker's). */
    suspend fun syncAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasContacts()) return@withContext
            val signer = signer() ?: return@withContext
            for (s in store.all()) {
                if (!SharedLabelMembership.syncs(s.membership)) continue
                runCatching { engine(s.folderUri, signer).run(s) }.getOrNull()?.let { save(it.state) }
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
            val out = engine(s.folderUri, signer).run(s, allowMassDelete).state
            save(out)
            out
        }
    }

    /** The user's choice for a contact changed on two phones. */
    suspend fun resolve(labelId: String, sid: String, picks: Map<CardField, Side>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext false
            val signer = signer() ?: return@withContext false
            engine(s.folderUri, signer).resolve(s, sid, picks)?.let { save(it) } ?: false
        }
    }

    /** Removes members (key hashes) by changing the label's key to one from [passphrase]. */
    suspend fun removeMembers(labelId: String, members: Set<String>, passphrase: CharArray): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext false
            val signer = signer() ?: return@withContext false
            engine(s.folderUri, signer).removeMembers(s, members, passphrase)?.let { save(it) } ?: false
        }
    }

    /** Leaves the label: the others see it, this phone forgets the key; the contacts stay here. */
    suspend fun leave(labelId: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = store.get(labelId) ?: return@withContext true
            val signer = signer()
            if (signer != null) runCatching { engine(s.folderUri, signer).leave(s) }
            store.remove(labelId)
            SafLabelFolder.release(context, Uri.parse(s.folderUri))
            _states.value = store.all()
            true
        }
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
