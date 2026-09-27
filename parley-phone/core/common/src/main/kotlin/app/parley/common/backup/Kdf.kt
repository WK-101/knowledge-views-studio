package app.parley.common.backup

import app.parley.common.security.Scrypt
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * How a passphrase becomes a key. Stored in files as `u8 alg | u32 param`: for PBKDF2 the parameter is the
 * iteration count; for scrypt it packs `log2(N) << 16 | r << 8 | p`.
 */
sealed interface KdfParams {
    val alg: Int
    val param: Int

    data class Pbkdf2(val iterations: Int) : KdfParams {
        override val alg get() = KDF_PBKDF2_SHA256
        override val param get() = iterations
    }

    /** scrypt with N = 2^[log2N]; memory is 128·r·N bytes. */
    data class Scrypt(val log2N: Int, val r: Int, val p: Int) : KdfParams {
        override val alg get() = KDF_SCRYPT
        override val param get() = (log2N shl 16) or (r shl 8) or p
        val memoryBytes: Long get() = 128L * r * (1L shl log2N)
    }

    companion object {
        const val KDF_PBKDF2_SHA256 = 1
        const val KDF_SCRYPT = 2

        /** Decodes a stored pair; throws [BackupIntegrityException] for an unknown algorithm. */
        fun of(alg: Int, param: Int): KdfParams = when (alg) {
            KDF_PBKDF2_SHA256 -> Pbkdf2(param)
            KDF_SCRYPT -> Scrypt((param ushr 16) and 0xFF, (param ushr 8) and 0xFF, param and 0xFF)
            else -> throw BackupIntegrityException("Unknown KDF $alg")
        }
    }
}

/**
 * Which KDF parameters a reader accepts from a file. Someone who crafts a file chooses them, so a reader caps the
 * cost (no minute-long or gigabyte derivations per attempt) and, where the sender always uses one setting (QR codes),
 * accepts only that.
 */
fun interface KdfPolicy {
    fun accepts(kdf: KdfParams): Boolean

    companion object {
        /** Backups and key bundles: PBKDF2 up to 2M iterations, scrypt up to N = 2^16 and 64 MB. */
        val BACKUP = KdfPolicy { k ->
            when (k) {
                is KdfParams.Pbkdf2 -> k.iterations in BackupCrypto.MIN_ITERATIONS..BackupCrypto.MAX_ITERATIONS
                is KdfParams.Scrypt -> k.log2N in MIN_LOG2N..MAX_LOG2N && k.r in 1..16 && k.p in 1..4 && k.memoryBytes <= MAX_SCRYPT_MEMORY
            }
        }

        /** Only exactly these settings (payloads whose sender always uses the same ones). */
        fun exactly(vararg allowed: KdfParams) = KdfPolicy { it in allowed }

        const val MIN_LOG2N = 10
        const val MAX_LOG2N = 16
        const val MAX_SCRYPT_MEMORY = 64L shl 20
    }
}

internal object Kdf {
    /** A 256-bit key from [passphrase] (NFC-normalised, UTF-8), [salt] and [kdf]. */
    fun derive(passphrase: CharArray, salt: ByteArray, kdf: KdfParams): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase must not be empty" }
        require(KdfPolicy.BACKUP.accepts(kdf)) { "KDF parameters out of range: $kdf" }
        // NFC so the same passphrase typed on another keyboard/device derives the same key.
        val normalized = Normalizer.normalize(String(passphrase), Normalizer.Form.NFC).toCharArray()
        return when (kdf) {
            is KdfParams.Pbkdf2 -> {
                val spec = PBEKeySpec(normalized, salt, kdf.iterations, 256)
                try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
                } finally {
                    spec.clearPassword(); normalized.fill('\u0000')
                }
            }
            is KdfParams.Scrypt -> {
                val bytes = String(normalized).toByteArray(Charsets.UTF_8)
                normalized.fill('\u0000')
                try {
                    Scrypt.derive(bytes, salt, 1 shl kdf.log2N, kdf.r, kdf.p, 32)
                } finally {
                    bytes.fill(0)
                }
            }
        }
    }
}
