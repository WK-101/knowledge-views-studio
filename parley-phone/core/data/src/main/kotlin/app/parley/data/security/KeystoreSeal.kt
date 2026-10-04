package app.parley.data.security

import app.parley.common.crypto.Aead
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * The envelope of everything sealed directly with an AndroidKeyStore key (wrapped store keys, private contacts'
 * records): the iv's length in one byte, the iv the Keystore chose, then the AES-GCM ciphertext and tag. The Keystore
 * picks the iv itself, which is why this isn't [Aead.seal].
 */
object KeystoreSeal {
    fun seal(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(plain)
    }

    /** Opens [seal]'s output starting at [off] in [blob]. */
    fun open(key: SecretKey, blob: ByteArray, off: Int = 0): ByteArray {
        val ivLen = blob[off].toInt()
        return Aead.decrypt(key, blob, off + 1, ivLen, blob, off + 1 + ivLen, blob.size - off - 1 - ivLen)
    }
}
