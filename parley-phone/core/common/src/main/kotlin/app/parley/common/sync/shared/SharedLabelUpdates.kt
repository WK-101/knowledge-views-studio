package app.parley.common.sync.shared

import app.parley.common.Codecs
import app.parley.common.backup.RecordJson
import app.parley.common.crypto.Aead
import app.parley.common.security.Bounded
import app.parley.common.spam.Ed25519
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * One update file of a shared label (docs/SHARED_LABELS.md, "Sharing by file"): the label's files as one member's
 * phone holds them ([files]: the header, key-change notes, contact files and journals, each still sealed and signed
 * as in a folder), sent by [from] at [sentAt]. Whoever opens it merges the files as if a sync app had brought them.
 */
class LabelUpdate(
    val labelId: String,
    val epoch: Int,
    val from: ByteArray,
    val fromName: String,
    val sentAt: Long,
    val files: Map<String, ByteArray>,
) {
    val fromHex: String get() = SharedLabelFiles.keyHex(from)
}

/**
 * The update file's format. The whole file is sealed with the label's key (AES-256-GCM; the associated data binds
 * it to the label and the key's epoch) and signed by its sender's My card key over a fixed header, the label, the
 * epoch and the whole body, so a signature can never pass for a card's, a journal's or a folder file's.
 *
 *   file := "PARLEYU1" | u8 idLen | label id | u32 epoch | nonce[12] | GCM(key, gzip(signed), aad = "PARLEYU1|<label id>|<epoch>")
 *   signed := {"body": <JSON text>, "sig": <base64>}
 *   body := {"label", "epoch", "from", "name", "at", "files": {name: base64}}
 *
 * Reading is strict and bounded: only a label's own file names, each no larger than a folder file, at most
 * [MAX_FILES] of them and [MAX_EXPANDED] bytes in all.
 */
object SharedLabelUpdates {
    const val FILE_EXTENSION = ".parleyupdate"

    /** The type Parley shares update files as, and opens them by (with the extension, for apps that send any type). */
    const val MIME = "application/vnd.parley.label-update"
    private const val MAGIC = "PARLEYU1"
    private const val SIGNED_HEADER = "PARLEY-LABEL-UPDATE-1"
    private const val NONCE = 12
    private const val MAX_ID = 64

    /** Files in one update: a family label's contacts and journals with room to spare. */
    const val MAX_FILES = 5_000

    /** What an update may expand to, and how large the file itself may be. */
    const val MAX_EXPANDED: Long = 48L shl 20
    const val MAX_SEALED: Long = 24L shl 20

    /** How far ahead of this phone's clock an update's time may be (phones' clocks differ a little). */
    const val MAX_AHEAD_MS: Long = 24L * 60 * 60 * 1000

