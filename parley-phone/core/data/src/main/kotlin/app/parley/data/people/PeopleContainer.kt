package app.parley.data.people

import app.parley.common.LabelRefs
import app.parley.common.storage.PersistentStores
import app.parley.common.PhoneIdentity
import android.util.Base64
import app.parley.common.PhoneNumbers
import app.parley.common.people.ContactRef
import app.parley.data.DataContainer
import app.parley.data.backup.BackupExtras
import app.parley.data.backup.ConfirmedRestore
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contacts and privacy features (labels, favourites order, account tools, SIM, provenance, call backgrounds,
 * contacts-access audit, private-name lookup, diagnostics). Created lazily by [DataContainer.people].
 *
 * Hooks for other parts of the app:
 * - Ringer: [ringtoneForLabel] / [ringtoneForNumber] resolve contact → label → default. The contact's own
 *   ringtone (Contacts.CUSTOM_RINGTONE, see `CallerInfo.customRingtone`) wins; call [ringtoneForNumber] only
 *   when it's null; null here means "use the default ringtone".
 * - In-call screen: [callBackgroundFor] returns a file URI string of the caller's background image, or null.
 */
class PeopleContainer(private val c: DataContainer) {
    val prefs: PeoplePrefs = c.peoplePrefs
    val index = PeopleIndex(c.appContext, c.contacts, c.scope, c.fullStart.sharing)
    val labelRefs = LabelReferences(c, prefs)
    val labels = LabelsRepository(c.appContext, c.contacts, labelRefs) { c.privateLabels }
    val backgrounds = CallBackgrounds(c.appContext, c.contacts)

    /** Contact photos as picked (full size, uncropped), beside Android's reduced copy. */
    val originals by lazy { OriginalPhotos(c.appContext) }

    /** Two-way relations between saved contacts. */
    val relationMirrors by lazy { RelationMirrors(c.appContext, c.contacts, c.meta) }

    val mover by lazy { ContactMover(c.appContext, c.contacts, c.records) }
    val accounts by lazy { AccountDiagnostics(c.appContext) }
    val sim by lazy { SimContacts(c.appContext) }
    val writeLog: ParleyWriteLog get() = c.contacts.writeLog
    val provenance by lazy { ProvenanceReader(c.appContext, writeLog) }
    val audit by lazy { ContactsAudit(c.appContext) }
    val privateNames by lazy { PrivateNameAccess(c.appContext) }
    val diagnostics by lazy { Diagnostics(c.appContext) }

    /**
     * Your own card. The old "My details" is folded into it the first time it's used, so there's one copy: written to
     * disk first, then the old copy is forgotten. [DataContainer.warmStores] builds it on IO at start-up.
     */
    val me by lazy {
        MeCardStore(c.appContext).also { store ->
            c.messaging.legacyMyDetails()?.let { old ->
                store.absorbMyDetails(old.name, old.number)
                c.messaging.forgetLegacyMyDetails()
            }
        }
    }

    /** My card's id and signing key (I14). */
    val cardIdentity by lazy { MyCardIdentity(c.appContext) }

    /** "Shared with": who got your card (I22). */
    val shareLedger by lazy { ShareLedgerStore(c.appContext) }

    /** Contacts linked to their signed cards, and updates waiting (I14). */
    val cardLinks by lazy { CardLinkStore(c.appContext) }

    /** Opt-in local crash capture. */
    val crashes by lazy { CrashStore(c.appContext) }
    val backupExtras: BackupExtras by lazy { PeopleBackupExtras(this, c) }

    /** True when any label has a ringtone, so incoming calls go through the path that plays it. */
    fun hasLabelRingtones(): Boolean = prefs.settings.value.labelRingtones.isNotEmpty()

    /** Ringtone chosen for a label (by title), or null. */
    fun ringtoneForLabel(label: String): String? = prefs.settings.value.labelRingtones[label]

