package com.todocompanion.app.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the Room database (Plan A). The SQLite file is encrypted with SQLCipher
 * (AES-256); the database passphrase is a high-entropy random string wrapped by a hardware-backed
 * AndroidKeyStore key (StrongBox when the device has it) and stored — wrapped — in app-private prefs.
 *
 * Threat model, stated honestly:
 *  - Defends: the database file extracted from a lost/stolen or powered-off device, or pulled off
 *    storage — it's unreadable without the KeyStore key, which never leaves secure hardware and is
 *    excluded from cloud backup & device transfer.
 *  - Does NOT defend: a rooted device while the app is installed and the screen is unlocked — code
 *    running as the app can ask the KeyStore to unwrap the key. (The optional app-lock adds a gate
 *    on top; true defence there needs a user-passphrase mode, noted as future "High" security.)
 *
 * Key-loss caveat: the KeyStore key is destroyed by uninstall / factory reset / most device-to-device
 * restores. If it's gone, the encrypted DB can't be read — so a JSON backup is the recovery path
 * (the app nudges you to keep one). Documented in Settings → Security.
 *
 * The passphrase is a Base64 string used identically by the migration (`ATTACH ... KEY '<b64>'`) and
 * the runtime open ([SupportFactory] with the same bytes), so the two paths can never disagree on how
 * the key is interpreted — the one correctness trap in a plaintext→encrypted migration.
 *
 * Fully offline: SQLCipher's native library adds no network permission.
 */
object SecureDb {
    private const val PREFS = "secure_db_v1"
    private const val K_DESIRED = "desired_encrypted"   // what the user wants
    private const val K_ACTUAL = "file_encrypted"       // the DB file's real state
    private const val K_SEEDED = "seeded"               // first-run defaults applied
    private const val K_WRAPPED = "wrapped_pass"        // base64(AES-GCM ciphertext of the passphrase)
    private const val K_IV = "wrap_iv"                  // base64(GCM IV)
    private const val K_LAST_ERROR = "last_error"       // human-readable last migration error (or "")

    private const val KS_PROVIDER = "AndroidKeyStore"
    private const val KS_ALIAS = "todo_db_key_wrap"
    private const val DB_NAME = "todocompanion.db"

    @Volatile private var libsLoaded = false

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun dbFile(context: Context): File = context.getDatabasePath(DB_NAME)

