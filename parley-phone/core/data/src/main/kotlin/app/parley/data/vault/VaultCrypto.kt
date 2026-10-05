package app.parley.data.vault

import app.parley.data.security.KeystoreSeal
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
import androidx.annotation.VisibleForTesting
import java.io.File
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

    /** The detail-key generation in effect (vault_keys prefs); a newer alias without it is an unfinished upgrade. */
    private const val K_COMMITTED = "committedGeneration"

    /** First byte of a blob sealed by a generation ≥ 1 key (an IV length is never this large). */
    private const val GEN_MARK: Byte = 0x7A

    /** "P2" and the main part's length: a detail blob in two parts. */
    private const val PARTS_MARK_0: Byte = 0x50
    private const val PARTS_MARK_1: Byte = 0x32
    private const val PARTS_HEADER = 6

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

    /**
     * Key handles already looked up, by alias. A handle is only a reference (the key never leaves the Keystore), but
     * each lookup is a call into the Keystore service; the vault opens many blobs in a row (every caller-ID copy of a
     * listing, a page's details). A handle that fails is dropped and looked up again once ([withKey]), and every key
     * Parley deletes or replaces is dropped here first, so a stale handle never decides that data is lost.
     */
    private val handles = java.util.concurrent.ConcurrentHashMap<String, SecretKey>()

    /** Forgets every looked-up key handle (tests that delete aliases behind the vault's back). */
    fun forgetKeyHandles() = handles.clear()

    private fun deleteAlias(alias: String) {
        handles.remove(alias)
        ks.deleteEntry(alias)
    }

    /**
     * What the vault opened and looked up, for the open-cost tests and docs/PERFORMANCE_BENCHMARKS.md. Counting only:
     * nothing about the data is kept.
     */
    object Meter {
        val keyLookups = java.util.concurrent.atomic.AtomicInteger()
        val callerOpens = java.util.concurrent.atomic.AtomicInteger()
        val callerBytes = java.util.concurrent.atomic.AtomicLong()
        val detailOpens = java.util.concurrent.atomic.AtomicInteger()
        val detailBytes = java.util.concurrent.atomic.AtomicLong()

        /** Number fingerprints made with the Keystore HMAC key (each a Keystore operation). */
        val hmacs = java.util.concurrent.atomic.AtomicInteger()

        fun reset() {
            keyLookups.set(0); callerOpens.set(0); callerBytes.set(0); detailOpens.set(0); detailBytes.set(0); hmacs.set(0)
        }
    }

    /** Set once by the app so key generation can check for a secure lock screen and StrongBox. */
    @Volatile var appContext: Context? = null

    /**
     * I21: after a duress unlock (with "Keep private details locked"), details refuse to open as if the key needed a
     * fresh unlock, until the real Parley PIN ends it ([app.parley.data.security.Concealment]). Nothing is changed in
     * the Keystore: it is Parley declining to use the key, and every caller already treats "locked" as temporary.
     */
    @Volatile var detailLocked = false

    /**
     * "Lock private contacts": the person locked them again, so details neither open nor seal (as if the key needed a
     * fresh unlock) until their next unlock in Parley, even inside the phone's own 5-minute window. Kept in a file of
     * this phone's that is never backed up ([LOCKED_FILE]), so Parley being closed or stopped by Android doesn't undo
     * it; nothing changes in the Keystore. See [VaultRepository.lockAll].
     */
    var lockedByPerson: Boolean
        get() {
            if (!lockedRead) appContext?.let { ctx -> lockedNow = lockedFile(ctx).exists(); lockedRead = true }
            return lockedNow
        }
        set(value) {
            lockedNow = value
            appContext?.let { ctx ->
                lockedRead = true
                runCatching { lockedFile(ctx).let { f -> if (value) f.createNewFile() else f.delete() } }
            }
        }

    @Volatile private var lockedNow = false

    @Volatile private var lockedRead = false

    /** The file whose presence says private contacts were locked by the person (in `no_backup`, never exported). */
    const val LOCKED_FILE = "vault_locked"

    // Named as written for the storage registry's check (PersistentStores), the same as [LOCKED_FILE].
    private fun lockedFile(ctx: Context) = File(ctx.noBackupFilesDir, "vault_locked")

    /** As if Parley's process had just started: [lockedByPerson] is read from its file again. */
    @VisibleForTesting
    fun forgetLockForTest() {
        lockedRead = false
        lockedNow = false
    }

    /** Parley declines to use the detail key now ([detailLocked] or [lockedByPerson]). */
    private fun refused() = detailLocked || lockedByPerson

    private fun checkNotLocked() {
        if (refused()) throw LockedException()
    }

    private fun detailAlias(gen: Int) = if (gen == 0) LEGACY_DETAIL_KEY else "$DETAIL_PREFIX$gen"

    /** Every detail-key generation the Keystore holds. */
    private fun storedGenerations(): Set<Int> = buildSet {
        if (ks.containsAlias(LEGACY_DETAIL_KEY)) add(0)
        for (a in ks.aliases().toList()) {
            if (a.startsWith(DETAIL_PREFIX)) a.removePrefix(DETAIL_PREFIX).toIntOrNull()?.let { add(it) }
        }
    }

    /**
     * The generation new details are sealed with, or null when there is none yet. It is the committed one: a key that
     * an upgrade created but hasn't committed (still re-sealing, or interrupted by the process dying) is never used
     * for sealing and never counts for the audit. Installs from before the commit record use the newest key until
     * [reconcileGenerations] settles it from the stored blobs.
     */
    private fun currentGeneration(): Int? {
        val committed = committedGeneration()
        if (committed != null && ks.containsAlias(detailAlias(committed))) return committed
        return if (committed == null) storedGenerations().maxOrNull() else null
    }

    private fun committedGeneration(): Int? = generationPrefs()?.getInt(K_COMMITTED, -1)?.takeIf { it >= 0 }

    private fun commitGeneration(gen: Int) {
        // Synchronous: the commit is what makes an upgrade final, so it must be on disk before old keys go.
        generationPrefs()?.edit()?.putInt(K_COMMITTED, gen)?.commit()
    }

    /**
     * Settles which generation is in effect from the blobs actually stored ([inUse]) and removes what an interrupted
     * upgrade left behind: a key newer than every blob and than the committed generation holds nothing and is deleted,
     * so the audit looks at the key the data really uses and the upgrade runs again. Call with every writer of detail
     * blobs held off (the vault's key lock).
     */
    fun reconcileGenerations(inUse: Set<Int>) {
        val stored = storedGenerations()
        if (stored.isEmpty()) return
        val newestUsed = inUse.maxOrNull()
        val committed = committedGeneration()
        val effective = when {
            // Re-sealed blobs under a newer key mean the upgrade's transaction went through before the commit record.
            committed != null && newestUsed != null && newestUsed > committed -> newestUsed
            committed != null -> committed
            newestUsed != null -> newestUsed
            else -> stored.max()
        }
        if (effective != committed) commitGeneration(effective)
        for (g in stored) {
            if (g > effective && g !in inUse) {
                Log.w("VaultCrypto", "Removing detail key generation $g left by an interrupted upgrade")
                runCatching { deleteAlias(detailAlias(g)) }
            }
        }
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
        lookup(detailAlias(gen))
    } catch (_: UnrecoverableKeyException) {
        throw KeyLostException()
    } catch (e: GeneralSecurityException) {
        throw KeyUnavailableException(e)
    }

    /** The key new detail blobs are sealed with (created on first use). */
    @Synchronized
    private fun sealingGeneration(): Int {
        currentGeneration()?.let { return it }
        // A fresh install starts at generation 1 (with the authentication and unlock requirements). A generation
        // number is never reused, so a blob of a vanished key can't be mistaken for one of a new key.
        val next = maxOf(highestGenerationEver(), storedGenerations().maxOrNull() ?: 0) + 1
        createDetailKey(next)
        commitGeneration(next)
        return next
    }

    private fun generationPrefs() = appContext?.getSharedPreferences("vault_keys", Context.MODE_PRIVATE)

    private fun highestGenerationEver(): Int = generationPrefs()?.getInt("maxGeneration", 0) ?: 0

    /** The key of [alias], from [handles] or the Keystore; null when the Keystore has none. */
    private fun lookup(alias: String): SecretKey? {
        handles[alias]?.let { return it }
        Meter.keyLookups.incrementAndGet()
        return (ks.getKey(alias, null) as? SecretKey)?.also { handles[alias] = it }
    }

    private fun simpleKey(alias: String): SecretKey {
        lookup(alias)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(spec(alias, auth = false, strongBox = false))
        return gen.generateKey().also { handles[alias] = it }
    }

    /**
     * Runs [use] with the key of [alias] ([key] finds it); when a remembered handle fails, it is dropped and [use] runs
     * once more with a fresh lookup, so only the Keystore's own answer counts.
     */
    @Suppress("TooGenericExceptionCaught") // Any failure with a remembered handle is retried once; the second is rethrown.
    private inline fun <T> withKey(alias: String, key: () -> SecretKey?, use: (SecretKey?) -> T): T {
        val cached = handles.containsKey(alias)
        return try {
            use(key())
        } catch (e: Exception) {
            if (!cached) throw e
            handles.remove(alias)
            use(key())
        }
    }

    fun sealCallerId(plain: ByteArray) = withKey(CALLER_KEY, { simpleKey(CALLER_KEY) }) { KeystoreSeal.seal(it!!, plain) }
    fun openCallerId(blob: ByteArray): ByteArray {
        Meter.callerOpens.incrementAndGet()
        Meter.callerBytes.addAndGet(blob.size.toLong())
        return withKey(CALLER_KEY, { simpleKey(CALLER_KEY) }) { KeystoreSeal.open(it!!, blob, 0) }
    }

    fun sealDetail(plain: ByteArray): ByteArray {
        // Saving waits for the unlock too once the person locked private contacts (opening already does).
        if (lockedByPerson) throw LockedException()
        return sealDetail(sealingGeneration(), plain)
    }

    private fun sealDetail(gen: Int, plain: ByteArray): ByteArray {
        val sealed = try {
            withKey(detailAlias(gen), { detailKey(gen) }) { key -> KeystoreSeal.seal(key ?: throw KeyLostException(), plain) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            // New data goes under a new generation; blobs of the invalidated key stay as they are (never deleted here).
            val next = maxOf(gen, highestGenerationEver()) + 1
            createDetailKey(next)
            commitGeneration(next)
            return sealDetail(next, plain)
        } catch (e: InvalidKeyException) {
            throw classify(e)
        }
        return if (gen == 0) sealed else byteArrayOf(GEN_MARK, gen.toByte()) + sealed
    }

    /** The generation that sealed [blob] (both parts of a two-part blob share one). */
    fun generationOf(blob: ByteArray): Int {
        val b = if (isParts(blob)) parts(blob).first else blob
        return if (b.size > 2 && b[0] == GEN_MARK) b[1].toInt() and 0xFF else 0
    }

    // ---- Details in two parts

    /** Whether [blob] holds two sealed parts ([sealDetailParts]); a single blob starts with an IV length or [GEN_MARK]. */
    fun isParts(blob: ByteArray): Boolean = blob.size > PARTS_HEADER && blob[0] == PARTS_MARK_0 && blob[1] == PARTS_MARK_1

    /** The two sealed parts of a two-part blob: main, and extra (null when it has none). */
    private fun parts(blob: ByteArray): Pair<ByteArray, ByteArray?> {
        val n = java.nio.ByteBuffer.wrap(blob, 2, 4).int
        require(n > 0 && PARTS_HEADER + n <= blob.size) { "Damaged two-part blob" }
        val main = blob.copyOfRange(PARTS_HEADER, PARTS_HEADER + n)
        val extra = blob.copyOfRange(PARTS_HEADER + n, blob.size).takeIf { it.isNotEmpty() }
        return main to extra
    }

    private fun pack(main: ByteArray, extra: ByteArray?): ByteArray =
        java.nio.ByteBuffer.allocate(PARTS_HEADER + main.size + (extra?.size ?: 0))
            .put(PARTS_MARK_0).put(PARTS_MARK_1).putInt(main.size).put(main).apply { extra?.let { put(it) } }.array()

    /**
     * Seals a private contact's details as two blobs under the same detail key: [main], what its page and editor show
     * (a few kB), and [extra], what only "Make visible", backups and the first seeding read: the address-book record
     * it was moved in with (its photo included, up to hundreds of kB) and its carried interactions. Opening a page then
     * opens only [main]; on a phone whose detail key is in StrongBox, opening the whole record took seconds.
     * [keptExtra]: the extra part of the entry's current blob ([extraPart]), kept as sealed when its generation is the
     * one sealing now, so an edit neither opens nor re-seals it.
     */
    fun sealDetailParts(main: ByteArray, extra: ByteArray?, keptExtra: ByteArray? = null): ByteArray {
        val m = sealDetail(main)
        val gen = generationOf(m)
        val x = when {
            extra != null -> sealDetail(gen, extra)
            keptExtra == null -> null
            generationOf(keptExtra) == gen -> keptExtra
            else -> sealDetail(gen, openDetail(keptExtra))
        }
        return pack(m, x)
    }

    /** The main part of a detail blob, opened (the whole blob for one sealed before details had two parts). */
    fun openDetailMain(blob: ByteArray): ByteArray = openDetail(if (isParts(blob)) parts(blob).first else blob)

    /** The extra part of a two-part blob, opened; null when it has none or is a single blob (which holds everything). */
    fun openDetailExtra(blob: ByteArray): ByteArray? = if (isParts(blob)) parts(blob).second?.let(::openDetail) else null

    /** The extra part of a two-part blob, still sealed (to keep it through an edit), or null. */
    fun extraPart(blob: ByteArray): ByteArray? = if (isParts(blob)) parts(blob).second else null

    /**
     * Throws [LockedException] when a fresh unlock is needed, [KeyLostException] only when the key is gone for good
     * (invalidated by removing the screen lock, or provably missing), and [KeyUnavailableException] for anything else,
     * which callers must treat as "try again", never as a reason to overwrite the blob.
     */
    fun openDetail(blob: ByteArray): ByteArray {
        require(!isParts(blob)) { "A two-part blob opens with openDetailMain / openDetailExtra" }
        checkNotLocked()
        val gen = generationOf(blob)
        Meter.detailOpens.incrementAndGet()
        Meter.detailBytes.addAndGet(blob.size.toLong())
        return try {
            withKey(detailAlias(gen), { presentDetailKey(gen) }) { key -> KeystoreSeal.open(key!!, blob, if (gen == 0) 0 else 2) }
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

    /**
     * Whether the detail key currently needs a fresh unlock. It gates private data (number memory's private hints,
     * backups of private contacts), so it fails closed: only a key that is there and usable right now counts as open.
     * A key that is missing, invalidated (the screen lock was changed) or that the Keystore can't load now counts as
     * locked. No detail key ever made means nothing was sealed with one: open.
     */
    fun detailNeedsUnlock(): Boolean = refused() || try {
        val gen = currentGeneration()
        if (gen == null) {
            // A committed generation whose key is gone can't open anything.
            committedGeneration() != null
        } else {
            val key = detailKey(gen)
            if (key == null) {
                true
            } else {
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
                false
            }
        }
    } catch (_: Exception) {
        true
    }

    /**
     * Whether the detail key is gone for good (deleted, or invalidated by a screen-lock change), as opposed to locked or
     * briefly unavailable. A backup still saves what is left then (the caller-ID copies); a busy Keystore is not lost.
     */
    fun detailKeyLost(): Boolean = try {
        val gen = currentGeneration()
        if (gen == null) {
            committedGeneration() != null
        } else {
            val key = detailKey(gen)
            if (key == null) {
                !ks.containsAlias(detailAlias(gen))
            } else {
                Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
                false
            }
        }
    } catch (_: KeyPermanentlyInvalidatedException) {
        true
    } catch (_: KeyLostException) {
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
     * one transaction, returning false to abandon. The new key is committed only after that transaction, so nothing
     * else seals with it before, and old keys are deleted only once nothing refers to them ([inUse]). The caller holds
     * off every other writer of detail blobs for the whole call. Returns true when the vault now uses the new key.
     */
    suspend fun upgradeDetailKey(reseal: suspend (convert: (ByteArray) -> ByteArray) -> Boolean, inUse: suspend () -> Set<Int>): Boolean {
        if (refused() || !detailKeyNeedsUpgrade()) return false
        val old = currentGeneration() ?: return false
        val next = maxOf(old, highestGenerationEver(), storedGenerations().max()) + 1
        if (!createDetailKey(next)) {
            deleteAlias(detailAlias(next))
            return false
        }
        val ok = try {
            reseal { blob ->
                fun convert(one: ByteArray): ByteArray {
                    val plain = openDetail(one)
                    val sealed = sealDetail(next, plain)
                    // Verified before anything is written.
                    check(openDetail(sealed).contentEquals(plain)) { "Re-sealed data didn't read back" }
                    plain.fill(0)
                    return sealed
                }
                if (isParts(blob)) parts(blob).let { (m, x) -> pack(convert(m), x?.let(::convert)) } else convert(blob)
            }
        } catch (e: CancellationException) {
            deleteAlias(detailAlias(next))
            throw e
        } catch (ignored: Exception) {
            // Anything (locked, unavailable, a failed check): nothing was written; the next authentication tries again.
            Log.w("VaultCrypto", "Key upgrade postponed: ${ignored.javaClass.simpleName}")
            false
        }
        if (!ok) {
            deleteAlias(detailAlias(next))
            return false
        }
        commitGeneration(next)
        val used = inUse()
        for (g in 0 until next) if (g !in used && ks.containsAlias(detailAlias(g))) deleteAlias(detailAlias(g))
        return true
    }

    fun hmac(value: String): String {
        Meter.hmacs.incrementAndGet()
        return withKey(HMAC_KEY, {
            lookup(HMAC_KEY) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, STORE).run {
                init(KeyGenParameterSpec.Builder(HMAC_KEY, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }.also { handles[HMAC_KEY] = it }
        }) { key ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            Hex.encode(mac.doFinal(value.toByteArray()))
        }
    }

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
}
