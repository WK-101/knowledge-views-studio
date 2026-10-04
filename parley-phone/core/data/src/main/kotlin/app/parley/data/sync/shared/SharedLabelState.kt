package app.parley.data.sync.shared

import android.util.Base64
import app.parley.common.sync.shared.CardField
import app.parley.common.sync.shared.Carried
import app.parley.common.sync.shared.ChangeKind
import app.parley.common.sync.shared.HistoryItem
import app.parley.common.sync.shared.JournalEntry
import app.parley.common.sync.shared.LabelMember
import app.parley.common.sync.shared.SharedLabelMembership.State
import app.parley.common.sync.shared.SharedLabelRules
import app.parley.common.sync.shared.Ticket
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** How a shared label's last run ended, for its status line. */
enum class SharedRunResult { SYNCED, FOLDER_GONE, FOLDER_LOADING, NO_PERMISSION, KEY_CHANGED, PAUSED, CANT_SIGN, NOT_A_LABEL, LABEL_GONE }

/**
 * Everything this phone keeps about one shared label (docs/SHARED_LABELS.md): its key (only ever stored sealed),
 * who anchors it, this phone's membership and journal, the last synced version of each contact, the versions seen of
 * each sid (deletions too: replay detection), changes waiting for a choice, and the history lines.
 */