    /**
     * Label ringtone for a caller: the first of the caller's labels (alphabetically) that has a ringtone.
     * Blocking contacts query: call off the main thread. Null = no label ringtone.
     */
    fun ringtoneForNumber(number: String): String? {
        val tones = prefs.settings.value.labelRingtones
        if (tones.isEmpty()) return null
        val contactId = c.contacts.lookup(number)?.contactId ?: return null
        return LabelRefs.ringtoneFor(labelsOf(contactId), tones)
    }

    fun callBackgroundFor(number: String): String? = backgrounds.callBackgroundFor(number)

    /** Label titles of one contact, straight from the provider (works before the index has loaded). */
    fun labelsOf(contactId: Long): Set<String> = c.contacts.labelTitlesOf(contactId)
}

/**
 * Backs up people preferences, private-name approvals and call backgrounds (matched back by name and number). The
 * approvals decide which apps may read private names, so a restore keeps them waiting for the user's confirmation.
 */
private class PeopleBackupExtras(private val p: PeopleContainer, private val c: DataContainer) : BackupExtras, ConfirmedRestore {
    @Volatile private var pendingApprovals: String? = null

    override fun hasPending(): Boolean = pendingApprovals != null

    override suspend fun applyPending(): Boolean {
        val a = pendingApprovals ?: return false
        pendingApprovals = null
        p.privateNames.importApprovals(a)
        return true
    }

    override fun discardPending() {
        pendingApprovals = null
    }

    override val section = "people"
    override val sections = setOf(PersistentStores.Sections.PEOPLE)

