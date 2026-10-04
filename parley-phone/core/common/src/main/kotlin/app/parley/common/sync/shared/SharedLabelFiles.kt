package app.parley.common.sync.shared

import app.parley.common.Codecs
import app.parley.common.backup.RecordJson
import app.parley.common.spam.Ed25519
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.util.Base64

/** Who signs: a member's Ed25519 key (My card's, in the app). [sign] returns null while the key can't be read. */
interface MemberSigner {
    val publicKey: ByteArray

    fun sign(message: ByteArray): ByteArray?
}

/** What a change did to a contact, as members' journals record it. */
enum class ChangeKind { ADDED, EDITED, REMOVED }

/**
 * One contact file of a shared label (`c-<sid>.plabel`): the contact's shared fields ([card], a [SharedCards]
 * encoding; null for a deletion), its [version], when and by whom ([author]) it was written. The signature covers the
 * file's exact [body] with the label id and file name, so a signed file can't be moved to another name or label.
 * [parent]: the version its writer had synced when it wrote this one (null in files from before it was recorded), so
 * two edits made from the same version, which update files often carry, merge instead of one replacing the other.
 */
class CardFile(
    val sid: String,
    val version: Long,
    val at: Long,
    val author: ByteArray,
    val deleted: Boolean,
    val card: String?,
    internal val body: String,
    internal val signature: ByteArray,
    val parent: Long? = null,
) {
    val authorHex: String get() = SharedLabelFiles.keyHex(author)

    /** What exactly was signed: a phone remembers it for the version it accepted (re-signing checks it, see the engine). */
    val bodyHash: String get() = RecordJson.sha256Hex(body.toByteArray(Charsets.UTF_8))
}

/**
 * The invitation a member was let in with: [inviter] signed the label, the key's [epoch], [inviteId] and when the
 * invitation stops working ([expiresAt], wall-clock ms). One invitation lets in one member ([SharedLabelRoster]).
 */
class Ticket(val inviter: ByteArray, val inviteId: String, val epoch: Int, val signature: ByteArray, val expiresAt: Long)

/**
 * The signature of a new header ([SharedLabelCrypto.headerSigName]): the member who changed the label's key signs the
 * label, the new [epoch] and the header's hash. It is sealed with the *previous* key, so every member who still holds
 * that key can tell a real key change from a header someone with folder access swapped in.
 */
class HeaderSig(val signer: ByteArray, val epoch: Int, val headerHash: String) {
    val signerHex: String get() = SharedLabelFiles.keyHex(signer)
}

/** A member who stays after a key change, as the new anchor's journal lists them. */
class Carried(val key: ByteArray, val name: String)

/** One change in a member's journal. [id] is unique within that journal. */
data class JournalEntry(val id: Long, val sid: String, val kind: ChangeKind, val fields: Set<CardField>, val contactName: String, val at: Long)

/**
 * A member's journal (`j-<member>.plabel`): who they are ([member], [name]), the [ticket] that let them in (none for
 * the anchor), the members the anchor carried over a key change ([carried]), whether they [left], and their recent
 * changes. Only its member writes it; the signature covers the whole [body].
 */
class Journal(
    val member: ByteArray,
    val name: String,
    val epoch: Int,
    val ticket: Ticket?,
    val carried: List<Carried>,
    val left: Boolean,
    val entries: List<JournalEntry>,
    internal val body: String = "",
    internal val signature: ByteArray = ByteArray(0),
    /** When its member wrote it (0 in journals from before it was recorded): the newer of two copies wins. */
    val at: Long = 0,
) {
    val memberHex: String get() = SharedLabelFiles.keyHex(member)
}

/**
 * The files of a shared label's folder, their signatures and their JSON (docs/SHARED_LABELS.md). Every signed text
 * starts with its own fixed header, so a label signature can never pass for a signed card's, or the other way round.
 * Reading is strict and bounded: anything malformed or oversized is null, never half read.
 */
