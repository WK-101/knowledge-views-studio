package app.parley.common.sync.shared

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.KdfParams
import app.parley.common.backup.KdfPolicy
import app.parley.common.backup.Recipient
import app.parley.common.backup.Unlock
import app.parley.common.security.Bounded
import app.parley.common.spam.Ed25519
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPOutputStream

/**
 * An invitation to a shared label (docs/SHARED_LABELS.md, "Joining"): the label, the folder hint, its key (wrapped:
 * the invitation itself is sealed), the anchor and the inviter, and the inviter's ticket. Whoever opens it can join,
 * so it travels sealed: a QR code under a one-time passcode, a file under the label's passphrase.
 */
@Suppress("LongParameterList") // One field per part of the invitation, all of which travel together.
class Invitation(
    val labelId: String,
    val title: String,
    val folderHint: String,
    val epoch: Int,
    val key: ByteArray,
    val anchor: ByteArray,
    val anchorName: String,
    val inviter: ByteArray,
    val inviterName: String,
    val ticket: Ticket,
) {
    val inviterFingerprint: String get() = Ed25519.fingerprint(inviter)
}

object SharedLabelInvites {
    const val LINK_PREFIX = "parley://label?v=1&d="
    const val FILE_EXTENSION = ".parleyinvite"

    /**
     * The QR passcode's cost: the backup's scrypt setting. A QR code is kept as a photo or a screenshot (Parley has no
     * camera), often backed up somewhere, and it holds the label's key: guessing its passcode offline must stay out of
     * reach. A scanned code must use exactly this, so a crafted code can't stall the phone.
     */
    val QR_KDF: KdfParams = BackupCrypto.DEFAULT_KDF

    /** An invitation works for a week ([Ticket.expiresAt]); after that, ask for a new one. */
    const val TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** The passcode's letters: no 0/O, 1/I/L or U/V lookalikes, so it can be read out. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789"

    /** 16 letters of 30: about 78 bits, out of reach of offline guessing even with a cheap KDF. */
    const val PASSCODE_LENGTH = 16

    /** A new one-time passcode for a QR invitation, in groups of four ("K7Q2-9XMC-…"). */
    fun newPasscode(random: SecureRandom = SecureRandom()): String =
        (1..PASSCODE_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("").chunked(4).joinToString("-")
    private const val MAX_TITLE = 80
    private const val MAX_HINT = 200

    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun encode(i: Invitation): ByteArray = json.encodeToString(
        JsonElement.serializer(),
        buildJsonObject {
            put("label", i.labelId); put("title", i.title.take(MAX_TITLE)); put("folder", i.folderHint.take(MAX_HINT)); put("epoch", i.epoch)
            put("key", b64.encodeToString(i.key)); put("anchor", b64.encodeToString(i.anchor)); put("anchorName", i.anchorName.take(SharedLabelFiles.MAX_NAME))
            put("inviter", b64.encodeToString(i.inviter)); put("inviterName", i.inviterName.take(SharedLabelFiles.MAX_NAME))
            put("invite", i.ticket.inviteId); put("ticketEpoch", i.ticket.epoch); put("sig", b64.encodeToString(i.ticket.signature))
            put("exp", i.ticket.expiresAt)
        },
    ).toByteArray(Charsets.UTF_8)

    /**
     * An invitation from its JSON, checked: well formed, the key 32 bytes, the ticket the inviter's for this label and
     * epoch, and not expired at [now]. Null otherwise.
     */
    fun decode(bytes: ByteArray, now: Long = System.currentTimeMillis()): Invitation? = runCatching {
        val o = json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun key(k: String) = unb64.decode(s(k)!!).takeIf { it.size == 32 }!!
        val labelId = s("label")?.takeIf(SharedLabelFiles::isId)!!
        val epoch = (o["epoch"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 }!!
        val inviter = key("inviter")
        val expires = (o["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()!!
        val ticketEpoch = (o["ticketEpoch"] as? JsonPrimitive)?.intOrNull!!
        val ticket = Ticket(inviter, s("invite")?.takeIf(SharedLabelFiles::isId)!!, ticketEpoch, unb64.decode(s("sig")!!), expires)
        if (ticket.epoch != epoch || !SharedLabelFiles.verifyTicket(ticket, labelId) || now > expires) return null
        Invitation(
            labelId, s("title").orEmpty().take(MAX_TITLE).trim().ifEmpty { return null }, s("folder").orEmpty().take(MAX_HINT), epoch,
            key("key"), key("anchor"), s("anchorName").orEmpty().take(SharedLabelFiles.MAX_NAME),
            inviter, s("inviterName").orEmpty().take(SharedLabelFiles.MAX_NAME), ticket,
        )
    }.getOrNull()

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun normalize(passcode: String) = passcode.uppercase().filter { it.isLetterOrDigit() }.toCharArray()

    /** The QR code's link, sealed under [passcode] (shown under the code, read out in person). */
    fun qrLink(i: Invitation, passcode: String): String {
        val sealed = BackupCrypto.encryptBytes(gzip(encode(i)), listOf(Recipient.Passphrase(normalize(passcode))), QR_KDF)
        return LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(sealed)
    }

    fun isLink(text: String) = text.trim().startsWith("parley://label?", ignoreCase = true)

    /** The invitation in a scanned link; null for a wrong passcode or a code that isn't one. */
    fun fromLink(link: String, passcode: String, now: Long = System.currentTimeMillis()): Invitation? = runCatching {
        val d = link.trim().substringAfter("d=", "").substringBefore('&')
        val sealed = Base64.getUrlDecoder().decode(d)
        val plain = BackupCrypto.decryptBytes(sealed, Unlock.Passphrase(normalize(passcode)), KdfPolicy.exactly(QR_KDF))
        decode(Bounded.gunzip(plain, Bounded.Caps.QR_GUNZIP, "invitation"), now)
    }.getOrNull()

    /** An invitation file, sealed under [passphrase] (the label's: the person joining types it). [kdf] is for tests. */
    fun file(i: Invitation, passphrase: CharArray, kdf: KdfParams = BackupCrypto.DEFAULT_KDF): ByteArray =
        BackupCrypto.encryptBytes(gzip(encode(i)), listOf(Recipient.Passphrase(passphrase)), kdf)

    /** The invitation in a file; null for a wrong passphrase or a file that isn't one. */
    fun fromFile(
        bytes: ByteArray,
        passphrase: CharArray,
        policy: KdfPolicy = KdfPolicy.exactly(BackupCrypto.DEFAULT_KDF),
        now: Long = System.currentTimeMillis(),
    ): Invitation? = runCatching {
        decode(Bounded.gunzip(BackupCrypto.decryptBytes(bytes, Unlock.Passphrase(passphrase), policy), Bounded.Caps.QR_GUNZIP, "invitation"), now)
    }.getOrNull()
}
