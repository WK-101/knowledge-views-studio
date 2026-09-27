package app.parley.common.backup

import kotlinx.serialization.Serializable
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Signature of an archive by the phone that made it, stored in the manifest (older readers ignore it).
 *
 * The public-key wrap lets anyone who has seen one backup write a new archive that "opens with your passphrase". A
 * signature by a key that never leaves the phone's Keystore tells the two apart. [key] is that device key (X.509,
 * EC P-256); [endorsement] is an RSA-PSS signature of it by the backup key bundle's private key, which only someone
 * with the passphrase or recovery key can make, so another phone of yours is recognised too.
 */
@Serializable
data class ArchiveSignature(
    val alg: String = ALG,
    val key: String,
    val endorsement: String? = null,
    val sig: String,
) {
    companion object {
        const val ALG = "ES256"
    }
}

/** Where a restored archive came from, as far as its signature shows. */
enum class ArchiveOrigin {
    /** Signed by this phone's own key. */
    THIS_PHONE,

    /** Signed by another phone that your backup key vouches for. */
    OTHER_PHONE,

    /** Signed, but by a key your backup key never vouched for: possibly made by someone else. */
    UNKNOWN_SIGNER,

    /** Made before signatures, or the signature was removed. */
    UNSIGNED,

    /** The signature doesn't match the content: the file was changed or forged. */
    BAD_SIGNATURE,
}

/** Signs archives with a key the caller keeps (the Android Keystore on a phone). */
interface ArchiveSigner {
    /** The public key, X.509-encoded. */
    val publicKey: ByteArray

    /** The key bundle's endorsement of [publicKey], if one was made ([ArchiveSignatures.endorse]). */
    val endorsement: ByteArray?

    /** SHA256withECDSA over [data]. */
    fun sign(data: ByteArray): ByteArray
}

object ArchiveSignatures {
    private const val SIG_DOMAIN = "PARLEY-ARCHIVE-SIG1\n"
    private const val ENDORSE_DOMAIN = "PARLEY-ENDORSE1\n"
    private val PSS = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    /**
     * What is signed: the envelope header (key wraps, embedded bundle, KDF) and every manifest field except the
     * signature itself, so neither the recipients nor any entry can change without breaking it.
     */
    fun signedBytes(header: ByteArray, m: Manifest): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(SIG_DOMAIN.toByteArray(Charsets.US_ASCII))
        md.update(MessageDigest.getInstance("SHA-256").digest(header))
        val text = buildString {
            append(m.formatVersion).append('\n').append(m.createdAt).append('\n').append(m.appVersion).append('\n')
            m.device.toSortedMap().forEach { (k, v) -> append("d\u0000").append(k).append('\u0000').append(v).append('\n') }
            m.counts.toSortedMap().forEach { (k, v) -> append("c\u0000").append(k).append('\u0000').append(v).append('\n') }
            m.entries.forEach { e -> append("e\u0000").append(e.name).append('\u0000').append(e.size).append('\u0000').append(e.sha256).append('\n') }
        }
        md.update(text.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    fun sign(signer: ArchiveSigner, header: ByteArray, m: Manifest): ArchiveSignature =
        ArchiveSignature(key = b64.encodeToString(signer.publicKey), endorsement = signer.endorsement?.let(b64::encodeToString), sig = b64.encodeToString(signer.sign(signedBytes(header, m))))

    /** The key bundle's private key vouches for a device's signing key (RSA-PSS). */
    fun endorse(bundlePrivateKey: PrivateKey, deviceKey: ByteArray): ByteArray = pss().run {
        initSign(bundlePrivateKey)
        update(ENDORSE_DOMAIN.toByteArray(Charsets.US_ASCII))
        update(deviceKey)
        sign()
    }

    /** RSA-PSS with SHA-256, MGF1-SHA-256 and a 32-byte salt: Android's provider names it, the JDK takes parameters. */
    private fun pss(): Signature = try {
        Signature.getInstance("SHA256withRSA/PSS")
    } catch (_: GeneralSecurityException) {
        Signature.getInstance("RSASSA-PSS").apply { setParameter(PSS) }
    }

    fun endorsementValid(bundlePublicKey: PublicKey, deviceKey: ByteArray, endorsement: ByteArray): Boolean = try {
        pss().run {
            initVerify(bundlePublicKey)
            update(ENDORSE_DOMAIN.toByteArray(Charsets.US_ASCII))
            update(deviceKey)
            verify(endorsement)
        }
    } catch (_: GeneralSecurityException) {
        false
    }

    /**
     * Checks an opened archive's signature. [bundle] is the key bundle that opened it (see [BackupCrypto.open]); it is
     * the only one trusted to vouch for another phone. [thisPhoneKey] is this phone's own signing key.
     */
    fun verify(header: ByteArray, m: Manifest, bundle: KeyBundle?, thisPhoneKey: ByteArray?): ArchiveOrigin {
        val s = m.signature ?: return ArchiveOrigin.UNSIGNED
        if (s.alg != ArchiveSignature.ALG) return ArchiveOrigin.BAD_SIGNATURE
        val key = runCatching { unb64.decode(s.key) }.getOrNull() ?: return ArchiveOrigin.BAD_SIGNATURE
        val ok = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(signedBytes(header, m))
                verify(unb64.decode(s.sig))
            }
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!ok) return ArchiveOrigin.BAD_SIGNATURE
        if (thisPhoneKey != null && MessageDigest.isEqual(thisPhoneKey, key)) return ArchiveOrigin.THIS_PHONE
        val endorsement = s.endorsement?.let { runCatching { unb64.decode(it) }.getOrNull() }
        if (bundle != null && endorsement != null && endorsementValid(bundle.publicKey, key, endorsement)) return ArchiveOrigin.OTHER_PHONE
        return ArchiveOrigin.UNKNOWN_SIGNER
    }
}
