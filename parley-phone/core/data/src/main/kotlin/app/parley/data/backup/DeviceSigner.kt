package app.parley.data.backup

import android.content.pm.PackageManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import app.parley.common.backup.ArchiveSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's archive-signing key: EC P-256 in the Android Keystore, never exportable. Scheduled backups run while
 * the phone may be locked, so it needs no user authentication; what it proves is "made on this phone", not "made by
 * you right now". [endorsement] is the key bundle vouching for this key (kept in [BackupPrefs]).
 */
internal class DeviceSigner private constructor(
    private val key: PrivateKey,
    override val publicKey: ByteArray,
    override val endorsement: ByteArray?,
) : ArchiveSigner {
    /** The same key carrying [e] as its endorsement. */
    fun endorsed(e: ByteArray?) = DeviceSigner(key, publicKey, e)

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(key)
        update(data)
        sign()
    }

    companion object {
        private const val STORE = "AndroidKeyStore"
        private const val ALIAS = "parley_backup_sign_v1"

        /** This phone's signing key, created on first use; null when the Keystore can't make one (backups stay unsigned). */
        fun load(context: Context): DeviceSigner? = try {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            val entry = ks.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry ?: run {
                generate(context)
                ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            }
            DeviceSigner(entry.privateKey, entry.certificate.publicKey.encoded, null)
        } catch (ignored: Exception) {
            // Any Keystore failure only means backups stay unsigned; they are never blocked by it.
            Log.w("DeviceSigner", "No archive signing key: ${ignored.javaClass.simpleName}")
            null
        }

        /** The public half only (to recognise this phone's archives); null when there is none yet. */
        fun publicKey(): ByteArray? = runCatching {
            val ks = KeyStore.getInstance(STORE).apply { load(null) }
            ks.getCertificate(ALIAS)?.publicKey?.encoded
        }.getOrNull()

        private fun generate(context: Context) {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
            // A dedicated secure chip where the phone has one; the TEE otherwise.
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) {
                try {
                    gen.initialize(spec(true))
                    gen.generateKeyPair()
                    return
                } catch (_: StrongBoxUnavailableException) {
                    // Fall through to the TEE.
                }
            }
            gen.initialize(spec(false))
            gen.generateKeyPair()
        }
    }
}
