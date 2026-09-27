package app.parley.common.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncCryptoTest {
    private val kdf = KdfParams.Scrypt(10, 8, 1)
    private val pass = "harbour lantern quiet mosaic".toCharArray()

    @Test fun another_phone_with_the_passphrase_gets_the_same_key() {
        val (header, key) = SyncCrypto.newFolder(pass, kdf)
        assertArrayEquals(key, SyncCrypto.unlock(header, pass))
        assertNull(SyncCrypto.unlock(header, "wrong passphrase".toCharArray()))
    }

    @Test fun files_round_trip_and_are_bound_to_their_name() {
        val (_, key) = SyncCrypto.newFolder(pass, kdf)
        val card = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Ada Lovelace\r\nEND:VCARD\r\n".encodeToByteArray()
        val sealed = SyncCrypto.seal(key, "a1.parleycard", card)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Lovelace"))
        assertArrayEquals(card, SyncCrypto.open(key, "a1.parleycard", sealed))
        // Renamed (swapped with another person's file), altered, or plain: not accepted.
        assertNull(SyncCrypto.open(key, "b2.parleycard", sealed))
        assertNull(SyncCrypto.open(key, "a1.parleycard", sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }))
        assertNull(SyncCrypto.open(key, "a1.parleycard", card))
        val (_, otherKey) = SyncCrypto.newFolder(pass, kdf)
        assertNull(SyncCrypto.open(otherKey, "a1.parleycard", sealed))
    }

    @Test fun a_crafted_header_cant_demand_a_huge_kdf_cost() {
        val (header, _) = SyncCrypto.newFolder(pass, kdf)
        val crafted = header.copyOf().also { h ->
            val p = KdfParams.Scrypt(22, 8, 1).param
            h[9] = (p ushr 24).toByte(); h[10] = (p ushr 16).toByte(); h[11] = (p ushr 8).toByte(); h[12] = p.toByte()
        }
        assertThrows(BackupIntegrityException::class.java) { SyncCrypto.unlock(crafted, pass) }
        assertThrows(BackupIntegrityException::class.java) { SyncCrypto.unlock("not a header".encodeToByteArray(), pass) }
        assertNotNull(SyncCrypto.unlock(header, pass))
    }
}
