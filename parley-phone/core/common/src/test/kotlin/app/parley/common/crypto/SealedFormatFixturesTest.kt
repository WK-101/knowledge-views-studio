package app.parley.common.crypto

import app.parley.common.backup.KdfParams
import app.parley.common.backup.SyncCrypto
import app.parley.common.sync.shared.SharedLabelCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

/**
 * Sealed formats, byte for byte: these outputs were recorded from the code before the cipher calls moved into [Aead]
 * and [Hkdf]. Files written by any earlier version must keep opening, and new ones must stay readable by them.
 */
class SealedFormatFixturesTest {
    /** A "random" source that counts, so every output is fixed. */
    private fun counting() = object : SecureRandom() {
        var n = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (n++).toByte()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun bytes(h: String) = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val key = ByteArray(32) { it.toByte() }
    private val vcard = "BEGIN:VCARD".toByteArray()

    @Test fun sync_folder_header_and_file() {
        val (header, folderKey) = SyncCrypto.newFolder("correct horse".toCharArray(), KdfParams.Pbkdf2(1000), counting())
        assertEquals(SYNC_HEADER, hex(header))
        assertEquals("c914cc4f06cc6e8f46d157e3a1b5aa7abceebb17bb0444cd4c4ac16ca2ae9864", hex(folderKey))
        assertEquals(SYNC_FILE, hex(SyncCrypto.seal(key, "a.vcf", vcard, 3, counting())))
        val opened = SyncCrypto.openVersioned(key, "a.vcf", bytes(SYNC_FILE))!!
        assertArrayEquals(vcard, opened.vcard)
        assertEquals(3L, opened.version)
    }

    @Test fun shared_label_header_and_file() {
        val (header, _) = SharedLabelCrypto.newHeader("lab1", 2, "correct horse".toCharArray(), KdfParams.Pbkdf2(1000), counting())
        assertEquals(LABEL_HEADER, hex(header))
        assertEquals(LABEL_FILE, hex(SharedLabelCrypto.seal(key, "lab1", "f.vcf", vcard, counting())))
        assertArrayEquals(vcard, SharedLabelCrypto.open(key, "lab1", "f.vcf", bytes(LABEL_FILE)))
    }

    @Test fun hkdf_and_seal() {
        assertEquals(
            "e0ce24a7a4d40e80867d24318148e8e289741b54b8d9ff7c1c8996dd19ff6d18",
            hex(Hkdf.sha256(key, ByteArray(16) { 7 }, "parley/v1/archive-recovery")),
        )
        // The backup key wraps: nonce first, then what the sync file carries after its magic and nonce.
        val sealed = Aead.seal(key, byteArrayOf(0, 0, 0, 0, 0, 0, 0, 3) + vcard, "PARLEYF2|a.vcf".toByteArray(), counting())
        assertEquals(SYNC_FILE.substring("PARLEYF2".length * 2), hex(sealed))
        assertEquals(null, Aead.openOrNull(key, sealed, "PARLEYF2|b.vcf".toByteArray()))
    }

    private companion object {
        const val SYNC_HEADER =
            "5041524c4559533101000003e810000102030405060708090a0b0c0d0e0f101112131415161718191a1bf9624d44bf0ff61631bfb27b52ffa93b"
        const val SYNC_FILE = "5041524c45594632000102030405060708090a0b4702d61bc5e5c218cf04d0c2ffd32e2ec284c373397f7362e30922961ae02321d7212b"
        const val LABEL_HEADER =
            "5041524c45594c31046c6162310000000201000003e810000102030405060708090a0b0c0d0e0f101112131415161718191a1b55c3a53176288411d9ee93cd5660c7c4"
        const val LABEL_FILE = "5041524c45594c31000102030405060708090a0b054791528bdf9458cc13d376633fdd6dee46eadb9f19b6d2af7358"
    }
}