    /** Seed first-run defaults. Encryption is OPT-IN for everyone (fresh installs included): the
     *  SQLCipher code path then only ever executes when the user deliberately enables it, and a
     *  failure on any device degrades gracefully to plaintext instead of bricking the app. The whole
     *  feature is shipped and one toggle away — just off until chosen. */
    fun init(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(K_SEEDED, false)) return
        p.edit()
            .putBoolean(K_SEEDED, true)
            .putBoolean(K_DESIRED, false)
            .putBoolean(K_ACTUAL, false)
            .apply()
    }

    fun desiredEncrypted(context: Context): Boolean = prefs(context).getBoolean(K_DESIRED, false)
    fun fileEncrypted(context: Context): Boolean = prefs(context).getBoolean(K_ACTUAL, false)
    fun lastError(context: Context): String = prefs(context).getString(K_LAST_ERROR, "") ?: ""

    /** True while the desired state differs from the file's real state (a migration is pending a restart). */
    fun migrationPending(context: Context): Boolean =
        desiredEncrypted(context) != fileEncrypted(context)

    /** User toggles encryption on/off. Generates+wraps the key when first enabling. The actual
     *  migration runs on the next app start (via [reconcile]); the UI prompts for a restart. */
    fun setDesiredEncrypted(context: Context, want: Boolean) {
        if (want) runCatching { ensurePassphrase(context) }
        prefs(context).edit().putBoolean(K_DESIRED, want).apply()
    }

    // ── KeyStore-wrapped passphrase ─────────────────────────────────────────────────────────────

    private fun ensurePassphrase(context: Context): String {
        val existing = unwrapPassphrase(context)
        if (existing != null) return existing
        // 32 random bytes → Base64 → the SQLCipher passphrase (KDF is applied to these bytes).
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pass = Base64.encodeToString(raw, Base64.NO_WRAP)
        val (iv, ct) = wrap(pass.toByteArray(Charsets.US_ASCII))
        prefs(context).edit()
            .putString(K_WRAPPED, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
        return pass
    }

    private fun unwrapPassphrase(context: Context): String? {
        val p = prefs(context)
        val ctB64 = p.getString(K_WRAPPED, null) ?: return null
        val ivB64 = p.getString(K_IV, null) ?: return null
        return runCatching {
            val ct = Base64.decode(ctB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            String(unwrap(iv, ct), Charsets.US_ASCII)
        }.getOrNull()
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val ks = KeyStore.getInstance(KS_PROVIDER).apply { load(null) }
        (ks.getEntry(KS_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS_PROVIDER)
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        // Prefer StrongBox (a discrete secure element); gracefully fall back to the TEE if absent.
        return try {
            kg.init(spec(true)); kg.generateKey()
        } catch (_: Exception) {
            kg.init(spec(false)); kg.generateKey()
        }
    }

    private fun wrap(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        return cipher.iv to cipher.doFinal(plain)
    }

    private fun unwrap(iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    // ── SQLCipher plumbing ──────────────────────────────────────────────────────────────────────

    private fun ensureLibs(context: Context) {
        if (libsLoaded) return
        synchronized(this) {
            if (!libsLoaded) {
                net.sqlcipher.database.SQLiteDatabase.loadLibs(context.applicationContext)
                libsLoaded = true
            }
        }
    }

    /**
     * The Room open-helper factory when the DB file is encrypted, else null (plaintext / default).
     * Call AFTER [reconcile] so the file's state matches the returned factory.
     */
    fun openFactory(context: Context): net.sqlcipher.database.SupportFactory? {
        if (!fileEncrypted(context)) return null
        ensureLibs(context)
        val pass = unwrapPassphrase(context) ?: return null   // key lost → cannot open encrypted; caller handles
        return net.sqlcipher.database.SupportFactory(pass.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Bring the DB file to the desired state before Room opens it. Runs at most one migration per
     * start, guarded by a backup + integrity/row-count verify + atomic swap + rollback. On any
     * failure it restores the original file and records the error, never leaving a half-state.
     * No-op when desired == actual, or (for enabling) when the file doesn't exist yet.
     */
    fun reconcile(context: Context) {
        val want = desiredEncrypted(context)
        val have = fileEncrypted(context)
        if (want == have) return
        val db = dbFile(context)
        if (!db.exists()) {
            // Nothing on disk yet — Room will create it fresh in the desired state.
            prefs(context).edit().putBoolean(K_ACTUAL, want).apply()
            return
        }
        runCatching {
            ensureLibs(context)
            val pass = ensurePassphrase(context)
            if (want) migrate(context, db, srcKey = "", dstKey = pass)   // plaintext → encrypted
            else migrate(context, db, srcKey = pass, dstKey = "")        // encrypted → plaintext
            prefs(context).edit().putBoolean(K_ACTUAL, want).putString(K_LAST_ERROR, "").apply()
        }.onFailure { e ->
            // Roll back leaves the file in its ORIGINAL state; keep desired as-is so the UI can show
            // the error and let the user retry.
            prefs(context).edit().putBoolean(K_ACTUAL, have).putString(K_LAST_ERROR, e.message ?: e.toString()).apply()
        }
    }

    /**
     * Convert the DB between plaintext ("" key) and encrypted (passphrase) via sqlcipher_export.
     * Backup → checkpoint → export → set user_version → verify (integrity + row counts) → atomic
     * swap → delete the transient backup. Throws (leaving the original intact + restored) on failure.
     */
    private fun migrate(context: Context, db: File, srcKey: String, dstKey: String) {
        val parent = db.parentFile ?: throw IllegalStateException("no db dir")
        val bak = File(parent, "$DB_NAME.premigrate.bak")
        val tmp = File(parent, "$DB_NAME.migrate.tmp")
        val wal = File(parent, "$DB_NAME-wal"); val shm = File(parent, "$DB_NAME-shm")
        tmp.delete()
        db.copyTo(bak, overwrite = true)   // rollback copy of the ORIGINAL (same encryption state)
        try {
            // CREATE_IF_NECESSARY is required: ATTACH derives the attached file's open flags from this
            // connection, and the migrate target (tmp) was just deleted — without the create bit SQLite
            // returns "unable to open database" (SQLITE_CANTOPEN) and the migration can never complete.
            val src = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                db.absolutePath, srcKey, null,
                net.sqlcipher.database.SQLiteDatabase.OPEN_READWRITE or net.sqlcipher.database.SQLiteDatabase.CREATE_IF_NECESSARY,
            )
            val version: Int
            val srcCount: Long
            try {
                src.rawExecSQL("PRAGMA wal_checkpoint(FULL)")           // fold any WAL into the main file
                version = src.version
                srcCount = keyRowCount(src)
                // dstKey "" attaches a plaintext target; a real key attaches an encrypted one.
                val keyClause = if (dstKey.isEmpty()) "KEY ''" else "KEY '$dstKey'"
                src.rawExecSQL("ATTACH DATABASE '${tmp.absolutePath}' AS mig $keyClause")
                src.rawExecSQL("SELECT sqlcipher_export('mig')")
                src.rawExecSQL("DETACH DATABASE mig")
            } finally { src.close() }

            // Set user_version on the target (sqlcipher_export doesn't copy it) and verify.
            val dst = net.sqlcipher.database.SQLiteDatabase.openDatabase(
                tmp.absolutePath, dstKey, null, net.sqlcipher.database.SQLiteDatabase.OPEN_READWRITE,
            )
            val dstCount: Long
            val integrityOk: Boolean
            try {
                dst.version = version
                integrityOk = dst.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0).equals("ok", true) }
                dstCount = keyRowCount(dst)
            } finally { dst.close() }
            if (!integrityOk) throw IllegalStateException("integrity_check failed on migrated DB")
            if (dstCount != srcCount) throw IllegalStateException("row-count mismatch ($srcCount → $dstCount)")

            // Atomic-ish swap: drop the original + its stale WAL/SHM, move the verified target in.
            if (!db.delete()) throw IllegalStateException("could not remove original DB")
            wal.delete(); shm.delete()
            if (!tmp.renameTo(db)) {
                // Rename failed — restore from backup and bail.
                bak.copyTo(db, overwrite = true)
                throw IllegalStateException("could not swap migrated DB into place")
            }
            // Success: the transient plaintext/encrypted backup is sensitive — remove it.
            bak.delete()
        } catch (e: Throwable) {
            // Restore the original from backup if the live file was touched, then rethrow. The backup is a
            // full plaintext/encrypted copy of the DB — delete it once restored so a failed attempt never
            // leaves a sensitive copy at rest.
            runCatching { if (bak.exists()) bak.copyTo(db, overwrite = true) }
            tmp.delete(); bak.delete()
            throw e
        }
    }

    /** A cheap fingerprint of "did all the rows survive": summed counts of the big tables. */
    private fun keyRowCount(db: net.sqlcipher.database.SQLiteDatabase): Long {
        var total = 0L
        for (t in listOf("tasks", "habits", "habit_checkins", "time_entries", "reminders", "settings")) {
            runCatching {
                db.rawQuery("SELECT COUNT(*) FROM `$t`", null).use { if (it.moveToFirst()) total += it.getLong(0) }
            }
        }
        return total
    }
}
