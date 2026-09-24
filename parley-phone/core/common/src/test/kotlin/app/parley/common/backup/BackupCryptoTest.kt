package app.parley.common.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.random.Random

class BackupCryptoTest {
    companion object {
        const val IT = BackupCrypto.MIN_ITERATIONS
        const val SEG = BackupCrypto.SEGMENT_SIZE
        const val CSEG = SEG + BackupCrypto.TAG_SIZE
        val PASS = "correct horse battery staple".toCharArray()
        val RECOVERY: RecoveryKey = RecoveryKey.generate()
        // RSA-3072 generation is slow; share one bundle across tests.
        val BUNDLE: KeyBundle by lazy { BackupCrypto.createKeyBundle(PASS, RECOVERY, IT) }
    }

    private fun data(n: Int) = Random(n).nextBytes(n)

    private fun enc(plain: ByteArray, recipients: List<Recipient> = listOf(Recipient.Passphrase(PASS))): ByteArray =
        BackupCrypto.encryptBytes(plain, recipients, IT)

    private fun dec(ct: ByteArray, unlock: Unlock = Unlock.Passphrase(PASS)): ByteArray = BackupCrypto.decryptBytes(ct, unlock)

    private fun headerLen(ct: ByteArray) = 13 + ByteBuffer.wrap(ct, 9, 4).int

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("Expected ${T::class.simpleName} but got $t", t)
        }
        fail("Expected ${T::class.simpleName}")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------ round trips

    @Test fun roundTripsAllBoundarySizes() {
        for (n in listOf(0, 1, 100, SEG - 1, SEG, SEG + 1, 2 * SEG, 3 * SEG + 17)) {
            val p = data(n)
            val ct = enc(p)
            assertArrayEquals("size $n", p, dec(ct))
            val segments = maxOf(1, (n + SEG - 1) / SEG)
            assertEquals("ciphertext length for $n", headerLen(ct) + n + segments * BackupCrypto.TAG_SIZE, ct.size)
        }
    }

    @Test fun streamingWithOddWriteAndReadSizes() {
        val p = data(2 * SEG + 12345)
        val bo = ByteArrayOutputStream()
        BackupCrypto.encrypt(bo, listOf(Recipient.Passphrase(PASS)), IT).use { out ->
            var i = 0
            var step = 1
            while (i < p.size) {
                val k = minOf(step, p.size - i)
                if (k == 1) out.write(p[i].toInt()) else out.write(p, i, k)
                i += k; step = (step * 7 + 3) % 9000 + 1
            }
        }
        val input = BackupCrypto.decrypt(ByteArrayInputStream(bo.toByteArray()), Unlock.Passphrase(PASS))
        val got = ByteArrayOutputStream()
        while (true) { val b = input.read(); if (b < 0) break; got.write(b) }
        assertArrayEquals(p, got.toByteArray())
    }

    @Test fun finishDoesNotCloseUnderlyingStream() {
        var closed = false
        val bo = object : ByteArrayOutputStream() { override fun close() { closed = true } }
        val e = BackupCrypto.encrypt(bo, listOf(Recipient.Passphrase(PASS)), IT)
        e.write(byteArrayOf(1, 2, 3)); e.finish()
        assertFalse(closed)
        assertArrayEquals(byteArrayOf(1, 2, 3), dec(bo.toByteArray()))
    }

    @Test fun sameInputEncryptsDifferently() {
        val p = data(10)
        assertFalse(enc(p).contentEquals(enc(p)))
    }

    // ------------------------------------------------------------ tampering

    @Test fun flippedHeaderBytesAreDetected() {
        val ct = enc(data(1000))
        val hl = headerLen(ct)
        // Every single header byte: magic, version, lengths, KDF params, salt, nonce prefix, wraps.
        for (i in 0 until hl) {
            val t = ct.copyOf(); t[i] = (t[i].toInt() xor 0x01).toByte()
            try {
                dec(t)
                fail("flip at header byte $i not detected")
            } catch (_: IOException) {
                // BackupIntegrityException or WrongKeyException
            } catch (_: IllegalArgumentException) {
                fail("header byte $i: parser must throw IOException")
            }
        }
    }

    @Test fun flippedPayloadByteIsDetected() {
        val ct = enc(data(3 * SEG))
        for (pos in listOf(headerLen(ct), headerLen(ct) + CSEG + 5, ct.size - 1)) {
            val t = ct.copyOf(); t[pos] = (t[pos].toInt() xor 0x80).toByte()
            assertThrows<BackupIntegrityException> { dec(t) }
        }
    }

    @Test fun truncationIsDetected() {
        val p = data(2 * SEG + 500)
        val ct = enc(p)
        val hl = headerLen(ct)
        // Drop the whole last segment: the remaining segments end on a boundary but aren't flagged last.
        assertThrows<BackupIntegrityException> { dec(ct.copyOf(hl + 2 * CSEG)) }
        // Cut inside the last segment.
        assertThrows<BackupIntegrityException> { dec(ct.copyOf(ct.size - 1)) }
        // Only the header.
        assertThrows<BackupIntegrityException> { dec(ct.copyOf(hl)) }
        // Cut inside the header.
        assertThrows<BackupIntegrityException> { dec(ct.copyOf(hl - 3)) }
    }

    @Test fun truncationOfExactMultipleIsDetected() {
        val ct = enc(data(2 * SEG))
        assertThrows<BackupIntegrityException> { dec(ct.copyOf(headerLen(ct) + CSEG)) }
    }

    @Test fun reorderedSegmentsAreDetected() {
        val ct = enc(data(3 * SEG + 10))
        val hl = headerLen(ct)
        val t = ct.copyOf()
        System.arraycopy(ct, hl + CSEG, t, hl, CSEG)
        System.arraycopy(ct, hl, t, hl + CSEG, CSEG)
        assertThrows<BackupIntegrityException> { dec(t) }
    }

    @Test fun appendedDataIsDetected() {
        val ct = enc(data(SEG + 10))
        assertThrows<BackupIntegrityException> { dec(ct + byteArrayOf(0)) }
        assertThrows<BackupIntegrityException> { dec(ct + ct.copyOfRange(headerLen(ct), headerLen(ct) + CSEG)) }
        val exact = enc(data(SEG))
        assertThrows<BackupIntegrityException> { dec(exact + ByteArray(40)) }
    }

    @Test fun segmentsFromAnotherArchiveAreRejected() {
        val a = enc(data(SEG + 5))
        val b = enc(data(SEG + 5))
        val mixed = a.copyOf()
        System.arraycopy(b, headerLen(b), mixed, headerLen(a), CSEG)
        assertThrows<BackupIntegrityException> { dec(mixed) }
    }

    // ------------------------------------------------------------ keys

    @Test fun wrongPassphraseIsRejected() {
        val ct = enc(data(10))
        assertThrows<WrongKeyException> { dec(ct, Unlock.Passphrase("wrong".toCharArray())) }
        assertThrows<WrongKeyException> { dec(ct, Unlock.Recovery(RECOVERY)) }
    }

    @Test fun recoveryKeyUnlocksArchive() {
        val p = data(5000)
        val ct = enc(p, listOf(Recipient.Passphrase(PASS), Recipient.Recovery(RECOVERY)))
        assertArrayEquals(p, dec(ct, Unlock.Recovery(RECOVERY)))
        assertArrayEquals(p, dec(ct, Unlock.Recovery(RecoveryKey.parse(RECOVERY.format().lowercase()))))
        assertArrayEquals(p, dec(ct, Unlock.Passphrase(PASS)))
        assertThrows<WrongKeyException> { dec(ct, Unlock.Recovery(RecoveryKey.generate())) }
        val h = BackupCrypto.readHeader(ByteArrayInputStream(ct))
        assertEquals(setOf(WrapType.PASSPHRASE, WrapType.RECOVERY), h.wrapTypes)
        assertEquals(IT, h.iterations)
    }

    @Test fun passphraseIsUnicodeNormalized() {
        val composed = "café".toCharArray()
        val decomposed = "café".toCharArray()
        val ct = enc(data(10), listOf(Recipient.Passphrase(composed)))
        assertArrayEquals(data(10), dec(ct, Unlock.Passphrase(decomposed)))
    }

    @Test fun publicKeyWrapNeedsNoSecretAndUnlocksWithPrivateKey() {
        val p = data(SEG + 99)
        val ct = enc(p, listOf(Recipient.PublicKey(BUNDLE)))
        val header = BackupCrypto.readHeader(ByteArrayInputStream(ct))
        assertEquals(setOf(WrapType.PUBLIC_KEY), header.wrapTypes)
        assertEquals(BUNDLE.keyId, header.keyBundle!!.keyId)

        val pk = BackupCrypto.unlockPrivateKey(BUNDLE, PASS)
        assertArrayEquals(p, dec(ct, Unlock.WithPrivateKey(pk)))
        // New device, no stored bundle: the bundle embedded in the header opens with passphrase or recovery key.
        assertArrayEquals(p, dec(ct, Unlock.Passphrase(PASS)))
        assertArrayEquals(p, dec(ct, Unlock.Recovery(RECOVERY)))
        assertThrows<WrongKeyException> { dec(ct, Unlock.Passphrase("nope".toCharArray())) }
    }

    @Test fun reusableDataKeyDecryptsTwice() {
        val p = data(300)
        val ct = enc(p)
        val key = BackupCrypto.unwrapDataKey(BackupCrypto.readHeader(ByteArrayInputStream(ct)), Unlock.Passphrase(PASS))
        repeat(2) { assertArrayEquals(p, BackupCrypto.decrypt(ByteArrayInputStream(ct), key).readBytes()) }
    }

    @Test fun keyBundleUnlocksAndSerializes() {
        val restored = KeyBundle.fromBytes(BUNDLE.toBytes())
        assertEquals(BUNDLE, restored)
        assertEquals(BUNDLE.keyId, restored.keyId)
        val a = BackupCrypto.unlockPrivateKey(restored, PASS)
        val b = BackupCrypto.unlockPrivateKey(restored, RECOVERY)
        assertArrayEquals(a.encoded, b.encoded)
        assertThrows<WrongKeyException> { BackupCrypto.unlockPrivateKey(restored, "bad".toCharArray()) }
        assertThrows<WrongKeyException> { BackupCrypto.unlockPrivateKey(restored, RecoveryKey.generate()) }
        assertEquals(3072, (BUNDLE.publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength())
    }

    @Test fun changePassphraseRewrapsOnly() {
        val p = data(1234)
        val ct = enc(p, listOf(Recipient.PublicKey(BUNDLE)))
        val newPass = "new passphrase 2".toCharArray()
        val changed = BackupCrypto.changePassphrase(BUNDLE, PASS, newPass, random = SecureRandom())
        assertArrayEquals(BUNDLE.publicKeyBytes, changed.publicKeyBytes)
        assertArrayEquals(BUNDLE.recoveryWrap, changed.recoveryWrap)
        assertNotEquals(BUNDLE, changed)
        // Old backups still readable via the new bundle and new passphrase.
        assertArrayEquals(p, dec(ct, Unlock.WithPrivateKey(BackupCrypto.unlockPrivateKey(changed, newPass))))
        assertThrows<WrongKeyException> { BackupCrypto.unlockPrivateKey(changed, PASS) }
        BackupCrypto.unlockPrivateKey(changed, RECOVERY)
        assertThrows<WrongKeyException> { BackupCrypto.changePassphrase(BUNDLE, "bad".toCharArray(), newPass) }
    }

    @Test fun headerIterationBoundsAreEnforced() {
        val ct = enc(data(10))
        fun withIterations(n: Int) = ct.copyOf().also { ByteBuffer.wrap(it).putInt(14, n) }
        assertThrows<BackupIntegrityException> { BackupCrypto.readHeader(ByteArrayInputStream(withIterations(999))) }
        assertThrows<BackupIntegrityException> { BackupCrypto.readHeader(ByteArrayInputStream(withIterations(10_000_001))) }
        assertThrows<BackupIntegrityException> { BackupCrypto.readHeader(ByteArrayInputStream(withIterations(-1))) }
        assertThrows<IllegalArgumentException> { enc(data(1), listOf(Recipient.Passphrase(PASS))).let { BackupCrypto.encryptBytes(it, listOf(Recipient.Passphrase(PASS)), 999) } }
        assertThrows<IllegalArgumentException> { BackupCrypto.encryptBytes(ByteArray(1), listOf(Recipient.Passphrase(CharArray(0))), IT) }
        assertThrows<IllegalArgumentException> { BackupCrypto.encryptBytes(ByteArray(1), emptyList(), IT) }
    }

    @Test fun headerLengthAndGarbageAreRejected() {
        assertThrows<BackupIntegrityException> { BackupCrypto.readHeader(ByteArrayInputStream("PK\u0003\u0004 not ours".toByteArray())) }
        val ct = enc(data(10))
        val huge = ct.copyOf().also { ByteBuffer.wrap(it).putInt(9, Int.MAX_VALUE) }
        assertThrows<BackupIntegrityException> { BackupCrypto.readHeader(ByteArrayInputStream(huge)) }
        val badBundle = BUNDLE.toBytes().also { ByteBuffer.wrap(it).putInt(9, 50) }
        assertThrows<BackupIntegrityException> { KeyBundle.fromBytes(badBundle) }
        assertThrows<BackupIntegrityException> { KeyBundle.fromBytes(BUNDLE.toBytes().copyOf(100)) }
    }

    // ------------------------------------------------------------ recovery key format

    @Test fun recoveryKeyFormat() {
        val k = RecoveryKey.generate()
        val s = k.format()
        assertTrue(s, Regex("([0-9A-HJKMNP-TV-Z]{4}-){8}[0-9A-HJKMNP-TV-Z]{4}").matches(s))
        assertEquals(k, RecoveryKey.parse(s))
        assertEquals(k, RecoveryKey.parse(s.replace("-", " ").lowercase()))
        assertEquals(k, RecoveryKey.parse(s.replace("-", "")))
        assertEquals("RecoveryKey(****)", k.toString())
    }

    @Test fun recoveryKeyAcceptsCrockfordAliases() {
        val k = RecoveryKey.fromBytes(ByteArray(20))
        val s = k.format()
        assertTrue(s.startsWith("0000-0000"))
        assertEquals(k, RecoveryKey.parse(s.replace('0', 'O')))
        val ones = RecoveryKey.fromBytes(ByteArray(20) { if (it == 19) 0x21 else 0 })
        val f = ones.format()
        assertEquals(ones, RecoveryKey.parse(f.replace('1', 'l')))
        assertEquals(ones, RecoveryKey.parse(f.replace('1', 'I')))
    }

    @Test fun recoveryKeyChecksumCatchesTypos() {
        val s = RecoveryKey.generate().format()
        var detected = 0
        for (i in s.indices) {
            if (s[i] == '-') continue
            val c = RecoveryKey.ALPHABET[(RecoveryKey.ALPHABET.indexOf(s[i]) + 1) % 32]
            val typo = s.substring(0, i) + c + s.substring(i + 1)
            if (!RecoveryKey.isValid(typo)) detected++
        }
        assertEquals(36, detected)
        assertFalse(RecoveryKey.isValid(s.dropLast(1)))
        assertFalse(RecoveryKey.isValid(s + "0"))
        assertFalse(RecoveryKey.isValid(s.replaceFirst(Regex("[0-9A-Z]"), "U")))
        assertFalse(RecoveryKey.isValid(""))
        assertThrows<IllegalArgumentException> { RecoveryKey.parse("ABCD") }
    }

    @Test fun recoveryKeyEncodingIsKnownAnswer() {
        val k = RecoveryKey.fromBytes(ByteArray(20) { 0xFF.toByte() })
        assertTrue(k.format().startsWith("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-"))
    }
}
