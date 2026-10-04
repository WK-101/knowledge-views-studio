package app.parley.common.vcard

import app.parley.common.backup.BackupCrypto
import app.parley.common.backup.BackupIntegrityException
import app.parley.common.backup.KdfParams
import app.parley.common.backup.Recipient
import app.parley.common.backup.RecoveryKey
import app.parley.common.backup.WrongKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The encrypted vCard: opens with the passphrase only, refuses backups, damage and cut-off files. */
class SealedVCardTest {
    private val kdf = KdfParams.Pbkdf2(1_000)
    private val pass = "otter plum seven".toCharArray()
    private val text = (1..300).joinToString("") { "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Person $it\r\nX-PARLEY-PRIVATE:1\r\nEND:VCARD\r\n" }

    private fun sealed(plain: String = text): ByteArray {
        val bo = ByteArrayOutputStream()
        SealedVCard.seal(bo, pass.copyOf(), kdf).use { it.write(plain.toByteArray(Charsets.UTF_8)) }
        return bo.toByteArray()
    }

    @Test fun it_round_trips_and_hides_the_text() {
        val file = sealed()
        assertTrue(SealedVCard.looksSealed(file))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("Person"))
        assertEquals(text, SealedVCard.open(file.inputStream(), pass).readBytes().toString(Charsets.UTF_8))
        // A plain vCard isn't sealed.
        assertFalse(SealedVCard.looksSealed(text.toByteArray()))
    }

    @Test fun a_wrong_passphrase_opens_nothing() {
        assertThrows(WrongKeyException::class.java) { SealedVCard.open(sealed().inputStream(), "otter plum eight".toCharArray()) }
    }

    @Test fun a_cut_off_or_changed_file_is_refused() {
        val file = sealed()
        assertThrows(BackupIntegrityException::class.java) { SealedVCard.open(file.copyOf(file.size - 20).inputStream(), pass).readBytes() }
        val changed = file.copyOf().also { it[it.size - 40] = (it[it.size - 40] + 1).toByte() }
        assertThrows(BackupIntegrityException::class.java) { SealedVCard.open(changed.inputStream(), pass).readBytes() }
    }

    @Test fun a_backup_is_not_taken_for_a_vcard() {
        // Sealed to the passphrase alone but holding something else (a backup's ZIP).
        val zip = BackupCrypto.encryptBytes(byteArrayOf(0x50, 0x4B, 3, 4, 0, 0), listOf(Recipient.Passphrase(pass.copyOf())), kdf)
        assertThrows(SealedVCard.BackupFileException::class.java) { SealedVCard.open(zip.inputStream(), pass) }
        // Sealed with more than the passphrase (a backup's recovery key too).
        val two = BackupCrypto.encryptBytes(text.toByteArray(), listOf(Recipient.Passphrase(pass.copyOf()), Recipient.Recovery(RecoveryKey.generate())), kdf)
        assertThrows(SealedVCard.BackupFileException::class.java) { SealedVCard.open(two.inputStream(), pass) }
    }

    @Test fun an_empty_export_opens_empty() {
        assertEquals(0, SealedVCard.open(sealed("").inputStream(), pass).readBytes().size)
    }
}