object SharedLabelFiles {
    const val CARD_PREFIX = "c-"
    const val JOURNAL_PREFIX = "j-"
    private const val CARD_HEADER = "PARLEY-LABEL-CARD-1"
    private const val JOURNAL_HEADER = "PARLEY-LABEL-JOURNAL-1"
    private const val TICKET_HEADER = "PARLEY-LABEL-TICKET-1"
    private const val HEADER_SIG_HEADER = "PARLEY-LABEL-HEADER-1"

    /** A contact's card is at most this long (no photos travel); a journal keeps at most [MAX_ENTRIES] changes. */
    const val MAX_CARD_CHARS = 256 * 1024
    const val MAX_ENTRIES = 300
    const val MAX_NAME = 80
    const val MAX_FILE_BYTES = 1L shl 20

    private val json = Codecs.stored
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()
    private val SID = Regex("[0-9a-f]{32}")

    fun keyHex(key: ByteArray): String = RecordJson.sha256Hex(key)

    /** A new random contact id (or label id, or invitation id): 128 bits as hex. */
    fun newId(random: SecureRandom = SecureRandom()): String = RecordJson.sha256Hex(ByteArray(16).also(random::nextBytes)).take(32)

    fun isId(s: String): Boolean = SID.matches(s)

    fun cardName(sid: String) = CARD_PREFIX + sid + SharedLabelCrypto.EXTENSION

    /** A member's journal name: the first 128 bits of their key's hash. */
    fun journalName(member: ByteArray) = JOURNAL_PREFIX + keyHex(member).take(32) + SharedLabelCrypto.EXTENSION

    /** The sid in a contact file's name, or null when the name isn't one. */
    fun sidOf(fileName: String): String? =
        fileName.takeIf { it.startsWith(CARD_PREFIX) && it.endsWith(SharedLabelCrypto.EXTENSION) }
            ?.removePrefix(CARD_PREFIX)?.removeSuffix(SharedLabelCrypto.EXTENSION)?.takeIf(::isId)

    fun isJournalName(fileName: String) = fileName.startsWith(JOURNAL_PREFIX) && fileName.endsWith(SharedLabelCrypto.EXTENSION)

    private fun payload(header: String, labelId: String, name: String, body: String) = "$header\n$labelId\n$name\n$body".toByteArray(Charsets.UTF_8)

    private fun envelope(body: String, sig: ByteArray): ByteArray =
        json.encodeToString(JsonElement.serializer(), buildJsonObject { put("body", body); put("sig", b64.encodeToString(sig)) }).toByteArray(Charsets.UTF_8)

    /** The hash of a signed file's body ([CardFile.bodyHash]) without checking its signature; null when it isn't one. */
    fun bodyHash(signed: ByteArray): String? = unwrap(signed)?.first?.let { RecordJson.sha256Hex(it.toByteArray(Charsets.UTF_8)) }

    private fun unwrap(bytes: ByteArray): Pair<String, ByteArray>? = runCatching {
        if (bytes.size > MAX_FILE_BYTES) return null
        val o = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        val body = o.str("body") ?: return null
        val sig = unb64.decode(o.str("sig") ?: return null)
        if (sig.size != 64) return null
        body to sig
    }.getOrNull()

    // ---------------------------------------------------------------- contact files

