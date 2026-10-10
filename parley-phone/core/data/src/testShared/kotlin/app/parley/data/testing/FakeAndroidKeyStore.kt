package app.parley.data.testing

import android.security.keystore.KeyGenParameterSpec
import app.parley.data.vault.VaultCrypto
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * An in-memory stand-in for the "AndroidKeyStore" JCA provider, which Robolectric doesn't have. Secret keys are
 * generated in software and kept by alias, so the vault, call-history and interaction crypto can be round-tripped in
 * JVM tests. It knows nothing of user authentication or invalidation: those paths need a device.
 */
object FakeAndroidKeyStore {
    private const val NAME = "AndroidKeyStore"
    internal val keys: MutableMap<String, SecretKey> = ConcurrentHashMap()

    /** When set, every key read throws what it returns: a Keystore that is busy, failing or reports a key unrecoverable. */
    @Volatile var failure: (() -> Exception)? = null
        set(value) {
            field = value
            // A real Keystore fails the operation itself; here only lookups fail, so remembered handles go.
            VaultCrypto.forgetKeyHandles()
        }

    /** How often each alias's key was read (a lookup or an unwrap: a call into the Keystore on a phone). */
    val reads: MutableMap<String, java.util.concurrent.atomic.AtomicInteger> = ConcurrentHashMap()

    /** Installs the provider (once per JVM) and forgets every key, so each test starts with an empty Keystore. */
    fun install() {
        keys.clear()
        reads.clear()
        VaultCrypto.forgetKeyHandles()
        failure = null
        if (Security.getProvider(NAME) !is FakeProvider) {
            Security.removeProvider(NAME)
            Security.insertProviderAt(FakeProvider(), 1)
        }
    }

    /** Drops one alias, as the platform does when a key is invalidated or the app data is cleared. */
    fun delete(alias: String) {
        keys.remove(alias)
        // On a phone the handle of a deleted key fails from then on; this fake's software key wouldn't.
        VaultCrypto.forgetKeyHandles()
    }

    private class FakeProvider : Provider(NAME, 1.0, "Fake Android Keystore for tests") {
        init {
            put("KeyStore.$NAME", FakeKeyStoreSpi::class.java.name)
            put("KeyGenerator.AES", AesGenerator::class.java.name)
            put("KeyGenerator.HmacSHA256", HmacGenerator::class.java.name)
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? {
            failure?.let { throw it() }
            reads.getOrPut(alias) { java.util.concurrent.atomic.AtomicInteger() }.incrementAndGet()
            return keys[alias]
        }
        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String?): Certificate? = null
        override fun engineGetCreationDate(alias: String?): Date? = if (alias in keys) Date(0) else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key as SecretKey
        }
        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String): Boolean = alias in keys
        override fun engineSize(): Int = keys.size
        override fun engineIsKeyEntry(alias: String): Boolean = alias in keys
        override fun engineIsCertificateEntry(alias: String?): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate?): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    abstract class Generator(private val algorithm: String, private val defaultBits: Int) : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("A KeyGenParameterSpec is required")
        override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("A KeyGenParameterSpec is required")
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            spec = params as? KeyGenParameterSpec ?: throw IllegalArgumentException("Expected a KeyGenParameterSpec")
        }

        override fun engineGenerateKey(): SecretKey {
            val s = checkNotNull(spec) { "Not initialised" }
            val bits = s.keySize.takeIf { it > 0 } ?: defaultBits
            // The JDK's own generator, looked up by its provider so this fake isn't picked again.
            val key = KeyGenerator.getInstance(algorithm, "SunJCE").apply { init(bits) }.generateKey()
            keys[s.keystoreAlias] = key
            return key
        }
    }

    class AesGenerator : Generator("AES", 256)
    class HmacGenerator : Generator("HmacSHA256", 256)
}
