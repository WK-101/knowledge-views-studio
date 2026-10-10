package app.parley.data.vault

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.provider.CallLog
import android.util.Base64
import androidx.room.withTransaction
import app.parley.common.NotificationPrivacy
import app.parley.common.VaultNumberKeys
import app.parley.common.backup.RecordJson
import app.parley.common.catching
import app.parley.common.circle.Agenda
import app.parley.common.people.CallerCard
import app.parley.common.people.NameOrder
import app.parley.common.people.PrivateCallerChoices
import app.parley.common.people.PrivateLabels
import app.parley.common.record.ContactRecord
import app.parley.common.storage.DurableFiles
import app.parley.data.CallerInfo
import app.parley.data.ContactDetails
import app.parley.data.ContactDetailsJson
import app.parley.data.ContactPhotoProcessor
import app.parley.data.PhoneEnv
import app.parley.data.TelephonyPackages
import app.parley.common.calls.InternetCalls
import app.parley.data.R
import app.parley.data.StartGate
import app.parley.data.db.AppDatabase
import app.parley.data.db.PrivateCallEntity
import app.parley.data.db.VaultCallerRow
import app.parley.data.db.VaultContactEntity
import app.parley.data.db.VaultNumberEntity
import app.parley.data.vault.CallerIdCopy.C_COMPANY
import app.parley.data.vault.CallerIdCopy.C_LABELS
import app.parley.data.vault.CallerIdCopy.C_NAME_ALT
import app.parley.data.vault.CallerIdCopy.C_PRONOUNS
import app.parley.data.vault.CallerIdCopy.C_REGION
import app.parley.data.vault.CallerIdCopy.C_SEEDED
import app.parley.data.vault.CallerIdCopy.C_STAR
import app.parley.data.vault.CallerIdCopy.C_TITLE
import app.parley.data.vault.CallerIdCopy.C_TONE
import app.parley.data.vault.CallerIdCopy.C_VOICEMAIL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Readable without unlocking: enough to show who is calling and list the vault. */
data class VaultSummary(
    val id: Long,
    val name: String,
    val numbers: List<String>,
    val expiresAt: Long?,
    /** Last saved (from the caller-ID copy; the creation time for entries saved before this was recorded). */
    val updatedAt: Long = 0,
    /** A temporary private contact whose call history goes with it when it expires. */
    val purgeHistory: Boolean = false,
    /** A favourite: Parley's own star (private contacts aren't in the address book, whose star other apps read). */
    val starred: Boolean = false,
    /** Its labels (the address book's groups; only the membership is kept here, see [PrivateLabels]). */
    val labels: List<PrivateLabels.Membership> = emptyList(),
    /** Its own ringtone, played by Parley's ringer when it calls. */
    val ringtone: String? = null,
    /** Its calls are declined to voicemail by Parley's call screening. */
    val sendToVoicemail: Boolean = false,
    /** Its haptic caller ID ([app.parley.common.calls.CallerHaptics] spec), applied by Parley's ringer. */
    val vibration: String? = null,
    /** Its calls are answered automatically when "For chosen people and labels" is on. */
    val autoAnswer: Boolean = false,
    /** "They never call me": "This number never calls you" stays on for its numbers. */
    val neverCalls: Boolean = false,
    /**
     * The caller-ID copy holds the star, labels, ringtone and "send to voicemail". False for an entry saved before
     * they were kept there and not seeded yet ([VaultRepository.seedCallerChoices]): the fields above are then only
     * defaults, and nothing may be overwritten with them.
     */
    val choicesKnown: Boolean = true,
    /** The "Family, Given" form of [name], for "Sort by" and "Show names as" last name first. */
    val nameAlt: String = name,
    /** The region its national numbers were read with when saved; null for entries saved before it was kept. */
    val region: String? = null,
    /** Its company, from the caller-ID copy (readable while the vault is locked), for sorting Contacts by company. */
    val company: String = "",
    /** When it was first saved here (made private or created), for sorting Contacts by recently added. */
    val createdAt: Long = 0,
    /**
     * When it was archived: out of Parley's lists (Contacts › ⋮ › Archived shows it while private contacts may show),
     * still private, still named on calls. Null while it is listed.
     */
    val archivedAt: Long? = null,
) {
    /** Archived: kept in the vault, out of the lists. */
    val archived: Boolean get() = archivedAt != null

    /** Anything the call path must apply for this contact (Parley screens its calls then). */
    val hasCallChoices: Boolean get() = ringtone != null || sendToVoicemail || labels.isNotEmpty()
}

/**
 * What the call screen shows for a private contact, readable without unlocking (from the caller-ID copy):
 * job/company, the "who is this" line, the note for calls, and whether an encrypted photo exists.
 */
data class VaultCallerCard(
    val name: String,
    val subtitle: String?,
    val context: String?,
    val note: String?,
    val photoUri: String?,
    /** "she/her", shown beside the name. */
    val pronouns: String? = null,
    /** Their name in their own language ("Иван Петров"), under the name. */
    val nativeName: String? = null,
)

data class PrivateCall(
    val id: Long,
    val vaultId: Long,
    val number: String,
    val name: String,
    val date: Long,
    val durationSec: Long,
    val type: Int,
    /** Android logged it as a video call (kept sealed with the number; false for calls stored before it was). */
    val video: Boolean = false,
    /** The app the call went through over the internet (WhatsApp…), or null for a phone call; sealed with the number. */
    val app: String? = null,
)

/**
 * The sweep's call-log row under [c] (id, number, date, duration, type, features, account) stored as a private call: its id, to
 * delete from the log, or null when it isn't a private contact's or couldn't be stored (it stays in the log).
 */
private suspend fun VaultRepository.keepPrivate(c: android.database.Cursor, maybePrivate: (String) -> Boolean, telephony: Set<String>): Long? {
    val number = c.getString(1)?.takeIf(maybePrivate) ?: return null
    val hit = lookup(number) ?: return null
    // A contact being made visible: its calls are going back to this log, never into its private history.
    if (hit.first in leaving) return null
    val stored = catching {
        storePrivateCall(
            hit.first, number, hit.second.name, c.getLong(2), c.getLong(3), c.getInt(4), isVideo(c.getInt(5)),
            app = InternetCalls.appPackage(c.getString(6), telephony),
        )
    }.getOrDefault(false)
    return c.getLong(0).takeIf { stored }
}

/** The sweep's columns: [keepPrivate] reads them by position. */
private val SWEEP_COLUMNS = arrayOf(
    CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.FEATURES,
    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
)

/**
 * What a private call seals: the number and name, "v" for a video call, and "app" for a call an app made over the
 * internet (WhatsApp…), which stays that app's call in the private history.
 */
private fun privateCallPlain(number: String, name: String, video: Boolean, app: String?): ByteArray =
    JSONObject().put("n", number).put("name", name).apply {
        if (video) put("v", true)
        app?.let { put("app", it) }
    }.toString().toByteArray()

/** A call-log row's FEATURES say it was a video call. */
private fun isVideo(features: Int): Boolean = (features and CallLog.Calls.FEATURES_VIDEO) != 0

/**
 * [VaultRepository.hasCallChoices] without building the vault: false until it has been read. A call in a process
 * started for it is screened by the call-screening service anyway, which reads the vault itself.
 */
object VaultCallChoices {
    @Volatile var any: Boolean = false