    /**
     * A signed contact file's bytes (before sealing), or null when the signer can't sign right now. [parent]: the
     * version this phone had synced (see [CardFile.parent]); readers that predate it ignore it.
     */
    @Suppress("LongParameterList") // The signed fields of a contact file.
    fun writeCard(signer: MemberSigner, labelId: String, sid: String, version: Long, at: Long, card: String?, parent: Long? = null): ByteArray? {
        require(isId(sid)) { "Bad sid" }
        val body = json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("sid", sid); put("v", version); put("at", at); put("by", b64.encodeToString(signer.publicKey))
                put("del", card == null)
                if (card != null) put("card", card)
                if (parent != null && parent > 0) put("p", parent)
            },
        )
        val sig = signer.sign(payload(CARD_HEADER, labelId, cardName(sid), body)) ?: return null
        return envelope(body, sig)
    }

    /**
     * A contact file read from [fileName], checked: well formed, its sid matching its name, its signature valid for
     * its author over this label and name. Null otherwise. Whether the author is a member is the caller's question.
     */
    @Suppress("CyclomaticComplexMethod")
    fun readCard(labelId: String, fileName: String, bytes: ByteArray): CardFile? {
        val (body, sig) = unwrap(bytes) ?: return null
        val f = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val sid = o.str("sid") ?: return null
            val v = o.long("v") ?: return null
            val at = o.long("at") ?: return null
            val by = unb64.decode(o.str("by") ?: return null)
            val del = o.bool("del") ?: return null
            val card = o.str("card")
            val parent = o.long("p")
            if (by.size != 32 || v < 0 || del != (card == null)) return null
            if (card != null && card.length > MAX_CARD_CHARS) return null
            if (parent != null && (parent <= 0 || parent >= v)) return null
            CardFile(sid, v, at, by, del, card, body, sig, parent)
        }.getOrNull() ?: return null
        if (sidOf(fileName) != f.sid) return null
        if (!Ed25519.verify(f.author, payload(CARD_HEADER, labelId, fileName, body), sig)) return null
        return f
    }

    // ---------------------------------------------------------------- tickets

    private fun ticketPayload(labelId: String, epoch: Int, inviteId: String, expiresAt: Long) =
        "$TICKET_HEADER\n$labelId\n$epoch\n$inviteId\n$expiresAt".toByteArray(Charsets.UTF_8)

    /** A ticket for one invitation, signed by the member who invites (null when the key can't sign now). */
    fun ticket(signer: MemberSigner, labelId: String, epoch: Int, inviteId: String, expiresAt: Long): Ticket? =
        signer.sign(ticketPayload(labelId, epoch, inviteId, expiresAt))?.let { Ticket(signer.publicKey, inviteId, epoch, it, expiresAt) }

    fun verifyTicket(t: Ticket, labelId: String): Boolean =
        Ed25519.verify(t.inviter, ticketPayload(labelId, t.epoch, t.inviteId, t.expiresAt), t.signature)

    // ---------------------------------------------------------------- the header's signature

    private fun headerSigPayload(labelId: String, epoch: Int, headerHash: String) =
        "$HEADER_SIG_HEADER\n$labelId\n$epoch\n$headerHash".toByteArray(Charsets.UTF_8)

    /** Signs a new [header] at [epoch] (bytes before sealing), or null when the key can't sign now. */
    fun writeHeaderSig(signer: MemberSigner, labelId: String, epoch: Int, header: ByteArray): ByteArray? {
        val hash = RecordJson.sha256Hex(header)
        val body = json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject { put("by", b64.encodeToString(signer.publicKey)); put("epoch", epoch); put("header", hash) },
        )
        val sig = signer.sign(headerSigPayload(labelId, epoch, hash)) ?: return null
        return envelope(body, sig)
    }

    /** A header signature, checked: well formed and signed by its signer. Whether the signer may sign is the caller's question. */
    fun readHeaderSig(labelId: String, bytes: ByteArray): HeaderSig? {
        val (body, sig) = unwrap(bytes) ?: return null
        val h = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val by = unb64.decode(o.str("by") ?: return null)
            if (by.size != 32) return null
            HeaderSig(by, o.int("epoch") ?: return null, o.str("header") ?: return null)
        }.getOrNull() ?: return null
        if (!Ed25519.verify(h.signer, headerSigPayload(labelId, h.epoch, h.headerHash), sig)) return null
        return h
    }

    fun headerHash(header: ByteArray): String = RecordJson.sha256Hex(header)

    // ---------------------------------------------------------------- journals

    /** A signed journal's bytes (before sealing), or null when the signer can't sign right now. */
    fun writeJournal(signer: MemberSigner, labelId: String, j: Journal): ByteArray? {
        require(j.member.contentEquals(signer.publicKey)) { "A journal is signed by its member" }
        val body = json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("member", b64.encodeToString(j.member)); put("name", j.name.take(MAX_NAME)); put("epoch", j.epoch); put("left", j.left)
                if (j.at > 0) put("at", j.at)
                j.ticket?.let { t ->
                    put(
                        "ticket",
                        buildJsonObject {
                            put("by", b64.encodeToString(t.inviter)); put("id", t.inviteId); put("epoch", t.epoch); put("sig", b64.encodeToString(t.signature))
                            put("exp", t.expiresAt)
                        },
                    )
                }
                put(
                    "carried",
                    buildJsonArray {
                        j.carried.forEach { c -> add(buildJsonObject { put("key", b64.encodeToString(c.key)); put("name", c.name.take(MAX_NAME)) }) }
                    },
                )
                put(
                    "entries",
                    buildJsonArray {
                        j.entries.takeLast(MAX_ENTRIES).forEach { e ->
                            add(
                                buildJsonObject {
                                    put("id", e.id); put("sid", e.sid); put("kind", e.kind.name); put("at", e.at); put("name", e.contactName.take(MAX_NAME))
                                    put("fields", buildJsonArray { e.fields.sorted().forEach { add(JsonPrimitive(it.name)) } })
                                },
                            )
                        }
                    },
                )
            },
        )
        val sig = signer.sign(payload(JOURNAL_HEADER, labelId, journalName(j.member), body)) ?: return null
        return envelope(body, sig)
    }

    /** A journal read from [fileName], checked: well formed, named after its member, signed by them. Null otherwise. */
    @Suppress("CyclomaticComplexMethod")
    fun readJournal(labelId: String, fileName: String, bytes: ByteArray): Journal? {
        val (body, sig) = unwrap(bytes) ?: return null
        val j = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val member = unb64.decode(o.str("member") ?: return null)
            if (member.size != 32) return null
            val ticket = (o["ticket"] as? JsonObject)?.let { t ->
                val by = unb64.decode(t.str("by") ?: return null)
                val s = unb64.decode(t.str("sig") ?: return null)
                val id = t.str("id")?.takeIf(::isId) ?: return null
                if (by.size != 32 || s.size != 64) return null
                Ticket(by, id, t.int("epoch") ?: return null, s, t.long("exp") ?: return null)
            }
            val carried = (o["carried"] as? JsonArray).orEmpty().take(MAX_MEMBERS).mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                val key = runCatching { unb64.decode(c.str("key")) }.getOrNull()?.takeIf { it.size == 32 } ?: return@mapNotNull null
                Carried(key, c.str("name").orEmpty().take(MAX_NAME))
            }
            val entries = (o["entries"] as? JsonArray).orEmpty().takeLast(MAX_ENTRIES).mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                JournalEntry(
                    id = x.long("id") ?: return@mapNotNull null,
                    sid = x.str("sid")?.takeIf(::isId) ?: return@mapNotNull null,
                    kind = runCatching { ChangeKind.valueOf(x.str("kind")!!) }.getOrNull() ?: return@mapNotNull null,
                    fields = (x["fields"] as? JsonArray).orEmpty()
                        .mapNotNull { f -> runCatching { CardField.valueOf((f as JsonPrimitive).content) }.getOrNull() }.toSet(),
                    contactName = x.str("name").orEmpty().take(MAX_NAME),
                    at = x.long("at") ?: return@mapNotNull null,
                )
            }
            val name = o.str("name").orEmpty().take(MAX_NAME)
            val at = o.long("at")?.coerceAtLeast(0) ?: 0
            Journal(member, name, o.int("epoch") ?: return null, ticket, carried, o.bool("left") ?: false, entries, body, sig, at)
        }.getOrNull() ?: return null
        if (journalName(j.member) != fileName) return null
        if (!Ed25519.verify(j.member, payload(JOURNAL_HEADER, labelId, fileName, body), sig)) return null
        return j
    }

    const val MAX_MEMBERS = 50

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
