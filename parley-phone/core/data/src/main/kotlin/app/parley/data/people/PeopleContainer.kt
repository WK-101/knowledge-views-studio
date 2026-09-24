package app.parley.data.people

import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.Data
import android.util.Base64
import app.parley.common.Duplicates
import app.parley.common.PhoneNumbers
import app.parley.data.DataContainer
import app.parley.data.backup.BackupExtras
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
    val prefs = PeoplePrefs(c.appContext, c.scope)
    val index = PeopleIndex(c.appContext, c.contacts, c.scope)
    val labels = LabelsRepository(c.appContext, c.contacts)
    val backgrounds = CallBackgrounds(c.appContext, c.contacts)
    val mover by lazy { ContactMover(c.appContext, c.contacts, c.records) }
    val accounts by lazy { AccountDiagnostics(c.appContext) }
    val sim by lazy { SimContacts(c.appContext) }
    val writeLog: ParleyWriteLog get() = c.contacts.writeLog
    val provenance by lazy { ProvenanceReader(c.appContext, writeLog) }
    val audit by lazy { ContactsAudit(c.appContext) }
    val privateNames by lazy { PrivateNameAccess(c.appContext) }
    val diagnostics by lazy { Diagnostics(c.appContext) }
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
        return labelsOf(contactId).sorted().firstNotNullOfOrNull { tones[it] }
    }

    fun callBackgroundFor(number: String): String? = backgrounds.callBackgroundFor(number)

    /** Label titles of one contact, straight from the provider (works before the index has loaded). */
    fun labelsOf(contactId: Long): Set<String> {
        val cr = c.appContext.contentResolver
        val groupIds = HashSet<Long>()
        try {
            cr.query(Data.CONTENT_URI, arrayOf(GroupMembership.GROUP_ROW_ID), "${Data.CONTACT_ID}=? AND ${Data.MIMETYPE}=?", arrayOf(contactId.toString(), GroupMembership.CONTENT_ITEM_TYPE), null)
                ?.use { q -> while (q.moveToNext()) groupIds += q.getLong(0) }
        } catch (_: Exception) {
        }
        if (groupIds.isEmpty()) return emptySet()
        return c.contacts.groups().filter { it.id in groupIds }.map { it.title.trim() }.toSet()
    }
}

/** Backs up people preferences, private-name approvals and call backgrounds (matched back by name and number). */
private class PeopleBackupExtras(private val p: PeopleContainer, private val c: DataContainer) : BackupExtras {
    override suspend fun export(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        p.prefs.exportMap().forEach { (k, v) -> out["${BackupExtras.PREFIX}people.$k"] = v }
        out["${BackupExtras.PREFIX}privatenames.approvals"] = p.privateNames.exportApprovals()
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
                    .put("phones", JSONArray(ct.phones.mapNotNull { Duplicates.phoneKey(it.number) }))
                    .put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .toString()
            }
        }
        return out
    }

    override suspend fun import(values: Map<String, String>) {
        val peoplePrefix = "${BackupExtras.PREFIX}people."
        p.prefs.importMap(values.filterKeys { it.startsWith(peoplePrefix) }.mapKeys { it.key.removePrefix(peoplePrefix) })
        values["${BackupExtras.PREFIX}privatenames.approvals"]?.let { p.privateNames.importApprovals(it) }
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
                ct.displayName == name && (phones.isEmpty() || ct.phones.any { PhoneNumbers.matchKey(it.number).let { mk -> mk.length >= 7 && mk in phones } })
            } ?: continue
            val bytes = runCatching { Base64.decode(o.getString("jpeg"), Base64.NO_WRAP) }.getOrNull() ?: continue
            p.backgrounds.write(target.lookupKey, bytes)
        }
    }

    private companion object {
        /** Keeps the backup's settings section well under its in-memory limit. */
        const val MAX_BACKGROUND_BYTES = 6L shl 20
    }
}
