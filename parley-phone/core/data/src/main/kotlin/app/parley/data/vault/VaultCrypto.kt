package app.parley.data.vault

import kotlinx.coroutines.CancellationException
import java.security.GeneralSecurityException
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import app.parley.common.Hex
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Vault keys live in the Android Keystore and never leave it (see docs/SECURITY_MODEL.md for what that does and
 * doesn't protect against).
 * - caller-ID key: no user auth, so incoming calls can show vault names on the lock screen;
 * - detail key: usable only for 5 minutes after the user proved it's them (biometric or screen lock) and only while
 *   the phone is unlocked; in a dedicated secure chip (StrongBox) where the phone has one. On a phone without a secure
 *   lock screen it can't require authentication; it is replaced by one that does ([upgradeDetailKey]) once there is one;
 * - HMAC key: fingerprints phone numbers so lookups don't need decryption.
 *
 * Detail keys come in generations: blobs name the generation that sealed them, so a new key can be introduced and the
 * blobs re-sealed one transaction later, and a key that fails is never silently replaced over data it sealed.
 */
object VaultCrypto {
    private const val STORE = "AndroidKeyStore"
    private const val CALLER_KEY = "parley_vault_callerid_v1"

    /** The first detail key (generation 0); its blobs carry no generation marker. */
    private const val LEGACY_DETAIL_KEY = "parley_vault_detail_v1"
    private const val DETAIL_PREFIX = "parley_vault_detail_g"
    private const val HMAC_KEY = "parley_vault_hmac_v1"
    private const val AUTH_SECONDS = 300

    /** First byte of a blob sealed by a generation ≥ 1 key (an IV length is never this large). */
    private const val GEN_MARK: Byte = 0x7A

    class LockedException : Exception("Unlock needed")

    /** The key was invalidated (screen lock removed or reset); data sealed with it can't be read any more. */
    class KeyLostException : Exception("Vault key invalidated")

    /** The Keystore couldn't use the key right now (busy, a vendor glitch). Nothing is lost: keep the data and retry. */
    class KeyUnavailableException(cause: Throwable?) : Exception("Vault key temporarily unavailable", cause)

    /** What the detail key is like, from the Keystore's own [KeyInfo]. */
    data class KeyAudit(
        val generation: Int,
        val exists: Boolean,
        val userAuthenticationRequired: Boolean,
        val unlockedDeviceRequired: Boolean,
        val secureHardware: Boolean,
        val strongBox: Boolean,
    )

    private val ks: KeyStore by lazy { KeyStore.getInstance(STORE).apply { load(null) } }
    private val random = SecureRandom()

    /** Set once by the app so key generation can check for a secure lock screen and StrongBox. */
    @Volatile var appContext: Context? = null

    private fun detailAlias(gen: Int) = if (gen == 0) LEGACY_DETAIL_KEY else "$DETAIL_PREFIX$gen"

    /** The newest detail-key generation in the Keystore, or null when there is none yet. */
    private fun currentGeneration(): Int? {
        var best: Int? = if (ks.containsAlias(LEGACY_DETAIL_KEY)) 0 else null
        for (a in ks.aliases().toList()) {
            val g = a.removePrefix(DETAIL_PREFIX).takeIf { a.startsWith(DETAIL_PREFIX) }?.toIntOrNull() ?: continue
            if (best == null || g > best) best = g
        }
        return best
    }

    private fun deviceSecure(): Boolean = appContext?.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun spec(alias: String, auth: Boolean, strongBox: Boolean) =
        KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply {
                if (auth) {
                    setUserAuthenticationRequired(true)
                    // Enrolling a new fingerprint must not destroy private contacts.
                    setInvalidatedByBiometricEnrollment(false)
                    if (Build.VERSION.SDK_INT >= 30) {
                        setUserAuthenticationParameters(AUTH_SECONDS, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                    }
                    // Details are only ever shown on an unlocked phone; a locked one can't decrypt them at all.
                    setUnlockedDeviceRequired(true)
                }
                if (strongBox) setIsStrongBoxBacked(true)
            }
            .build()

    /**
     * Creates detail key generation [gen]: authentication-bound when the phone has a secure lock screen, in StrongBox
     * when available. Returns whether the key requires authentication.
     */
    private fun createDetailKey(gen: Int): Boolean {
        val alias = detailAlias(gen)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        val strongBox = appContext?.packageManager?.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE) == true
        val auth = deviceSecure()
        val attempts = buildList {
            if (auth && strongBox) add(true to true)
            if (auth) add(true to false)
            // No secure lock screen: a key without authentication, replaced by an authenticated one later.
            add(false to false)
        }
        var last: Exception? = null
        for ((a, sb) in attempts) {
            try {
                generator.init(spec(alias, a, sb))
                generator.generateKey()
                if (gen > highestGenerationEver()) generationPrefs()?.edit()?.putInt("maxGeneration", gen)?.apply()
                return a
            } catch (e: StrongBoxUnavailableException) {
                last = e
            } catch (e: ProviderException) {
                last = e
            } catch (e: InvalidAlgorithmParameterException) {
                last = e
            }
        }
        throw KeyUnavailableException(last)
    }

    /** Throws [KeyLostException] when the Keystore can't recover the key, [KeyUnavailableException] for other failures. */
    private fun detailKey(gen: Int): SecretKey? = try {
        ks.getKey(detailAlias(gen), null) as? SecretKey
    } catch (_: UnrecoverableKeyException) {
        throw KeyLostException()
    } catch (e: GeneralSecurityException) {
        throw KeyUnavailableException(e)
    }

    /** The key new detail blobs are sealed with (created on first use). */
    private fun sealingGeneration(): Int {
        currentGeneration()?.let { return it }
        // A fresh install starts at generation 1 (with the authentication and unlock requirements). A generation
        // number is never reused, so a blob of a vanished key can't be mistaken for one of a new key.
        val next = highestGenerationEver() + 1
        createDetailKey(next)
        return next
    }

    private fun generationPrefs() = appContext?.getSharedPreferences("vault_keys", Context.MODE_PRIVATE)

    private fun highestGenerationEver(): Int = generationPrefs()?.getInt("maxGeneration", 0) ?: 0

    private fun simpleKey(alias: String): SecretKey {
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(spec(alias, auth = false, strongBox = false))
        return gen.generateKey()
    }

    private fun gcmSeal(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    private fun gcmOpen(key: SecretKey, blob: ByteArray, off: Int): ByteArray {
        val ivLen = blob[off].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, off + 1, ivLen))
        return c.doFinal(blob, off + 1 + ivLen, blob.size - off - 1 - ivLen)
    }

    fun sealCallerId(plain: ByteArray) = gcmSeal(simpleKey(CALLER_KEY), plain)
    fun openCallerId(blob: ByteArray) = gcmOpen(simpleKey(CALLER_KEY), blob, 0)

    fun sealDetail(plain: ByteArray): ByteArray = sealDetail(sealingGeneration(), plain)

    private fun sealDetail(gen: Int, plain: ByteArray): ByteArray {
        val key = detailKey(gen) ?: throw KeyLostException()
        val sealed = try {
            gcmSeal(key, plain)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // New data goes under a new generation; blobs of the invalidated key stay as they are (never deleted here).
            val next = maxOf(gen, highestGenerationEver()) + 1
            createDetailKey(next)
            return sealDetail(next, plain)
        } catch (e: InvalidKeyException) {
            throw classify(e)
        }
        return if (gen == 0) sealed else byteArrayOf(GEN_MARK, gen.toByte()) + sealed
    }

    /** The generation that sealed [blob]. */
    fun generationOf(blob: ByteArray): Int = if (blob.size > 2 && blob[0] == GEN_MARK) blob[1].toInt() and 0xFF else 0

    /**
     * Throws [LockedException] when a fresh unlock is needed, [KeyLostException] only when the key is gone for good
     * (invalidated by removing the screen lock, or provably missing), and [KeyUnavailableException] for anything else,
     * which callers must treat as "try again", never as a reason to overwrite the blob.
     */
    fun openDetail(blob: ByteArray): ByteArray {
        val gen = generationOf(blob)
        val key = presentDetailKey(gen)
        return try {
            gcmOpen(key, blob, if (gen == 0) 0 else 2)
        } catch (e: GeneralSecurityException) {
            throw classify(e)
        } catch (e: ProviderException) {
            throw KeyUnavailableException(e)
        }
    }

    /** The detail key of [gen]; lost only when the Keystore loaded and provably has no such alias. */
    private fun presentDetailKey(gen: Int): SecretKey =
        detailKey(gen) ?: throw if (!ks.containsAlias(detailAlias(gen))) KeyLostException() else KeyUnavailableException(null)

    /** What a Keystore failure means for the data: locked for now, lost for good, or only unavailable right now. */
    private fun classify(e: GeneralSecurityException): Exception = when (e) {
        is UserNotAuthenticatedException -> LockedException()
        is KeyPermanentlyInvalidatedException -> KeyLostException()
        // This key can't open the blob (it isn't the key that sealed it, or the blob is damaged).
        is AEADBadTagException -> KeyLostException()
        // Some OEM Keystores report transient failures as InvalidKeyException: keep the data, it may open next time.
        else -> KeyUnavailableException(e)
    }

    /** Whether the detail key currently needs a fresh unlock. */
    fun detailNeedsUnlock(): Boolean = try {
        val gen = currentGeneration() ?: return false
        val key = detailKey(gen) ?: return false
        Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
        false
    } catch (_: UserNotAuthenticatedException) {
        true
    } catch (_: Exception) {
        false
    }

    /** The current detail key's properties from the Keystore ([KeyInfo]); a generation of -1 when there is no key. */
    fun auditDetailKey(): KeyAudit {
        val gen = currentGeneration() ?: return KeyAudit(-1, false, false, false, false, false)
        val key = runCatching { detailKey(gen) }.getOrNull() ?: return KeyAudit(gen, false, false, false, false, false)
        val info = runCatching { SecretKeyFactory.getInstance(key.algorithm, STORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo }.getOrNull()
            ?: return KeyAudit(gen, true, false, false, false, false)
        val level = if (Build.VERSION.SDK_INT >= 31) info.securityLevel else null

        @Suppress("DEPRECATION")
        val secure = if (level != null) level != KeyProperties.SECURITY_LEVEL_SOFTWARE else info.isInsideSecureHardware
        return KeyAudit(
            generation = gen,
            exists = true,
            userAuthenticationRequired = info.isUserAuthenticationRequired,
            // KeyInfo doesn't report it; every authenticated key from generation 1 on was created with it.
            unlockedDeviceRequired = gen >= 1 && info.isUserAuthenticationRequired,
            secureHardware = secure,
            strongBox = level == KeyProperties.SECURITY_LEVEL_STRONGBOX,
        )
    }

    /**
     * Whether the detail key should be replaced by a stronger one: it doesn't require authentication (made before the
     * phone had a secure lock screen) or predates the unlocked-device requirement, and the phone now has a secure lock.
     */
    fun detailKeyNeedsUpgrade(): Boolean {
        val a = auditDetailKey()
        return a.exists && deviceSecure() && (!a.userAuthenticationRequired || !a.unlockedDeviceRequired)
    }

    /**
     * Re-seals every detail blob under a new, authentication-bound key generation. Call right after the user
     * authenticated. [reseal] receives a function that turns an old blob into a new one and must store all results in
     * one transaction, returning false to abandon. Old keys are deleted only once nothing refers to them ([inUse]).
     * Returns true when the vault now uses the new key.
     */
    suspend fun upgradeDetailKey(reseal: suspend (convert: (ByteArray) -> ByteArray) -> Boolean, inUse: suspend () -> Set<Int>): Boolean {
        if (!detailKeyNeedsUpgrade()) return false
        val old = currentGeneration() ?: return false
        val next = maxOf(old, highestGenerationEver()) + 1
        if (!createDetailKey(next)) {
            ks.deleteEntry(detailAlias(next))
            return false
        }
        val ok = try {
            reseal { blob ->
                val plain = openDetail(blob)
                val sealed = sealDetail(next, plain)
                // Verified before anything is written.
                check(openDetail(sealed).contentEquals(plain)) { "Re-sealed data didn't read back" }
                plain.fill(0)
                sealed
            }
        } catch (e: CancellationException) {
            ks.deleteEntry(detailAlias(next))
            throw e
        } catch (ignored: Exception) {
            // Anything (locked, unavailable, a failed check): nothing was written; the next authentication tries again.
            Log.w("VaultCrypto", "Key upgrade postponed: ${ignored.javaClass.simpleName}")
            false
        }
        if (!ok) {
            ks.deleteEntry(detailAlias(next))
            return false
        }
        val used = inUse()
        for (g in 0 until next) if (g !in used && ks.containsAlias(detailAlias(g))) ks.deleteEntry(detailAlias(g))
        return true
    }

    fun hmac(value: String): String {
        val key = (ks.getKey(HMAC_KEY, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).run {
            init(KeyGenParameterSpec.Builder(HMAC_KEY, KeyProperties.PURPOSE_SIGN).build())
            generateKey()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return Hex.encode(mac.doFinal(value.toByteArray()))
    }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
}