data class SharedLabelState(
    val labelId: String,
    /** The label on this phone that is shared (follows renames made on the label page). */
    val title: String,
    /** The shared folder; empty for a label shared by update files only ([byFile]), whose files stay on this phone. */
    val folderUri: String,
    val folderName: String,
    val key: ByteArray,
    val anchor: ByteArray,
    val anchorName: String,
    val myName: String,
    val ticket: Ticket?,
    val carried: List<Carried> = emptyList(),
    val membership: State,
    val entries: Map<String, Entry> = emptyMap(),
    val seen: Map<String, Long> = emptyMap(),
    val pending: Map<String, Pending> = emptyMap(),
    val history: List<HistoryItem> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val members: List<LabelMember> = emptyList(),
    /** File name → stamp when last read, so unchanged files aren't read again. */
    val stamps: Map<String, String> = emptyMap(),
    val lastSyncAt: Long = 0,
    val lastResult: SharedRunResult? = null,
    val pendingDeletions: Int = 0,
    val privateLeftOut: Int = 0,
    /** While a key change this phone started isn't finished in the folder: the old key and the new header to write. */
    val oldKey: ByteArray? = null,
    val newHeader: ByteArray? = null,
    /** M2: contact files this phone syncs that it couldn't use, by sid: since when (see [SharedLabelRules.unreadable]). */
    val unreadable: Map<String, Unreadable> = emptyMap(),
    /** L7: files that couldn't be used, by name → stamp, so the same junk isn't opened every run. */
    val junk: Map<String, String> = emptyMap(),
    /** M3: the last header this phone accepted; a header swapped in without a signed key change is not followed. */
    val header: ByteArray? = null,
    /** M3: the folder's header isn't the one this phone accepted, and no member signed a key change. */
    val headerWarning: Boolean = false,
    /** Update files opened, by sender (member key hash) → when they sent the last one: older ones are copies put back. */
    val exchanged: Map<String, Long> = emptyMap(),
    /** When this phone last made an update file (0: never). */
    val lastSentAt: Long = 0,
) {
    /** Shared by update files only: no folder, the label's files are kept in this phone's own storage. */
    val byFile: Boolean get() = folderUri.isEmpty()

    /**
     * One contact's last synced state: its contact, the version and card ([base]) synced, and how it got here.
     * [fileHash]: the hash of the signed file this phone accepted (or wrote) at [ver] ([CardFile.bodyHash]), so it can
     * tell later that a file is exactly that one. [prior]: the versions this phone held before [ver], newest first
     * (at most [SharedLabelRules.PRIOR_VERSIONS]), so an edit made from one of them merges against it
     * ([SharedLabelRules.concurrent]).
     */
    data class Entry(
        val key: String,
        val contactId: Long,
        val ver: Long,
        val base: String,
        val baseHash: String,
        val imported: Boolean,
        val fileHash: String = "",
        val prior: List<Prior> = emptyList(),
    ) {
        /** [prior] with this entry's own version first, and [also] (another version the next one descends from). */
        fun priorForNext(also: Prior? = null): List<Prior> =
            (listOfNotNull(also, Prior(ver, base).takeIf { ver > 0 && base.isNotEmpty() }) + prior)
                .distinctBy { it.ver }.take(SharedLabelRules.PRIOR_VERSIONS)
    }

    /** A version of a contact this phone held, and its card then. */
    data class Prior(val ver: Long, val base: String)

    /** A file this phone couldn't use [since] then; [stranger]: well formed and signed, but not by a member. */
    data class Unreadable(val since: Long, val stranger: Boolean)

    /**
     * A contact both this phone and [authorName] changed: [theirs] (at [ver]) waits until the user picks [fields].
     * [base]: the card both started from when it isn't the synced one (an edit made alongside); empty otherwise.
     */
    data class Pending(val theirs: String, val ver: Long, val authorName: String, val fields: Set<CardField>, val base: String = "")

    val epoch: Int get() = when (val m = membership) {
        is State.Active -> m.epoch
        is State.KeyChanged -> m.epoch
        State.Left -> 0
    }

    override fun equals(other: Any?) = other is SharedLabelState && other.toJson().toString() == toJson().toString()

    override fun hashCode() = labelId.hashCode()

    @Suppress("CyclomaticComplexMethod")
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", labelId); put("title", title); put("folder", folderUri); put("folderName", folderName)
        put("key", b64(key)); put("anchor", b64(anchor)); put("anchorName", anchorName); put("me", myName)
        ticket?.let {
            val t = JSONObject().put("by", b64(it.inviter)).put("id", it.inviteId).put("epoch", it.epoch).put("sig", b64(it.signature))
            put("ticket", t.put("exp", it.expiresAt))
        }
        put("carried", JSONArray().apply { carried.forEach { put(JSONObject().put("key", b64(it.key)).put("name", it.name)) } })
        put(
            "membership",
            when (val m = membership) {
                is State.Active -> JSONObject().put("s", "active").put("e", m.epoch)
                is State.KeyChanged -> JSONObject().put("s", "key").put("e", m.epoch).put("f", m.folderEpoch)
                State.Left -> JSONObject().put("s", "left")
            },
        )
        put(
            "entries",
            JSONObject().apply {
                entries.forEach { (sid, e) ->
                    put(
                        sid,
                        JSONObject().put("k", e.key).put("c", e.contactId).put("v", e.ver).put("b", e.base).put("h", e.baseHash).put("i", e.imported)
                            .put("fh", e.fileHash).put("pr", JSONArray().apply { e.prior.forEach { put(JSONObject().put("v", it.ver).put("b", it.base)) } }),
                    )
                }
            },
        )
        put("seen", JSONObject().apply { seen.forEach { (k, v) -> put(k, v) } })
        put(
            "pending",
            JSONObject().apply {
                pending.forEach { (sid, p) ->
                    val o = JSONObject().put("t", p.theirs).put("v", p.ver).put("a", p.authorName).put("f", JSONArray(p.fields.map { it.name }))
                    put(sid, if (p.base.isEmpty()) o else o.put("b", p.base))
                }
            },
        )
        put("history", JSONArray().apply { history.forEach { put(historyJson(it)) } })
        put("journal", JSONArray().apply { journal.forEach { put(entryJson(it)) } })
        put(
            "members",
            JSONArray().apply {
                members.forEach { m ->
                    put(
                        JSONObject().put("key", b64(m.key)).put("name", m.name).put("fp", m.fingerprint).putOpt("by", m.invitedBy)
                            .put("anchor", m.anchor).put("await", m.awaitingKey),
                    )
                }
            },
        )
        put("stamps", JSONObject().apply { stamps.forEach { (k, v) -> put(k, v) } })
        put("lastAt", lastSyncAt); putOpt("result", lastResult?.name); put("pendingDel", pendingDeletions); put("private", privateLeftOut)
        oldKey?.let { put("oldKey", b64(it)) }
        newHeader?.let { put("newHeader", b64(it)) }
        put("unreadable", JSONObject().apply { unreadable.forEach { (k, u) -> put(k, JSONObject().put("t", u.since).put("s", u.stranger)) } })
        put("junk", JSONObject().apply { junk.forEach { (k, v) -> put(k, v) } })
        header?.let { put("header", b64(it)) }
        put("headerWarning", headerWarning)
        put("exchanged", JSONObject().apply { exchanged.forEach { (k, v) -> put(k, v) } })
        put("sentAt", lastSentAt)
    }

    companion object {
        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

        private fun historyJson(h: HistoryItem) = JSONObject().put("m", h.memberHex).put("n", h.memberName).put("e", h.entryId).put("s", h.sid)
            .put("c", h.contactName).put("k", h.kind.name).put("f", JSONArray(h.fields.map { it.name })).put("at", h.at)

        private fun entryJson(e: JournalEntry) = JSONObject().put("id", e.id).put("s", e.sid).put("k", e.kind.name)
            .put("f", JSONArray(e.fields.map { it.name })).put("c", e.contactName).put("at", e.at)

        private fun fields(a: JSONArray?): Set<CardField> =
            (0 until (a?.length() ?: 0)).mapNotNull { i -> runCatching { CardField.valueOf(a!!.getString(i)) }.getOrNull() }.toSet()

        private fun <T> JSONArray?.items(f: (JSONObject) -> T?): List<T> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }

        private fun <T> JSONObject?.byKey(f: (String, JSONObject) -> T?): Map<String, T> =
            if (this == null) emptyMap() else keys().asSequence().mapNotNull { k -> optJSONObject(k)?.let { f(k, it) }?.let { k to it } }.toMap()

        @Suppress("LongMethod")
        fun fromJson(o: JSONObject): SharedLabelState {
            val m = o.getJSONObject("membership")
            val membership = when (m.getString("s")) {
                "active" -> State.Active(m.getInt("e"))
                "key" -> State.KeyChanged(m.getInt("e"), m.getInt("f"))
                else -> State.Left
            }
            val seen = o.optJSONObject("seen")?.let { s -> s.keys().asSequence().associateWith { s.getLong(it) } }.orEmpty()
            val stamps = o.optJSONObject("stamps")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty()
            return SharedLabelState(
                labelId = o.getString("id"),
                title = o.getString("title"),
                folderUri = o.getString("folder"),
                folderName = o.optString("folderName"),
                key = unb64(o.getString("key")),
                anchor = unb64(o.getString("anchor")),
                anchorName = o.optString("anchorName"),
                myName = o.optString("me"),
                ticket = o.optJSONObject("ticket")?.let { t ->
                    Ticket(unb64(t.getString("by")), t.getString("id"), t.getInt("epoch"), unb64(t.getString("sig")), t.optLong("exp"))
                },
                carried = o.optJSONArray("carried").items { Carried(unb64(it.getString("key")), it.optString("name")) },
                membership = membership,
                entries = o.optJSONObject("entries").byKey { _, e ->
                    val prior = e.optJSONArray("pr").items { Prior(it.getLong("v"), it.getString("b")) }
                    Entry(e.getString("k"), e.getLong("c"), e.getLong("v"), e.getString("b"), e.getString("h"), e.optBoolean("i"), e.optString("fh"), prior)
                },
                seen = seen,
                pending = o.optJSONObject("pending").byKey { _, p ->
                    Pending(p.getString("t"), p.getLong("v"), p.optString("a"), fields(p.optJSONArray("f")), p.optString("b"))
                },
                history = o.optJSONArray("history").items { h ->
                    HistoryItem(
                        h.getString("m"), h.optString("n"), h.getLong("e"), h.getString("s"), h.optString("c"),
                        ChangeKind.valueOf(h.getString("k")), fields(h.optJSONArray("f")), h.getLong("at"),
                    )
                },
                journal = o.optJSONArray("journal").items { e ->
                    val kind = ChangeKind.valueOf(e.getString("k"))
                    JournalEntry(e.getLong("id"), e.getString("s"), kind, fields(e.optJSONArray("f")), e.optString("c"), e.getLong("at"))
                },
                members = o.optJSONArray("members").items { x ->
                    val key = unb64(x.getString("key"))
                    LabelMember(
                        app.parley.common.sync.shared.SharedLabelFiles.keyHex(key), key, x.optString("name"),
                        // Worked out from the key, not read back: stored fingerprints of older versions were shorter.
                        app.parley.common.spam.Ed25519.fingerprint(key),
                        x.optString("by").ifEmpty { null }, x.optBoolean("anchor"), x.optBoolean("await"),
                    )
                },
                stamps = stamps,
                lastSyncAt = o.optLong("lastAt"),
                lastResult = o.optString("result").takeIf { it.isNotEmpty() }?.let { r -> runCatching { SharedRunResult.valueOf(r) }.getOrNull() },
                pendingDeletions = o.optInt("pendingDel"),
                privateLeftOut = o.optInt("private"),
                oldKey = o.optString("oldKey").takeIf { it.isNotEmpty() }?.let(::unb64),
                newHeader = o.optString("newHeader").takeIf { it.isNotEmpty() }?.let(::unb64),
                unreadable = o.optJSONObject("unreadable").byKey { _, u -> Unreadable(u.getLong("t"), u.optBoolean("s")) },
                junk = o.optJSONObject("junk")?.let { s -> s.keys().asSequence().associateWith { s.getString(it) } }.orEmpty(),
                header = o.optString("header").takeIf { it.isNotEmpty() }?.let(::unb64),
                headerWarning = o.optBoolean("headerWarning"),
                exchanged = o.optJSONObject("exchanged")?.let { x -> x.keys().asSequence().associateWith { x.getLong(it) } }.orEmpty(),
                lastSentAt = o.optLong("sentAt"),
            )
        }
    }
}