    override suspend fun export(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        p.prefs.exportMap().forEach { (k, v) -> out["${BackupExtras.PREFIX}people.$k"] = v }
        out["${BackupExtras.PREFIX}privatenames.approvals"] = p.privateNames.exportApprovals()
        p.me.exportJson()?.let { out["${BackupExtras.PREFIX}me.card"] = it }
        p.cardIdentity.exportJson()?.let { out["${BackupExtras.PREFIX}me.identity"] = it }
        p.shareLedger.exportJson()?.let { out["${BackupExtras.PREFIX}me.shared"] = it }
        // Private contacts' links travel only in the private-contacts section (ContactKeys.exportPrivate).
        p.cardLinks.exportDevice(ContactRef::isPrivateKey)?.let { out["${BackupExtras.PREFIX}cards.links"] = it }
        val stored = p.backgrounds.storedHashes()
        if (stored.isNotEmpty()) {
            val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }.orEmpty()
            var total = 0L
            for (ct in contacts) {
                if (p.backgrounds.hashOf(ct.lookupKey) !in stored) continue
                val bytes = p.backgrounds.read(ct.lookupKey) ?: continue
                if (total + bytes.size > MAX_BACKGROUND_BYTES) break
                total += bytes.size
                out["${BackupExtras.PREFIX}bg.${ct.lookupKey}"] = JSONObject()
                    .put("name", ct.displayName)
                    .put("phones", JSONArray(ct.phones.mapNotNull { PhoneIdentity.portableKey(it.number) }))
                    .put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString()
            }
        }
        exportOriginals(out)
        return out
    }

    /**
     * Original photos of phone contacts, matched back like the backgrounds. The section is held in memory on
     * restore, so they share a budget: the rest keep Android's copy (in the contacts section) after a restore.
     */
    private suspend fun exportOriginals(out: MutableMap<String, String>) {
        val keys = p.originals.keys()
        if (keys.isEmpty()) return
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }.orEmpty()
        var total = 0L
        for (ct in contacts) {
            if (ct.lookupKey !in keys) continue
            val bytes = p.originals.read(ct.lookupKey) ?: continue
            if (total + bytes.size > MAX_ORIGINAL_BYTES) continue
            total += bytes.size
            out["${BackupExtras.PREFIX}orig.${ct.lookupKey}"] = JSONObject()
                .put("name", ct.displayName)
                .put("phones", JSONArray(ct.phones.mapNotNull { PhoneIdentity.portableKey(it.number) }))
                .put("image", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .toString()
        }
    }

    private suspend fun importOriginals(values: Map<String, String>) {
        val all = values.filterKeys { it.startsWith("${BackupExtras.PREFIX}orig.") }
        if (all.isEmpty()) return
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }.orEmpty()
        val byKey = contacts.associateBy { it.lookupKey }
        for ((k, json) in all) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val key = k.removePrefix("${BackupExtras.PREFIX}orig.")
            val phones = o.optJSONArray("phones")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
            val name = o.optString("name")
            val target = byKey[key] ?: contacts.firstOrNull { ct ->
                ct.displayName == name && (phones.isEmpty() || ct.phones.any { PhoneIdentity.portableKey(it.number) in phones })
            } ?: continue
            val bytes = runCatching { Base64.decode(o.getString("image"), Base64.NO_WRAP) }.getOrNull() ?: continue
            runCatching { p.originals.restore(target.lookupKey, bytes, target.photoUri) }
        }
    }

    override suspend fun import(values: Map<String, String>) {
        val peoplePrefix = "${BackupExtras.PREFIX}people."
        p.prefs.importMap(values.filterKeys { it.startsWith(peoplePrefix) }.mapKeys { it.key.removePrefix(peoplePrefix) })
        values["${BackupExtras.PREFIX}privatenames.approvals"]?.let { a -> if (a != p.privateNames.exportApprovals()) pendingApprovals = a }
        values["${BackupExtras.PREFIX}me.card"]?.let { p.me.importJson(it) }
        values["${BackupExtras.PREFIX}me.identity"]?.let { p.cardIdentity.importJson(it) }
        values["${BackupExtras.PREFIX}me.shared"]?.let { p.shareLedger.importJson(it) }
        values["${BackupExtras.PREFIX}cards.links"]?.let { importCardLinks(it) }
        importOriginals(values)
        val bgs = values.filterKeys { it.startsWith("${BackupExtras.PREFIX}bg.") }
        if (bgs.isEmpty()) return
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }.orEmpty()
        val byKey = contacts.associateBy { it.lookupKey }
        for ((k, json) in bgs) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: continue
            val key = k.removePrefix("${BackupExtras.PREFIX}bg.")
            val phones = o.optJSONArray("phones")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
            val name = o.optString("name")
            // Lookup keys change when contacts are restored on another phone: fall back to name + a shared number.
            val target = byKey[key] ?: contacts.firstOrNull { ct ->
                ct.displayName == name && (phones.isEmpty() || ct.phones.any { PhoneIdentity.portableKey(it.number) in phones })
            } ?: continue
            val bytes = runCatching { Base64.decode(o.getString("jpeg"), Base64.NO_WRAP) }.getOrNull() ?: continue
            p.backgrounds.write(target.lookupKey, bytes)
        }
    }

    /** Device contacts' card links: under the same lookup key when it still exists, else the contact with a card number. */
    private suspend fun importCardLinks(json: String) {
        val contacts = withTimeoutOrNull(30_000) { c.contacts.contacts.filterNotNull().first() }.orEmpty()
        val keys = contacts.map { it.lookupKey }.toSet()
        p.cardLinks.importDevice(json) { key, fields ->
            key.takeIf { it in keys } ?: contacts.filter { ct -> ct.phones.any { ph -> fields.phones.any { PhoneNumbers.same(it, ph.number, null) } } }
                .singleOrNull()?.lookupKey
        }
    }

    private companion object {
        /** Keeps the backup's settings section well under its in-memory limit. */
        const val MAX_BACKGROUND_BYTES = 6L shl 20

        /** Original photos' share of the same section (the section is read into memory, at most 16 MB). */
        const val MAX_ORIGINAL_BYTES = 4L shl 20
    }
}