    /**
     * Whether [any] has been read yet. Until then the call path treats it as "yes": in a process started for a call,
     * other settings may finish loading first, and a private contact's voicemail or ringtone must not be skipped.
     */
    @Volatile var loaded: Boolean = false
}

/** A private contact exactly as the vault stores it, every part still sealed ([VaultRepository.sealedCopy]). */
class SealedEntry(
    val callerIdBlob: ByteArray,
    val detailBlob: ByteArray,
    val expiresAt: Long?,
    val createdAt: Long,
    /** The photo file's bytes, sealed with the caller-ID key. */
    val photo: ByteArray?,
    val calls: List<SealedCall>,
)

/** One private call, its number and name still sealed. */
class SealedCall(val blob: ByteArray, val date: Long, val durationSec: Long, val type: Int)

/**
 * Private contacts stored only inside Parley (encrypted), invisible to every other app.
 * Caller ID uses an HMAC index + the caller-ID key, so it works even while the phone is locked.
 *
 * [gate] holds back the listings and the upkeep until the full app starts ([StartGate]): a process started for a call
 * looks numbers up ([lookup]) and nothing else, so screening never waits behind the vault's Keystore work.
 */
class VaultRepository(private val context: Context, private val db: AppDatabase, private val scope: CoroutineScope, private val gate: StartGate? = null) {
    private val dao = db.vaultDao()
    private val callSeal = PrivateCallSeal(context)

    /**
     * The address book's labels (id, title), set by the container: a private contact's label membership is stored by
     * group id and title ([PrivateLabels]) and read back as the labels are now. Empty when contacts can't be read.
     */
    @Volatile var labelGroups: () -> List<PrivateLabels.Group> = { emptyList() }

    /**
     * Whether any private contact has a ringtone, "send to voicemail" or labels, so the call path screens calls even
     * when no other screening feature is on. Memory only (the call path asks on the main thread, see
     * [VaultCallChoices]): kept from the last listing and remembered across restarts.
     */
    var hasCallChoices: Boolean
        get() = VaultCallChoices.any
        private set(v) {
            VaultCallChoices.any = v
        }

    /** Each private contact's list row: opened once, kept between runs ([PrivateRows]). */
    private val listRows = PrivateRows(context)

    /**
     * Every private contact from its caller-ID copy (the sealed details stay in the database, see VaultCallerRow); null
     * until the first listing has been opened, so Parley's lists can wait for it instead of showing everyone else first
     * and the private contacts a moment later. Declared after what a listing uses: an eager start can list on another
     * thread before the constructor reaches later initializers.
     */
    val listing: StateFlow<List<VaultSummary>?> = dao.callerRows()
        .map { list -> summarizeAll(list).sortedBy { it.name.lowercase() } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, gate?.sharing ?: SharingStarted.Eagerly, null)

    /** [listing], empty until it has loaded. */
    val contacts: StateFlow<List<VaultSummary>> = listing.map { it.orEmpty() }
        .stateIn(scope, gate?.sharing ?: SharingStarted.Eagerly, emptyList())

    /** Private contacts stored, counted without opening anything (cheap: a cold start asks before the listing is open). */
    suspend fun countNow(): Int = withContext(Dispatchers.IO) { dao.count() }

    val privateCalls: StateFlow<List<PrivateCall>> = dao.privateCalls()
        .map { list -> list.mapNotNull { callSeal.opened(it) }.also { callSeal.resealOlder(list, scope, dao::resealPrivateCall) } }
        .flowOn(Dispatchers.IO)
        .stateIn(scope, gate?.sharing ?: SharingStarted.Eagerly, emptyList())

    private fun summarize(e: VaultContactEntity): VaultSummary? = summarize(VaultCallerRow(e.id, e.callerIdBlob, e.expiresAt, e.createdAt))

    /** Each row's summary, in one batch ([PrivateRows.all]): never one Keystore operation per contact at a cold start. */
    private suspend fun summarizeAll(list: List<VaultCallerRow>): List<VaultSummary> = listRows.all(list)

    private fun summarize(e: VaultCallerRow): VaultSummary? = listRows.one(e)

    /**
     * What the caller-ID copy [o] keeps for the call path and the lists (the star, labels, ringtone and "send to
     * voicemail") laid over details read from the sealed record: it is the one place they are stored, so they can be
     * changed without unlocking and are applied to calls while the phone is locked.
     */
    private fun withCallerChoices(d: ContactDetails, o: JSONObject): ContactDetails = if (!o.has(C_SEEDED)) d else d.copy(
        starred = o.optBoolean(C_STAR, false),
        groupIds = PrivateLabels.ids(CallerIdCopy.labelsOf(o), runCatching { labelGroups() }.getOrDefault(emptyList())),
        customRingtone = o.optString(C_TONE).ifEmpty { null },
        sendToVoicemail = o.optBoolean(C_VOICEMAIL, false),
    )

    /**
     * Full details; throws [VaultCrypto.LockedException] if the user must unlock first and
     * [VaultCrypto.KeyUnavailableException] when the Keystore can't open them right now (try again later).
     *
     * When the detail key is gone for good (the screen lock was removed or reset), name, numbers, labels and the
     * caller card survive in the caller-ID copy: they are returned, but nothing is written. The sealed record stays as
     * it is until the user chooses [keepWhatIsLeft] (see [detailsLost]).
     */
    suspend fun details(id: Long): ContactDetails? = open(id)?.details

    /** [details], and whether they had to be rebuilt from the caller-ID copy because the detail key is gone ([detailsLost]). */
    data class Opened(val details: ContactDetails, val lost: Boolean)

    /**
     * [details] with [Opened.lost], in one opening: the contact page needs both, and each opening of the sealed details
     * is a Keystore operation (in StrongBox where the phone has one, where it is slow). Only the main part is opened
     * (VaultCrypto.sealDetailParts); a blob sealed before details had two parts is split on the way, so this is its
     * last whole opening. Opened details are kept in memory for [OPENED_MS] while the phone is unlocked.
     */
    suspend fun open(id: Long): Opened? = withContext(Dispatchers.IO) {
        // An entry from before the caller-ID copy kept the star, labels, ringtone and voicemail gets them now (the
        // details are being opened anyway), so the page, the editor and "Make visible" see them.
        if (dao.callerRow(id)?.let { summarize(it)?.choicesKnown } == false) runCatching { seedCallerChoices(id) }
        val e = dao.get(id) ?: return@withContext null
        val caller = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
        val (d, lost) = try {
            // The photo is kept apart (encrypted, readable for caller ID); anything older in the record is stale.
            openMain(e) to false
        } catch (_: VaultCrypto.KeyLostException) {
            CallerIdCopy.rebuilt(id, caller) to true
        }
        Opened(withCallerChoices(d.copy(photoUri = photoUri(id)), caller), lost)
    }

    /**
     * What a private contact's page shows before its sealed details are open: name, numbers and their types, job and
     * company, the "who is this" line, the note for calls, pronouns, star, labels and photo, all from the caller-ID
     * copy (one small opening with the key that needs no unlock). Null when the entry is gone or unreadable.
     */
    suspend fun callerCopy(id: Long): ContactDetails? = withContext(Dispatchers.IO) {
        val e = dao.callerRow(id) ?: return@withContext null
        runCatching {
            val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
            withCallerChoices(CallerIdCopy.rebuilt(id, o), o).copy(photoUri = photoUri(id), starred = o.optBoolean(C_STAR, false))
        }.getOrNull()
    }