    private val json = Codecs.stored
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /** Whether [bytes] start like an update file (before any key is tried). */
    fun looksLikeUpdate(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.length && String(bytes.copyOf(MAGIC.length), Charsets.US_ASCII) == MAGIC

    /** What an update file says in the clear: which label and key epoch it is sealed for. */
    class Peek(val labelId: String, val epoch: Int, internal val sealedAt: Int)

    /** The label and epoch an update file names, or null when it isn't one. */
    fun peek(bytes: ByteArray): Peek? {
        if (!looksLikeUpdate(bytes) || bytes.size > MAX_SEALED) return null
        val d = DataInputStream(ByteArrayInputStream(bytes))
        return try {
            d.skipBytes(MAGIC.length)
            val len = d.readUnsignedByte()
            if (len !in 1..MAX_ID) return null
            val id = String(ByteArray(len).also(d::readFully), Charsets.US_ASCII)
            if (!SharedLabelFiles.isId(id)) return null
            val epoch = d.readInt()
            if (epoch < 1) return null
            Peek(id, epoch, MAGIC.length + 1 + len + 4)
        } catch (_: EOFException) {
            null
        }
    }

    /** Which of a label's files an update may carry: its header, key-change notes, contact files and journals. */
    fun isLabelFile(name: String): Boolean =
        name == SharedLabelCrypto.HEADER_NAME || SIG_NAME.matches(name) || SharedLabelFiles.sidOf(name) != null || JOURNAL_NAME.matches(name)

    private val SIG_NAME = Regex("""\.parley-label-sig-[1-9][0-9]{0,8}""")
    private val JOURNAL_NAME = Regex("""j-[0-9a-f]{32}\.plabel""")

    /**
     * An update file for the label [labelId] at [epoch], sealed with its [key] and signed by [signer] as [name] at
     * [sentAt]; null when the key can't sign right now. [files] are the folder's files as they are (sealed already).
     */
    @Suppress("LongParameterList") // The label, its key and epoch, the sender, and what it carries.
    fun write(
        signer: MemberSigner,
        key: ByteArray,
        labelId: String,
        epoch: Int,
        name: String,
        sentAt: Long,
        files: Map<String, ByteArray>,
        random: SecureRandom = SecureRandom(),
    ): ByteArray? {
        require(SharedLabelFiles.isId(labelId)) { "Bad label id" }
        require(files.keys.all(::isLabelFile)) { "Not a label file" }
        val body = json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("label", labelId); put("epoch", epoch); put("from", b64.encodeToString(signer.publicKey))
                put("name", name.take(SharedLabelFiles.MAX_NAME)); put("at", sentAt)
                put("files", buildJsonObject { files.toSortedMap().forEach { (n, b) -> put(n, b64.encodeToString(b)) } })
            },
        )
        val sig = signer.sign(signedPayload(labelId, epoch, body)) ?: return null
        val signed = json.encodeToString(JsonElement.serializer(), buildJsonObject { put("body", body); put("sig", b64.encodeToString(sig)) })
        val plain = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(signed.toByteArray(Charsets.UTF_8)) } }.toByteArray()
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            val id = labelId.toByteArray(Charsets.US_ASCII)
            writeByte(id.size); write(id); writeInt(epoch)
            write(nonce)
            write(Aead.encrypt(key, nonce, plain, aad(labelId, epoch)))
        }
        return out.toByteArray()
    }

    /** What opening an update file found. */
    sealed interface Opened {
        data class Ok(val update: LabelUpdate) : Opened

        /** Not an update file, or not one Parley can read. */
        data object NotAnUpdate : Opened

        /** Sealed with another key than this phone's for the label (another label's, or before a key change). */
        data object WrongKey : Opened

        /** Opened, but altered or not signed by the member it names. */
        data object Damaged : Opened
    }

    /** Opens [bytes] with the label's [key], for [labelId] at [epoch]: checked whole, or why not. */
    fun open(bytes: ByteArray, key: ByteArray, labelId: String, epoch: Int): Opened {
        val p = peek(bytes) ?: return Opened.NotAnUpdate
        if (p.labelId != labelId || p.epoch != epoch) return Opened.WrongKey
        val plain = try {
            val start = p.sealedAt
            if (bytes.size < start + NONCE + 16) return Opened.NotAnUpdate
            Aead.decrypt(key, bytes.copyOfRange(start, start + NONCE), bytes.copyOfRange(start + NONCE, bytes.size), aad(labelId, epoch))
        } catch (_: GeneralSecurityException) {
            return Opened.WrongKey
        }
        return read(plain, labelId, epoch)?.let(Opened::Ok) ?: Opened.Damaged
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun read(plain: ByteArray, labelId: String, epoch: Int): LabelUpdate? = runCatching {
        val signed = json.parseToJsonElement(String(Bounded.gunzip(plain, MAX_EXPANDED, "label update"), Charsets.UTF_8)).jsonObject
        val body = signed.str("body") ?: return null
        val sig = unb64.decode(signed.str("sig") ?: return null)
        val o = json.parseToJsonElement(body).jsonObject
        val from = unb64.decode(o.str("from") ?: return null)
        if (sig.size != 64 || from.size != 32) return null
        if (o.str("label") != labelId || (o["epoch"] as? JsonPrimitive)?.intOrNull != epoch) return null
        if (!Ed25519.verify(from, signedPayload(labelId, epoch, body), sig)) return null
        val at = (o["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val listed = o["files"] as? JsonObject ?: return null
        if (listed.size > MAX_FILES) return null
        val files = LinkedHashMap<String, ByteArray>()
        for ((name, v) in listed) {
            if (!isLabelFile(name)) return null
            val bytes = unb64.decode((v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null)
            if (bytes.size > SharedLabelFiles.MAX_FILE_BYTES) return null
            files[name] = bytes
        }
        LabelUpdate(labelId, epoch, from, o.str("name").orEmpty().take(SharedLabelFiles.MAX_NAME), at, files)
    }.getOrNull()

    /**
     * Whether an update sent at [sentAt] is new from its sender: later than the last one this phone opened from them
     * ([lastFromThem]; an update opened again, or an older one, is a copy put back), and not far ahead of [now].
     */
    fun fresh(sentAt: Long, lastFromThem: Long?, now: Long): Boolean =
        (lastFromThem == null || sentAt > lastFromThem) && sentAt <= now + MAX_AHEAD_MS

    // ---------------------------------------------------------------- merging into the label's folder

    /** Whether an update's contact file is taken over the folder's [existing] one: newer, or an edit alongside it. */
    fun takesCard(incoming: CardFile, existing: CardFile?): Boolean = when {
        existing == null -> true
        incoming.version == existing.version -> false
        incoming.version > existing.version -> true
        // Older, but written from the same version as the folder's: two edits that didn't see each other.
        else -> incoming.parent != null && incoming.parent == existing.parent
    }

    /**
     * Whether an update's contact file stays in the folder after the merge: only a newer one. An older edit made
     * alongside the folder's ([takesCard]) is merged by the run, which writes the result; the folder keeps the newer.
     */
    fun keepsCard(incoming: CardFile, existing: CardFile?): Boolean = existing == null || incoming.version > existing.version

    /** Whether an update's journal replaces the folder's copy of the same member's: a later key, a later write, or more changes. */
    fun takesJournal(incoming: Journal, existing: Journal?): Boolean {
        if (existing == null) return true
        val order = compareValuesBy(incoming, existing, { it.epoch }, { it.at }, { j -> j.entries.maxOfOrNull { it.id } ?: 0L })
        return order > 0
    }

    /** Whether an update's header replaces the folder's: none there, or one for a later key (the run checks it is signed). */
    fun takesHeader(incomingEpoch: Int, existingEpoch: Int?): Boolean = existingEpoch == null || incomingEpoch > existingEpoch

    private fun signedPayload(labelId: String, epoch: Int, body: String) = "$SIGNED_HEADER\n$labelId\n$epoch\n$body".toByteArray(Charsets.UTF_8)

    private fun aad(labelId: String, epoch: Int) = "$MAGIC|$labelId|$epoch".toByteArray(Charsets.US_ASCII)

    /** A content stamp for a file (the folder sync's stand-in when a provider gives no modified time). */
    fun stamp(bytes: ByteArray): String = "h:" + RecordJson.sha256Hex(bytes)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