/** Seals a label's state for storage; null when it can't be sealed right now (it is then not stored at all). */
interface StateSealer {
    fun seal(text: String): String?

    fun open(text: String): String?
}

/**
 * The shared labels' states, one sealed file each in [dir]. A file that can't be opened right now (the Keystore
 * briefly unavailable) is kept as it is and its label is left out until it can.
 */
class SharedLabelStateStore(private val dir: File, private val sealer: StateSealer) {
    private fun fileOf(id: String) = File(dir, "$id.json")

    fun all(): List<SharedLabelState> = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
        runCatching { sealer.open(f.readText())?.let { SharedLabelState.fromJson(JSONObject(it)) } }.getOrNull()
    }

    fun isNotEmpty(): Boolean = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().isNotEmpty()

    fun get(id: String): SharedLabelState? = runCatching { sealer.open(fileOf(id).readText())?.let { SharedLabelState.fromJson(JSONObject(it)) } }.getOrNull()

    /** Written to a temporary file and renamed, so a crash never leaves half a state. False when it can't be sealed. */
    fun put(s: SharedLabelState): Boolean {
        val sealed = sealer.seal(s.toJson().toString()) ?: return false
        dir.mkdirs()
        val tmp = File(dir, "${s.labelId}.json.tmp")
        tmp.writeText(sealed)
        val target = fileOf(s.labelId)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) return false
        }
        return true
    }

    fun remove(id: String) {
        fileOf(id).delete()
    }
}