    /** Main parts opened lately, by entry, with the blob they came from (memory only; see [open]). */
    private class OpenedMain(val blob: ByteArray, val details: ContactDetails, val at: Long)

    private val openedMain = java.util.concurrent.ConcurrentHashMap<Long, OpenedMain>()

    /**
     * Counts [forgetOpened] calls, so what was made from opened details elsewhere (the Contacts search's private docs)
     * is forgotten at the same moments.
     */
    val forgets: StateFlow<Int> get() = forgetCount
    private val forgetCount = MutableStateFlow(0)

    /** How long opened details stay in memory ([OPENED_MS]): what is made from them elsewhere follows the same rule. */
    val openedForMs: Long get() = OPENED_MS

    /** Forgets every opened detail (the app lock locked, the screen went off, or a test). */
    fun forgetOpened() {
        openedMain.clear()
        lock.forgotten()
        // The kept listing's key goes too: the next write unwraps it again.
        listRows.forgetKey()
        forgetCount.value++
    }

    /** Whether private contacts are unlocked now, and "Lock private contacts" ([VaultLock]). */
    val lock = VaultLock(scope)

    /** The person's unlock in Parley succeeded: an earlier [lockAll] no longer holds. */
    fun unlockedByPerson() = lock.unlockedByPerson()

    /**
     * "Lock private contacts": their details lock again at once, whatever time the key's own window has left. Opened
     * details are forgotten (pages, the Contacts search's private details, everything made from them), and nothing
     * opens or seals details until the next unlock in Parley ([unlockedByPerson]). Names and numbers stay listed, as
     * they are while locked (the caller-ID copy needs no unlock); discreet mode is what hides them.
     */
    fun lockAll() {
        lock.lockAll(::forgetOpened)
        onConcealed()
    }

    /**
     * Set by the container: what else must forget private contacts at once when they are locked ("Lock private
     * contacts"), such as the Contacts list's first screenful kept for a cold start.
     */
    @Volatile var onConcealed: () -> Unit = {}

    private fun deviceLocked(): Boolean = runCatching { context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }.getOrDefault(true)

    /**
     * Entry [e]'s details from its main part: from memory when opened in the last [OPENED_MS] and unchanged since, and
     * never while the phone is locked (the detail key couldn't open them then); else opened now. A single (older) blob
     * is split into two parts once it is open, so its record and photo are never opened again for the page.
     */
    private suspend fun openMain(e: VaultContactEntity): ContactDetails {
        val now = SystemClock.elapsedRealtime()
        openedMain[e.id]?.let { o -> if (o.blob.contentEquals(e.detailBlob) && now - o.at < OPENED_MS && !deviceLocked()) return o.details }
        val text = String(VaultCrypto.openDetailMain(e.detailBlob))
        lock.noteUnlocked()
        val d = ContactDetailsJson.decode(text)
        val blob = if (VaultCrypto.isParts(e.detailBlob)) {
            e.detailBlob
        } else {
            runCatching { split(e.id, e.detailBlob, JSONObject(text)) }.getOrNull() ?: e.detailBlob
        }
        openedMain[e.id] = OpenedMain(blob, d, now)
        return d
    }

    /**
     * Re-seals entry [id]'s single detail blob [old] (opened: [whole]) as two parts, its record, photo and carried
     * interactions in the extra part. Written only if the row still holds [old]; returns the new blob, or null.
     */
    private suspend fun split(id: Long, old: ByteArray, whole: JSONObject): ByteArray? = keysLock.withLock {
        val extra = JSONObject()
        for (k in EXTRA_KEYS) if (whole.has(k)) extra.put(k, whole.get(k))
        val main = JSONObject(whole.toString()).apply { EXTRA_KEYS.forEach { remove(it) } }
        val blob = VaultCrypto.sealDetailParts(main.toString().toByteArray(), extra.takeIf { it.length() > 0 }?.toString()?.toByteArray())
        if (dao.replaceDetailBlob(id, old, blob) == 1) blob else null
    }

    /**
     * Migration, after the vault's unlock: splits every entry still sealed as one blob ([split]), so later page opens
     * read only the small main part. Stops while the vault is locked and runs again on the next unlock; marked done once
     * every entry is split. Returns how many were split now.
     */
    suspend fun splitDetails(): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(K_SPLIT, false)) return@withContext 0
        var done = 0
        var pending = false
        for (id in dao.callerRowsNow().map { it.id }) {
            val e = dao.get(id) ?: continue
            if (VaultCrypto.isParts(e.detailBlob)) continue
            try {
                val whole = JSONObject(String(VaultCrypto.openDetail(e.detailBlob)))
                if (split(id, e.detailBlob, whole) != null) done++ else pending = true
            } catch (_: VaultCrypto.LockedException) {
                return@withContext done
            } catch (_: VaultCrypto.KeyLostException) {
                // Its key is gone: it stays as it is until "Keep what's left".
                continue
            } catch (_: VaultCrypto.KeyUnavailableException) {
                pending = true
            }
        }
        if (!pending) prefs.edit().putBoolean(K_SPLIT, true).apply()
        done
    }

    /**
     * What only "Make visible", backups and seeding read (the stored record, its photos, carried interactions): the
     * extra part of [blob], or for a single blob the whole of it ([main] when that is already open).
     */
    private fun extrasOf(blob: ByteArray, main: JSONObject? = null): JSONObject = when {
        VaultCrypto.isParts(blob) -> VaultCrypto.openDetailExtra(blob)?.let { JSONObject(String(it)) } ?: JSONObject()
        main != null -> main
        else -> JSONObject(String(VaultCrypto.openDetail(blob)))
    }

    /** Whether this entry's full details can no longer be opened (only what the caller-ID copy holds is left). */
    suspend fun detailsLost(id: Long): Boolean = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext false
        try {
            VaultCrypto.openDetailMain(e.detailBlob)
            false
        } catch (_: VaultCrypto.KeyLostException) {
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The user's choice after [detailsLost]: keep the name, numbers and caller card under the current key. The old
     * sealed record is kept in a file beside the database (a later Keystore recovery could still open it).
     */
    suspend fun keepWhatIsLeft(id: Long): Boolean = withContext(Dispatchers.IO) {
        val e = dao.callerRow(id) ?: return@withContext false
        if (!detailsLost(id)) return@withContext false
        save(id, CallerIdCopy.rebuilt(id, JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))))
        true
    }

    /** Moves a detail blob that can't be opened any more out of the way, never deleting it. */
    private fun setAside(id: Long, blob: ByteArray) {
        val dir = File(context.noBackupFilesDir, "vault-unreadable").apply { mkdirs() }
        File(dir, "$id-${System.currentTimeMillis()}.bin").writeBytes(blob)
    }

    /**
     * Moves every private contact's details to a stronger detail key when the current one is weaker than the phone
     * allows ([VaultCrypto.detailKeyNeedsUpgrade]): no authentication (made before a screen lock existed) or no
     * unlocked-device requirement. Runs right after the user authenticated; all entries change in one transaction, or
     * none do.
     */
    suspend fun upgradeDetailKey(): Boolean = withContext(Dispatchers.IO) {
        // Under the key lock throughout: every save seals and writes under it too, so no blob can be sealed with a
        // key this deletes, and no row read before the upgrade is written back after it.
        keysLock.withLock {
            VaultCrypto.reconcileGenerations(generationsInUse())
            if (!VaultCrypto.detailKeyNeedsUpgrade()) return@withLock false
            VaultCrypto.upgradeDetailKey(
                reseal = { convert ->
                    // Entries whose key was already lost stay as they are; everything else must convert.
                    val converted = dao.all().mapNotNull { e ->
                        try {
                            e.id to convert(e.detailBlob)
                        } catch (_: VaultCrypto.KeyLostException) {
                            null
                        }
                    }
                    // Only the sealed details change: expiry and the caller-ID copy stay as they are now.
                    db.withTransaction { converted.forEach { (id, blob) -> dao.setDetailBlob(id, blob) } }
                    true
                },
                inUse = { generationsInUse() },
            )
        }
    }

    /** Detail key generations still needed: the entries', and sealed copies kept by "Recently deleted". */
    private suspend fun generationsInUse(): Set<Int> =
        // One entry's details at a time, never every entry's at once.
        dao.callerRowsNow().mapNotNull { r -> dao.detailBlob(r.id)?.let(VaultCrypto::generationOf) }.toSet() +
            runCatching { keptGenerations() }.getOrDefault(emptySet())

    /** Generations of detail blobs kept outside the table (set by the container: [PrivateTrash]). */
    @Volatile var keptGenerations: () -> Set<Int> = { emptySet() }

    /**
     * Entry [id] exactly as stored, still sealed (its details under the detail key, the rest under the caller-ID key),
     * with its photo file and private calls: what "Recently deleted" keeps. Nothing is opened, so no unlock is needed.
     */
    suspend fun sealedCopy(id: Long): SealedEntry? = withContext(Dispatchers.IO) {
        keysLock.withLock {
            val e = dao.get(id) ?: return@withLock null
            val photo = photoFile(id).takeIf { it.isFile }?.readBytes()
            val calls = dao.privateCallsOf(id).map { SealedCall(it.blob, it.date, it.durationSec, it.type) }
            SealedEntry(e.callerIdBlob, e.detailBlob, e.expiresAt, e.createdAt, photo, calls)
        }
    }

    /** Puts a [sealedCopy] back as a new entry (its fingerprints rebuilt); returns its id. */
    suspend fun restoreSealed(s: SealedEntry): Long = withContext(Dispatchers.IO) {
        val caller = JSONObject(String(VaultCrypto.openCallerId(s.callerIdBlob)))
        val nums = caller.optJSONArray("numbers") ?: JSONArray()
        val numbers = (0 until nums.length()).map { nums.getString(it) }
        val region = caller.optString(C_REGION).ifEmpty { region() }
        // A temporary contact whose date passed meanwhile would be deleted again at once: it comes back permanent.
        val expiresAt = s.expiresAt?.takeIf { it > System.currentTimeMillis() }
        val id = keysLock.withLock {
            db.withTransaction {
                val newId = dao.upsert(
                    VaultContactEntity(callerIdBlob = s.callerIdBlob, detailBlob = s.detailBlob, expiresAt = expiresAt, createdAt = s.createdAt),
                )
                dao.addNumbers(numberRows(newId, numbers, region))
                s.calls.forEach { c ->
                    val key = PrivateCallEntity.dedupeKey(newId, c.date, c.type)
                    dao.addPrivateCall(
                        PrivateCallEntity(vaultId = newId, blob = c.blob, date = c.date, durationSec = c.durationSec, type = c.type, dedupeKey = key),
                    )
                }
                newId
            }
        }
        s.photo?.let { bytes ->
            DurableFiles.write(photoFile(id), bytes)
        }
        if (caller.optBoolean(C_VOICEMAIL) || caller.has(C_TONE) || caller.has(C_LABELS)) noteCallChoices(true)
        id
    }

    /**
     * Saves a private contact. [expiresAt] makes it temporary (null keeps the current expiry); [purgeHistory] (null
     * keeps the current choice) removes its call history when it expires. [record]: the lossless image of the phone
     * contact it came from ("Move to private"); it is sealed with the details (photo included) so moving back out
     * restores every field. Editing an entry later keeps the stored record (see [storedRecord]). [interactions]: the
     * contact's logged interactions ([app.parley.common.circle.Interactions.encodeCarried]), sealed with the details
     * so they come back on "Move out" and are never shown while the contact is private; edits keep them too.
     * [loaded]: the editor's starting point for an edit, so only the star, labels, ringtone and voicemail it changed
     * are written over the entry's current ones.
     */
    @Suppress("CyclomaticComplexMethod") // One sealed write: caller-ID copy, details and fingerprints together.
    suspend fun save(
        id: Long?,
        d: ContactDetails,
        expiresAt: Long? = null,
        purgeHistory: Boolean? = null,
        record: ContactRecord? = null,
        recordOf: String? = null,
        interactions: String? = null,
        loaded: ContactDetails? = null,
    ): Long = withContext(Dispatchers.IO) {
        val name = d.composedName.ifBlank { d.company.ifBlank { d.phones.firstOrNull()?.value ?: context.getString(R.string.data_vault_fallback_name) } }
        val region = region()
        val numbers = d.phones.map { it.value }.filter { it.isNotBlank() }
        // Read, sealed and written under the key lock, so a key upgrade can't delete the key this seals with, and an
        // expiry or a re-seal made meanwhile isn't overwritten with what was read before it.
        keysLock.withLock {
            val existing = id?.let { dao.get(it) }
            val existingSummary = existing?.let { summarize(it) }
            val purge = purgeHistory ?: existingSummary?.purgeHistory ?: false
            val groups = runCatching { labelGroups() }.getOrDefault(emptyList())
            // [loaded]: the editor's starting point. Only what the editor changed is taken from it; the rest stays as the
            // entry has it now (a star or a label changed from the list while the editor was open).
            val base = existingSummary?.takeIf { loaded != null && it.choicesKnown }
            val shown = if (base == null || loaded == null) d else d.copy(
                starred = if (d.starred != loaded.starred) d.starred else base.starred,
                customRingtone = if (d.customRingtone != loaded.customRingtone) d.customRingtone else base.ringtone,
                sendToVoicemail = if (d.sendToVoicemail != loaded.sendToVoicemail) d.sendToVoicemail else base.sendToVoicemail,
            )
            // Labels as the editor chose them, keeping any it couldn't show (a label that isn't there right now).
            val labels = if (base == null || loaded == null) {
                PrivateLabels.fromIds(shown.groupIds, groups, existingSummary?.labels.orEmpty())
            } else {
                PrivateLabels.edited(base.labels, added = shown.groupIds - loaded.groupIds, removed = loaded.groupIds - shown.groupIds, groups)
            }
            // "Family, Given", from the name parts; a name made from the company or a number stays as it is.
            val alt = NameOrder.alternative(d.given, d.middle, d.family, d.suffix)
                ?: if (d.composedName.isBlank()) name else NameOrder.guessAlternative(name)
            val caller = JSONObject().put("name", name).put(C_NAME_ALT, alt).put("numbers", JSONArray(numbers))
                .put("labels", JSONArray(shown.phones.filter { it.value.isNotBlank() }.map { it.type }))
                // The caller card's extra lines, readable while the phone is locked like the name.
                .apply {
                    CallerCard.subtitle(shown.title, shown.company)?.let { put("sub", it) }
                    // Kept apart too, so a lost detail key can restore them (see CallerIdCopy.rebuilt).
                    shown.title.trim().ifEmpty { null }?.let { put(C_TITLE, it) }
                    shown.company.trim().ifEmpty { null }?.let { put(C_COMPANY, it) }
                    shown.context.trim().ifEmpty { null }?.let { put("ctx", it) }
                    // Without the agenda's items: they are read from the sealed details, only once unlocked.
                    Agenda.withoutItems(shown.pinnedNote)?.let { put("note", it) }
                    shown.pronouns.trim().ifEmpty { null }?.let { put(C_PRONOUNS, it) }
                    shown.nativeName.shown.ifEmpty { null }?.let { put(CallerIdCopy.C_NATIVE_NAME, it) }
                }
                // When it was last saved, so the newest of two entries sharing a number wins.
                .put("u", System.currentTimeMillis())
                .apply { if (purge) put("purge", true) }
                // In Favourites while the vault is locked, like the name in Contacts.
                .apply { if (shown.starred) put(C_STAR, true) }
                // Labels, ringtone and "send to voicemail": applied to calls while the phone is locked.
                .apply {
                    CallerIdCopy.putLabels(this, labels)
                    shown.customRingtone?.takeIf { it.isNotBlank() }?.let { put(C_TONE, it) }
                    if (shown.sendToVoicemail) put(C_VOICEMAIL, true)
                    // The vibration and auto-answer are set from the page only (the editor doesn't show them): kept.
                    existingSummary?.let { CallerIdCopy.putPageChoices(this, it) }
                    // An edit keeps an archived contact archived (Unarchive is what lists it again).
                    existingSummary?.archivedAt?.let { put(CallerIdCopy.C_ARCHIVED, it) }
                }
                // The region national numbers were read with, so re-fingerprinting later uses the same one.
                .put(C_REGION, region)
                // The four choices above are this copy's own from now on (see seedCallerChoices).
                .put(C_SEEDED, 1)
            val detailsJson = ContactDetailsJson.encode(shown.copy(photoUri = null))
            val detailBlob = sealDetails(existing, detailsJson, record, recordOf, interactions)
            val entity = VaultContactEntity(
                id = id ?: 0,
                callerIdBlob = VaultCrypto.sealCallerId(caller.toString().toByteArray()),
                detailBlob = detailBlob,
                expiresAt = expiresAt ?: existing?.expiresAt,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            db.withTransaction {
                val newId = dao.upsert(entity).let { if (id != null) id else it }
                dao.clearNumbers(newId)
                dao.addNumbers(numberRows(newId, numbers, region))
                newId
            }.also { newId ->
                // The page reopens right after an edit: what was just sealed needn't be opened again.
                openedMain[newId] = OpenedMain(detailBlob, ContactDetailsJson.decode(detailsJson), SystemClock.elapsedRealtime())
            }.also { noteCallChoices(labels.isNotEmpty() || shown.sendToVoicemail || !shown.customRingtone.isNullOrBlank()) }
        }
    }

    /**
     * The sealed details of a save, in two parts (VaultCrypto.sealDetailParts): [detailsJson], what the page opens, and
     * what only "Make visible", backups and seeding read ([record], [interactions], or what [existing] already has).
     * Call under [keysLock].
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth") // Keeping, re-sealing or setting aside the old part: one decision each.
    private fun sealDetails(
        existing: VaultContactEntity?,
        detailsJson: String,
        record: ContactRecord?,
        recordOf: String?,
        interactions: String?,
    ): ByteArray {
        val extra = JSONObject()
        if (record != null) {
            val blobs = JSONObject()
            extra.put(REC, RecordJson.encode(record) { h, b -> blobs.put(h, Base64.encodeToString(b, Base64.NO_WRAP)) })
            extra.put(REC_BLOBS, blobs)
            // [recordOf] (a restored backup): the hash stored with the record, so "edited since" survives.
            extra.put(REC_OF, recordOf ?: RecordJson.sha256Hex(ContactDetailsJson.encode(ContactDetailsJson.decode(detailsJson)).toByteArray()))
        }
        if (interactions != null) extra.put(INTERACTIONS, interactions)
        var keptExtra: ByteArray? = null
        var asideDone = false
        if (existing != null && (record == null || interactions == null)) {
            // Keep the original record (the details hash then no longer matches: it was edited) and the carried
            // interactions through edits: an extra part as it is sealed, without opening it.
            val keep = (if (record == null) listOf(REC, REC_BLOBS, REC_OF) else emptyList()) +
                (if (interactions == null) listOf(INTERACTIONS) else emptyList())
            if (record == null && interactions == null && VaultCrypto.isParts(existing.detailBlob)) {
                keptExtra = VaultCrypto.extraPart(existing.detailBlob)
            } else {
                val old = try {
                    extrasOf(existing.detailBlob)
                } catch (_: VaultCrypto.KeyLostException) {
                    // The user is saving over a record that can't be opened any more: keep the old blob aside first.
                    setAside(existing.id, existing.detailBlob)
                    asideDone = true
                    null
                }
                old?.let { keep.forEach { k -> if (old.has(k)) extra.put(k, old.get(k)) } }
            }
        }
        val extraBytes = extra.takeIf { it.length() > 0 }?.toString()?.toByteArray()
        val detailBlob = try {
            VaultCrypto.sealDetailParts(detailsJson.toByteArray(), extraBytes, keptExtra)
        } catch (lost: VaultCrypto.KeyLostException) {
            // The kept part's key is gone (it would have to be re-sealed): keep the old blob aside, save without it.
            if (keptExtra == null || existing == null) throw lost
            setAside(existing.id, existing.detailBlob)
            asideDone = true
            VaultCrypto.sealDetailParts(detailsJson.toByteArray(), null)
        }
        // Saved under another key than before: if the old one is gone for good, the old blob is kept aside too.
        if (existing != null && !asideDone && VaultCrypto.generationOf(existing.detailBlob) != VaultCrypto.generationOf(detailBlob)) {
            val lost = try {
                VaultCrypto.openDetailMain(existing.detailBlob)
                false
            } catch (_: VaultCrypto.KeyLostException) {
                true
            } catch (_: Exception) {
                false
            }
            if (lost) setAside(existing.id, existing.detailBlob)
        }
        return detailBlob
    }

    /**
     * Changes only entry [id]'s caller-ID copy ([change] edits its JSON): the star, labels, ringtone and "send to
     * voicemail" live there, so they change without unlocking the vault and the sealed details are never rewritten.
     * False when the entry is gone or its caller-ID copy can't be opened.
     */
    suspend fun updateCallerChoices(id: Long, change: (VaultSummary) -> VaultSummary): Boolean = withContext(Dispatchers.IO) {
        // An entry not seeded yet would have its old star, tone and labels replaced by the defaults: seed it first when
        // its details can be opened (a locked vault keeps the change to what the caller-ID copy has).
        if (dao.callerRow(id)?.let { summarize(it)?.choicesKnown } == false) runCatching { seedCallerChoices(id) }
        keysLock.withLock {
            val e = dao.callerRow(id) ?: return@withLock false
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock false
            val before = summarize(e) ?: return@withLock false
            val after = change(before)
            if (after == before) return@withLock true
            if (after.starred) o.put(C_STAR, true) else o.remove(C_STAR)
            CallerIdCopy.putLabels(o, after.labels)
            if (after.ringtone.isNullOrBlank()) o.remove(C_TONE) else o.put(C_TONE, after.ringtone)
            if (after.sendToVoicemail) o.put(C_VOICEMAIL, true) else o.remove(C_VOICEMAIL)
            CallerIdCopy.putPageChoices(o, after)
            after.archivedAt?.let { o.put(CallerIdCopy.C_ARCHIVED, it) } ?: o.remove(CallerIdCopy.C_ARCHIVED)
            // "u" stays: which of two entries sharing a number wins follows edits of the contact, not a star or a label.
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
            noteCallChoices(after.hasCallChoices)
            true
        }
    }

    /**
     * Fills entry [id]'s caller-ID copy with the star, labels, ringtone and "send to voicemail" it had before they were
     * kept there: from its sealed details and the address-book record it was moved in with
     * ([app.parley.common.people.PrivateCallerChoices]). Only keys the copy doesn't have yet are written, then it is
     * marked, so this runs once per entry. Throws [VaultCrypto.LockedException] when the details can't be opened now
     * (nothing changes); an entry whose detail key is lost is marked as it is. True when the entry is seeded now.
     */
    @Suppress("CyclomaticComplexMethod") // Each of the four choices is seeded only when absent: one check each.
    suspend fun seedCallerChoices(id: Long): Boolean = withContext(Dispatchers.IO) {
        keysLock.withLock {
            val e = dao.get(id) ?: return@withLock false
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock false
            if (o.has(C_SEEDED)) return@withLock true
            val detail = try {
                JSONObject(String(VaultCrypto.openDetailMain(e.detailBlob)))
            } catch (_: VaultCrypto.KeyLostException) {
                null
            }
            if (detail != null) {
                // Photos aren't needed for this: their blobs are left out.
                val line = runCatching { extrasOf(e.detailBlob, detail) }.getOrNull()?.optString(REC).orEmpty()
                val record = line.takeIf { it.isNotEmpty() }?.let { runCatching { RecordJson.decode(it) { null } }.getOrNull() }
                val seed = PrivateCallerChoices.seed(
                    detail.optBoolean("starred"), detail.optString("ringtone").ifEmpty { null }, detail.optBoolean("vm"), record,
                )
                if (!o.has(C_STAR) && seed.starred) o.put(C_STAR, true)
                if (!o.has(C_LABELS)) CallerIdCopy.putLabels(o, seed.labels)
                if (!o.has(C_TONE)) seed.ringtone?.let { o.put(C_TONE, it) }
                if (!o.has(C_VOICEMAIL) && seed.sendToVoicemail) o.put(C_VOICEMAIL, true)
            }
            o.put(C_SEEDED, 1)
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
            noteCallChoices(o.optBoolean(C_VOICEMAIL) || o.has(C_TONE) || o.has(C_LABELS))
            true
        }
    }

    /**
     * Migration, once, after the vault's unlock: [seedCallerChoices] for every entry saved before the caller-ID copy
     * kept the star, labels, ringtone and voicemail. Stops while the vault is locked and runs again on the next unlock;
     * marked done when every entry is seeded. Idempotent. Returns how many entries were seeded now.
     */
    suspend fun migrateCallerChoices(): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(K_CHOICES_SEEDED, false)) return@withContext 0
        var seeded = 0
        var pending = false
        for (e in dao.callerRowsNow()) {
            if (summarize(e)?.choicesKnown != false) continue
            try {
                if (seedCallerChoices(e.id)) seeded++ else pending = true
            } catch (_: VaultCrypto.LockedException) {
                return@withContext seeded
            } catch (_: VaultCrypto.KeyUnavailableException) {
                pending = true
            }
        }
        if (!pending) prefs.edit().putBoolean(K_CHOICES_SEEDED, true).apply()
        seeded
    }

    /** Entry [id]'s summary (caller-ID copy), read straight from the database; null when gone or unreadable. */
    suspend fun summary(id: Long): VaultSummary? = withContext(Dispatchers.IO) { dao.callerRow(id)?.let { summarize(it) } }

    /** A save or change gave a private contact something the call path applies. */
    private fun noteCallChoices(any: Boolean) {
        if (any) rememberCallChoices(true)
    }

    /** Set from the full listing; also stored, for a process started for a call before the listing is read. */
    private fun rememberCallChoices(any: Boolean) {
        if (hasCallChoices == any) return
        hasCallChoices = any
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_CALL_CHOICES, any).apply()
    }

    private fun region(): String = PhoneEnv.countryIso(context)

    /** Serialises writes of sealed details and fingerprints: saves, expiry changes, key upgrades and re-keying never interleave. */
    private val keysLock = Mutex()

    /**
     * E.164 fingerprints, plus the last-digits one as an extra fallback (so a number read with a different
     * region than at save time is still found by non-exact lookups; exact lookups never use it).
     */
    private fun numberRows(id: Long, numbers: List<String>, region: String?, withPrevious: Boolean = false): List<VaultNumberEntity> =
        (if (withPrevious) VaultNumberKeys.storedWithPrevious(numbers, region) else VaultNumberKeys.storedWithFallback(numbers, region))
            .map { VaultNumberEntity(id, VaultCrypto.hmac(it)) }

    /**
     * Migration, once: entries saved before E.164 keys were fingerprinted by their last 9 digits only. The
     * numbers are in the caller-ID copy, which opens without unlocking, so every entry is re-fingerprinted in place
     * (no schema change: same table, new rows). An entry that can't be read keeps its old rows, so it still works
     * as before. Until this finishes, lookups still find the old rows through the last-digits fallback.
     *
     * It runs again whenever [KEYS_VERSION] rises, re-fingerprinting every entry with how numbers are read now (and
     * the form an older version read, see [VaultNumberKeys.storedWithPrevious]).
     */
    private suspend fun migrateNumberKeys() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(K_KEYS_VERSION, 1) >= KEYS_VERSION) return@withContext
        val fallbackRegion = region()
        var failed = false
        for (id in dao.callerRowsNow().map { it.id }) {
            // Under the same lock as save and re-read inside the transaction, so a save that ran meanwhile is
            // never overwritten with the numbers it replaced.
            val ok = keysLock.withLock {
                db.withTransaction {
                    val e = dao.callerRow(id) ?: return@withTransaction true
                    val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withTransaction false
                    val s = summarize(e) ?: return@withTransaction false
                    val region = o.optString(C_REGION).ifEmpty { fallbackRegion }
                    val rows = catching { numberRows(e.id, s.numbers, region, withPrevious = true) }.getOrNull() ?: return@withTransaction false
                    dao.clearNumbers(e.id)
                    dao.addNumbers(rows)
                    true
                }
            }
            if (!ok) failed = true
        }
        // An unreadable entry (caller-ID key lost) can't get better by retrying; a Keystore hiccup might.
        if (!failed || prefs.getInt(K_KEYS_ATTEMPTS, 0) >= 2) {
            // The next re-keying starts with its own attempts.
            prefs.edit().putInt(K_KEYS_VERSION, KEYS_VERSION).remove(K_KEYS_ATTEMPTS).apply()
        } else {
            prefs.edit().putInt(K_KEYS_ATTEMPTS, prefs.getInt(K_KEYS_ATTEMPTS, 0) + 1).apply()
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_CALL_CHOICES, false)) hasCallChoices = true }
            VaultCallChoices.loaded = true
            // Then the listing decides (a stale "yes" costs only a lookup per call; a "no" only once none has any). It
            // opens every caller-ID copy, so it waits for the full app; a call's process goes by the stored answer.
            gate?.await()
            // From the listing itself, so a cold start opens nothing twice (and nothing one by one).
            catching { listing.filterNotNull().collect { list -> rememberCallChoices(list.any { it.hasCallChoices }) } }
        }
        scope.launch {
            gate?.await()
            runCatching { migrateNumberKeys() }
            // Settles the key generation from the stored blobs before anything audits it (an interrupted upgrade).
            runCatching { keysLock.withLock { VaultCrypto.reconcileGenerations(generationsInUse()) } }
        }
    }

    /**
     * Only the expiry column, and with [purgeHistory] (null: unchanged) the call-history choice, which the caller-ID
     * copy keeps: the sealed details are never re-sealed, so this works while the vault is locked or its detail key is
     * lost, and a re-seal or a save running meanwhile is never undone by an older copy of the row.
     */
    suspend fun setExpiry(id: Long, expiresAt: Long?, purgeHistory: Boolean? = null) = withContext(Dispatchers.IO) {
        keysLock.withLock {
            dao.setExpiry(id, expiresAt)
            if (purgeHistory == null) return@withLock
            val e = dao.callerRow(id) ?: return@withLock
            val o = runCatching { JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob))) }.getOrNull() ?: return@withLock
            if (o.optBoolean("purge", false) == purgeHistory) return@withLock
            if (purgeHistory) o.put("purge", true) else o.remove("purge")
            dao.setCallerIdBlob(id, VaultCrypto.sealCallerId(o.toString().toByteArray()))
        }
    }

    /** Deletes a private contact with its fingerprints and private calls, all or nothing; then its photo. */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        keysLock.withLock {
            db.withTransaction {
                dao.delete(id)
                dao.clearNumbers(id)
                dao.deletePrivateCalls(id)
            }
        }
        openedMain.remove(id)
        listRows.forget(id)
        photoFile(id).delete()
        app.parley.data.people.OriginalPhotos.forgetPrivate(context, id)
    }

    /**
     * Caller ID for incoming calls; works without user authentication.
     *
     * Matched on the E.164 form, reading a national number with [countryIso] (the country of the SIM that took
     * the call when known, else this phone's region); the last digits are only a fallback for entries stored without
     * an E.164 form, and [exact] (the private-name provider) never uses them. Expired entries never match (a temporary contact that ran out names nobody), and
     * of several entries sharing a number the most recently updated wins.
     */
    suspend fun lookup(number: String, countryIso: String? = null, exact: Boolean = false): Pair<Long, CallerInfo>? = withContext(Dispatchers.IO) {
        if (number.isBlank()) return@withContext null
        val now = System.currentTimeMillis()
        for (input in VaultNumberKeys.lookup(number, countryIso ?: region(), exact)) {
            val ids = dao.idsByHmac(listOf(VaultCrypto.hmac(input)))
            if (ids.isEmpty()) continue
            val candidates = ids.mapNotNull { id ->
                // The caller row only: the sealed details stay in the database while the phone rings.
                val e = dao.callerRow(id) ?: return@mapNotNull null
                val s = summarize(e) ?: return@mapNotNull null
                s to VaultNumberKeys.Candidate(id, s.updatedAt, e.createdAt, e.expiresAt)
            }
            val win = VaultNumberKeys.winner(candidates.map { it.second }, now) ?: continue
            val s = candidates.first { it.second.id == win.id }.first
            return@withContext win.id to CallerInfo(
                contactId = -win.id, lookupKey = null, name = s.name, photoUri = null,
                numberLabel = NotificationPrivacy.VAULT_LABEL, customRingtone = s.ringtone, sendToVoicemail = s.sendToVoicemail,
                starred = s.starred, alternativeName = s.nameAlt,
            )
        }
        null
    }

    /** The caller card of entry [id] (no unlock needed), or null. */
    suspend fun callerCard(id: Long): VaultCallerCard? = withContext(Dispatchers.IO) {
        val e = dao.callerRow(id) ?: return@withContext null
        catching {
            val o = JSONObject(String(VaultCrypto.openCallerId(e.callerIdBlob)))
            VaultCallerCard(
                o.optString("name"), o.optString("sub").ifEmpty { null }, o.optString("ctx").ifEmpty { null },
                o.optString("note").ifEmpty { null }, photoUri(id), pronouns = o.optString(C_PRONOUNS).ifEmpty { null },
                nativeName = o.optString(CallerIdCopy.C_NATIVE_NAME).ifEmpty { null },
            )
        }.getOrNull()
    }

    // ---- Encrypted photo (the caller-ID key, so the call screen can show it while the phone is locked) ----

    private fun photoDir() = File(context.filesDir, "vault_photos").apply { mkdirs() }
    private fun photoFile(id: Long) = File(photoDir(), "$id.bin")

    /**
     * The in-app URI of entry [id]'s photo (served decrypted only inside Parley by the non-exported vault photo
     * provider), or null when it has none. The file time is part of the URI so a new photo isn't served from cache.
     */
    fun photoUri(id: Long): String? {
        val f = photoFile(id)
        return if (f.isFile) "content://${context.packageName}.vaultphotos/$id/${f.lastModified()}" else null
    }

    /** The decrypted photo (JPEG) of entry [id], or null. */
    fun photoBytes(id: Long): ByteArray? = runCatching {
        val f = photoFile(id)
        if (!f.isFile) null else VaultCrypto.openCallerId(f.readBytes())
    }.getOrNull()

    /** Stores [image] (any format Android decodes) as entry [id]'s photo, scaled down and encrypted. */
    suspend fun setPhoto(id: Long, image: ByteArray): Boolean = withContext(Dispatchers.IO) {
        // Bounded decode, upright, centre square (512 px is plenty for a caller photo).
        val jpeg = ContactPhotoProcessor.process(image, PHOTO_PX) ?: return@withContext false
        DurableFiles.write(photoFile(id), VaultCrypto.sealCallerId(jpeg))
    }

    /** Deletes entry [id]'s photo; off the main thread, like [setPhoto] (the editor's save calls it from there). */
    suspend fun removePhoto(id: Long) = withContext(Dispatchers.IO) { photoFile(id).delete() }

    /** Expired entries with what housekeeping needs to clean up after them. */
    suspend fun expiredEntries(now: Long): List<VaultSummary> = withContext(Dispatchers.IO) {
        dao.expired(now).map { e -> summarize(e) ?: VaultSummary(e.id, "", emptyList(), e.expiresAt) }
    }

    /**
     * Entries being made visible ([VaultMoves.moveOut]): their calls were just put back into the phone's call history,
     * so a sweep running meanwhile must not take them back into the private history the entry is deleted with.
     */
    val leaving: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Moves call-log rows for vault numbers out of the system log into the encrypted private
     * history (needs WRITE_CALL_LOG, granted by the dialer role). Returns rows moved.
     *
     * A provider row is deleted only once its private copy is stored (or was already, from an earlier sweep whose
     * delete failed): the unique dedupe key means a retry never stores a call twice, and a failed insert never loses
     * the call.
     */
    suspend fun sweepCallLog(sinceMillis: Long): Int = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val ids = ArrayList<Long>()
        // Only calls that may be private are looked up (see PrivateCallSeal.prefilter).
        val maybePrivate = PrivateCallSeal.prefilter(summarizeAll(dao.callerRowsNow()).map { it.numbers to it.region }, region())
            ?: return@withContext 0
        try {
            cr.query(
                CallLog.Calls.CONTENT_URI,
                SWEEP_COLUMNS, "${CallLog.Calls.DATE} >= ?", arrayOf(sinceMillis.toString()), null,
            )?.use { c ->
                val telephony = TelephonyPackages.of(context)
                while (c.moveToNext()) keepPrivate(c, maybePrivate, telephony)?.let { ids += it }
            }
            ids.chunked(500).forEach { chunk -> cr.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${chunk.joinToString(",")})", null) }
        } catch (_: SecurityException) {
        }
        ids.size
    }

    /**
     * Stores one private call unless it is already there (a sweep retried, a restore run twice). True when the call is
     * now stored, whether by this call or before.
     */
    suspend fun storePrivateCall(
        vaultId: Long, number: String, name: String, date: Long, durationSec: Long, type: Int, video: Boolean = false, app: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        // Rows from before the dedupe key have none: count those by their columns.
        if (dao.countPrivateCall(vaultId, date, type) > 0) return@withContext true
        val blob = callSeal.seal(privateCallPlain(number, name, video, app))
        dao.addPrivateCall(
            PrivateCallEntity(
                vaultId = vaultId,
                blob = blob,
                date = date,
                durationSec = durationSec,
                type = type,
                dedupeKey = PrivateCallEntity.dedupeKey(vaultId, date, type),
            ),
        )
        true
    }

    suspend fun deletePrivateCall(id: Long) = withContext(Dispatchers.IO) { dao.deletePrivateCall(id) }

    /**
     * Deletes these private calls and returns their stored (still sealed) rows, so [restorePrivateCalls] can put them
     * back for an Undo without opening them.
     */
    suspend fun deletePrivateCallsForUndo(ids: List<Long>): List<PrivateCallEntity> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val rows = dao.privateCallsById(ids)
        rows.forEach { dao.deletePrivateCall(it.id) }
        rows
    }

    suspend fun restorePrivateCalls(rows: List<PrivateCallEntity>) = withContext(Dispatchers.IO) { rows.forEach { dao.addPrivateCall(it) } }

    /** Every private call, read straight from the database (not the listing, which starts with the full app). */
    suspend fun privateCallsNow(): List<PrivateCall> = withContext(Dispatchers.IO) { dao.allPrivateCalls().mapNotNull { callSeal.opened(it) } }

    suspend fun expired(now: Long) = withContext(Dispatchers.IO) { dao.expired(now).map { it.id } }

    /** The lossless phone-contact image stored by "Move to private", and whether the details were edited since. */
    data class StoredRecord(val record: ContactRecord, val editedSince: Boolean, val recordOf: String = "")

    /**
     * The [StoredRecord] of entry [id], or null for entries made in the vault or before records were kept. Throws
     * [VaultCrypto.LockedException] when the vault must be unlocked first.
     */
    suspend fun storedRecord(id: Long): StoredRecord? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        val o = JSONObject(String(VaultCrypto.openDetailMain(e.detailBlob)))
        val x = extrasOf(e.detailBlob, o)
        val line = x.optString(REC).takeIf { it.isNotEmpty() } ?: return@withContext null
        val blobs = x.optJSONObject(REC_BLOBS)
        val record = runCatching { RecordJson.decode(line) { h -> blobs?.optString(h)?.takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) } } }.getOrNull()
            ?: return@withContext null
        val now = ContactDetailsJson.encode(ContactDetailsJson.decode(o.toString()))
        StoredRecord(record, RecordJson.sha256Hex(now.toByteArray()) != x.optString(REC_OF), x.optString(REC_OF))
    }

    /**
     * The interactions carried into entry [id] by "Move to private" ([app.parley.common.circle.Interactions.encodeCarried]
     * text), or null. Throws [VaultCrypto.LockedException] when the vault must be unlocked first.
     */
    suspend fun storedInteractions(id: Long): String? = withContext(Dispatchers.IO) {
        val e = dao.get(id) ?: return@withContext null
        extrasOf(e.detailBlob).optString(INTERACTIONS).takeIf { it.isNotEmpty() }
    }

    /**
     * Every private contact's own ringtone; null when one can't be read now (then no ringtone file counts as unused).
     * From the listing once it is open, else in one batch ([summarizeAll]), never one Keystore operation per contact.
     */
    suspend fun ringtonesNow(): List<String>? = withContext(Dispatchers.IO) {
        val rows = dao.callerRowsNow()
        val list = summarizeAll(rows)
        if (list.size != rows.size) null else list.mapNotNull { it.ringtone }
    }

    /** Every private contact, read straight from the database (not the UI flow, which starts empty), in one batch. */
    suspend fun summariesNow(): List<VaultSummary> = withContext(Dispatchers.IO) { summarizeAll(dao.callerRowsNow()) }

    /**
     * Archives private contact [id] ([at] its time) or, with null, lists it again. Only the caller-ID copy changes: the
     * contact stays sealed in the vault, under the same keys and the same lock, and its calls are still named. An
     * archived contact doesn't delete itself (its expiry goes, as an archived address-book contact's does). False when
     * the entry is gone or its copy can't be opened.
     */
    suspend fun setArchived(id: Long, at: Long?): Boolean {
        if (!updateCallerChoices(id) { it.copy(archivedAt = at) }) return false
        if (at != null) setExpiry(id, null)
        return true
    }

    /** The private calls of entry [vaultId], read straight from the database (backup). */
    suspend fun privateCallsOf(vaultId: Long): List<PrivateCall> = withContext(Dispatchers.IO) { dao.privateCallsOf(vaultId).mapNotNull(callSeal::opened) }

    /** How many private calls entry [vaultId] holds, whether or not each can be opened now. */
    suspend fun privateCallCount(vaultId: Long): Int = withContext(Dispatchers.IO) { dao.privateCallCount(vaultId) }

    /** Every private contact's numbers, read straight from the database (import duplicate checks). */
    suspend fun allNumbers(): List<String> = withContext(Dispatchers.IO) { summarizeAll(dao.callerRowsNow()).flatMap { it.numbers } }

    private companion object {
        const val PREFS = "vault"
        const val PHOTO_PX = 512
        const val K_KEYS_VERSION = "number_keys_version"
        const val K_KEYS_ATTEMPTS = "number_keys_attempts"

        /**
         * 1: last 9 digits (the oldest entries); 2: E.164 with the last digits only as a fallback; 3: E.164 plus the last
         * digits as an extra fallback for every number, with the region stored at save time; 4: the E.164 form
         * libphonenumber reads (an Argentine "15" mobile, a country the older table missed), keeping the older form too.
         */
        const val KEYS_VERSION = 4
        const val REC = "parleyRecord"
        const val REC_BLOBS = "parleyRecordBlobs"
        const val REC_OF = "parleyRecordOf"
        const val INTERACTIONS = "parleyInteractions"
        const val K_CALL_CHOICES = "call_choices"
        const val K_CHOICES_SEEDED = "caller_choices_seeded"
        const val K_SPLIT = "details_split"

        /** What goes in the extra part of the sealed details (VaultCrypto.sealDetailParts). */
        val EXTRA_KEYS = listOf(REC, REC_BLOBS, REC_OF, INTERACTIONS)

        /** How long opened details stay in memory: short, and only while the phone is unlocked (see openMain). */
        const val OPENED_MS = 60_000L
    }
}
